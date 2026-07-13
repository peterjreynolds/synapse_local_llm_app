package app.synapse.localllm.data.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.data.sms.RoomSmsAutoReplyRepository
import app.synapse.localllm.domain.chat.AddHumanRoomMemberCommand
import app.synapse.localllm.domain.chat.AiResponsePolicy
import app.synapse.localllm.domain.chat.BuiltInParticipantIds
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.CreateRoomCommand
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.ParticipantKind
import app.synapse.localllm.domain.chat.RoomKind
import app.synapse.localllm.domain.chat.SubmitHumanMessageCommand
import app.synapse.localllm.domain.ids.ParticipantId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.sms.LinkSmsAutoReplyTurnCommand
import app.synapse.localllm.domain.sms.RecordSmsAutoReplyAcceptedCommand
import app.synapse.localllm.domain.sms.SmsInboundMessageKey
import app.synapse.localllm.domain.sms.SmsSenderAddress
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomConversationRepositoryTest {
    private lateinit var database: SynapseDatabase
    private lateinit var repository: RoomConversationRepository
    private lateinit var smsAutoReplyRepository: RoomSmsAutoReplyRepository
    private lateinit var clock: IncrementingSynapseClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = IncrementingSynapseClock()
        val idFactory = SynapseIdFactory()
        repository = RoomConversationRepository(
            database = database,
            chatDao = database.chatDao(),
            idFactory = idFactory,
            clock = clock,
        )
        smsAutoReplyRepository = RoomSmsAutoReplyRepository(
            smsAutoReplyDao = database.smsAutoReplyDao(),
            idFactory = idFactory,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun defaultRoomIsParticipantAwareAiChat() = runTest {
        val room = repository.ensureDefaultRoom()

        assertEquals(RoomKind.AI_CHAT, room.kind)
        assertEquals(
            setOf(BuiltInParticipantIds.LOCAL_HUMAN, BuiltInParticipantIds.SYNAPSE_LOCAL_AI),
            room.activeMembers.map { member -> member.participant.id }.toSet(),
        )
        assertEquals(
            AiResponsePolicy.AUTOMATIC,
            room.activeMembers.single { member ->
                member.participant.id == BuiltInParticipantIds.SYNAPSE_LOCAL_AI
            }.aiResponsePolicy,
        )
    }

    @Test
    fun humanMessageAndAiResponseAreSeparateAuthoredOperations() = runTest {
        val room = repository.ensureDefaultRoom()

        val humanReceipt = repository.submitHumanMessage(
            SubmitHumanMessageCommand(
                threadId = room.id,
                body = "First message",
                attachments = emptyList(),
            ),
        )

        val messagesAfterHumanSend = repository.listRecentMessages(room.id, limit = 10)
        assertEquals(1, messagesAfterHumanSend.size)
        assertEquals(humanReceipt.messageId, messagesAfterHumanSend.single().id)
        assertEquals(BuiltInParticipantIds.LOCAL_HUMAN, messagesAfterHumanSend.single().author.id)
        assertEquals(ConversationRole.USER, messagesAfterHumanSend.single().role)

        val aiReceipt = repository.startAiResponse(room.id)
        repository.appendAssistantToken(aiReceipt.messageId, "Hello")
        repository.completeAssistantMessage(aiReceipt.messageId)

        val completedMessages = repository.listRecentMessages(room.id, limit = 10)
        assertEquals(2, completedMessages.size)
        assertEquals(BuiltInParticipantIds.SYNAPSE_LOCAL_AI, completedMessages.last().author.id)
        assertEquals("Hello", completedMessages.last().body)
        assertEquals(MessageDeliveryState.COMPLETE, completedMessages.last().deliveryState)
    }

    @Test
    fun aiResponseSortsAfterItsHumanMessageWhenClockTimestampsTie() = runTest {
        val fixedClockRepository = RoomConversationRepository(
            database = database,
            chatDao = database.chatDao(),
            idFactory = SynapseIdFactory(),
            clock = FixedSynapseClock,
        )
        val room = fixedClockRepository.ensureDefaultRoom()
        val humanReceipt = fixedClockRepository.submitHumanMessage(
            SubmitHumanMessageCommand(
                threadId = room.id,
                body = "Human first",
                attachments = emptyList(),
            ),
        )

        val aiReceipt = fixedClockRepository.startAiResponse(
            roomId = room.id,
            inReplyToHumanMessageId = humanReceipt.messageId,
        )

        val orderedMessages = fixedClockRepository.listRecentMessages(room.id, limit = 10)
        assertEquals(listOf(humanReceipt.messageId, aiReceipt.messageId), orderedMessages.map { message -> message.id })
        assertTrue(aiReceipt.startedAt.isAfter(humanReceipt.submittedAt))
    }

    @Test
    fun humanOnlyRoomCannotStartLocalAiResponse() = runTest {
        val room = repository.createRoom(
            CreateRoomCommand(
                title = "Peter and Alex",
                kind = RoomKind.DIRECT,
                placeholderHumanDisplayNames = listOf("Alex"),
                includeSynapseAi = false,
                synapseAiAutoResponseEnabled = false,
            ),
        )

        repository.submitHumanMessage(
            SubmitHumanMessageCommand(
                threadId = room.id,
                body = "Human-only message",
                attachments = emptyList(),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository.startAiResponse(room.id) }
        }
        assertEquals(1, repository.listRecentMessages(room.id, 10).size)
    }

    @Test
    fun createsDirectAndGroupRoomsWithMemberSummaries() = runTest {
        val directRoom = repository.createRoom(
            CreateRoomCommand(
                title = "Planning with Alex",
                kind = RoomKind.DIRECT,
                placeholderHumanDisplayNames = listOf("Alex"),
                includeSynapseAi = false,
                synapseAiAutoResponseEnabled = false,
            ),
        )
        val groupRoom = repository.createRoom(
            CreateRoomCommand(
                title = "Launch crew",
                kind = RoomKind.GROUP,
                placeholderHumanDisplayNames = listOf("Alex", "Morgan"),
                includeSynapseAi = true,
                synapseAiAutoResponseEnabled = false,
            ),
        )

        assertEquals(RoomKind.DIRECT, directRoom.kind)
        assertEquals(2, directRoom.activeMemberCount)
        assertTrue(directRoom.memberSummary.contains("Alex"))
        assertEquals(RoomKind.GROUP, groupRoom.kind)
        assertEquals(4, groupRoom.activeMemberCount)
        assertTrue(groupRoom.memberSummary.contains("Synapse"))
        assertEquals(
            AiResponsePolicy.MENTION_ONLY,
            groupRoom.activeMembers.single { member ->
                member.participant.id == BuiltInParticipantIds.SYNAPSE_LOCAL_AI
            }.aiResponsePolicy,
        )
    }

    @Test
    fun addsAndSoftRemovesHumanAndSynapseMembers() = runTest {
        val room = repository.createRoom(
            CreateRoomCommand(
                title = "Crew",
                kind = RoomKind.GROUP,
                placeholderHumanDisplayNames = listOf("Alex"),
                includeSynapseAi = false,
                synapseAiAutoResponseEnabled = false,
            ),
        )
        val humanReceipt = repository.addHumanRoomMember(
            AddHumanRoomMemberCommand(roomId = room.id, displayName = "Morgan"),
        )
        repository.setSynapseAiEnabled(room.id, enabled = true)
        repository.setRoomAiAutoResponse(room.id, enabled = true)

        val roomWithMembers = repository.findRoom(room.id)!!
        assertTrue(roomWithMembers.activeMembers.any { member -> member.participant.displayName == "Morgan" })
        assertEquals(
            AiResponsePolicy.AUTOMATIC,
            roomWithMembers.activeMembers.single { member ->
                member.participant.id == BuiltInParticipantIds.SYNAPSE_LOCAL_AI
            }.aiResponsePolicy,
        )

        repository.removeRoomMember(room.id, humanReceipt.participantId)
        repository.setSynapseAiEnabled(room.id, enabled = false)

        val roomAfterRemoval = repository.findRoom(room.id)!!
        assertFalse(roomAfterRemoval.activeMembers.any { member -> member.participant.displayName == "Morgan" })
        assertFalse(
            roomAfterRemoval.activeMembers.any { member ->
                member.participant.id == BuiltInParticipantIds.SYNAPSE_LOCAL_AI
            },
        )
    }

    @Test
    fun enforcesHumanMembershipLimitsForAiAndDirectRooms() = runTest {
        val aiRoom = repository.ensureDefaultRoom()
        val directRoom = repository.createRoom(
            CreateRoomCommand(
                title = "Peter and Alex",
                kind = RoomKind.DIRECT,
                placeholderHumanDisplayNames = listOf("Alex"),
                includeSynapseAi = false,
                synapseAiAutoResponseEnabled = false,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.addHumanRoomMember(
                    AddHumanRoomMemberCommand(roomId = aiRoom.id, displayName = "Morgan"),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.addHumanRoomMember(
                    AddHumanRoomMemberCommand(roomId = directRoom.id, displayName = "Morgan"),
                )
            }
        }
    }

    @Test
    fun directRoomRejectsReplacementHumanAfterOriginalPeerLeaves() = runTest {
        val directRoom = repository.createRoom(
            CreateRoomCommand(
                title = "Peter and Alex",
                kind = RoomKind.DIRECT,
                placeholderHumanDisplayNames = listOf("Alex"),
                includeSynapseAi = false,
                synapseAiAutoResponseEnabled = false,
            ),
        )
        val originalOtherHuman = directRoom.activeMembers.single { member ->
            member.participant.id != BuiltInParticipantIds.LOCAL_HUMAN
        }
        repository.removeRoomMember(directRoom.id, originalOtherHuman.participant.id)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.addHumanRoomMember(
                    AddHumanRoomMemberCommand(roomId = directRoom.id, displayName = "Morgan"),
                )
            }
        }

        val roomMembers = repository.observeRoomMembers(directRoom.id).first()
        assertTrue(
            roomMembers.any { member ->
                member.participant.displayName == "Alex" && !member.isActive
            },
        )
    }

    @Test
    fun rejectsAddingHumanToArchivedRoom() = runTest {
        val room = repository.createRoom(
            CreateRoomCommand(
                title = "Archived group",
                kind = RoomKind.GROUP,
                placeholderHumanDisplayNames = listOf("Alex"),
                includeSynapseAi = false,
                synapseAiAutoResponseEnabled = false,
            ),
        )
        repository.archiveRoom(room.id)

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.addHumanRoomMember(
                    AddHumanRoomMemberCommand(roomId = room.id, displayName = "Morgan"),
                )
            }
        }
    }

    @Test
    fun rejectsHumanMessageFromNonMemberParticipant() = runTest {
        val room = repository.ensureDefaultRoom()

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.submitHumanMessage(
                    SubmitHumanMessageCommand(
                        threadId = room.id,
                        body = "Unauthorized",
                        attachments = emptyList(),
                        authorParticipantId = ParticipantId("participant-outsider"),
                    ),
                )
            }
        }
    }

    @Test
    fun explicitRoomTitleSurvivesNewMessages() = runTest {
        val room = repository.createRoom(
            CreateRoomCommand(
                title = "Pinned project brain",
                kind = RoomKind.AI_CHAT,
                placeholderHumanDisplayNames = emptyList(),
                includeSynapseAi = true,
                synapseAiAutoResponseEnabled = true,
            ),
        )

        repository.submitHumanMessage(
            SubmitHumanMessageCommand(
                threadId = room.id,
                body = "This must not replace the room title",
                attachments = emptyList(),
            ),
        )

        assertEquals("Pinned project brain", repository.findRoom(room.id)?.title)
    }

    @Test
    fun failStaleStreamingAssistantMessagesSkipsRecentSmsAutoReply() = runTest {
        val room = repository.ensureDefaultRoom()
        val humanReceipt = repository.submitHumanMessage(
            SubmitHumanMessageCommand(
                threadId = room.id,
                body = "Inbound SMS",
                attachments = emptyList(),
            ),
        )
        val aiReceipt = repository.startAiResponse(room.id)
        val smsReceipt = smsAutoReplyRepository.recordAutoReplyAccepted(acceptedCommand("recent"))!!
        smsAutoReplyRepository.linkAutoReplyTurn(
            LinkSmsAutoReplyTurnCommand(
                receiptId = smsReceipt.id,
                threadId = room.id,
                userMessageId = humanReceipt.messageId,
                assistantMessageId = aiReceipt.messageId,
            ),
        )

        val failedCount = repository.failStaleStreamingAssistantMessages(
            reason = "Generation was interrupted before Synapse reopened.",
            activeSmsAutoReplyAfter = Instant.parse("2026-06-15T13:59:00Z"),
        )

        assertEquals(0, failedCount)
        assertEquals(MessageDeliveryState.STREAMING, repository.findMessage(aiReceipt.messageId)?.deliveryState)
    }

    @Test
    fun observeRoomsOrdersPinnedBeforeMoreRecentRooms() = runTest {
        val olderRoom = repository.createRoom(aiChatCommand("Older"))
        val newerRoom = repository.createRoom(aiChatCommand("Newer"))

        repository.setRoomPinned(olderRoom.id, pinned = true)

        val rooms = repository.observeRooms().first()
        assertEquals(listOf(olderRoom.id, newerRoom.id), rooms.map { room -> room.id })
        assertTrue(rooms.first().isPinned)
    }

    @Test
    fun archiveAndDeleteRemoveRoomsFromVisibleList() = runTest {
        val archivedRoom = repository.createRoom(aiChatCommand("Archived"))
        val deletedRoom = repository.createRoom(aiChatCommand("Deleted"))
        val visibleRoom = repository.createRoom(aiChatCommand("Visible"))

        repository.archiveRoom(archivedRoom.id)
        repository.deleteRoom(deletedRoom.id)

        val visibleRooms = repository.observeRooms().first()
        assertEquals(listOf(visibleRoom.id), visibleRooms.map { room -> room.id })
        assertNotEquals(archivedRoom.id, visibleRooms.single().id)
    }

    private fun aiChatCommand(title: String): CreateRoomCommand =
        CreateRoomCommand(
            title = title,
            kind = RoomKind.AI_CHAT,
            placeholderHumanDisplayNames = emptyList(),
            includeSynapseAi = true,
            synapseAiAutoResponseEnabled = true,
        )

    private fun acceptedCommand(keySuffix: String): RecordSmsAutoReplyAcceptedCommand =
        RecordSmsAutoReplyAcceptedCommand(
            inboundMessageKey = SmsInboundMessageKey("sms-$keySuffix"),
            senderAddress = SmsSenderAddress("+15551234567"),
            receivedAt = Instant.parse("2026-06-15T14:00:00Z"),
            inboundBody = "Incoming SMS body",
        )

    private class IncrementingSynapseClock : SynapseClock {
        private var tickMillis = 0L

        override fun now(): Instant {
            val instant = Instant.parse("2026-06-15T14:00:00Z").plusMillis(tickMillis)
            tickMillis += 10
            return instant
        }
    }

    private object FixedSynapseClock : SynapseClock {
        override fun now(): Instant = Instant.parse("2026-06-15T14:00:00Z")
    }
}
