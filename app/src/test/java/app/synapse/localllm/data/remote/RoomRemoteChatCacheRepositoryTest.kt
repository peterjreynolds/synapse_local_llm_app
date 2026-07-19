package app.synapse.localllm.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.remote.CacheRemoteMessagesCommand
import app.synapse.localllm.domain.remote.CacheRemoteProfilesCommand
import app.synapse.localllm.domain.remote.CacheRemoteRoomsCommand
import app.synapse.localllm.domain.remote.EnqueueRemoteMessageCommand
import app.synapse.localllm.domain.remote.EnsureRemoteAssistantConversationCommand
import app.synapse.localllm.domain.remote.RemoteAccountSessionResource
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAiProvenance
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCacheMutation
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageDraft
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteOutboxState
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.remote.SearchRemoteMessagesCommand
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomRemoteChatCacheRepositoryTest {
    private lateinit var database: SynapseDatabase
    private lateinit var sessionCoordinator: RemoteAccountSessionCoordinator
    private lateinit var applicationScope: CoroutineScope
    private lateinit var repository: RoomRemoteChatCacheRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionCoordinator = RemoteAccountSessionCoordinator()
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = RoomRemoteChatCacheRepository(
            database = database,
            remoteChatCacheDao = database.remoteChatCacheDao(),
            sessionController = sessionCoordinator,
            clock = FixedClock,
            applicationScope = applicationScope,
        )
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
        database.close()
    }

    @Test
    fun accountSwitchScopesEveryQueryWithoutDeletingPriorAccountRows() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        repository.cacheProfiles(
            CacheRemoteProfilesCommand(
                accountUid = PETER_ACCOUNT,
                profiles = listOf(remoteProfile(PETER_ACCOUNT, TRISH_PROFILE, "Trish")),
            ),
        )
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)

        repository.activateAccount(TRISH_ACCOUNT)
        repository.cacheProfiles(
            CacheRemoteProfilesCommand(
                accountUid = TRISH_ACCOUNT,
                profiles = listOf(remoteProfile(TRISH_ACCOUNT, PETER_PROFILE, "Peter")),
            ),
        )
        cacheRoom(TRISH_ACCOUNT, PETER_PROFILE)

        assertEquals(listOf("Peter"), repository.observeProfiles().first().map { profile -> profile.username })
        assertEquals(PETER_PROFILE, repository.observeRooms().first().single().peerUid)

        repository.activateAccount(PETER_ACCOUNT)
        assertEquals(listOf("Trish"), repository.observeProfiles().first().map { profile -> profile.username })
        assertEquals(TRISH_PROFILE, repository.observeRooms().first().single().peerUid)

        repository.clearActiveAccount()
        assertTrue(repository.observeProfiles().first().isEmpty())
        assertTrue(repository.observeRooms().first().isEmpty())

        repository.activateAccount(TRISH_ACCOUNT)
        assertEquals(listOf("Peter"), repository.observeProfiles().first().map { profile -> profile.username })
    }

    @Test
    fun idempotencyKeyPreventsDuplicateCachedMessageAndOutboxRows() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        val command = enqueueMessageCommand(PETER_ACCOUNT, "message-1", "idempotency-1")

        val firstReceipt = repository.enqueueMessage(command)
        val duplicateReceipt = repository.enqueueMessage(command)

        assertEquals(RemoteCacheMutation.MESSAGE_ENQUEUED, firstReceipt.mutation)
        assertEquals(2, firstReceipt.affectedRows)
        assertEquals(RemoteCacheMutation.MESSAGE_ALREADY_ENQUEUED, duplicateReceipt.mutation)
        assertEquals(0, duplicateReceipt.affectedRows)
        assertEquals(1, repository.observeMessages(ROOM_ID).first().size)
        assertEquals(1, repository.observePendingOutbox().first().size)

        repository.activateAccount(TRISH_ACCOUNT)
        assertTrue(repository.observeMessages(ROOM_ID).first().isEmpty())
        assertTrue(repository.observePendingOutbox().first().isEmpty())
    }

    @Test
    fun cinderDoorUsesTheNormalCacheAndSurvivesAuthoritativeFirebaseRoomReconciliation() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        repository.ensureAssistantConversation(
            EnsureRemoteAssistantConversationCommand(
                accountUid = PETER_ACCOUNT,
                endpoint = RemoteAssistantConversationCatalog.cinder,
            ),
        )

        val initialDoor = repository.observeRooms().first().single()
        assertEquals(RemoteAssistantConversationCatalog.cinder.roomId, initialDoor.roomId)
        assertEquals(RemoteRoomKind.ASSISTANT, initialDoor.kind)

        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        assertEquals(
            setOf(ROOM_ID, RemoteAssistantConversationCatalog.cinder.roomId),
            repository.observeRooms().first().map(RemoteCachedRoom::roomId).toSet(),
        )

        repository.cacheRooms(CacheRemoteRoomsCommand(PETER_ACCOUNT, emptyList()))
        assertEquals(
            listOf(RemoteAssistantConversationCatalog.cinder.roomId),
            repository.observeRooms().first().map(RemoteCachedRoom::roomId),
        )
    }

    @Test
    fun cinderOutboxUsesTheSameIdempotentMessageAndOperationRows() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        repository.ensureAssistantConversation(
            EnsureRemoteAssistantConversationCommand(
                accountUid = PETER_ACCOUNT,
                endpoint = RemoteAssistantConversationCatalog.cinder,
            ),
        )
        val command = enqueueMessageCommand(
            accountUid = PETER_ACCOUNT,
            messageId = "cinder-message-1",
            idempotencyKey = "cinder-message-1",
            roomId = RemoteAssistantConversationCatalog.cinder.roomId,
        )

        val firstReceipt = repository.enqueueMessage(command)
        val duplicateReceipt = repository.enqueueMessage(command)

        assertEquals(RemoteCacheMutation.MESSAGE_ENQUEUED, firstReceipt.mutation)
        assertEquals(RemoteCacheMutation.MESSAGE_ALREADY_ENQUEUED, duplicateReceipt.mutation)
        assertEquals(1, repository.observeMessages(RemoteAssistantConversationCatalog.cinder.roomId).first().size)
        assertEquals(1, repository.observePendingOutbox().first().size)
    }

    @Test
    fun tiedRemoteTimestampsUseMessageIdAsDeterministicOrderingKey() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        val tiedTimestamp = Instant.parse("2026-07-13T05:00:00Z")
        val messages = listOf(
            remoteMessage(PETER_ACCOUNT, "message-b", "key-b", tiedTimestamp),
            remoteMessage(PETER_ACCOUNT, "message-a", "key-a", tiedTimestamp),
        )

        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, messages))

        assertEquals(
            listOf("message-a", "message-b"),
            repository.observeMessages(ROOM_ID).first().map { message -> message.messageId.raw },
        )
    }

    @Test
    fun authoritativeRoomRemovalCascadesCachedMessagesAndOutbox() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        repository.enqueueMessage(enqueueMessageCommand(PETER_ACCOUNT, "message-1", "idempotency-1"))
        repository.saveDraft(RemoteMessageDraft(PETER_ACCOUNT, ROOM_ID, "Unsent thought", FixedClock.now()))

        val receipt = repository.cacheRooms(CacheRemoteRoomsCommand(PETER_ACCOUNT, emptyList()))

        assertEquals(1, receipt.affectedRows)
        assertTrue(repository.observeRooms().first().isEmpty())
        assertTrue(repository.observeMessages(ROOM_ID).first().isEmpty())
        assertTrue(repository.observePendingOutbox().first().isEmpty())
        assertNull(repository.observeDraft(ROOM_ID).first())
    }

    @Test
    fun fullTextSearchIsAccountAndRoomScoped() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRooms(
            accountUid = PETER_ACCOUNT,
            remoteRoom(PETER_ACCOUNT, ROOM_ID, TRISH_PROFILE, "Peter, Trish"),
            remoteRoom(PETER_ACCOUNT, SECOND_ROOM_ID, JOSH_PROFILE, "Peter, Josh"),
        )
        repository.cacheMessages(
            CacheRemoteMessagesCommand(
                PETER_ACCOUNT,
                listOf(
                    remoteMessage(PETER_ACCOUNT, "message-forecast", "key-forecast", FixedClock.now())
                        .copy(body = "Forecast review is tomorrow"),
                    remoteMessage(
                        PETER_ACCOUNT,
                        "message-sealcoat",
                        "key-sealcoat",
                        FixedClock.now(),
                        SECOND_ROOM_ID,
                    ).copy(body = "Sealcoat estimate is ready"),
                ),
            ),
        )

        assertEquals(
            listOf("message-forecast"),
            repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "FOREC"))
                .map { result -> result.messageId.raw },
        )
        assertTrue(
            repository.searchMessages(
                SearchRemoteMessagesCommand(PETER_ACCOUNT, "sealcoat", roomId = ROOM_ID),
            ).isEmpty(),
        )

        repository.activateAccount(TRISH_ACCOUNT)
        cacheRoom(TRISH_ACCOUNT, PETER_PROFILE)
        repository.cacheMessages(
            CacheRemoteMessagesCommand(
                TRISH_ACCOUNT,
                listOf(
                    remoteMessage(TRISH_ACCOUNT, "message-private", "key-private", FixedClock.now())
                        .copy(body = "Private budget notes"),
                ),
            ),
        )
        assertTrue(
            repository.searchMessages(SearchRemoteMessagesCommand(TRISH_ACCOUNT, "forecast")).isEmpty(),
        )
        assertEquals(
            listOf("message-private"),
            repository.searchMessages(SearchRemoteMessagesCommand(TRISH_ACCOUNT, "budget"))
                .map { result -> result.messageId.raw },
        )

        repository.activateAccount(PETER_ACCOUNT)
        assertTrue(repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "budget")).isEmpty())
    }

    @Test
    fun deletedMessagesAndUnauthorizedRoomsAreRemovedFromSearch() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRooms(
            accountUid = PETER_ACCOUNT,
            remoteRoom(PETER_ACCOUNT, ROOM_ID, TRISH_PROFILE, "Peter, Trish"),
            remoteRoom(PETER_ACCOUNT, SECOND_ROOM_ID, JOSH_PROFILE, "Peter, Josh"),
        )
        val deletedMessage = remoteMessage(PETER_ACCOUNT, "message-delete", "key-delete", FixedClock.now())
            .copy(body = "Delete this searchable phrase")
        val removedRoomMessage = remoteMessage(
            PETER_ACCOUNT,
            "message-removed-room",
            "key-removed-room",
            FixedClock.now(),
            SECOND_ROOM_ID,
        ).copy(body = "Orphaned searchable phrase")
        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, listOf(deletedMessage, removedRoomMessage)))

        repository.cacheMessages(
            CacheRemoteMessagesCommand(
                PETER_ACCOUNT,
                listOf(deletedMessage.copy(body = "", deletedAt = FixedClock.now(), revision = 2)),
            ),
        )
        assertTrue(repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "delete")).isEmpty())

        repository.cacheRooms(
            CacheRemoteRoomsCommand(
                PETER_ACCOUNT,
                listOf(remoteRoom(PETER_ACCOUNT, ROOM_ID, TRISH_PROFILE, "Peter, Trish")),
            ),
        )
        assertTrue(repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "orphaned")).isEmpty())
    }

    @Test
    fun hidingMessageLocallySurvivesServerRecacheAndRemovesSearchResult() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        val message = remoteMessage(PETER_ACCOUNT, "message-private", "key-private", FixedClock.now())
            .copy(body = "Private phrase stays hidden")
        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, listOf(message)))

        val receipt = repository.hideMessageLocally(PETER_ACCOUNT, ROOM_ID, message.messageId)

        assertEquals(RemoteCacheMutation.MESSAGE_HIDDEN_LOCALLY, receipt.mutation)
        assertTrue(repository.observeMessages(ROOM_ID).first().isEmpty())
        assertTrue(repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "private")).isEmpty())

        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, listOf(message.copy(revision = 2))))

        assertTrue(repository.observeMessages(ROOM_ID).first().isEmpty())
        assertTrue(repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "private")).isEmpty())
    }

    @Test
    fun hidingConversationLocallyKeepsPeerCopyAndAllowsOnlyNewHistoryToReturn() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        val room = remoteRoom(PETER_ACCOUNT, ROOM_ID, TRISH_PROFILE, "Peter, Trish")
        cacheRooms(PETER_ACCOUNT, room)
        val oldMessage = remoteMessage(PETER_ACCOUNT, "message-old", "key-old", FixedClock.now())
            .copy(body = "Old local history")
        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, listOf(oldMessage)))
        repository.saveDraft(RemoteMessageDraft(PETER_ACCOUNT, ROOM_ID, "Unsent thought", FixedClock.now()))

        val hideReceipt = repository.hideConversationLocally(PETER_ACCOUNT, room)

        assertEquals(RemoteCacheMutation.CONVERSATION_HIDDEN_LOCALLY, hideReceipt.mutation)
        assertTrue(repository.observeRooms().first().isEmpty())
        assertTrue(repository.observeMessages(ROOM_ID).first().isEmpty())
        assertNull(repository.observeDraft(ROOM_ID).first())
        assertTrue(repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "history")).isEmpty())

        val newTimestamp = FixedClock.now().plusSeconds(60)
        cacheRooms(PETER_ACCOUNT, room.copy(remoteUpdatedAt = newTimestamp))
        val newMessage = remoteMessage(PETER_ACCOUNT, "message-new", "key-new", newTimestamp)
            .copy(body = "New conversation activity")
        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, listOf(oldMessage, newMessage)))

        assertEquals(listOf(ROOM_ID), repository.observeRooms().first().map(RemoteCachedRoom::roomId))
        assertEquals(listOf("message-new"), repository.observeMessages(ROOM_ID).first().map { it.messageId.raw })
        assertTrue(repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "history")).isEmpty())
        assertEquals(
            listOf("message-new"),
            repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "activity"))
                .map { it.messageId.raw },
        )
    }

    @Test
    fun explicitlyReopeningLocallyHiddenConversationDoesNotRestoreDeletedHistory() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        val room = remoteRoom(PETER_ACCOUNT, ROOM_ID, TRISH_PROFILE, "Peter, Trish")
        cacheRooms(PETER_ACCOUNT, room)
        repository.cacheMessages(
            CacheRemoteMessagesCommand(
                PETER_ACCOUNT,
                listOf(remoteMessage(PETER_ACCOUNT, "message-old", "key-old", FixedClock.now())),
            ),
        )
        repository.hideConversationLocally(PETER_ACCOUNT, room)

        val receipt = repository.showConversationLocally(PETER_ACCOUNT, ROOM_ID)

        assertEquals(RemoteCacheMutation.CONVERSATION_SHOWN_LOCALLY, receipt.mutation)
        assertEquals(listOf(ROOM_ID), repository.observeRooms().first().map(RemoteCachedRoom::roomId))
        assertTrue(repository.observeMessages(ROOM_ID).first().isEmpty())
    }

    @Test
    fun fullTextSearchRejectsUnboundedResultLimits() = runTest {
        repository.activateAccount(PETER_ACCOUNT)

        val failure = runCatching {
            repository.searchMessages(SearchRemoteMessagesCommand(PETER_ACCOUNT, "hello", limit = 51))
        }

        assertTrue(failure.isFailure)
    }

    @Test
    fun draftsRemainAccountScopedAcrossSwitches() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        repository.saveDraft(RemoteMessageDraft(PETER_ACCOUNT, ROOM_ID, "Peter draft", FixedClock.now()))

        repository.activateAccount(TRISH_ACCOUNT)
        cacheRoom(TRISH_ACCOUNT, PETER_PROFILE)
        assertNull(repository.observeDraft(ROOM_ID).first())
        repository.saveDraft(RemoteMessageDraft(TRISH_ACCOUNT, ROOM_ID, "Trish draft", FixedClock.now()))

        repository.activateAccount(PETER_ACCOUNT)
        assertEquals("Peter draft", repository.observeDraft(ROOM_ID).first()?.body)
        repository.clearDraft(PETER_ACCOUNT, ROOM_ID)
        assertNull(repository.observeDraft(ROOM_ID).first())

        repository.activateAccount(TRISH_ACCOUNT)
        assertEquals("Trish draft", repository.observeDraft(ROOM_ID).first()?.body)
    }

    @Test
    fun richMessageFieldsRoundTripThroughTheAccountCache() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        val richMessage = remoteMessage(PETER_ACCOUNT, "message-rich", "key-rich", FixedClock.now()).copy(
            attachments = listOf(
                RemoteCachedAttachment(
                    attachmentId = RemoteAttachmentId("attachment-12345678-1234-4123-8123-123456789abc"),
                    displayName = "report.pdf",
                    mimeType = "application/pdf",
                    byteCount = 1_024,
                    kind = RemoteAttachmentKind.DOCUMENT,
                    durationMillis = null,
                    contentObjectPath =
                        "roomAttachments/direct-room/message-rich/attachment-12345678-1234-4123-8123-123456789abc/content",
                    thumbnailObjectPath = null,
                ),
            ),
            replyToMessageId = RemoteMessageId("message-parent"),
            editedAt = FixedClock.now(),
            revision = 3,
            reactionCounts = mapOf("👍" to 2, "❤️" to 1),
            deliveredToCount = 2,
            readByCount = 1,
            deliveryState = RemoteMessageDeliveryState.READ,
        )

        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, listOf(richMessage)))

        assertEquals(richMessage, repository.observeMessages(ROOM_ID).first().single())
    }

    @Test
    fun localAiParticipantProvenanceRoundTripsThroughTheAccountCache() = runTest {
        repository.activateAccount(PETER_ACCOUNT)
        cacheRoom(PETER_ACCOUNT, TRISH_PROFILE)
        val aiMessage = remoteMessage(PETER_ACCOUNT, "message-ai", "key-ai", FixedClock.now()).copy(
            senderUid = RemoteProfileUid("participant-synapse-local-ai"),
            authorKind = "SYNAPSE_AI",
            aiParticipantId = "participant-synapse-local-ai",
            aiProvenance = RemoteAiProvenance.PHONE_LOCAL,
        )

        repository.cacheMessages(CacheRemoteMessagesCommand(PETER_ACCOUNT, listOf(aiMessage)))

        assertEquals(aiMessage, repository.observeMessages(ROOM_ID).first().single())
    }

    @Test
    fun accountSwitchAndLogoutCancelRegisteredSessionResources() = runTest {
        val peterResource = RecordingSessionResource()
        val peterToken = sessionCoordinator.beginSession(PETER_ACCOUNT)
        sessionCoordinator.registerResource(peterToken, peterResource)

        val trishResource = RecordingSessionResource()
        val trishToken = sessionCoordinator.beginSession(TRISH_ACCOUNT)
        assertTrue(peterResource.wasCancelled)
        sessionCoordinator.registerResource(trishToken, trishResource)

        val staleResource = RecordingSessionResource()
        val staleRegistration = runCatching {
            sessionCoordinator.registerResource(peterToken, staleResource)
        }
        assertTrue(staleRegistration.isFailure)
        assertTrue(staleResource.wasCancelled)

        sessionCoordinator.endSession()
        assertTrue(trishResource.wasCancelled)
        assertNull(sessionCoordinator.activeSession.value)
    }

    private suspend fun cacheRoom(
        accountUid: RemoteAccountUid,
        peerUid: RemoteProfileUid,
    ) {
        cacheRooms(
            accountUid,
            remoteRoom(accountUid, ROOM_ID, peerUid, "Peter, Trish"),
        )
    }

    private suspend fun cacheRooms(
        accountUid: RemoteAccountUid,
        vararg rooms: RemoteCachedRoom,
    ) {
        repository.cacheRooms(CacheRemoteRoomsCommand(accountUid, rooms.toList()))
    }

    private fun remoteRoom(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        peerUid: RemoteProfileUid,
        title: String,
    ): RemoteCachedRoom =
        RemoteCachedRoom(
            accountUid = accountUid,
            roomId = roomId,
            kind = RemoteRoomKind.DIRECT,
            directKey = "${accountUid.raw}:${peerUid.raw}",
            peerUid = peerUid,
            title = title,
            avatarObjectPath = null,
            unreadCount = 0,
            latestMessagePreview = null,
            latestMessageSenderUid = null,
            currentMemberRole = RemoteRoomMemberRole.MEMBER,
            notificationsEnabled = true,
            isMuted = false,
            isArchived = false,
            isPinned = false,
            joinedAt = FixedClock.now(),
            lastReadAt = null,
            remoteUpdatedAt = FixedClock.now(),
        )

    private fun remoteProfile(
        accountUid: RemoteAccountUid,
        profileUid: RemoteProfileUid,
        username: String,
    ): RemoteCachedProfile =
        RemoteCachedProfile(
            accountUid = accountUid,
            profileUid = profileUid,
            username = username,
            displayName = username,
            bio = "",
            avatarUrl = null,
            isAllowed = true,
            isOnline = false,
            lastSeenAt = null,
            remoteUpdatedAt = FixedClock.now(),
        )

    private fun enqueueMessageCommand(
        accountUid: RemoteAccountUid,
        messageId: String,
        idempotencyKey: String,
        roomId: RemoteRoomId = ROOM_ID,
    ): EnqueueRemoteMessageCommand {
        val cachedMessage = remoteMessage(accountUid, messageId, idempotencyKey, null, roomId)
        return EnqueueRemoteMessageCommand(
            message = cachedMessage,
            outboxOperation = RemoteMessageOutboxOperation(
                accountUid = accountUid,
                operationId = "operation-$messageId",
                roomId = roomId,
                messageId = cachedMessage.messageId,
                idempotencyKey = cachedMessage.idempotencyKey,
                senderUid = PETER_PROFILE,
                body = cachedMessage.body,
                attachments = cachedMessage.attachments,
                replyToMessageId = null,
                state = RemoteOutboxState.PENDING,
                attemptCount = 0,
                createdAt = cachedMessage.clientCreatedAt,
                lastAttemptAt = null,
                failureReason = null,
            ),
        )
    }

    private fun remoteMessage(
        accountUid: RemoteAccountUid,
        messageId: String,
        idempotencyKey: String,
        serverCreatedAt: Instant?,
        roomId: RemoteRoomId = ROOM_ID,
    ): RemoteCachedMessage =
        RemoteCachedMessage(
            accountUid = accountUid,
            roomId = roomId,
            messageId = RemoteMessageId(messageId),
            idempotencyKey = RemoteIdempotencyKey(idempotencyKey),
            senderUid = PETER_PROFILE,
            authorKind = "HUMAN",
            body = "Hello",
            replyToMessageId = null,
            editedAt = null,
            deletedAt = null,
            revision = 1,
            reactionCounts = emptyMap(),
            deliveredToCount = 0,
            readByCount = 0,
            deliveryState = if (serverCreatedAt == null) {
                RemoteMessageDeliveryState.PENDING
            } else {
                RemoteMessageDeliveryState.SENT
            },
            clientCreatedAt = FixedClock.now(),
            serverCreatedAt = serverCreatedAt,
            failureReason = null,
        )

    private class RecordingSessionResource : RemoteAccountSessionResource {
        var wasCancelled = false
            private set

        override fun cancel() {
            wasCancelled = true
        }
    }

    private object FixedClock : SynapseClock {
        override fun now(): Instant = Instant.parse("2026-07-13T04:00:00Z")
    }

    private companion object {
        val PETER_ACCOUNT = RemoteAccountUid("peter-uid")
        val TRISH_ACCOUNT = RemoteAccountUid("trish-uid")
        val PETER_PROFILE = RemoteProfileUid("peter-uid")
        val TRISH_PROFILE = RemoteProfileUid("trish-uid")
        val JOSH_PROFILE = RemoteProfileUid("josh-uid")
        val ROOM_ID = RemoteRoomId("direct-room")
        val SECOND_ROOM_ID = RemoteRoomId("direct-room-two")
    }
}
