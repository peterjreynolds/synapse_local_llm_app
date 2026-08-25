package app.synapse.privatechat.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.synapse.privatechat.domain.account.PrivateAccountAccessDraft
import app.synapse.privatechat.domain.account.PrivateAccountAccessMode
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountAccessValidation
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountInputField
import app.synapse.privatechat.domain.account.PrivateAccountSessionOutcome
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.domain.account.PrivateAccountSignOutOutcome
import app.synapse.privatechat.domain.account.PrivateRemoteSessionRevocationStatus
import app.synapse.privatechat.domain.account.validatePrivateAccountAccessDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

data class PrivateAccountAccessUiState(
    val mode: PrivateAccountAccessMode = PrivateAccountAccessMode.REGISTER_WITH_INVITE,
    val session: PrivateAccountSessionUiState = PrivateAccountSessionUiState.Restoring,
    val submission: PrivateAccountSubmissionState = PrivateAccountSubmissionState.Idle,
    val signOut: PrivateAccountSignOutUiState = PrivateAccountSignOutUiState.Idle,
)

sealed interface PrivateAccountSessionUiState {
    data object Restoring : PrivateAccountSessionUiState

    data object SigningOut : PrivateAccountSessionUiState

    data object SignedOut : PrivateAccountSessionUiState

    data class Active(
        val receipt: PrivateAccountSessionReceipt.Active,
    ) : PrivateAccountSessionUiState

    data object TransportUnavailable : PrivateAccountSessionUiState

    data object LocalStateUnavailable : PrivateAccountSessionUiState

    data class VerificationRejected(
        val userMessage: String,
    ) : PrivateAccountSessionUiState

    data object VerificationFailed : PrivateAccountSessionUiState
}

sealed interface PrivateAccountSignOutUiState {
    data object Idle : PrivateAccountSignOutUiState

    data object SigningOut : PrivateAccountSignOutUiState

    data class LocallySignedOut(
        val remoteRevocation: PrivateRemoteSessionRevocationStatus,
    ) : PrivateAccountSignOutUiState

    data object AlreadySignedOut : PrivateAccountSignOutUiState

    data class Rejected(
        val userMessage: String,
    ) : PrivateAccountSignOutUiState

    data object TransportUnavailable : PrivateAccountSignOutUiState

    data object LocalStateUnavailable : PrivateAccountSignOutUiState

    data object VerificationFailed : PrivateAccountSignOutUiState
}

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

    data object LocalStateUnavailable : PrivateAccountSubmissionState

    data class AccessConfirmed(
        val receipt: PrivateAccountSessionReceipt,
    ) : PrivateAccountSubmissionState

    data object UnexpectedFailure : PrivateAccountSubmissionState
}

