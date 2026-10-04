package app.synapse.privatechat.ui.call

import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.chat.PrivateRoomSummary

data class PrivateCallUiActions(
    val available: Boolean,
    val start: (PrivateRoomSummary, PrivateCallMediaKind) -> Unit,
)
