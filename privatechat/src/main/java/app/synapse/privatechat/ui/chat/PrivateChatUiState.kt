package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateChatInputField
import app.synapse.privatechat.domain.chat.PrivateChatMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageText
import app.synapse.privatechat.domain.chat.PrivateMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateReplyPreview
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId

sealed interface PrivateChatSessionUiState {
    data object SignedOut : PrivateChatSessionUiState

    data class Active(
        val accountId: PrivateAccountId,
    ) : PrivateChatSessionUiState
}

sealed interface PrivateRoomFeedUiState {
    data object NotRequested : PrivateRoomFeedUiState

    data object Loading : PrivateRoomFeedUiState

    data class Available(
        val snapshot: PrivateRoomFeedSnapshot,
        val connectionState: PrivateChatConnectionUiState = PrivateChatConnectionUiState.CONNECTED,
    ) : PrivateRoomFeedUiState

    data object TransportUnavailable : PrivateRoomFeedUiState

    data object UnexpectedFailure : PrivateRoomFeedUiState
}

sealed interface PrivateConversationUiState {
    data object NotSelected : PrivateConversationUiState

    data object Loading : PrivateConversationUiState

    data class Available(
        val snapshot: PrivateConversationSnapshot,
        val connectionState: PrivateChatConnectionUiState = PrivateChatConnectionUiState.CONNECTED,
    ) : PrivateConversationUiState

    data object TransportUnavailable : PrivateConversationUiState

    data object UnexpectedFailure : PrivateConversationUiState
}

enum class PrivateChatConnectionUiState {
    CONNECTED,
    RECONNECTING,
}

sealed interface PrivateComposerMode {
    data object NewMessage : PrivateComposerMode

    data class ReplyingTo(
        val preview: PrivateReplyPreview,
    ) : PrivateComposerMode

    data class Editing(
        val messageId: PrivateMessageId,
        val expectedRevision: Long,
        val originalBody: PrivateMessageText,
        val draftBeforeEdit: String,
    ) : PrivateComposerMode {
        override fun toString(): String =
            "Editing(messageId=$messageId, expectedRevision=$expectedRevision, originalBody=[REDACTED], draftBeforeEdit=[REDACTED])"
    }
}

enum class PrivateChatOperationKind {
    SEND_MESSAGE,
    EDIT_MESSAGE,
    DELETE_MESSAGE_FOR_EVERYONE,
    CHANGE_REACTION,
    CHANGE_RETENTION,
    CHANGE_ROOM_PREFERENCES,
    CHANGE_ACTIVITY_SHARING,
    UPDATE_PROFILE,
    CREATE_ROOM,
    REDEEM_ROOM_INVITATION,
    CHANGE_GROUP_MEMBER_ROLE,
    REMOVE_GROUP_MEMBER,
    CHANGE_PRESENCE_SHARING,
}

sealed interface PrivateChatOperationUiState {
    data object Idle : PrivateChatOperationUiState

    data class Running(
        val kind: PrivateChatOperationKind,
    ) : PrivateChatOperationUiState

    data class Confirmed(
        val receipt: PrivateMutationReceipt,
    ) : PrivateChatOperationUiState

    data class Recovered(
        val kind: PrivateChatOperationKind,
    ) : PrivateChatOperationUiState

    data class InvalidInput(
        val field: PrivateChatInputField,
        val userMessage: String,
    ) : PrivateChatOperationUiState

    data class Rejected(
        val userMessage: String,
    ) : PrivateChatOperationUiState

    data object TransportUnavailable : PrivateChatOperationUiState

    data object UnexpectedFailure : PrivateChatOperationUiState
}

sealed interface PrivateRoomInvitationUiState {
    data object Hidden : PrivateRoomInvitationUiState

    data object Creating : PrivateRoomInvitationUiState

    data class Confirmed(
        val receipt: PrivateChatMutationReceipt.OneUseRoomInvitationCreated,
    ) : PrivateRoomInvitationUiState

    data class Rejected(
        val userMessage: String,
    ) : PrivateRoomInvitationUiState

    data object TransportUnavailable : PrivateRoomInvitationUiState

    data object UnexpectedFailure : PrivateRoomInvitationUiState
}

data class PrivateChatUiState(
    val session: PrivateChatSessionUiState = PrivateChatSessionUiState.SignedOut,
    val social: PrivateSocialUiState = PrivateSocialUiState.NotRequested,
    val roomFeed: PrivateRoomFeedUiState = PrivateRoomFeedUiState.NotRequested,
    val selectedRoomId: PrivateRoomId? = null,
    val conversation: PrivateConversationUiState = PrivateConversationUiState.NotSelected,
    val composerText: String = "",
    val composerMode: PrivateComposerMode = PrivateComposerMode.NewMessage,
    val operation: PrivateChatOperationUiState = PrivateChatOperationUiState.Idle,
    val roomInvitation: PrivateRoomInvitationUiState = PrivateRoomInvitationUiState.Hidden,
    val accountInvitation: PrivateAccountInvitationUiState = PrivateAccountInvitationUiState.Hidden,
    val presencePublication: PrivatePresencePublicationUiState = PrivatePresencePublicationUiState.NotSharing,
    val overlay: PrivateChatOverlay = PrivateChatOverlay.HIDDEN,
) {
    override fun toString(): String =
        "PrivateChatUiState(" +
            "session=$session, " +
            "social=${social::class.simpleName}, " +
            "roomFeed=${roomFeed::class.simpleName}, " +
            "selectedRoomId=$selectedRoomId, " +
            "conversation=${conversation::class.simpleName}, " +
            "composerText=[REDACTED], " +
            "composerMode=${composerMode::class.simpleName}, " +
            "operation=${operation::class.simpleName}, " +
            "roomInvitation=${roomInvitation::class.simpleName}, " +
            "accountInvitation=${accountInvitation::class.simpleName}, " +
            "presencePublication=${presencePublication::class.simpleName}, " +
            "overlay=$overlay)"
}
