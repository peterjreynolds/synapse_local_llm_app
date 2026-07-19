package app.synapse.localllm.ui

import app.synapse.localllm.application.RemoteLocalAiHostStatus
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentSelection
import app.synapse.localllm.domain.remote.RemoteAssistantAvailability
import app.synapse.localllm.domain.remote.RemoteAssistantConversationEndpoint
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageSearchResult
import app.synapse.localllm.domain.remote.RemoteNotificationPreferences
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomAiConfiguration
import app.synapse.localllm.domain.remote.RemoteRoomId

data class RemoteChatUiState(
    val authenticationState: RemoteAuthenticationState = RemoteAuthenticationState.SignedOut,
    val account: RemoteAuthenticatedAccount? = null,
    val profiles: List<RemoteCachedProfile> = emptyList(),
    val rooms: List<RemoteCachedRoom> = emptyList(),
    val selectedRoomId: RemoteRoomId? = null,
    val assistantAvailabilities: Map<RemoteRoomId, RemoteAssistantAvailability> = emptyMap(),
    val selectedAssistantEndpoint: RemoteAssistantConversationEndpoint? = null,
    val selectedAssistantAvailability: RemoteAssistantAvailability? = null,
    val messages: List<RemoteCachedMessage> = emptyList(),
    val composerText: String = "",
    val pendingAttachments: List<RemotePendingAttachmentUi> = emptyList(),
    val attachmentDownloads: Map<String, RemoteAttachmentDownloadUi> = emptyMap(),
    val isRecordingVoiceNote: Boolean = false,
    val replyToMessageId: RemoteMessageId? = null,
    val ownReactionSelections: Map<RemoteMessageId, String> = emptyMap(),
    val typingParticipantUids: List<RemoteProfileUid> = emptyList(),
    val hasReachedMessageStart: Boolean = false,
    val isLoadingOlderMessages: Boolean = false,
    val messageToRevealId: RemoteMessageId? = null,
    val messageSearchQuery: String = "",
    val messageSearchResults: List<RemoteMessageSearchResult> = emptyList(),
    val isSearchingMessages: Boolean = false,
    val notificationPreferences: RemoteNotificationPreferences = RemoteNotificationPreferences(),
    val currentDeviceId: RemoteDeviceId? = null,
    val roomAiConfiguration: RemoteRoomAiConfiguration? = null,
    val localAiHostStatus: RemoteLocalAiHostStatus = RemoteLocalAiHostStatus.Idle,
    val isActionRunning: Boolean = false,
    val notice: String? = null,
)

enum class RemoteAttachmentTransferState {
    UPLOADING,
    READY,
    FAILED,
}

data class RemotePendingAttachmentUi(
    val messageId: RemoteMessageId,
    val selection: RemoteAttachmentSelection,
    val state: RemoteAttachmentTransferState,
    val transferredBytes: Long,
    val uploadedAttachment: RemoteCachedAttachment?,
    val failureReason: String?,
)

data class RemoteAttachmentDownloadUi(
    val attachmentId: RemoteAttachmentId,
    val thumbnail: Boolean,
    val transferredBytes: Long,
    val totalBytes: Long,
    val localUri: String?,
    val failureReason: String?,
)

internal fun remoteAttachmentDownloadKey(
    attachmentId: RemoteAttachmentId,
    thumbnail: Boolean,
): String = "${attachmentId.raw}:${if (thumbnail) "thumbnail" else "content"}"
