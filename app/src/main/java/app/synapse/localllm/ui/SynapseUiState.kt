package app.synapse.localllm.ui

import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatThreadRecord
import app.synapse.localllm.domain.chat.PendingAttachment
import app.synapse.localllm.domain.library.LibraryArtifactRecord
import app.synapse.localllm.domain.memory.MemoryReviewFilter
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import app.synapse.localllm.domain.runtime.ModelPromptProfile
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageHealthSnapshot

enum class SynapsePanel {
    CHAT,
    LIBRARY,
    MEMORY,
    SETTINGS,
}

data class RuntimeSettingsDraft(
    val runtimeBackend: InferenceRuntimeBackend = InferenceRuntimeBackend.EMBEDDED_LLAMA,
    val baseUrl: String = "http://127.0.0.1:8080",
    val modelName: String = "local-llama",
    val modelPromptProfile: ModelPromptProfile = ModelPromptProfile.AUTO,
    val persona: String = "",
    val customInstructions: String = "",
    val temperature: String = "0.7",
    val maxTokens: String = "256",
)

enum class VoiceModeStatus {
    OFF,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR,
}

data class VoiceModeUiState(
    val status: VoiceModeStatus = VoiceModeStatus.OFF,
    val recognitionRequestId: Long = 0,
    val speechRequestId: Long = 0,
    val speechText: String = "",
    val errorMessage: String? = null,
) {
    val isActive: Boolean
        get() = status != VoiceModeStatus.OFF && status != VoiceModeStatus.ERROR
}

data class SynapseUiState(
    val settings: SynapseSettings = SynapseSettings(),
    val settingsDraft: RuntimeSettingsDraft = RuntimeSettingsDraft(
        persona = SynapseSettings().persona,
        customInstructions = SynapseSettings().customInstructions,
    ),
    val runtimeStatus: RuntimeStatus = RuntimeStatus.Unknown,
    val currentThread: ChatThreadRecord? = null,
    val threads: List<ChatThreadRecord> = emptyList(),
    val isThreadDrawerOpen: Boolean = false,
    val messages: List<ChatMessageRecord> = emptyList(),
    val composerText: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val activePanel: SynapsePanel = SynapsePanel.CHAT,
    val isSending: Boolean = false,
    val isImportingModel: Boolean = false,
    val lastNotice: String? = null,
    val memorySearchQuery: String = "",
    val memoryReviewFilter: MemoryReviewFilter = MemoryReviewFilter.ACTIVE,
    val memorySearchResults: List<RetrievedMemoryRef> = emptyList(),
    val libraryDraftTitle: String = "",
    val libraryDraftMarkdown: String = "",
    val libraryArtifacts: List<LibraryArtifactRecord> = emptyList(),
    val isCreatingLibraryArtifact: Boolean = false,
    val isExportingLibraryPdf: Boolean = false,
    val voiceMode: VoiceModeUiState = VoiceModeUiState(),
    val storageHealthSnapshot: StorageHealthSnapshot? = null,
)
