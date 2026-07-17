package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.security.AppLockPin
import app.synapse.localllm.domain.security.AppLockRepository
import app.synapse.localllm.domain.security.AppLockVerificationOutcome
import app.synapse.localllm.domain.security.AppLockVerificationReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppLockUiState(
    val isLoading: Boolean = true,
    val isEnabled: Boolean = false,
    val isCredentialAvailable: Boolean = true,
    val isUnlocked: Boolean = false,
    val isActionRunning: Boolean = false,
    val notice: String? = null,
)

class AppLockViewModel(
    private val repository: AppLockRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(AppLockUiState())
    val uiState: StateFlow<AppLockUiState> = mutableUiState

    init {
        viewModelScope.launch {
            repository.configuration.collect { configuration ->
                mutableUiState.update { state ->
                    state.copy(
                        isLoading = false,
                        isEnabled = configuration.enabled,
                        isCredentialAvailable = configuration.credentialAvailable,
                        isUnlocked = if (configuration.enabled) state.isUnlocked else true,
                        notice = if (configuration.enabled && !configuration.credentialAvailable) {
                            CREDENTIAL_UNAVAILABLE_MESSAGE
                        } else {
                            state.notice
                        },
                    )
                }
            }
        }
    }

    fun unlock(rawPin: String) = launchPinAction {
        val receipt = repository.verify(AppLockPin.parse(rawPin))
        applyVerificationReceipt(receipt, unlockOnSuccess = true)
    }

    fun enable(
        rawPin: String,
        confirmation: String,
    ) = launchPinAction {
        requireMatchingPins(rawPin, confirmation)
        repository.enable(AppLockPin.parse(rawPin))
        mutableUiState.update { state ->
            state.copy(
                isEnabled = true,
                isCredentialAvailable = true,
                isUnlocked = true,
                notice = "PIN lock enabled for this phone.",
            )
        }
    }

    fun changePin(
        currentRawPin: String,
        newRawPin: String,
        confirmation: String,
    ) = launchPinAction {
        requireMatchingPins(newRawPin, confirmation)
        val receipt = repository.changePin(
            currentPin = AppLockPin.parse(currentRawPin),
            newPin = AppLockPin.parse(newRawPin),
        )
        if (receipt.outcome == AppLockVerificationOutcome.VERIFIED) {
            mutableUiState.update { state -> state.copy(notice = "PIN changed.") }
        } else {
            applyVerificationReceipt(receipt, unlockOnSuccess = false)
        }
    }

    fun disable(rawPin: String) = launchPinAction {
        val receipt = repository.disable(AppLockPin.parse(rawPin))
        if (receipt.outcome == AppLockVerificationOutcome.VERIFIED) {
            mutableUiState.update { state ->
                state.copy(
                    isEnabled = false,
                    isCredentialAvailable = true,
                    isUnlocked = true,
                    notice = "PIN lock disabled on this phone.",
                )
            }
        } else {
            applyVerificationReceipt(receipt, unlockOnSuccess = false)
        }
    }

    fun lock() {
        mutableUiState.update { state ->
            if (state.isEnabled) state.copy(isUnlocked = false, notice = null) else state
        }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun launchPinAction(action: suspend () -> Unit) {
        if (mutableUiState.value.isActionRunning) return
        viewModelScope.launch {
            mutableUiState.update { state -> state.copy(isActionRunning = true, notice = null) }
            try {
                action()
                mutableUiState.update { state -> state.copy(isActionRunning = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        isActionRunning = false,
                        notice = exception.message ?: "PIN lock operation failed.",
                    )
                }
            }
        }
    }

    private fun applyVerificationReceipt(
        receipt: AppLockVerificationReceipt,
        unlockOnSuccess: Boolean,
    ) {
        mutableUiState.update { state ->
            when (receipt.outcome) {
                AppLockVerificationOutcome.VERIFIED -> state.copy(
                    isUnlocked = state.isUnlocked || unlockOnSuccess,
                    notice = null,
                )
                AppLockVerificationOutcome.INVALID_PIN -> state.copy(notice = "Incorrect PIN.")
                AppLockVerificationOutcome.TEMPORARILY_BLOCKED -> state.copy(
                    notice = "Too many attempts. Try again in ${retrySeconds(receipt.retryAfterMillis)} seconds.",
                )
                AppLockVerificationOutcome.NOT_ENABLED -> state.copy(
                    isEnabled = false,
                    isUnlocked = true,
                    notice = null,
                )
                AppLockVerificationOutcome.CREDENTIAL_UNAVAILABLE -> state.copy(
                    isCredentialAvailable = false,
                    isUnlocked = false,
                    notice = CREDENTIAL_UNAVAILABLE_MESSAGE,
                )
            }
        }
    }

    private fun requireMatchingPins(
        rawPin: String,
        confirmation: String,
    ) {
        require(rawPin == confirmation) { "PIN entries do not match." }
        AppLockPin.parse(rawPin)
    }

    private fun retrySeconds(retryAfterMillis: Long): Long =
        ((retryAfterMillis + 999L) / 1_000L).coerceAtLeast(1L)

    private companion object {
        const val CREDENTIAL_UNAVAILABLE_MESSAGE =
            "This phone's PIN credential is unavailable. Synapse stays locked; clear the app's storage to reset it."
    }
}

class AppLockViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppLockViewModel::class.java)) {
            return modelClass.cast(AppLockViewModel(graph.appLockRepository))
                ?: throw IllegalArgumentException("Unable to create AppLockViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
