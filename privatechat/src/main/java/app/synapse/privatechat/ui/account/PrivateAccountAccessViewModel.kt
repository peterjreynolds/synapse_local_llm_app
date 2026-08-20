package app.synapse.privatechat.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.synapse.privatechat.domain.account.PrivateAccountAccessDraft
import app.synapse.privatechat.domain.account.PrivateAccountAccessMode
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountAccessValidation
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountInputField
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.domain.account.validatePrivateAccountAccessDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class PrivateAccountAccessUiState(
    val mode: PrivateAccountAccessMode = PrivateAccountAccessMode.REGISTER_WITH_INVITE,
    val submission: PrivateAccountSubmissionState = PrivateAccountSubmissionState.Idle,
)

sealed interface PrivateAccountSubmissionState {
    data object Idle : PrivateAccountSubmissionState

    data object Submitting : PrivateAccountSubmissionState

    data class InvalidInput(
        val field: PrivateAccountInputField,
        val userMessage: String,
    ) : PrivateAccountSubmissionState

    data class AccessDenied(
        val userMessage: String,
    ) : PrivateAccountSubmissionState

    data object TransportUnavailable : PrivateAccountSubmissionState

    data class AccessConfirmed(
        val receipt: PrivateAccountSessionReceipt,
    ) : PrivateAccountSubmissionState

    data object UnexpectedFailure : PrivateAccountSubmissionState
}

class PrivateAccountAccessViewModel(
    private val accountGateway: PrivateAccountGateway,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PrivateAccountAccessUiState())
    private val submissionInFlight = AtomicBoolean(false)

    val uiState: StateFlow<PrivateAccountAccessUiState> = mutableUiState.asStateFlow()

    fun selectAccessMode(mode: PrivateAccountAccessMode) {
        if (submissionInFlight.get()) return
        mutableUiState.value = PrivateAccountAccessUiState(mode = mode)
    }

    fun submitAccountAccess(draft: PrivateAccountAccessDraft) {
        if (!submissionInFlight.compareAndSet(false, true)) return
        when (val validation = validatePrivateAccountAccessDraft(draft)) {
            is PrivateAccountAccessValidation.Rejected -> {
                submissionInFlight.set(false)
                mutableUiState.value =
                    mutableUiState.value.copy(
                        submission =
                            PrivateAccountSubmissionState.InvalidInput(
                                field = validation.field,
                                userMessage = validation.userMessage,
                            ),
                    )
            }

            is PrivateAccountAccessValidation.Accepted -> {
                mutableUiState.value =
                    mutableUiState.value.copy(
                        submission = PrivateAccountSubmissionState.Submitting,
                    )
                viewModelScope.launch {
                    val submissionState = requestAccountAccess(validation)
                    submissionInFlight.set(false)
                    mutableUiState.value = mutableUiState.value.copy(submission = submissionState)
                }
            }
        }
    }

    fun clearSubmissionNotice() {
        if (submissionInFlight.get()) return
        mutableUiState.value =
            mutableUiState.value.copy(
                submission = PrivateAccountSubmissionState.Idle,
            )
    }

    private suspend fun requestAccountAccess(validation: PrivateAccountAccessValidation.Accepted): PrivateAccountSubmissionState =
        try {
            when (val outcome = accountGateway.requestPrivateAccountAccess(validation.command)) {
                is PrivateAccountAccessOutcome.Confirmed ->
                    PrivateAccountSubmissionState.AccessConfirmed(outcome.receipt)

                is PrivateAccountAccessOutcome.Denied ->
                    PrivateAccountSubmissionState.AccessDenied(outcome.userMessage)

                PrivateAccountAccessOutcome.TransportNotConfigured ->
                    PrivateAccountSubmissionState.TransportUnavailable
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PrivateAccountSubmissionState.UnexpectedFailure
        }
}
