package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import java.util.UUID

internal sealed interface PrivateEncryptedMutationIntent {
    val clientMutationId: UUID

    fun attachEnvelopes(envelopes: List<PrivateChatEncryptedEnvelope>): PrivatePendingEncryptedMutation

    data class SendMessage(
        val roomId: UUID,
        override val clientMutationId: UUID,
        val replyToMessageId: UUID?,
    ) : PrivateEncryptedMutationIntent {
        override fun attachEnvelopes(envelopes: List<PrivateChatEncryptedEnvelope>): PrivatePendingEncryptedMutation =
            PrivatePendingEncryptedMutation.SendMessage(roomId, clientMutationId, replyToMessageId, envelopes)
    }

    data class EditMessage(
        val messageId: UUID,
        override val clientMutationId: UUID,
        val expectedServerRevision: Int,
    ) : PrivateEncryptedMutationIntent {
        override fun attachEnvelopes(envelopes: List<PrivateChatEncryptedEnvelope>): PrivatePendingEncryptedMutation =
            PrivatePendingEncryptedMutation.EditMessage(
                messageId,
                clientMutationId,
                expectedServerRevision,
                envelopes,
            )
    }

    data class AddReaction(
        val messageId: UUID,
        override val clientMutationId: UUID,
    ) : PrivateEncryptedMutationIntent {
        override fun attachEnvelopes(envelopes: List<PrivateChatEncryptedEnvelope>): PrivatePendingEncryptedMutation =
            PrivatePendingEncryptedMutation.AddReaction(messageId, clientMutationId, envelopes)
    }

    data class CreateRoom(
        val roomId: UUID,
        override val clientMutationId: UUID,
        val kind: PrivateRoomKind,
        val retention: PrivateMessageRetention,
    ) : PrivateEncryptedMutationIntent {
        override fun attachEnvelopes(envelopes: List<PrivateChatEncryptedEnvelope>): PrivatePendingEncryptedMutation =
            PrivatePendingEncryptedMutation.CreateRoom(roomId, clientMutationId, kind, retention, envelopes)
    }
}

internal sealed interface PrivatePendingEncryptedMutation {
    val clientMutationId: UUID
    val envelopes: List<PrivateChatEncryptedEnvelope>

    data class SendMessage(
        val roomId: UUID,
        override val clientMutationId: UUID,
        val replyToMessageId: UUID?,
        override val envelopes: List<PrivateChatEncryptedEnvelope>,
    ) : PrivatePendingEncryptedMutation

    data class EditMessage(
        val messageId: UUID,
        override val clientMutationId: UUID,
        val expectedServerRevision: Int,
        override val envelopes: List<PrivateChatEncryptedEnvelope>,
    ) : PrivatePendingEncryptedMutation

    data class AddReaction(
        val messageId: UUID,
        override val clientMutationId: UUID,
        override val envelopes: List<PrivateChatEncryptedEnvelope>,
    ) : PrivatePendingEncryptedMutation

    data class CreateRoom(
        val roomId: UUID,
        override val clientMutationId: UUID,
        val kind: PrivateRoomKind,
        val retention: PrivateMessageRetention,
        override val envelopes: List<PrivateChatEncryptedEnvelope>,
    ) : PrivatePendingEncryptedMutation
}

internal sealed interface PrivateEncryptedMutationBackendReceipt {
    data class MessageSent(
        val receipt: PrivateBackendMessageSendReceipt,
    ) : PrivateEncryptedMutationBackendReceipt

    data class MessageEdited(
        val receipt: PrivateBackendMessageEditReceipt,
    ) : PrivateEncryptedMutationBackendReceipt

    data class ReactionAdded(
        val receipt: PrivateBackendReactionSendReceipt,
    ) : PrivateEncryptedMutationBackendReceipt

    data class RoomCreated(
        val receipt: PrivateBackendRoomCreationReceipt,
    ) : PrivateEncryptedMutationBackendReceipt
}
