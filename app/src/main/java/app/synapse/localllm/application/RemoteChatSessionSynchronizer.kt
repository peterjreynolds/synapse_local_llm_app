package app.synapse.localllm.application

import app.synapse.localllm.domain.remote.AcknowledgeRemoteMessagesCommand
import app.synapse.localllm.domain.remote.CacheRemoteMessagesCommand
import app.synapse.localllm.domain.remote.CacheRemoteProfilesCommand
import app.synapse.localllm.domain.remote.CacheRemoteRoomsCommand
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDirectoryGateway
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteOutboxState
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.SendRemoteMessageCommand
import app.synapse.localllm.domain.time.SynapseClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatSessionSynchronizer(
    private val directoryGateway: RemoteDirectoryGateway,
    private val conversationGateway: RemoteConversationGateway,
    private val cacheRepository: RemoteChatCacheRepository,
    private val clock: SynapseClock,
) {
    suspend fun synchronize(
        accountUid: RemoteAccountUid,
        selectedRoomId: MutableStateFlow<RemoteRoomId?>,
        reportFailure: (String) -> Unit,
    ): Nothing = supervisorScope {
        val attemptedOutboxOperationIds = mutableSetOf<String>()
        launchBoundary("keep the people directory synchronized", reportFailure) {
            directoryGateway.observeAllowedProfiles(accountUid).collect { profiles ->
                cacheRepository.cacheProfiles(CacheRemoteProfilesCommand(accountUid, profiles))
            }
        }
        launchBoundary("keep remote conversations synchronized", reportFailure) {
            conversationGateway.observeRooms(accountUid).collect { rooms ->
                cacheRepository.cacheRooms(
                    CacheRemoteRoomsCommand(
                        accountUid = accountUid,
                        rooms = rooms,
                    ),
                )
            }
        }
        launchBoundary("keep remote messages synchronized", reportFailure) {
            selectedRoomId
                .filterNotNull()
                .distinctUntilChanged()
                .flatMapLatest { roomId ->
                    conversationGateway.observeMessages(accountUid, roomId).map { messages -> roomId to messages }
                }
                .collect { (roomId, messages) ->
                    cacheRepository.cacheMessages(CacheRemoteMessagesCommand(accountUid, messages))
                    messages
                        .filter { message -> message.senderUid.raw != accountUid.raw }
                        .map { message -> message.messageId }
                        .chunked(MAXIMUM_ACKNOWLEDGEMENT_SIZE)
                        .filter { messageIds -> messageIds.isNotEmpty() }
                        .forEach { messageIds ->
                            conversationGateway.acknowledgeMessages(
                                AcknowledgeRemoteMessagesCommand(accountUid, roomId, messageIds, read = false),
                            )
                        }
                }
        }
        launchBoundary("reconcile pending remote messages", reportFailure) {
            cacheRepository.observePendingOutbox().collect { operations ->
                operations
                    .filter { operation ->
                        operation.accountUid == accountUid && attemptedOutboxOperationIds.add(operation.operationId)
                    }
                    .forEach { operation -> reconcileOutboxOperation(operation, reportFailure) }
            }
        }
        awaitCancellation()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchBoundary(
        operation: String,
        reportFailure: (String) -> Unit,
        block: suspend () -> Unit,
    ) = launch {
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RemoteChatException) {
            reportFailure(exception.userMessage)
        } catch (_: Exception) {
            reportFailure("Could not $operation. Try signing in again.")
        }
    }

    private suspend fun reconcileOutboxOperation(
        operation: RemoteMessageOutboxOperation,
        reportFailure: (String) -> Unit,
    ) {
        try {
            conversationGateway.sendMessage(
                SendRemoteMessageCommand(
                    message = RemoteCachedMessage(
                        accountUid = operation.accountUid,
                        roomId = operation.roomId,
                        messageId = operation.messageId,
                        idempotencyKey = operation.idempotencyKey,
                        senderUid = operation.senderUid,
                        authorKind = HUMAN_AUTHOR_KIND,
                        body = operation.body,
                        replyToMessageId = operation.replyToMessageId,
                        editedAt = null,
                        deletedAt = null,
                        revision = 1,
                        reactionCounts = emptyMap(),
                        deliveredToCount = 0,
                        readByCount = 0,
                        deliveryState = RemoteMessageDeliveryState.PENDING,
                        clientCreatedAt = operation.createdAt,
                        serverCreatedAt = null,
                        failureReason = null,
                    ),
                ),
            )
            cacheRepository.updateMessageDelivery(
                accountUid = operation.accountUid,
                roomId = operation.roomId,
                messageId = operation.messageId,
                deliveryState = RemoteMessageDeliveryState.SENT,
                outboxState = RemoteOutboxState.COMPLETE,
                attemptedAt = clock.now(),
                failureReason = null,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            val userMessage = (exception as? RemoteChatException)?.userMessage
                ?: "Could not send the message. It will retry after the next sign-in."
            cacheRepository.updateMessageDelivery(
                accountUid = operation.accountUid,
                roomId = operation.roomId,
                messageId = operation.messageId,
                deliveryState = RemoteMessageDeliveryState.FAILED,
                outboxState = RemoteOutboxState.FAILED,
                attemptedAt = clock.now(),
                failureReason = userMessage,
            )
            reportFailure(userMessage)
        }
    }

    private companion object {
        const val HUMAN_AUTHOR_KIND = "HUMAN"
        const val MAXIMUM_ACKNOWLEDGEMENT_SIZE = 50
    }
}
