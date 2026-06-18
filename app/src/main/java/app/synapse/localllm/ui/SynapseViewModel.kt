package app.synapse.localllm.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.SynapseTurnOutcome
import app.synapse.localllm.data.library.MarkdownPdfExportCommand
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.chat.ChatThreadRecord
import app.synapse.localllm.domain.chat.PendingAttachment
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.diagnostics.DebugUiSnapshot
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.MemoryObjectId
import app.synapse.localllm.domain.library.CreateMarkdownArtifactCommand
import app.synapse.localllm.domain.library.LibraryArtifactRecord
import app.synapse.localllm.domain.memory.MemoryReviewFilter
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import app.synapse.localllm.domain.runtime.ImportEmbeddedModelCommand
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.runtime.StartLlamaServerCommand
import app.synapse.localllm.domain.settings.InferenceRuntimeBackend
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.storage.StorageThresholds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SynapseViewModel(
    private val graph: SynapseApplicationGraph,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SynapseUiState())
    val uiState: StateFlow<SynapseUiState> = mutableUiState

    private val voiceModeStateMachine = VoiceModeStateMachine()
    private var messageObservationJob: Job? = null
    private var activeSendJob: Job? = null
    private var activeVoiceModeTurnJob: Job? = null

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
        when (panel) {
            SynapsePanel.LIBRARY -> loadLibraryArtifacts()
            SynapsePanel.MEMORY -> loadMemoryList()
            SynapsePanel.CHAT,
            SynapsePanel.SETTINGS,
            -> Unit
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
                voiceMode = if (state.voiceMode.isActive) {
                    voiceModeStateMachine.stop()
                } else {
                    state.voiceMode
                },
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
        activeVoiceModeTurnJob?.cancel()
        mutableUiState.update { state ->
            if (state.voiceMode.isActive) {
                state.copy(
                    isSending = false,
                    voiceMode = voiceModeStateMachine.stop(),
                    lastNotice = "Voice Mode stopped.",
                )
            } else {
                state.copy(isSending = false, lastNotice = "Generation stopped.")
            }
        }
    }

    fun toggleVoiceMode() {
        val voiceMode = mutableUiState.value.voiceMode
        when (voiceMode.status) {
            VoiceModeStatus.OFF,
            VoiceModeStatus.ERROR,
            -> startVoiceMode()

            VoiceModeStatus.LISTENING,
            VoiceModeStatus.PROCESSING,
            VoiceModeStatus.SPEAKING,
            -> stopVoiceMode()
        }
    }

    fun stopVoiceMode() {
        graph.localInferenceRuntime.cancelActiveGeneration()
        activeVoiceModeTurnJob?.cancel()
        if (activeSendJob == activeVoiceModeTurnJob) {
            activeSendJob = null
        }
        activeVoiceModeTurnJob = null
        mutableUiState.update { state ->
            state.copy(
                isSending = if (state.voiceMode.status == VoiceModeStatus.PROCESSING) {
                    false
                } else {
                    state.isSending
                },
                voiceMode = voiceModeStateMachine.stop(),
                lastNotice = "Voice Mode stopped.",
            )
        }
    }

    fun onVoiceModeSpeechResult(transcript: String) {
        val snapshot = mutableUiState.value
        val thread = snapshot.currentThread ?: run {
            failVoiceMode("No active chat is ready for Voice Mode.")
            return
        }
        val userText = transcript.trim()
        if (snapshot.voiceMode.status != VoiceModeStatus.LISTENING) return
        if (userText.isBlank()) {
            failVoiceMode("No speech was recognized.")
            return
        }
        if (snapshot.isSending || activeVoiceModeTurnJob != null) {
            failVoiceMode("Synapse is already responding.")
            return
        }

        mutableUiState.update { state ->
            state.copy(
                isSending = true,
                activePanel = SynapsePanel.CHAT,
                voiceMode = voiceModeStateMachine.processTranscript(state.voiceMode),
                lastNotice = "Voice Mode heard: $userText",
            )
        }
        val voiceTurnJob = viewModelScope.launch {
            try {
                val outcome = graph.turnCoordinator.sendUserTurn(
                    command = SubmitUserMessageCommand(
                        threadId = thread.id,
                        body = userText,
                        attachments = emptyList(),
                    ),
                    settings = snapshot.settings,
                )
                when (outcome) {
                    is SynapseTurnOutcome.Completed -> {
                        val assistantText = graph.conversationRepository
                            .listRecentMessages(thread.id, limit = VOICE_MODE_RECENT_MESSAGE_LIMIT)
                            .firstOrNull { message -> message.id == outcome.assistantMessageId }
                            ?.body
                            .orEmpty()
                            .trim()
                        if (assistantText.isBlank()) {
                            failVoiceMode("Synapse finished without speakable text.")
                        } else {
                            mutableUiState.update { state ->
                                if (state.voiceMode.status != VoiceModeStatus.PROCESSING) {
                                    state
                                } else {
                                    state.copy(
                                        voiceMode = voiceModeStateMachine.speakAssistantReply(
                                            currentState = state.voiceMode,
                                            assistantText = assistantText,
                                        ),
                                        lastNotice = "Voice Mode speaking.",
                                    )
                                }
                            }
                        }
                    }

                    is SynapseTurnOutcome.Failed ->
                        failVoiceMode(outcome.reason)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failVoiceMode(exception.message ?: "Voice Mode turn failed.")
            } finally {
                activeVoiceModeTurnJob = null
                if (activeSendJob == this.coroutineContext[Job]) {
                    activeSendJob = null
                }
                mutableUiState.update { state -> state.copy(isSending = false) }
            }
        }
        activeVoiceModeTurnJob = voiceTurnJob
        activeSendJob = voiceTurnJob
    }

    fun onVoiceModeSpeechError(reason: String) {
        val snapshot = mutableUiState.value
        if (snapshot.voiceMode.status != VoiceModeStatus.LISTENING) return
        failVoiceMode(reason)
    }

    fun onVoiceModeSpeechPlaybackFinished() {
        mutableUiState.update { state ->
            if (state.voiceMode.status != VoiceModeStatus.SPEAKING) {
                state
            } else {
                state.copy(
                    voiceMode = voiceModeStateMachine.finishSpeaking(state.voiceMode),
                    lastNotice = "Voice Mode listening.",
                )
            }
        }
    }

    fun onVoiceModeSpeechPlaybackError(reason: String) {
        val snapshot = mutableUiState.value
        if (snapshot.voiceMode.status != VoiceModeStatus.SPEAKING) return
        failVoiceMode(reason)
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

    fun updateLibraryDraftTitle(title: String) {
        mutableUiState.update { state -> state.copy(libraryDraftTitle = title) }
    }

    fun updateLibraryDraftMarkdown(markdown: String) {
        mutableUiState.update { state -> state.copy(libraryDraftMarkdown = markdown) }
    }

    fun loadLibraryArtifacts() {
        viewModelScope.launch {
            try {
                refreshLibraryArtifactsIntoState()
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(lastNotice = exception.message ?: "Library catalog could not be loaded.")
                }
            }
        }
    }

    fun createLibraryMarkdownArtifact() {
        val snapshot = mutableUiState.value
        val title = snapshot.libraryDraftTitle.trim()
        val markdown = snapshot.libraryDraftMarkdown.trim()
        if (snapshot.isCreatingLibraryArtifact) return
        if (title.isBlank() || markdown.isBlank()) {
            mutableUiState.update { state ->
                state.copy(lastNotice = "Add a title and Markdown body before saving.")
            }
            return
        }

        mutableUiState.update { state ->
            state.copy(isCreatingLibraryArtifact = true, lastNotice = null)
        }
        viewModelScope.launch {
            try {
                val receipt = withContext(ioDispatcher) {
                    graph.libraryWorkspaceRepository.createMarkdownArtifact(
                        CreateMarkdownArtifactCommand(
                            title = title,
                            markdown = markdown,
                            catalogSummary = buildLibraryCatalogSummary(markdown),
                        ),
                    )
                }
                val artifacts = withContext(ioDispatcher) {
                    graph.libraryWorkspaceRepository.listCatalogArtifacts(limit = 50)
                }
                mutableUiState.update { state ->
                    state.copy(
                        libraryDraftTitle = "",
                        libraryDraftMarkdown = "",
                        libraryArtifacts = artifacts,
                        lastNotice = "Saved Markdown note: ${receipt.artifact.displayName}",
                    )
                }
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(lastNotice = exception.message ?: "Markdown note could not be saved.")
                }
            } finally {
                mutableUiState.update { state -> state.copy(isCreatingLibraryArtifact = false) }
            }
        }
    }

    fun exportLibraryDraftAsPdf(onPdfReady: (Uri) -> Unit) {
        val snapshot = mutableUiState.value
        val title = snapshot.libraryDraftTitle.trim()
        val markdown = snapshot.libraryDraftMarkdown.trim()
        if (snapshot.isExportingLibraryPdf) return
        if (title.isBlank() || markdown.isBlank()) {
            mutableUiState.update { state ->
                state.copy(lastNotice = "Add a title and Markdown body before exporting.")
            }
            return
        }

        exportMarkdownAsPdf(
            title = title,
            markdown = markdown,
            onPdfReady = onPdfReady,
        )
    }

    fun exportLibraryArtifactAsPdf(
        artifact: LibraryArtifactRecord,
        onPdfReady: (Uri) -> Unit,
    ) {
        if (mutableUiState.value.isExportingLibraryPdf) return
        mutableUiState.update { state ->
            state.copy(isExportingLibraryPdf = true, lastNotice = null)
        }
        viewModelScope.launch {
            try {
                val markdownContent = withContext(ioDispatcher) {
                    graph.libraryWorkspaceRepository.readMarkdownArtifactContent(artifact.id)
                }
                if (markdownContent == null) {
                    mutableUiState.update { state ->
                        state.copy(lastNotice = "Markdown artifact is missing from the workspace.")
                    }
                    return@launch
                }
                val receipt = withContext(ioDispatcher) {
                    graph.markdownPdfExporter.exportMarkdownAsPdf(
                        MarkdownPdfExportCommand(
                            title = markdownContent.artifact.title,
                            markdown = markdownContent.markdown,
                        ),
                    )
                }
                mutableUiState.update { state ->
                    state.copy(lastNotice = "PDF ready: ${receipt.displayName}")
                }
                onPdfReady(receipt.uri)
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(lastNotice = exception.message ?: "Markdown artifact could not be exported.")
                }
            } finally {
                mutableUiState.update { state -> state.copy(isExportingLibraryPdf = false) }
            }
        }
    }

    fun updateMemorySearchQuery(query: String) {
        mutableUiState.update { state -> state.copy(memorySearchQuery = query) }
    }

    fun updateMemoryReviewFilter(filter: MemoryReviewFilter) {
        mutableUiState.update { state -> state.copy(memoryReviewFilter = filter) }
        searchMemory()
    }

    fun searchMemory() {
        val snapshot = mutableUiState.value
        val query = snapshot.memorySearchQuery
        viewModelScope.launch {
            val shouldUseReviewList =
                query.isBlank() || snapshot.memoryReviewFilter != MemoryReviewFilter.ACTIVE
            val memoryRefs = if (shouldUseReviewList) {
                val reviewRefs = graph.memoryRepository.listMemoriesForReview(
                    filter = snapshot.memoryReviewFilter,
                    limit = 100,
                )
                if (query.isBlank()) {
                    reviewRefs.take(50)
                } else {
                    reviewRefs.filter { memory -> memory.matchesReviewQuery(query) }.take(50)
                }
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
            val memoryRefs = graph.memoryRepository.listMemoriesForReview(
                filter = mutableUiState.value.memoryReviewFilter,
                limit = 50,
            )
            mutableUiState.update { state ->
                state.copy(
                    memorySearchResults = memoryRefs,
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
            val memoryRefs = graph.memoryRepository.listMemoriesForReview(
                filter = mutableUiState.value.memoryReviewFilter,
                limit = 50,
            )
            mutableUiState.update { state -> state.copy(memorySearchResults = memoryRefs) }
        }
    }

    private fun RetrievedMemoryRef.matchesReviewQuery(query: String): Boolean {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return true
        return listOfNotNull(
            text,
            kind.name,
            status.name,
            scope.name,
            subject,
            claimKey,
            keywords.joinToString(" "),
        ).any { searchableText ->
            searchableText.lowercase().contains(normalizedQuery)
        }
    }

    private fun startVoiceMode() {
        if (mutableUiState.value.isSending) {
            mutableUiState.update { state ->
                state.copy(
                    voiceMode = voiceModeStateMachine.fail("Wait for the current response before starting Voice Mode."),
                    lastNotice = "Wait for the current response before starting Voice Mode.",
                )
            }
            return
        }
        if (mutableUiState.value.currentThread == null) {
            mutableUiState.update { state ->
                state.copy(
                    voiceMode = voiceModeStateMachine.fail("No active chat is ready for Voice Mode."),
                    lastNotice = "No active chat is ready for Voice Mode.",
                )
            }
            return
        }
        mutableUiState.update { state ->
            state.copy(
                activePanel = SynapsePanel.CHAT,
                voiceMode = voiceModeStateMachine.startListening(state.voiceMode),
                lastNotice = "Voice Mode listening.",
            )
        }
    }

    private fun failVoiceMode(reason: String) {
        val failedVoiceTurnJob = activeVoiceModeTurnJob
        activeVoiceModeTurnJob = null
        if (failedVoiceTurnJob != null && activeSendJob == failedVoiceTurnJob) {
            activeSendJob = null
        }
        mutableUiState.update { state ->
            state.copy(
                voiceMode = voiceModeStateMachine.fail(reason),
                lastNotice = reason,
            )
        }
    }

    private fun exportMarkdownAsPdf(
        title: String,
        markdown: String,
        onPdfReady: (Uri) -> Unit,
    ) {
        mutableUiState.update { state ->
            state.copy(isExportingLibraryPdf = true, lastNotice = null)
        }
        viewModelScope.launch {
            try {
                val receipt = withContext(ioDispatcher) {
                    graph.markdownPdfExporter.exportMarkdownAsPdf(
                        MarkdownPdfExportCommand(
                            title = title,
                            markdown = markdown,
                        ),
                    )
                }
                mutableUiState.update { state ->
                    state.copy(lastNotice = "PDF ready: ${receipt.displayName}")
                }
                onPdfReady(receipt.uri)
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(lastNotice = exception.message ?: "Markdown PDF could not be exported.")
                }
            } finally {
                mutableUiState.update { state -> state.copy(isExportingLibraryPdf = false) }
            }
        }
    }

    private suspend fun refreshLibraryArtifactsIntoState() {
        val artifacts = withContext(ioDispatcher) {
            graph.libraryWorkspaceRepository.listCatalogArtifacts(limit = 50)
        }
        mutableUiState.update { state -> state.copy(libraryArtifacts = artifacts) }
    }

    private fun buildLibraryCatalogSummary(markdown: String): String? =
        markdown
            .lineSequence()
            .map { line -> line.trim().trimStart('#').trim() }
            .firstOrNull { line -> line.isNotBlank() }
            ?.take(180)
            ?.trimEnd()

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

    private companion object {
        const val VOICE_MODE_RECENT_MESSAGE_LIMIT = 12
    }
}

class SynapseViewModelFactory(
    private val graph: SynapseApplicationGraph,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SynapseViewModel::class.java)) {
            return modelClass.cast(SynapseViewModel(graph, ioDispatcher))
                ?: throw IllegalArgumentException("Unable to create SynapseViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
