package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.AcknowledgePrivateRoomReadCommand
import app.synapse.privatechat.domain.chat.ChangePrivateActivitySharingCommand
import app.synapse.privatechat.domain.chat.ChangePrivateReactionCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomPreferencesCommand
import app.synapse.privatechat.domain.chat.ChangePrivateRoomRetentionCommand
import app.synapse.privatechat.domain.chat.CreatePrivateOneUseRoomInvitationCommand
import app.synapse.privatechat.domain.chat.DeletePrivateMessageForEveryoneCommand
import app.synapse.privatechat.domain.chat.EditPrivateMessageCommand
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PublishPrivateTypingStateCommand
import app.synapse.privatechat.domain.chat.SendPrivateMessageCommand
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

internal class SupabasePrivateChatGateway(
    private val execution: PrivateChatGatewayExecution,
    private val pollingRepository: PrivateChatPollingRepository,
    private val snapshotAssembler: PrivateChatSnapshotAssembler,
    private val mutations: PrivateChatMutationCoordinator,
    private val waitForNextPoll: suspend () -> Unit = { delay(DEFAULT_POLL_INTERVAL_MILLIS) },
) : PrivateChatGateway {
    override fun observeRoomFeed(accountId: PrivateAccountId): Flow<PrivateChatObservation<PrivateRoomFeedSnapshot>> =
        pollingFlow {
            execution.observe(accountId) { session ->
                snapshotAssembler.roomFeed(pollingRepository.load(session))
            }
        }

    override fun observeConversation(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ): Flow<PrivateChatObservation<PrivateConversationSnapshot>> =
        pollingFlow {
            execution.observe(accountId) { session ->
                snapshotAssembler.conversation(
                    state = pollingRepository.load(session),
                    roomId = roomId.canonical.requireUuid(),
                ) ?: throw SupabasePrivateChatResponseException("Requested conversation is unavailable")
            }
        }

    override suspend fun sendMessage(
        command: SendPrivateMessageCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageSent> = mutations.sendMessage(command)

    override suspend fun editMessage(
        command: EditPrivateMessageCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageEdited> = mutations.editMessage(command)

    override suspend fun deleteMessageForEveryone(
        command: DeletePrivateMessageForEveryoneCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageDeletedForEveryone> = mutations.deleteMessageForEveryone(command)

    override suspend fun changeReaction(
        command: ChangePrivateReactionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ReactionChanged> = mutations.changeReaction(command)

    override suspend fun changeRoomRetention(
        command: ChangePrivateRoomRetentionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RetentionChanged> = mutations.changeRoomRetention(command)

    override suspend fun changeRoomPreferences(
        command: ChangePrivateRoomPreferencesCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomPreferencesChanged> = mutations.changeRoomPreferences(command)

    override suspend fun changeActivitySharing(
        command: ChangePrivateActivitySharingCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ActivitySharingChanged> = mutations.changeActivitySharing(command)

    override suspend fun acknowledgeRoomRead(
        command: AcknowledgePrivateRoomReadCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomReadAcknowledged> = mutations.acknowledgeRoomRead(command)

    override suspend fun publishTypingState(
        command: PublishPrivateTypingStateCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.TypingStatePublished> = mutations.publishTypingState(command)

    override suspend fun createOneUseRoomInvitation(
        command: CreatePrivateOneUseRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.OneUseRoomInvitationCreated> = mutations.createOneUseRoomInvitation(command)

    private fun <Snapshot> pollingFlow(
        loadObservation: suspend () -> PrivateChatObservation<Snapshot>,
    ): Flow<PrivateChatObservation<Snapshot>> =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(loadObservation())
                waitForNextPoll()
            }
        }
}

private const val DEFAULT_POLL_INTERVAL_MILLIS = 5_000L
