package app.synapse.localllm.application

import app.synapse.localllm.domain.remote.CacheRemoteMessagesCommand
import app.synapse.localllm.domain.remote.CacheRemoteProfilesCommand
import app.synapse.localllm.domain.remote.CacheRemoteRoomsCommand
import app.synapse.localllm.domain.remote.EnqueueRemoteMessageCommand
import app.synapse.localllm.domain.remote.OpenRemoteDirectRoomCommand
import app.synapse.localllm.domain.remote.OpenRemoteDirectRoomReceipt
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCacheMutation
import app.synapse.localllm.domain.remote.RemoteCacheMutationReceipt
import app.synapse.localllm.domain.remote.RemoteCachedDirectRoom
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDirectRoomSnapshot
import app.synapse.localllm.domain.remote.RemoteDirectoryGateway
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteMessageSendReceipt
import app.synapse.localllm.domain.remote.RemoteOutboxState
import app.synapse.localllm.domain.remote.RemoteProfileMutationReceipt
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteSyncCursor
import app.synapse.localllm.domain.remote.SendRemoteMessageCommand
import app.synapse.localllm.domain.remote.UpdateRemoteProfileCommand
import app.synapse.localllm.domain.remote.UploadRemoteAvatarCommand
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatSessionSynchronizerTest {
    @Test
    fun repeatedOutboxEmissionSendsOnceAndPersistsCompletionReceipt() = runTest {
        val conversationGateway = RecordingConversationGateway()
        val cacheRepository = RecordingCacheRepository()
        val synchronizer = RemoteChatSessionSynchronizer(
            directoryGateway = EmptyDirectoryGateway,
            conversationGateway = conversationGateway,
            cacheRepository = cacheRepository,
            clock = FixedClock,
        )
        backgroundScope.launch {
            synchronizer.synchronize(
                accountUid = PETER_ACCOUNT,
                selectedRoomId = MutableStateFlow(null),
                reportFailure = { failure -> error(failure) },
            )
        }
        runCurrent()
        val operation = pendingOperation()

        cacheRepository.outboxOperations.emit(listOf(operation))
        runCurrent()
        cacheRepository.outboxOperations.emit(listOf(operation))
        runCurrent()

        assertEquals(1, conversationGateway.sentCommands.size)
        assertEquals(operation.messageId, conversationGateway.sentCommands.single().message.messageId)
        assertEquals(RemoteMessageDeliveryState.SENT, cacheRepository.lastDeliveryState)
        assertEquals(RemoteOutboxState.COMPLETE, cacheRepository.lastOutboxState)
    }

    private fun pendingOperation(): RemoteMessageOutboxOperation =
        RemoteMessageOutboxOperation(
            accountUid = PETER_ACCOUNT,
            operationId = "send-message-1",
            roomId = ROOM_ID,
            messageId = RemoteMessageId("message-1"),
            idempotencyKey = RemoteIdempotencyKey("message-1"),
            senderUid = RemoteProfileUid(PETER_ACCOUNT.raw),
            body = "Hello Trish",
            state = RemoteOutboxState.PENDING,
            attemptCount = 0,
            createdAt = NOW,
            lastAttemptAt = null,
            failureReason = null,
        )

    private class RecordingConversationGateway : RemoteConversationGateway {
        val sentCommands = mutableListOf<SendRemoteMessageCommand>()

        override fun observeDirectRooms(
            accountUid: RemoteAccountUid,
        ): Flow<List<RemoteDirectRoomSnapshot>> = emptyFlow()

        override fun observeMessages(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
        ): Flow<List<RemoteCachedMessage>> = emptyFlow()

        override suspend fun openDirectRoom(
            command: OpenRemoteDirectRoomCommand,
        ): OpenRemoteDirectRoomReceipt = error("Not used by this test.")

        override suspend fun sendMessage(command: SendRemoteMessageCommand): RemoteMessageSendReceipt {
            sentCommands += command
            return RemoteMessageSendReceipt(
                accountUid = command.message.accountUid,
                roomId = command.message.roomId,
                messageId = command.message.messageId,
            )
        }

        override suspend fun markRoomRead(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
        ) = Unit
    }

    private class RecordingCacheRepository : RemoteChatCacheRepository {
        override val activeAccountUid: StateFlow<RemoteAccountUid?> = MutableStateFlow(PETER_ACCOUNT)
        val outboxOperations = MutableSharedFlow<List<RemoteMessageOutboxOperation>>(extraBufferCapacity = 2)
        var lastDeliveryState: RemoteMessageDeliveryState? = null
        var lastOutboxState: RemoteOutboxState? = null

        override suspend fun activateAccount(accountUid: RemoteAccountUid) = Unit

        override suspend fun clearActiveAccount() = Unit

        override fun observeProfiles(): Flow<List<RemoteCachedProfile>> = emptyFlow()

        override fun observeDirectRooms(): Flow<List<RemoteCachedDirectRoom>> = emptyFlow()

        override fun observeMessages(roomId: RemoteRoomId): Flow<List<RemoteCachedMessage>> = emptyFlow()

        override fun observePendingOutbox(): Flow<List<RemoteMessageOutboxOperation>> = outboxOperations

        override suspend fun cacheProfiles(
            command: CacheRemoteProfilesCommand,
        ): RemoteCacheMutationReceipt = receipt(RemoteCacheMutation.PROFILES_CACHED)

        override suspend fun cacheRooms(
            command: CacheRemoteRoomsCommand,
        ): RemoteCacheMutationReceipt = receipt(RemoteCacheMutation.ROOMS_CACHED)

        override suspend fun cacheMessages(
            command: CacheRemoteMessagesCommand,
        ): RemoteCacheMutationReceipt = receipt(RemoteCacheMutation.MESSAGES_CACHED)

        override suspend fun enqueueMessage(
            command: EnqueueRemoteMessageCommand,
        ): RemoteCacheMutationReceipt = receipt(RemoteCacheMutation.MESSAGE_ENQUEUED)

        override suspend fun updateMessageDelivery(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
            messageId: RemoteMessageId,
            deliveryState: RemoteMessageDeliveryState,
            outboxState: RemoteOutboxState,
            attemptedAt: Instant,
            failureReason: String?,
        ): RemoteCacheMutationReceipt {
            lastDeliveryState = deliveryState
            lastOutboxState = outboxState
            return receipt(RemoteCacheMutation.DELIVERY_UPDATED)
        }

        override suspend fun saveSyncCursor(cursor: RemoteSyncCursor): RemoteCacheMutationReceipt =
            receipt(RemoteCacheMutation.CURSOR_SAVED)

        override suspend fun findSyncCursor(
            collectionName: String,
            scopeId: String,
        ): RemoteSyncCursor? = null

        private fun receipt(mutation: RemoteCacheMutation) =
            RemoteCacheMutationReceipt(PETER_ACCOUNT, mutation, affectedRows = 1)
    }

    private object EmptyDirectoryGateway : RemoteDirectoryGateway {
        override fun observeAllowedProfiles(accountUid: RemoteAccountUid): Flow<List<RemoteCachedProfile>> =
            emptyFlow()

        override suspend fun updateProfile(command: UpdateRemoteProfileCommand): RemoteProfileMutationReceipt =
            error("Not used by this test.")

        override suspend fun updatePresence(
            accountUid: RemoteAccountUid,
            online: Boolean,
        ): RemoteProfileMutationReceipt = error("Not used by this test.")

        override suspend fun uploadAvatar(command: UploadRemoteAvatarCommand): RemoteProfileMutationReceipt =
            error("Not used by this test.")
    }

    private object FixedClock : SynapseClock {
        override fun now(): Instant = NOW
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-13T08:00:00Z")
        val PETER_ACCOUNT = RemoteAccountUid("peter-uid")
        val ROOM_ID = RemoteRoomId("direct_${"a".repeat(64)}")
    }
}
