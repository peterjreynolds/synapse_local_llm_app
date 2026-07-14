package app.synapse.localllm.application

import app.synapse.localllm.domain.remote.RegisterRemoteDeviceInstallationCommand
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RemoteDeviceRegistrationStatus {
    data object Idle : RemoteDeviceRegistrationStatus

    data class Registered(
        val receipt: RemoteDeviceRegistrationReceipt,
    ) : RemoteDeviceRegistrationStatus

    data class Failed(
        val userMessage: String,
    ) : RemoteDeviceRegistrationStatus
}

class RemoteDeviceRegistrationCoordinator(
    private val authenticationGateway: RemoteAuthenticationGateway,
    private val deviceRegistrationGateway: RemoteDeviceRegistrationGateway,
    private val applicationScope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow<RemoteDeviceRegistrationStatus>(
        RemoteDeviceRegistrationStatus.Idle,
    )

    val status: StateFlow<RemoteDeviceRegistrationStatus> = mutableStatus.asStateFlow()

    fun handleRefreshedInstallation(installationId: String) {
        val authenticationState = authenticationGateway.authenticationState.value
        val account = (authenticationState as? RemoteAuthenticationState.SignedIn)?.account
        if (account?.state != RemoteAccountState.ACTIVE) {
            mutableStatus.value = RemoteDeviceRegistrationStatus.Idle
            return
        }
        applicationScope.launch {
            mutableStatus.value = try {
                val receipt = deviceRegistrationGateway.registerRefreshedInstallation(
                    RegisterRemoteDeviceInstallationCommand(
                        accountUid = account.accountUid,
                        installationId = installationId,
                    ),
                )
                RemoteDeviceRegistrationStatus.Registered(receipt)
            } catch (exception: RemoteChatException) {
                RemoteDeviceRegistrationStatus.Failed(exception.userMessage)
            } catch (_: Exception) {
                RemoteDeviceRegistrationStatus.Failed(
                    "Could not refresh notification registration. Try signing in again.",
                )
            }
        }
    }
}
