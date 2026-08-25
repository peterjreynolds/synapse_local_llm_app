package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock

internal class PrivateExpiringContentCoordinator(
    private val clock: Clock,
    private val coroutineScope: CoroutineScope,
    private val stateStore: PrivateChatUiStateStore,
) {
    private var activeAccountId: PrivateAccountId? = null
    private var foreground = false
    private var sweepJob: Job? = null

    fun activateAccount(accountId: PrivateAccountId) {
        if (activeAccountId != accountId) {
            sweepJob?.cancel()
            sweepJob = null
            activeAccountId = accountId
        }
        reconcileSweep()
    }

    fun deactivateAccount() {
        sweepJob?.cancel()
        sweepJob = null
        activeAccountId = null
    }

    fun enterForeground() {
        foreground = true
        reconcileSweep()
    }

    fun leaveForeground() {
        foreground = false
        reconcileSweep()
    }

    private fun reconcileSweep() {
        val accountId = activeAccountId
        if (!foreground || accountId == null) {
            sweepJob?.cancel()
            sweepJob = null
            return
        }
        if (sweepJob?.isActive == true) return
        sweepJob =
            coroutineScope.launch {
                while (isActive && foreground && activeAccountId == accountId) {
                    removeExpiredPresentationState(accountId)
                    delay(PRIVATE_EXPIRY_SWEEP_INTERVAL_MILLIS)
                }
            }
    }

    private fun removeExpiredPresentationState(accountId: PrivateAccountId) {
        val now = clock.instant()
        stateStore.update { state ->
            val session = state.session as? PrivateChatSessionUiState.Active
            if (session?.accountId != accountId) {
                state
            } else {
                PrivateChatSnapshotPolicy.sanitizePresentedState(state, now)
            }
        }
    }
}

private const val PRIVATE_EXPIRY_SWEEP_INTERVAL_MILLIS = 1_000L
