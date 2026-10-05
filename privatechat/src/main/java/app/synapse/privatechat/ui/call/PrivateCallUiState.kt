package app.synapse.privatechat.ui.call

import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallPeerSafetyNumber
import app.synapse.privatechat.domain.call.PrivateCallPreparation
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import java.util.UUID

enum class PrivateCallAvailability { CONNECTING, AVAILABLE, UNAVAILABLE }

enum class PrivateCallDirection { OUTGOING, INCOMING }

enum class PrivateCallStage { CONNECTING, RINGING, CONNECTED, RECONNECTING }

sealed interface PrivateCallConsentRequest {
    val title: String
    val mediaKind: PrivateCallMediaKind

    data class Outgoing(
        val room: PrivateRoomSummary,
        val preparation: PrivateCallPreparation,
        override val mediaKind: PrivateCallMediaKind,
    ) : PrivateCallConsentRequest {
        override val title: String get() = room.title
    }

    data class Incoming(
        val session: PrivateCallSession,
        val offer: PrivateCallMediaSignal.Offer,
        override val title: String,
    ) : PrivateCallConsentRequest {
        override val mediaKind: PrivateCallMediaKind get() = session.mediaKind
    }
}

sealed interface PrivateCallUiState {
    data object Idle : PrivateCallUiState

    data class Preparing(
        val title: String,
    ) : PrivateCallUiState

    data class Consent(
        val request: PrivateCallConsentRequest,
        val safetyNumbers: List<PrivateCallPeerSafetyNumber>,
    ) : PrivateCallUiState

    data class Ongoing(
        val callId: UUID,
        val title: String,
        val mediaKind: PrivateCallMediaKind,
        val direction: PrivateCallDirection,
        val stage: PrivateCallStage,
        val microphoneMuted: Boolean = false,
        val speakerEnabled: Boolean = false,
        val cameraEnabled: Boolean = mediaKind == PrivateCallMediaKind.VIDEO,
        val mediaReady: Boolean = false,
    ) : PrivateCallUiState

    data class Stopping(
        val message: String,
    ) : PrivateCallUiState

    data class Finished(
        val message: String,
    ) : PrivateCallUiState
}
