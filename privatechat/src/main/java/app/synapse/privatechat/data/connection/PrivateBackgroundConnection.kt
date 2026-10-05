package app.synapse.privatechat.data.connection

import app.synapse.privatechat.data.diagnostics.PrivateConnectionDiagnostics
import app.synapse.privatechat.data.diagnostics.PrivateDiagnosticOperation
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateAccountSessionOutcome
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivatePeopleGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The foreground UI owns its heartbeat. This owner runs only while a visible
// Android connection service is enabled and the Activity is in the background.
internal class PrivateBackgroundConnection(
    private val accounts: PrivateAccountGateway,
    private val people: PrivatePeopleGateway,
    private val chats: PrivateChatGateway,
    private val scope: CoroutineScope,
    private val diagnostics: PrivateConnectionDiagnostics,
) {
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private val mutableFailureNotice = MutableStateFlow<String?>(null)
    val failureNotice = mutableFailureNotice.asStateFlow()
    private var foreground = false
    private var accountId: PrivateAccountId? = null
    private var heartbeatJob: Job? = null
    private var inboxJob: Job? = null

    fun activateAccount(accountId: PrivateAccountId) {
        if (this.accountId == accountId) return
        cancelBackgroundWork()
        this.accountId = accountId
        reconcile()
    }

    fun deactivateAccount() {
        accountId = null
        setEnabled(false)
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        reconcile()
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) mutableFailureNotice.value = null
        mutableEnabled.value = enabled && accountId != null
        reconcile()
    }

    fun reportStartFailure(failure: RuntimeException) {
        setEnabled(false)
        diagnostics.record(PrivateDiagnosticOperation.BACKGROUND, failure = failure)
        mutableFailureNotice.value = "Background connection could not start. Check that its notifications are enabled."
    }

    private fun reconcile() {
        val actor = accountId
        if (!mutableEnabled.value || foreground || actor == null) {
            cancelBackgroundWork()
            return
        }
        if (heartbeatJob?.isActive == true) return
        heartbeatJob =
            scope.launch {
                while (isActive) {
                    try {
                        when (val session = accounts.restorePrivateAccountSession()) {
                            is PrivateAccountSessionOutcome.Active -> {
                                if (session.receipt.accountId != actor) {
                                    deactivateAccount()
                                    return@launch
                                }
                                if (people.publishActivity(actor) !is PrivateChatMutationOutcome.Confirmed) {
                                    diagnostics.record(PrivateDiagnosticOperation.BACKGROUND)
                                }
                            }
                            PrivateAccountSessionOutcome.TransportUnavailable ->
                                diagnostics.record(PrivateDiagnosticOperation.SESSION)
                            else -> {
                                // Revocation, vault failure, or identity uncertainty cannot keep activity alive.
                                deactivateAccount()
                                return@launch
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        diagnostics.record(PrivateDiagnosticOperation.BACKGROUND, failure = failure)
                    }
                    delay(25_000L)
                }
            }
        if (!mutableEnabled.value || foreground || accountId != actor) return
        inboxJob =
            scope.launch {
                while (isActive) {
                    try {
                        // Retrieval caches encrypted deliveries without publishing read receipts.
                        chats.observeRoomFeed(actor).collect { observation ->
                            if (observation is PrivateChatObservation.TransportUnavailable) {
                                diagnostics.record(PrivateDiagnosticOperation.BACKGROUND)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        diagnostics.record(PrivateDiagnosticOperation.BACKGROUND, failure = failure)
                    }
                    delay(5_000L)
                }
            }
    }

    private fun cancelBackgroundWork() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        inboxJob?.cancel()
        inboxJob = null
    }
}
