package app.synapse.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.synapse.localllm.di.SynapseApplicationGraph
import app.synapse.localllm.domain.remote.CreateOwnerAccountCommand
import app.synapse.localllm.domain.remote.CreateOwnerInvitationCommand
import app.synapse.localllm.domain.remote.OwnerAccountSummary
import app.synapse.localllm.domain.remote.OwnerAdminGateway
import app.synapse.localllm.domain.remote.OwnerAuditEventSummary
import app.synapse.localllm.domain.remote.OwnerDeviceSummary
import app.synapse.localllm.domain.remote.OwnerInvitationSummary
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.ResetOwnerAccountPasswordCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OwnerAdminUiState(
    val accounts: List<OwnerAccountSummary> = emptyList(),
    val invitations: List<OwnerInvitationSummary> = emptyList(),
    val selectedAccountUid: RemoteAccountUid? = null,
    val selectedAccountDevices: List<OwnerDeviceSummary> = emptyList(),
    val auditEvents: List<OwnerAuditEventSummary> = emptyList(),
    val registrationApprovalRequired: Boolean = true,
    val isActionRunning: Boolean = false,
    val notice: String? = null,
)

class OwnerAdminViewModel(
    private val authenticationGateway: RemoteAuthenticationGateway,
    private val ownerAdminGateway: OwnerAdminGateway,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(OwnerAdminUiState())
    private var activeOwnerUid: RemoteAccountUid? = null

    val uiState: StateFlow<OwnerAdminUiState> = mutableUiState

    init {
        viewModelScope.launch {
            authenticationGateway.authenticationState.collectLatest { authenticationState ->
                when (authenticationState) {
                    is RemoteAuthenticationState.SignedIn -> {
                        val account = authenticationState.account
                        if (
                            account.role == RemoteAccountRole.OWNER &&
                            account.state == RemoteAccountState.ACTIVE &&
                            !account.mustChangePassword
                        ) {
                            if (activeOwnerUid != account.accountUid) {
                                activeOwnerUid = account.accountUid
                                mutableUiState.value = OwnerAdminUiState()
                                refresh()
                            }
                        } else {
                            clearOwnerState()
                        }
                    }
                    RemoteAuthenticationState.SignedOut,
                    is RemoteAuthenticationState.InvalidSession,
                    -> clearOwnerState()
                    RemoteAuthenticationState.Resolving -> Unit
                }
            }
        }
    }

    fun refresh(searchPrefix: String? = null) = launchAction {
        val ownerUid = requireOwnerSession()
        val snapshot = coroutineScope {
            val accounts = async { ownerAdminGateway.listAccounts(searchPrefix) }
            val invitations = async { ownerAdminGateway.listInvitations() }
            val auditEvents = async { ownerAdminGateway.listAuditEvents() }
            val registrationApprovalRequired = async {
                ownerAdminGateway.getRegistrationApprovalRequired()
            }
            OwnerAdminSnapshot(
                accounts = accounts.await(),
                invitations = invitations.await(),
                auditEvents = auditEvents.await(),
                registrationApprovalRequired = registrationApprovalRequired.await(),
            )
        }
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(
                accounts = snapshot.accounts,
                invitations = snapshot.invitations,
                auditEvents = snapshot.auditEvents,
                registrationApprovalRequired = snapshot.registrationApprovalRequired,
            )
        }
    }

    fun selectAccount(accountUid: RemoteAccountUid?) = launchAction {
        val ownerUid = requireOwnerSession()
        val devices = accountUid?.let { ownerAdminGateway.listDevices(it) }.orEmpty()
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(selectedAccountUid = accountUid, selectedAccountDevices = devices)
        }
    }

    fun reviewRegistration(
        targetUid: RemoteAccountUid,
        approve: Boolean,
    ) = launchMutation(if (approve) "Registration approved." else "Registration rejected.") {
        ownerAdminGateway.reviewRegistration(targetUid, approve)
    }

    fun setAccountEnabled(
        targetUid: RemoteAccountUid,
        enabled: Boolean,
    ) = launchMutation(if (enabled) "Account enabled." else "Account disabled.") {
        ownerAdminGateway.setAccountEnabled(targetUid, enabled)
    }

    fun revokeAccountSessions(targetUid: RemoteAccountUid) =
        launchMutation("Account sessions revoked.") {
            ownerAdminGateway.revokeAccountSessions(targetUid)
        }

    fun createAccount(
        ownerPassword: String,
        command: CreateOwnerAccountCommand,
        onCreated: () -> Unit,
    ) = launchSensitiveMutation(ownerPassword, "Account created.", onCreated) {
        ownerAdminGateway.createAccount(command)
    }

    fun resetAccountPassword(
        ownerPassword: String,
        command: ResetOwnerAccountPasswordCommand,
        onReset: () -> Unit,
    ) = launchSensitiveMutation(ownerPassword, "Temporary password set.", onReset) {
        ownerAdminGateway.resetAccountPassword(command)
    }

    fun deleteAccount(
        ownerPassword: String,
        targetUid: RemoteAccountUid,
        confirmUsername: String,
        onDeleted: () -> Unit,
    ) = launchSensitiveMutation(ownerPassword, "Account deleted.", onDeleted) {
        ownerAdminGateway.deleteAccount(targetUid, confirmUsername)
        mutableUiState.update { state ->
            state.copy(selectedAccountUid = null, selectedAccountDevices = emptyList())
        }
    }

    fun createInvitation(
        command: CreateOwnerInvitationCommand,
        onCreated: (String) -> Unit,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        val receipt = ownerAdminGateway.createInvitation(command)
        requireOwnerSession(ownerUid)
        onCreated(receipt.invitationCode)
        refreshAfterMutation("Invitation created.", ownerUid)
    }

    fun revokeInvitation(invitationId: String) = launchMutation("Invitation revoked.") {
        ownerAdminGateway.revokeInvitation(invitationId)
    }

    fun setRegistrationApprovalRequired(required: Boolean) = launchAction {
        val ownerUid = requireOwnerSession()
        ownerAdminGateway.setRegistrationApprovalRequired(required)
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(
                registrationApprovalRequired = required,
                notice = "Registration approval setting saved.",
            )
        }
    }

    fun removeDevice(
        targetUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        ownerAdminGateway.removeDevice(targetUid, deviceId)
        val devices = ownerAdminGateway.listDevices(targetUid)
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(selectedAccountDevices = devices, notice = "Device registration removed.")
        }
    }

    fun sendTestPush(
        targetUid: RemoteAccountUid,
        deviceId: RemoteDeviceId,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        ownerAdminGateway.sendTestPush(targetUid, deviceId)
        requireOwnerSession(ownerUid)
        mutableUiState.update { state -> state.copy(notice = "Test notification sent.") }
    }

    fun clearNotice() {
        mutableUiState.update { state -> state.copy(notice = null) }
    }

    private fun launchMutation(
        successNotice: String,
        mutation: suspend () -> Unit,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        mutation()
        requireOwnerSession(ownerUid)
        refreshAfterMutation(successNotice, ownerUid)
    }

    private fun launchSensitiveMutation(
        ownerPassword: String,
        successNotice: String,
        onSuccess: () -> Unit,
        mutation: suspend () -> Unit,
    ) = launchAction {
        val ownerUid = requireOwnerSession()
        authenticationGateway.reauthenticate(ownerPassword)
        requireOwnerSession(ownerUid)
        mutation()
        requireOwnerSession(ownerUid)
        onSuccess()
        refreshAfterMutation(successNotice, ownerUid)
    }

    private suspend fun refreshAfterMutation(
        successNotice: String,
        ownerUid: RemoteAccountUid,
    ) {
        requireOwnerSession(ownerUid)
        val accounts = ownerAdminGateway.listAccounts()
        val invitations = ownerAdminGateway.listInvitations()
        val auditEvents = ownerAdminGateway.listAuditEvents()
        val registrationApprovalRequired = ownerAdminGateway.getRegistrationApprovalRequired()
        val selectedUid = mutableUiState.value.selectedAccountUid
        val selectedDevices = selectedUid?.let { ownerAdminGateway.listDevices(it) }.orEmpty()
        requireOwnerSession(ownerUid)
        mutableUiState.update { state ->
            state.copy(
                accounts = accounts,
                invitations = invitations,
                auditEvents = auditEvents,
                selectedAccountDevices = selectedDevices,
                registrationApprovalRequired = registrationApprovalRequired,
                notice = successNotice,
            )
        }
    }

    private fun launchAction(action: suspend () -> Unit) {
        if (mutableUiState.value.isActionRunning) return
        mutableUiState.update { state -> state.copy(isActionRunning = true, notice = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        notice = (exception as? RemoteChatException)?.userMessage
                            ?: "Owner administration action failed.",
                    )
                }
            } finally {
                mutableUiState.update { state -> state.copy(isActionRunning = false) }
            }
        }
    }

    private fun requireOwnerSession(): RemoteAccountUid =
        activeOwnerUid ?: throw RemoteChatException("Owner access is no longer active.")

    private fun requireOwnerSession(expectedOwnerUid: RemoteAccountUid) {
        if (activeOwnerUid != expectedOwnerUid) throw OwnerSessionChangedException()
    }

    private fun clearOwnerState() {
        activeOwnerUid = null
        mutableUiState.value = OwnerAdminUiState()
    }
}

private class OwnerSessionChangedException : CancellationException("Owner session changed.")

private data class OwnerAdminSnapshot(
    val accounts: List<OwnerAccountSummary>,
    val invitations: List<OwnerInvitationSummary>,
    val auditEvents: List<OwnerAuditEventSummary>,
    val registrationApprovalRequired: Boolean,
)

class OwnerAdminViewModelFactory(
    private val graph: SynapseApplicationGraph,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OwnerAdminViewModel::class.java)) {
            return modelClass.cast(
                OwnerAdminViewModel(
                    authenticationGateway = graph.remoteAuthenticationGateway,
                    ownerAdminGateway = graph.ownerAdminGateway,
                ),
            ) ?: throw IllegalArgumentException("Unable to create OwnerAdminViewModel.")
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}.")
    }
}
