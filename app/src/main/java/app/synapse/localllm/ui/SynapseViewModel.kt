package app.synapse.localllm.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.SynapseTurnOutcome
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.chat.ChatThreadRecord
import app.synapse.localllm.domain.diagnostics.DebugUiSnapshot
import app.synapse.localllm.domain.chat.PendingAttachment
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.MemoryObjectId
import app.synapse.localllm.domain.runtime.ImportEmbeddedModelCommand
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.runtime.StartLlamaServerCommand
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageThresholds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SynapseViewModel(
    private val graph: SynapseApplicationGraph,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SynapseUiState())
    val uiState: StateFlow<SynapseUiState> = mutableUiState

    private var messageObservationJob: Job? = null
    private var activeSendJob: Job? = null

    init {
        observeSettings()
        observeStorageHealth()
        observeThreads()
        bindDefaultThread()
    }

    fun updateComposer(text: String) {
        mutableUiState.update { state -> state.copy(composerText = text) }
    }

    fun appendComposerText(text: String) {
        mutableUiState.update { state ->
            state.copy(composerText = (state.composerText + " " + text).trim())
        }
    }

    fun addPendingAttachment(attachment: PendingAttachment) {
        mutableUiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments + attachment)
        }
    }

    fun removePendingAttachment(index: Int) {
        mutableUiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterIndexed { candidateIndex, _ ->
                candidateIndex != index
            })
        }
    }

    fun selectPanel(panel: SynapsePanel) {
        mutableUiState.update { state -> state.copy(activePanel = panel, lastNotice = null) }
        if (panel == SynapsePanel.MEMORY) {
            loadMemoryList()
        }
    }

    fun openThreadDrawer() {
        mutableUiState.update { state -> state.copy(isThreadDrawerOpen = true) }
    }

    fun closeThreadDrawer() {
        mutableUiState.update { state -> state.copy(isThreadDrawerOpen = false) }
    }

    fun createNewThread() {
        viewModelScope.launch {
            val thread = graph.conversationRepository.createThread()
            bindThread(thread)
        }
    }

    fun selectThread(thread: ChatThreadRecord) {
        bindThread(thread)
    }

    fun setThreadPinned(thread: ChatThreadRecord, pinned: Boolean) {
        viewModelScope.launch {
            val receipt = graph.conversationRepository.setThreadPinned(thread.id, pinned)
            mutableUiState.update { state ->
                state.copy(
                    lastNotice = if (receipt.affectedRows > 0) {
                        if (pinned) "Pinned chat." else "Unpinned chat."
                    } else {
                        "Chat was not updated."
                    },
                )
            }
        }
    }

    fun renameThread(thread: ChatThreadRecord, title: String) {
        viewModelScope.launch {
            try {
                val receipt = graph.conversationRepository.renameThread(thread.id, title)
                mutableUiState.update { state ->
                    state.copy(
                        lastNotice = if (receipt.affectedRows > 0) {
                            "Renamed chat."
                        } else {
                            "Chat was not renamed."
                        },
                    )
                }
            } catch (exception: IllegalArgumentException) {
                mutableUiState.update { state ->
                    state.copy(lastNotice = exception.message ?: "Chat title is not valid.")
                }
            }
        }
    }

    fun archiveThread(thread: ChatThreadRecord) {
        viewModelScope.launch {
            val wasCurrentThread = thread.id == mutableUiState.value.currentThread?.id
            cancelGenerationIfActiveThread(thread.id)
            val receipt = graph.conversationRepository.archiveThread(thread.id)
            if (wasCurrentThread) {
                bindThread(graph.conversationRepository.ensureDefaultThread())
            }
            mutableUiState.update { state ->
                state.copy(
                    isSending = if (wasCurrentThread) false else state.isSending,
                    lastNotice = if (receipt.affectedRows > 0) {
                        "Archived chat."
                    } else {
                        "Chat was not archived."
                    },
                )
            }
        }
    }

    fun deleteThread(thread: ChatThreadRecord) {
        viewModelScope.launch {
            val wasCurrentThread = thread.id == mutableUiState.value.currentThread?.id
            cancelGenerationIfActiveThread(thread.id)
            val receipt = graph.conversationRepository.deleteThread(thread.id)
            if (wasCurrentThread) {
                bindThread(graph.conversationRepository.ensureDefaultThread())
            }
            mutableUiState.update { state ->
                state.copy(
                    isSending = if (wasCurrentThread) false else state.isSending,
                    lastNotice = if (receipt.affectedRows > 0) {
                        "Deleted chat."
                    } else {
                        "Chat was not deleted."
                    },
                )
            }
        }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(lastNotice = null) }
    }

    fun publishNotice(message: String) {
        mutableUiState.update { state -> state.copy(lastNotice = message) }
    }

    fun sendComposerMessage() {
        val snapshot = mutableUiState.value
        val thread = snapshot.currentThread ?: return
        val body = snapshot.composerText.trim()
        if (body.isBlank() && snapshot.pendingAttachments.isEmpty()) return
        if (snapshot.isSending) return

        mutableUiState.update { state ->
            state.copy(
                composerText = "",
                pendingAttachments = emptyList(),
                isSending = true,
                activePanel = SynapsePanel.CHAT,
                lastNotice = null,
            )
        }

        activeSendJob = viewModelScope.launch {
            try {
                val outcome = graph.turnCoordinator.sendUserTurn(
                    command = SubmitUserMessageCommand(
                        threadId = thread.id,
                        body = body.ifBlank { "Attached context." },
                        attachments = snapshot.pendingAttachments,
                    ),
                    settings = snapshot.settings,
                )
                mutableUiState.update { state ->
                    state.copy(
                        lastNotice = when (outcome) {
                            is SynapseTurnOutcome.Completed -> null
                            is SynapseTurnOutcome.Failed -> outcome.reason
                        },
                    )
                }
            } finally {
                activeSendJob = null
                mutableUiState.update { state -> state.copy(isSending = false) }
            }
        }
    }

    fun cancelActiveSend() {
        graph.localInferenceRuntime.cancelActiveGeneration()
        activeSendJob?.cancel()
        mutableUiState.update { state ->
            state.copy(isSending = false, lastNotice = "Generation stopped.")
        }
    }

    fun checkRuntimeStatus() {
        viewModelScope.launch {
            val settings = mutableUiState.value.settings
            val status = graph.localInferenceRuntime.checkRuntimeStatus(settings)
            mutableUiState.update { state -> state.copy(runtimeStatus = status) }
        }
    }

    fun startRuntime() {
        viewModelScope.launch {
            val settings = mutableUiState.value.settings
            val receipt = graph.localInferenceRuntime.startRuntime(settings, StartLlamaServerCommand())
            mutableUiState.update { state ->
                state.copy(
                    runtimeStatus = RuntimeStatus.Starting(receipt),
                    lastNotice = receipt.message,
                )
            }
        }
    }

    fun updateMemorySearchQuery(query: String) {
        mutableUiState.update { state -> state.copy(memorySearchQuery = query) }
    }

    fun searchMemory() {
        val query = mutableUiState.value.memorySearchQuery
        viewModelScope.launch {
            val memoryRefs = if (query.isBlank()) {
                graph.memoryRepository.listPromptVisibleMemories(limit = 50)
            } else {
                graph.memoryRepository.retrieveMemories(query = query, limit = 20).refs
            }
            mutableUiState.update { state ->
                state.copy(memorySearchResults = memoryRefs)
            }
        }
    }

    fun tombstoneMemory(memoryObjectId: MemoryObjectId) {
        viewModelScope.launch {
            val receipt = graph.memoryRepository.tombstoneMemory(
                memoryObjectId = memoryObjectId,
                reason = "Deleted from Synapse memory screen.",
            )
            mutableUiState.update { state ->
                state.copy(
                    memorySearchResults = state.memorySearchResults
                        .filterNot { memory -> memory.memoryObjectId == memoryObjectId },
                    lastNotice = "Memory tombstoned: ${receipt.id.raw}",
                )
            }
        }
    }

    fun updateSettingsDraft(draft: RuntimeSettingsDraft) {
        mutableUiState.update { state -> state.copy(settingsDraft = draft) }
    }

    fun saveSettingsDraft() {
        val draft = mutableUiState.value.settingsDraft
        viewModelScope.launch {
            graph.settingsStore.updateRuntimeSettings(
                runtimeBackend = draft.runtimeBackend,
                baseUrl = draft.baseUrl,
                modelName = draft.modelName,
                persona = draft.persona,
                customInstructions = draft.customInstructions,
                modelPromptProfile = draft.modelPromptProfile,
                temperature = draft.temperature.toDoubleOrNull() ?: 0.7,
                maxTokens = draft.maxTokens.toIntOrNull() ?: 768,
            )
            mutableUiState.update { state -> state.copy(lastNotice = "Runtime settings saved.") }
            inspectStorageHealth()
        }
    }

    fun importEmbeddedModel(command: ImportEmbeddedModelCommand) {
        if (mutableUiState.value.isImportingModel) return
        mutableUiState.update { state ->
            state.copy(isImportingModel = true, lastNotice = "Importing GGUF model...")
        }
        viewModelScope.launch {
            try {
                val receipt = graph.embeddedModelStore.importModel(command)
                graph.settingsStore.updateEmbeddedModel(
                    modelPath = receipt.modelPath,
                    displayName = receipt.displayName,
                    byteCount = receipt.byteCount,
                )
                mutableUiState.update { state ->
                    state.copy(lastNotice = "Imported model: ${receipt.displayName}")
                }
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(lastNotice = exception.message ?: "Model import failed.")
                }
            } finally {
                mutableUiState.update { state -> state.copy(isImportingModel = false) }
            }
        }
    }

    fun updateMemoryWritesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsStore.updateMemoryWritesEnabled(enabled)
        }
    }

    fun updateSpeechPlaybackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            graph.settingsStore.updateSpeechPlaybackEnabled(enabled)
        }
    }

    fun inspectStorageHealth() {
        viewModelScope.launch {
            val settings = mutableUiState.value.settings
            val snapshot = graph.storageHealthGovernor.inspectStorageHealth(settings.toStorageThresholds())
            graph.storageHealthSnapshotRepository.persistStorageHealthSnapshot(snapshot)
            mutableUiState.update { state -> state.copy(storageHealthSnapshot = snapshot) }
        }
    }

    fun exportDebugArchive(onArchiveReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val snapshot = mutableUiState.value
            try {
                val receipt = graph.debugArchiveExporter.exportDebugArchive(
                    settings = snapshot.settings,
                    runtimeStatus = snapshot.runtimeStatus,
                    storageHealthSnapshot = snapshot.storageHealthSnapshot,
                    uiSnapshot = snapshot.toDebugUiSnapshot(),
                )
                mutableUiState.update { state ->
                    state.copy(lastNotice = "Debug archive ready: ${receipt.displayName}")
                }
                onArchiveReady(receipt.uri)
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(lastNotice = exception.message ?: "Debug archive export failed.")
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            graph.settingsStore.settingsFlow.collect { settings ->
                mutableUiState.update { state ->
                    state.copy(
                        settings = settings,
                        settingsDraft = settings.toDraft(),
                    )
                }
                inspectStorageHealth()
            }
        }
    }

    private fun observeStorageHealth() {
        viewModelScope.launch {
            graph.storageHealthSnapshotRepository.observeLatestStorageHealth().collect { snapshot ->
                mutableUiState.update { state -> state.copy(storageHealthSnapshot = snapshot) }
            }
        }
    }

    private fun observeThreads() {
        viewModelScope.launch {
            graph.conversationRepository.observeThreads().collect { threads ->
                mutableUiState.update { state -> state.copy(threads = threads) }
            }
        }
    }

    private fun bindDefaultThread() {
        viewModelScope.launch {
            graph.conversationRepository.failStaleStreamingAssistantMessages(
                reason = "Generation was interrupted before Synapse reopened.",
            )
            val thread = graph.conversationRepository.ensureDefaultThread()
            bindThread(thread)
        }
    }

    private fun bindThread(thread: ChatThreadRecord) {
        mutableUiState.update { state ->
            state.copy(
                currentThread = thread,
                activePanel = SynapsePanel.CHAT,
                isThreadDrawerOpen = false,
                messages = emptyList(),
                lastNotice = null,
            )
        }
        messageObservationJob?.cancel()
        messageObservationJob = viewModelScope.launch {
            graph.conversationRepository.observeMessages(thread.id).collect { messages ->
                mutableUiState.update { state -> state.copy(messages = messages) }
            }
        }
    }

    private fun cancelGenerationIfActiveThread(threadId: ChatThreadId) {
        if (mutableUiState.value.currentThread?.id != threadId) return
        graph.localInferenceRuntime.cancelActiveGeneration()
        activeSendJob?.cancel()
    }

    private fun SynapseSettings.toDraft(): RuntimeSettingsDraft =
        RuntimeSettingsDraft(
            runtimeBackend = runtimeBackend,
            baseUrl = baseUrl,
            modelName = modelName,
            modelPromptProfile = modelPromptProfile,
            persona = persona,
            customInstructions = customInstructions,
            temperature = temperature.toString(),
            maxTokens = maxTokens.toString(),
        )

    private fun loadMemoryList() {
        viewModelScope.launch {
            val memoryRefs = graph.memoryRepository.listPromptVisibleMemories(limit = 50)
            mutableUiState.update { state -> state.copy(memorySearchResults = memoryRefs) }
        }
    }

    private fun SynapseSettings.toStorageThresholds(): StorageThresholds =
        StorageThresholds(
            memoryDatabaseWarningBytes = memoryDatabaseWarningBytes,
            attachmentCacheWarningBytes = attachmentCacheWarningBytes,
            minimumFreeStorageBytes = minimumFreeStorageBytes,
        )

    private fun SynapseUiState.toDebugUiSnapshot(): DebugUiSnapshot =
        DebugUiSnapshot(
            activePanel = activePanel.name,
            isThreadDrawerOpen = isThreadDrawerOpen,
            currentThreadId = currentThread?.id?.raw,
            currentThreadTitle = currentThread?.title,
            visibleThreadCount = threads.size,
            visibleMessageCount = messages.size,
            composerCharacterCount = composerText.length,
            pendingAttachmentCount = pendingAttachments.size,
            isSending = isSending,
            isImportingModel = isImportingModel,
            lastNotice = lastNotice,
        )
}

class SynapseViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SynapseViewModel::class.java)) {
            return modelClass.cast(SynapseViewModel(graph))
                ?: throw IllegalArgumentException("Unable to create SynapseViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