class PrivateAccountAccessViewModel(
    private val accountGateway: PrivateAccountGateway,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PrivateAccountAccessUiState())
    private val submissionInFlight = AtomicBoolean(false)
    private val sessionOperationInFlight = AtomicBoolean(false)
    private var accountAccessJob: Job? = null
    private var scheduledSessionRefresh: Job? = null
    private var activeReceiptBeforeSignOut: PrivateAccountSessionReceipt.Active? = null

    val uiState: StateFlow<PrivateAccountAccessUiState> = mutableUiState.asStateFlow()

    init {
        restorePersistedSession()
    }

    fun selectAccessMode(mode: PrivateAccountAccessMode) {
        if (submissionInFlight.get() || mutableUiState.value.session !is PrivateAccountSessionUiState.SignedOut) return
        mutableUiState.value = mutableUiState.value.copy(mode = mode, submission = PrivateAccountSubmissionState.Idle)
    }

    fun submitAccountAccess(draft: PrivateAccountAccessDraft) {
        if (mutableUiState.value.session !is PrivateAccountSessionUiState.SignedOut) return
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
                launchAccountAccess(validation)
            }
        }
    }

    fun retrySessionRestore() {
        restorePersistedSession()
    }

    fun onAppForegrounded() {
        val activeSession = mutableUiState.value.session as? PrivateAccountSessionUiState.Active ?: return
        val refreshAt = activeSession.receipt.expiresAt.minusSeconds(SESSION_REFRESH_LEAD_SECONDS)
        if (refreshAt.isAfter(clock.instant())) {
            scheduleSessionRefresh(activeSession.receipt)
        } else {
            refreshActiveSession(activeSession.receipt)
        }
    }

    fun onAppBackgrounded() {
        accountAccessJob?.cancel()
        accountAccessJob = null
        submissionInFlight.set(false)
        if (mutableUiState.value.submission is PrivateAccountSubmissionState.Submitting) {
            mutableUiState.value = mutableUiState.value.copy(submission = PrivateAccountSubmissionState.Idle)
        }
    }

    fun signOutPrivateAccount(deactivateActiveChat: () -> Unit = {}) {
        val activeSession = mutableUiState.value.session as? PrivateAccountSessionUiState.Active ?: return
        if (!sessionOperationInFlight.compareAndSet(false, true)) return
        activeReceiptBeforeSignOut = activeSession.receipt
        cancelScheduledSessionRefresh()
        mutableUiState.value =
            mutableUiState.value.copy(
                session = PrivateAccountSessionUiState.SigningOut,
                signOut = PrivateAccountSignOutUiState.SigningOut,
            )
        try {
            deactivateActiveChat()
        } catch (_: Exception) {
            sessionOperationInFlight.set(false)
            applySignOutOutcome(PrivateAccountSignOutOutcome.LocalStateUnavailable)
            return
        }
        viewModelScope.launch {
            val outcome = requestSignOut()
            sessionOperationInFlight.set(false)
            applySignOutOutcome(outcome)
        }
    }

    fun clearSubmissionNotice() {
        if (submissionInFlight.get()) return
        mutableUiState.value =
            mutableUiState.value.copy(
                submission = PrivateAccountSubmissionState.Idle,
                signOut = PrivateAccountSignOutUiState.Idle,
            )
    }

    private fun restorePersistedSession() {
        if (!sessionOperationInFlight.compareAndSet(false, true)) return
        cancelScheduledSessionRefresh()
        mutableUiState.value =
            mutableUiState.value.copy(
                session = PrivateAccountSessionUiState.Restoring,
                signOut = PrivateAccountSignOutUiState.Idle,
            )
        viewModelScope.launch {
            val outcome = requestSessionRestore()
            sessionOperationInFlight.set(false)
            applySessionOutcome(outcome)
        }
    }

    private fun refreshActiveSession(expectedReceipt: PrivateAccountSessionReceipt.Active) {
        val activeSession = mutableUiState.value.session as? PrivateAccountSessionUiState.Active ?: return
        if (activeSession.receipt != expectedReceipt) return
        if (!sessionOperationInFlight.compareAndSet(false, true)) return
        cancelScheduledSessionRefresh()
        viewModelScope.launch {
            val outcome = requestSessionRefresh().requireProgressFrom(expectedReceipt)
            sessionOperationInFlight.set(false)
            applySessionOutcome(outcome)
        }
    }

    private fun applySessionOutcome(outcome: PrivateAccountSessionOutcome) {
        mutableUiState.value =
            mutableUiState.value.copy(
                session = outcome.toUiState(),
                signOut = PrivateAccountSignOutUiState.Idle,
            )
        synchronizeSessionRefreshSchedule()
    }

    private fun synchronizeSessionRefreshSchedule() {
        val activeReceipt =
            (mutableUiState.value.session as? PrivateAccountSessionUiState.Active)?.receipt
                ?: return cancelScheduledSessionRefresh()
        scheduleSessionRefresh(activeReceipt)
    }

    private fun scheduleSessionRefresh(receipt: PrivateAccountSessionReceipt.Active) {
        cancelScheduledSessionRefresh()
        val refreshAt = receipt.expiresAt.minusSeconds(SESSION_REFRESH_LEAD_SECONDS)
        val delayMillis = Duration.between(clock.instant(), refreshAt).toMillis().coerceAtLeast(0L)
        scheduledSessionRefresh =
            viewModelScope.launch {
                delay(delayMillis)
                scheduledSessionRefresh = null
                refreshActiveSession(receipt)
            }
    }

    private fun cancelScheduledSessionRefresh() {
        scheduledSessionRefresh?.cancel()
        scheduledSessionRefresh = null
    }

    private fun launchAccountAccess(validation: PrivateAccountAccessValidation.Accepted) {
        val launchedJob =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val ownedJob = currentCoroutineContext().job
                try {
                    val outcome = requestAccountAccess(validation)
                    if (accountAccessJob === ownedJob) applyAccountAccessOutcome(outcome)
                } finally {
                    if (accountAccessJob === ownedJob) {
                        accountAccessJob = null
                        submissionInFlight.set(false)
                    }
                }
            }
        accountAccessJob = launchedJob
        launchedJob.start()
    }

    private suspend fun requestAccountAccess(validation: PrivateAccountAccessValidation.Accepted): PrivateAccountAccessOutcome =
        try {
            accountGateway.requestPrivateAccountAccess(validation.command)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PrivateAccountAccessOutcome.VerificationFailed
        }

    private suspend fun requestSessionRestore(): PrivateAccountSessionOutcome =
        try {
            accountGateway.restorePrivateAccountSession()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PrivateAccountSessionOutcome.VerificationFailed
        }

    private suspend fun requestSessionRefresh(): PrivateAccountSessionOutcome =
        try {
            accountGateway.refreshPrivateAccountSession()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PrivateAccountSessionOutcome.VerificationFailed
        }

    private suspend fun requestSignOut(): PrivateAccountSignOutOutcome =
        try {
            accountGateway.signOutPrivateAccount()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PrivateAccountSignOutOutcome.VerificationFailed
        }

    private fun applyAccountAccessOutcome(outcome: PrivateAccountAccessOutcome) {
        mutableUiState.value =
            when (outcome) {
                is PrivateAccountAccessOutcome.Confirmed ->
                    when (val receipt = outcome.receipt) {
                        is PrivateAccountSessionReceipt.Active ->
                            mutableUiState.value.copy(
                                session = PrivateAccountSessionUiState.Active(receipt),
                                submission = PrivateAccountSubmissionState.AccessConfirmed(receipt),
                            )

                        is PrivateAccountSessionReceipt.AwaitingApproval ->
                            mutableUiState.value.copy(
                                session = PrivateAccountSessionUiState.SignedOut,
                                submission = PrivateAccountSubmissionState.AccessConfirmed(receipt),
                            )
                    }

                is PrivateAccountAccessOutcome.Denied ->
                    mutableUiState.value.copy(
                        submission = PrivateAccountSubmissionState.AccessDenied(outcome.userMessage),
                    )

                PrivateAccountAccessOutcome.TransportUnavailable ->
                    mutableUiState.value.copy(submission = PrivateAccountSubmissionState.TransportUnavailable)

                PrivateAccountAccessOutcome.LocalStateUnavailable ->
                    mutableUiState.value.copy(submission = PrivateAccountSubmissionState.LocalStateUnavailable)

                PrivateAccountAccessOutcome.VerificationFailed ->
                    mutableUiState.value.copy(submission = PrivateAccountSubmissionState.UnexpectedFailure)
            }
        synchronizeSessionRefreshSchedule()
    }

    private fun applySignOutOutcome(outcome: PrivateAccountSignOutOutcome) {
        val previousActiveReceipt = activeReceiptBeforeSignOut
        activeReceiptBeforeSignOut = null
        mutableUiState.value =
            when (outcome) {
                is PrivateAccountSignOutOutcome.LocallySignedOut ->
                    PrivateAccountAccessUiState(
                        mode = PrivateAccountAccessMode.SIGN_IN,
                        session = PrivateAccountSessionUiState.SignedOut,
                        signOut =
                            PrivateAccountSignOutUiState.LocallySignedOut(
                                remoteRevocation = outcome.remoteRevocation,
                            ),
                    )

                PrivateAccountSignOutOutcome.AlreadySignedOut ->
                    PrivateAccountAccessUiState(
                        mode = PrivateAccountAccessMode.SIGN_IN,
                        session = PrivateAccountSessionUiState.SignedOut,
                        signOut = PrivateAccountSignOutUiState.AlreadySignedOut,
                    )

                is PrivateAccountSignOutOutcome.Rejected ->
                    stateAfterFailedSignOut(
                        previousActiveReceipt = previousActiveReceipt,
                        signOutState = PrivateAccountSignOutUiState.Rejected(outcome.userMessage),
                    )

                PrivateAccountSignOutOutcome.TransportUnavailable ->
                    stateAfterFailedSignOut(
                        previousActiveReceipt,
                        PrivateAccountSignOutUiState.TransportUnavailable,
                    )

                PrivateAccountSignOutOutcome.LocalStateUnavailable ->
                    stateAfterFailedSignOut(
                        previousActiveReceipt,
                        PrivateAccountSignOutUiState.LocalStateUnavailable,
                    )

                PrivateAccountSignOutOutcome.VerificationFailed ->
                    stateAfterFailedSignOut(
                        previousActiveReceipt,
                        PrivateAccountSignOutUiState.VerificationFailed,
                    )
            }
        synchronizeSessionRefreshSchedule()
    }

    private fun stateAfterFailedSignOut(
        previousActiveReceipt: PrivateAccountSessionReceipt.Active?,
        signOutState: PrivateAccountSignOutUiState,
    ): PrivateAccountAccessUiState =
        mutableUiState.value.copy(
            session =
                previousActiveReceipt?.let { receipt -> PrivateAccountSessionUiState.Active(receipt) }
                    ?: PrivateAccountSessionUiState.LocalStateUnavailable,
            signOut = signOutState,
        )

    private fun PrivateAccountSessionOutcome.toUiState(): PrivateAccountSessionUiState =
        when (this) {
            is PrivateAccountSessionOutcome.Active -> PrivateAccountSessionUiState.Active(receipt)
            PrivateAccountSessionOutcome.SignedOut -> PrivateAccountSessionUiState.SignedOut
            is PrivateAccountSessionOutcome.VerificationRejected ->
                PrivateAccountSessionUiState.VerificationRejected(userMessage)

            PrivateAccountSessionOutcome.TransportUnavailable -> PrivateAccountSessionUiState.TransportUnavailable
            PrivateAccountSessionOutcome.LocalStateUnavailable -> PrivateAccountSessionUiState.LocalStateUnavailable
            PrivateAccountSessionOutcome.VerificationFailed -> PrivateAccountSessionUiState.VerificationFailed
        }

    private fun PrivateAccountSessionOutcome.requireProgressFrom(
        previousReceipt: PrivateAccountSessionReceipt.Active,
    ): PrivateAccountSessionOutcome =
        if (
            this is PrivateAccountSessionOutcome.Active &&
            (receipt.accountId != previousReceipt.accountId || !receipt.expiresAt.isAfter(previousReceipt.expiresAt))
        ) {
            PrivateAccountSessionOutcome.VerificationFailed
        } else {
            this
        }

    private companion object {
        const val SESSION_REFRESH_LEAD_SECONDS = 60L
    }
}
