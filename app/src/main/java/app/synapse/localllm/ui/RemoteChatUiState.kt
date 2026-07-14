package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteProfileUid

data class RemoteChatUiState(
    val authenticationState: RemoteAuthenticationState = RemoteAuthenticationState.SignedOut,
    val account: RemoteAuthenticatedAccount? = null,
    val profiles: List<RemoteCachedProfile> = emptyList(),
    val rooms: List<RemoteCachedRoom> = emptyList(),
    val selectedRoomId: RemoteRoomId? = null,
    val messages: List<RemoteCachedMessage> = emptyList(),
    val composerText: String = "",
    val replyToMessageId: RemoteMessageId? = null,
    val ownReactions: Map<RemoteMessageId, Set<String>> = emptyMap(),
    val typingParticipantUids: List<RemoteProfileUid> = emptyList(),
    val hasReachedMessageStart: Boolean = false,
    val isLoadingOlderMessages: Boolean = false,
    val messageToRevealId: RemoteMessageId? = null,
    val isActionRunning: Boolean = false,
    val notice: String? = null,
)
