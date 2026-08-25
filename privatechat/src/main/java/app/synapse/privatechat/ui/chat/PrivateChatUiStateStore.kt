package app.synapse.privatechat.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class PrivateChatUiStateStore {
    private val mutableState = MutableStateFlow(PrivateChatUiState())

    val state: StateFlow<PrivateChatUiState> = mutableState.asStateFlow()

    val current: PrivateChatUiState
        get() = mutableState.value

    fun replace(state: PrivateChatUiState) {
        mutableState.value = state
    }

    fun update(transform: (PrivateChatUiState) -> PrivateChatUiState) {
        mutableState.update(transform)
    }
}
