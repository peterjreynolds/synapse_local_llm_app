package app.synapse.localllm.domain.diagnostics

data class DebugUiSnapshot(
    val activePanel: String,
    val isThreadDrawerOpen: Boolean,
    val currentThreadId: String?,
    val currentThreadTitle: String?,
    val visibleThreadCount: Int,
    val visibleMessageCount: Int,
    val composerCharacterCount: Int,
    val pendingAttachmentCount: Int,
    val isSending: Boolean,
    val isImportingModel: Boolean,
    val lastNotice: String?,
)
