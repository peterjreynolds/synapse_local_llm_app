package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateSocialSnapshot

sealed interface PrivateSocialUiState {
    data object NotRequested : PrivateSocialUiState

    data object Loading : PrivateSocialUiState

    data class Available(
        val snapshot: PrivateSocialSnapshot,
    ) : PrivateSocialUiState

    data object TransportUnavailable : PrivateSocialUiState

    data object UnexpectedFailure : PrivateSocialUiState
}

enum class PrivateChatOverlay {
    HIDDEN,
    PROFILE,
    CREATE_CONVERSATION,
    MANAGE_GROUP,
}

sealed interface PrivatePresencePublicationUiState {
    data object NotSharing : PrivatePresencePublicationUiState

    data object Background : PrivatePresencePublicationUiState

    data object Publishing : PrivatePresencePublicationUiState

    data class Confirmed(
        val expiresAt: java.time.Instant,
    ) : PrivatePresencePublicationUiState

    data object TransportUnavailable : PrivatePresencePublicationUiState

    data object UnexpectedFailure : PrivatePresencePublicationUiState
}

sealed interface PrivateAccountInvitationUiState {
    data object Hidden : PrivateAccountInvitationUiState

    data object Creating : PrivateAccountInvitationUiState

    data class Confirmed(
        val receipt: PrivateSocialMutationReceipt.OneUseAccountInvitationCreated,
    ) : PrivateAccountInvitationUiState

    data class Rejected(
        val userMessage: String,
    ) : PrivateAccountInvitationUiState

    data object TransportUnavailable : PrivateAccountInvitationUiState

    data object UnexpectedFailure : PrivateAccountInvitationUiState
}
