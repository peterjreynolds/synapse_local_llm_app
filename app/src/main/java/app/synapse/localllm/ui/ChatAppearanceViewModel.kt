package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.appearance.ChatAppearance
import app.synapse.localllm.domain.appearance.ChatAppearanceRepository
import app.synapse.localllm.domain.appearance.ChatBackground
import app.synapse.localllm.domain.appearance.ChatBubblePalette
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
    private val selectedConversation = MutableStateFlow<SelectedAppearanceConversation?>(null)
    private val mutableUiState = MutableStateFlow(ChatAppearanceUiState())
    val uiState: StateFlow<ChatAppearanceUiState> = mutableUiState

    init {
        viewModelScope.launch {
            selectedConversation.flatMapLatest { selection ->
                selection?.let {
                    repository.observeAppearance(it.accountUid, it.roomId)
                        .map { appearance -> selection to appearance }
                } ?: flowOf(null to ChatAppearance())
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
        selectedConversation.value = if (accountUid != null && roomId != null) {
            SelectedAppearanceConversation(accountUid, roomId)
        } else {
            null
        }
    }

    fun selectBubblePalette(palette: ChatBubblePalette) {
        saveAppearance { appearance -> appearance.copy(bubblePalette = palette) }
    }

    fun selectBackground(background: ChatBackground) {
        saveAppearance { appearance -> appearance.copy(background = background) }
    }

    fun resetAppearance() {
        val selection = selectedConversation.value ?: return
        launchSave {
            repository.resetAppearance(selection.accountUid, selection.roomId)
            "Chat appearance reset."
        }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun saveAppearance(transform: (ChatAppearance) -> ChatAppearance) {
        val selection = selectedConversation.value ?: return
        val nextAppearance = transform(mutableUiState.value.appearance)
        launchSave {
            repository.saveAppearance(selection.accountUid, selection.roomId, nextAppearance)
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

private data class SelectedAppearanceConversation(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
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
