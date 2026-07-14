package app.synapse.localllm.application

import app.synapse.localllm.domain.remote.RegisterRemoteDeviceInstallationCommand
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteDeviceMutation
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationReceipt
import app.synapse.localllm.domain.remote.RemotePasswordChangeCommand
import app.synapse.localllm.domain.remote.RemoteInviteRegistrationCommand
import app.synapse.localllm.domain.remote.RemoteSignInCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteDeviceRegistrationCoordinatorTest {
    @Test
    fun refreshedInstallationIsRegisteredForSignedInAccount() = runTest {
        val authenticationGateway = RecordingAuthenticationGateway(
            RemoteAuthenticationState.SignedIn(
                activeAccount(PETER_ACCOUNT, "peter"),
            ),
        )
        val deviceGateway = RecordingDeviceRegistrationGateway()
        val coordinator = RemoteDeviceRegistrationCoordinator(
            authenticationGateway = authenticationGateway,
            deviceRegistrationGateway = deviceGateway,
            applicationScope = this,
        )

        coordinator.handleRefreshedInstallation(FIREBASE_INSTALLATION_ID)
        advanceUntilIdle()

        assertEquals(PETER_ACCOUNT, deviceGateway.registeredCommand?.accountUid)
        assertEquals(FIREBASE_INSTALLATION_ID, deviceGateway.registeredCommand?.installationId)
        assertEquals(
            RemoteDeviceMutation.REGISTERED,
            (coordinator.status.value as RemoteDeviceRegistrationStatus.Registered).receipt.mutation,
        )
    }

    @Test
    fun refreshedInstallationIsIgnoredWhileSignedOut() = runTest {
        val deviceGateway = RecordingDeviceRegistrationGateway()
        val coordinator = RemoteDeviceRegistrationCoordinator(
            authenticationGateway = RecordingAuthenticationGateway(RemoteAuthenticationState.SignedOut),
            deviceRegistrationGateway = deviceGateway,
            applicationScope = this,
        )

        coordinator.handleRefreshedInstallation(FIREBASE_INSTALLATION_ID)
        advanceUntilIdle()

        assertNull(deviceGateway.registeredCommand)
        assertEquals(RemoteDeviceRegistrationStatus.Idle, coordinator.status.value)
    }

    @Test
    fun refreshedInstallationIsIgnoredWhileApprovalIsPending() = runTest {
        val deviceGateway = RecordingDeviceRegistrationGateway()
        val coordinator = RemoteDeviceRegistrationCoordinator(
            authenticationGateway = RecordingAuthenticationGateway(
                RemoteAuthenticationState.SignedIn(
                    activeAccount(PETER_ACCOUNT, "peter").copy(
                        state = RemoteAccountState.PENDING_APPROVAL,
                    ),
                ),
            ),
            deviceRegistrationGateway = deviceGateway,
            applicationScope = this,
        )

        coordinator.handleRefreshedInstallation(FIREBASE_INSTALLATION_ID)
        advanceUntilIdle()

        assertNull(deviceGateway.registeredCommand)
        assertEquals(RemoteDeviceRegistrationStatus.Idle, coordinator.status.value)
    }

    @Test
    fun refreshedInstallationIsIgnoredWhilePasswordChangeIsRequired() = runTest {
        val deviceGateway = RecordingDeviceRegistrationGateway()
        val coordinator = RemoteDeviceRegistrationCoordinator(
            authenticationGateway = RecordingAuthenticationGateway(
                RemoteAuthenticationState.SignedIn(
                    activeAccount(PETER_ACCOUNT, "peter").copy(mustChangePassword = true),
                ),
            ),
            deviceRegistrationGateway = deviceGateway,
            applicationScope = this,
        )

        coordinator.handleRefreshedInstallation(FIREBASE_INSTALLATION_ID)
        advanceUntilIdle()

        assertNull(deviceGateway.registeredCommand)
        assertEquals(RemoteDeviceRegistrationStatus.Idle, coordinator.status.value)
    }

    private class RecordingAuthenticationGateway(
        initialState: RemoteAuthenticationState,
    ) : RemoteAuthenticationGateway {
        override val authenticationState: StateFlow<RemoteAuthenticationState> = MutableStateFlow(initialState)

        override suspend fun signIn(command: RemoteSignInCommand): RemoteAuthenticatedAccount =
            error("Not used by this test.")

        override suspend fun registerWithInvite(
            command: RemoteInviteRegistrationCommand,
        ): RemoteAuthenticatedAccount = error("Not used by this test.")

        override suspend fun refreshAccount(): RemoteAuthenticatedAccount =
            error("Not used by this test.")

        override suspend fun reauthenticate(password: String) {
            error("Not used by this test.")
        }

        override suspend fun changePassword(command: RemotePasswordChangeCommand) {
            error("Not used by this test.")
        }

        override suspend fun signOut() {
            error("Not used by this test.")
        }
    }

    private class RecordingDeviceRegistrationGateway : RemoteDeviceRegistrationGateway {
        var registeredCommand: RegisterRemoteDeviceInstallationCommand? = null

        override suspend fun registerCurrentDevice(
            accountUid: RemoteAccountUid,
        ): RemoteDeviceRegistrationReceipt = error("Not used by this test.")

        override suspend fun registerRefreshedInstallation(
            command: RegisterRemoteDeviceInstallationCommand,
        ): RemoteDeviceRegistrationReceipt {
            registeredCommand = command
            return RemoteDeviceRegistrationReceipt(
                accountUid = command.accountUid,
                deviceId = RemoteDeviceId("device-id"),
                mutation = RemoteDeviceMutation.REGISTERED,
                affectedDevices = 1,
            )
        }

        override suspend fun removeCurrentDevice(
            accountUid: RemoteAccountUid,
        ): RemoteDeviceRegistrationReceipt = error("Not used by this test.")
    }

    private companion object {
        fun activeAccount(uid: RemoteAccountUid, username: String) = RemoteAuthenticatedAccount(
            accountUid = uid,
            usernameNormalized = username,
            role = RemoteAccountRole.USER,
            state = RemoteAccountState.ACTIVE,
            mustChangePassword = false,
        )

        val PETER_ACCOUNT = RemoteAccountUid("peter-uid")
        const val FIREBASE_INSTALLATION_ID = "firebase-installation-id"
    }
}
