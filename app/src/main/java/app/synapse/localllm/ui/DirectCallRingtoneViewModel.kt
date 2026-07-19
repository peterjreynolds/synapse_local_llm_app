package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.calling.DirectCallAlertGateway
import app.synapse.localllm.domain.calling.DirectCallRingtoneMutationReceipt
import app.synapse.localllm.domain.calling.DirectCallRingtoneRepository
import app.synapse.localllm.domain.calling.DirectCallRingtoneSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DirectCallRingtoneUiState(
    val selection: DirectCallRingtoneSelection = DirectCallRingtoneSelection(),
    val isSaving: Boolean = false,
    val isPreviewing: Boolean = false,
    val notice: String? = null,
)

class DirectCallRingtoneViewModel(
    private val repository: DirectCallRingtoneRepository,
    private val alertGateway: DirectCallAlertGateway,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(
        DirectCallRingtoneUiState(selection = repository.currentSelection()),
    )
    val uiState: StateFlow<DirectCallRingtoneUiState> = mutableUiState
    private var previewJob: Job? = null

    fun usePhoneDefaultRingtone() {
        saveSelection(repository::usePhoneDefaultRingtone)
    }

    fun selectPhoneRingtone(uri: String) {
        saveSelection { repository.selectPhoneRingtone(uri) }
    }

    fun selectAudioFile(uri: String) {
        saveSelection { repository.selectAudioFile(uri) }
    }

    fun togglePreview() {
        if (mutableUiState.value.isPreviewing) {
            stopPreview()
            return
        }
        previewJob?.cancel()
        alertGateway.startIncomingRingtone(nowEpochMillis() + RINGTONE_PREVIEW_MILLIS)
        mutableUiState.update { state -> state.copy(isPreviewing = true, notice = null) }
        previewJob = viewModelScope.launch {
            delay(RINGTONE_PREVIEW_MILLIS)
            alertGateway.stop()
            mutableUiState.update { state -> state.copy(isPreviewing = false) }
        }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun saveSelection(save: suspend () -> DirectCallRingtoneMutationReceipt) {
        if (mutableUiState.value.isSaving) return
        stopPreview()
        viewModelScope.launch {
            mutableUiState.update { state -> state.copy(isSaving = true, notice = null) }
            try {
                val receipt = save()
                mutableUiState.update { state ->
                    state.copy(
                        selection = receipt.selection,
                        isSaving = false,
                        notice = "Incoming ringtone saved on this phone.",
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        isSaving = false,
                        notice = exception.message ?: "Could not save the incoming ringtone.",
                    )
                }
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        alertGateway.stop()
        mutableUiState.update { state -> state.copy(isPreviewing = false) }
    }

    override fun onCleared() {
        stopPreview()
        super.onCleared()
    }

    private companion object {
        const val RINGTONE_PREVIEW_MILLIS = 8_000L
    }
}

class DirectCallRingtoneViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DirectCallRingtoneViewModel::class.java)) {
            return modelClass.cast(
                DirectCallRingtoneViewModel(
                    repository = graph.directCallRingtoneRepository,
                    alertGateway = graph.directCallAlertGateway,
                ),
            ) ?: throw IllegalArgumentException("Unable to create DirectCallRingtoneViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
