package app.synapse.localllm.ui

import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatThreadRecord
import app.synapse.localllm.domain.chat.PendingAttachment
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageHealthSnapshot

enum class SynapsePanel {
    CHAT,
    MEMORY,
    SETTINGS,
}

data class RuntimeSettingsDraft(
    val baseUrl: String = "http://127.0.0.1:8080",
    val modelName: String = "local-llama",
    val systemPrompt: String = "",
    val temperature: String = "0.7",
    val maxTokens: String = "768",
)

data class SynapseUiState(
    val settings: SynapseSettings = SynapseSettings(),
    val settingsDraft: RuntimeSettingsDraft = RuntimeSettingsDraft(
        systemPrompt = SynapseSettings().systemPrompt,
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
    val lastNotice: String? = null,
    val memorySearchQuery: String = "",
    val memorySearchResults: List<RetrievedMemoryRef> = emptyList(),
    val storageHealthSnapshot: StorageHealthSnapshot? = null,
)
