package app.synapse.privatechat.domain.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import kotlinx.coroutines.flow.Flow

sealed interface PrivateChatObservation<out Snapshot> {
    data class Available<Snapshot>(
        val snapshot: Snapshot,
    ) : PrivateChatObservation<Snapshot>

    data object TransportUnavailable : PrivateChatObservation<Nothing>
}

sealed interface PrivateChatMutationOutcome<out Receipt> {
    data class Confirmed<Receipt>(
        val receipt: Receipt,
    ) : PrivateChatMutationOutcome<Receipt>

    data class Rejected(
        val userMessage: String,
    ) : PrivateChatMutationOutcome<Nothing> {
        init {
            require(
                userMessage.isNotBlank() &&
                    userMessage.length <= PRIVATE_MUTATION_REJECTION_MESSAGE_LIMIT &&
                    userMessage.none(Char::isISOControl),
            ) {
                "A rejected mutation requires a bounded user-facing reason."
            }
        }
    }

    data object TransportUnavailable : PrivateChatMutationOutcome<Nothing>
}

interface PrivateChatGateway {
    fun observeRoomFeed(accountId: PrivateAccountId): Flow<PrivateChatObservation<PrivateRoomFeedSnapshot>>

    fun observeConversation(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ): Flow<PrivateChatObservation<PrivateConversationSnapshot>>

    suspend fun sendMessage(command: SendPrivateMessageCommand): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageSent>

    suspend fun editMessage(command: EditPrivateMessageCommand): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageEdited>

    suspend fun deleteMessageForEveryone(
        command: DeletePrivateMessageForEveryoneCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.MessageDeletedForEveryone>

    suspend fun changeReaction(
        command: ChangePrivateReactionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ReactionChanged>

    suspend fun changeRoomRetention(
        command: ChangePrivateRoomRetentionCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RetentionChanged>

    suspend fun changeRoomPreferences(
        command: ChangePrivateRoomPreferencesCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomPreferencesChanged>

    suspend fun changeActivitySharing(
        command: ChangePrivateActivitySharingCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.ActivitySharingChanged>

    suspend fun acknowledgeRoomRead(
        command: AcknowledgePrivateRoomReadCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.RoomReadAcknowledged>

    suspend fun publishTypingState(
        command: PublishPrivateTypingStateCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.TypingStatePublished>

    suspend fun createOneUseRoomInvitation(
        command: CreatePrivateOneUseRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateChatMutationReceipt.OneUseRoomInvitationCreated>
}

private const val PRIVATE_MUTATION_REJECTION_MESSAGE_LIMIT = 256
