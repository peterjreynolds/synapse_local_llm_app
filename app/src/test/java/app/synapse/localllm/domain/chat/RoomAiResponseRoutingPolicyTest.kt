package app.synapse.localllm.domain.chat

import app.synapse.localllm.domain.ids.ChatThreadId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAiResponseRoutingPolicyTest {
    private val policy = RoomAiResponseRoutingPolicy()

    @Test
    fun aiChatRespondsAutomatically() {
        val decision = policy.decide(
            room = room(RoomKind.AI_CHAT, AiResponsePolicy.AUTOMATIC),
            humanMessageBody = "Hello",
        )

        assertTrue(decision.shouldRespond)
        assertEquals(AiResponseDecisionReason.AI_CHAT_AUTOMATIC, decision.reason)
    }

    @Test
    fun groupWithSynapseRequiresMentionByDefault() {
        val decision = policy.decide(
            room = room(RoomKind.GROUP, AiResponsePolicy.MENTION_ONLY),
            humanMessageBody = "What does everyone think?",
        )

        assertFalse(decision.shouldRespond)
        assertEquals(AiResponseDecisionReason.MENTION_REQUIRED, decision.reason)
    }

    @Test
    fun directRoomRespondsToCaseInsensitiveSynapseMention() {
        val decision = policy.decide(
            room = room(RoomKind.DIRECT, AiResponsePolicy.MENTION_ONLY),
            humanMessageBody = "Could @sYnApSe summarize this?",
        )

        assertTrue(decision.shouldRespond)
        assertEquals(AiResponseDecisionReason.SYNAPSE_MENTIONED, decision.reason)
    }

    @Test
    fun groupAutoResponsePolicyRespondsWithoutMention() {
        val decision = policy.decide(
            room = room(RoomKind.GROUP, AiResponsePolicy.AUTOMATIC),
            humanMessageBody = "No mention here",
        )

        assertTrue(decision.shouldRespond)
        assertEquals(AiResponseDecisionReason.ROOM_AUTOMATIC, decision.reason)
    }

    @Test
    fun humanOnlyRoomNeverRespondsEvenWhenMentioned() {
        val decision = policy.decide(
            room = room(RoomKind.GROUP, aiResponsePolicy = null),
            humanMessageBody = "@Synapse are you there?",
        )

        assertFalse(decision.shouldRespond)
        assertEquals(AiResponseDecisionReason.SYNAPSE_NOT_A_MEMBER, decision.reason)
    }

    private fun room(
        kind: RoomKind,
        aiResponsePolicy: AiResponsePolicy?,
    ): ChatRoomRecord =
        ChatRoomRecord(
            id = ChatThreadId("room-1"),
            title = "Room",
            kind = kind,
            isPinned = false,
            members = buildList {
                add(member(localHumanParticipant(), AiResponsePolicy.NEVER, RoomMemberRole.OWNER))
                if (aiResponsePolicy != null) {
                    add(member(synapseParticipant(), aiResponsePolicy, RoomMemberRole.MEMBER))
                }
            },
            syncMetadata = SyncMetadata(),
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun member(
        participant: ParticipantRecord,
        aiResponsePolicy: AiResponsePolicy,
        role: RoomMemberRole,
    ): RoomMemberRecord =
        RoomMemberRecord(
            roomId = ChatThreadId("room-1"),
            participant = participant,
            role = role,
            canPost = true,
            joinedAt = NOW,
            leftAt = null,
            aiResponsePolicy = aiResponsePolicy,
            syncMetadata = SyncMetadata(),
        )

    private fun localHumanParticipant(): ParticipantRecord =
        participant(
            id = BuiltInParticipantIds.LOCAL_HUMAN,
            kind = ParticipantKind.HUMAN,
            displayName = "You",
        )

    private fun synapseParticipant(): ParticipantRecord =
        participant(
            id = BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
            kind = ParticipantKind.LOCAL_AI,
            displayName = "Synapse",
        )

    private fun participant(
        id: app.synapse.localllm.domain.ids.ParticipantId,
        kind: ParticipantKind,
        displayName: String,
    ): ParticipantRecord =
        ParticipantRecord(
            id = id,
            kind = kind,
            displayName = displayName,
            avatarUri = null,
            avatarColorArgb = null,
            syncMetadata = SyncMetadata(),
            createdAt = NOW,
            updatedAt = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-12T12:00:00Z")
    }
}
