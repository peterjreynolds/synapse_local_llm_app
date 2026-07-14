package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteRoomId

data class RemoteChatUiState(
    val authenticationState: RemoteAuthenticationState = RemoteAuthenticationState.SignedOut,
    val account: RemoteAuthenticatedAccount? = null,
    val profiles: List<RemoteCachedProfile> = emptyList(),
    val rooms: List<RemoteCachedRoom> = emptyList(),
    val selectedRoomId: RemoteRoomId? = null,
    val messages: List<RemoteCachedMessage> = emptyList(),
    val isActionRunning: Boolean = false,
    val notice: String? = null,
)
