package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.appearance.ChatAppearance
import app.synapse.localllm.domain.appearance.ChatAppearanceRepository
import app.synapse.localllm.domain.appearance.ChatBackground
import app.synapse.localllm.domain.appearance.ChatBubblePalette
import app.synapse.localllm.domain.appearance.clampChatMessageScale
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatAppearanceUiState(
    val accountUid: RemoteAccountUid? = null,
    val roomId: RemoteRoomId? = null,
    val appearance: ChatAppearance = ChatAppearance(),
    val isSaving: Boolean = false,
    val notice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatAppearanceViewModel(
    private val repository: ChatAppearanceRepository,
) : ViewModel() {
    private val selectedContext = MutableStateFlow<SelectedAppearanceContext?>(null)
    private val mutableUiState = MutableStateFlow(ChatAppearanceUiState())
    val uiState: StateFlow<ChatAppearanceUiState> = mutableUiState

    init {
        viewModelScope.launch {
            selectedContext.flatMapLatest { selection ->
                when {
                    selection == null -> flowOf(null to ChatAppearance())
                    selection.roomId == null -> repository.observeAccountBubblePalette(selection.accountUid)
                        .map { bubblePalette -> selection to ChatAppearance(bubblePalette = bubblePalette) }
                    else -> repository.observeAppearance(selection.accountUid, selection.roomId)
                        .map { appearance -> selection to appearance }
                }
            }.collect { (selection, appearance) ->
                mutableUiState.update { state ->
                    state.copy(
                        accountUid = selection?.accountUid,
                        roomId = selection?.roomId,
                        appearance = appearance,
                    )
                }
            }
        }
    }

    fun selectConversation(
        accountUid: RemoteAccountUid?,
        roomId: RemoteRoomId?,
    ) {
        selectedContext.value = if (accountUid != null && roomId != null) {
            SelectedAppearanceContext(accountUid, roomId)
        } else if (accountUid != null) {
            SelectedAppearanceContext(accountUid, null)
        } else {
            null
        }
    }

    fun selectBubblePalette(palette: ChatBubblePalette) {
        val accountUid = selectedContext.value?.accountUid ?: return
        launchSave {
            repository.saveAccountBubblePalette(accountUid, palette)
            "Bubble colors saved for this account."
        }
    }

    fun selectBackground(background: ChatBackground) {
        saveAppearance { appearance -> appearance.copy(background = background) }
    }

    fun selectMessageScale(messageScale: Float) {
        saveAppearance { appearance -> appearance.copy(messageScale = clampChatMessageScale(messageScale)) }
    }

    fun resetAppearance() {
        val selection = selectedContext.value?.takeIf { context -> context.roomId != null } ?: return
        launchSave {
            repository.resetAppearance(selection.accountUid, requireNotNull(selection.roomId))
            "Chat appearance reset."
        }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun saveAppearance(transform: (ChatAppearance) -> ChatAppearance) {
        val selection = selectedContext.value?.takeIf { context -> context.roomId != null } ?: return
        val nextAppearance = transform(mutableUiState.value.appearance)
        launchSave {
            repository.saveAppearance(selection.accountUid, requireNotNull(selection.roomId), nextAppearance)
            "Chat appearance saved."
        }
    }

    private fun launchSave(action: suspend () -> String) {
        if (mutableUiState.value.isSaving) return
        viewModelScope.launch {
            mutableUiState.update { state -> state.copy(isSaving = true, notice = null) }
            try {
                val notice = action()
                mutableUiState.update { state -> state.copy(isSaving = false, notice = notice) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        isSaving = false,
                        notice = exception.message ?: "Could not save chat appearance.",
                    )
                }
            }
        }
    }
}

private data class SelectedAppearanceContext(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId?,
)

class ChatAppearanceViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatAppearanceViewModel::class.java)) {
            return modelClass.cast(ChatAppearanceViewModel(graph.chatAppearanceRepository))
                ?: throw IllegalArgumentException("Unable to create ChatAppearanceViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
