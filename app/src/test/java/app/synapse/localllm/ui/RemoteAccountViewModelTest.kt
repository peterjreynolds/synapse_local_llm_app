package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.domain.remote.CreateRemoteInvitationCommand
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountSessionToken
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteBlockMutationReceipt
import app.synapse.localllm.domain.remote.RemoteDeletionRequestReceipt
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteInvitationCreatedReceipt
import app.synapse.localllm.domain.remote.RemoteInvitationGateway
import app.synapse.localllm.domain.remote.RemotePrivacyGateway
import app.synapse.localllm.domain.remote.RemotePrivacyState
import app.synapse.localllm.domain.remote.RemoteProfileUid
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAccountViewModelTest {
    @Test
    fun signInLoadsPrivacyButDefersDeviceInventoryUntilAccountControlsAreVisible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            signedInState(PETER_UID, "peter"),
        )
        val privacyGateway = mockk<RemotePrivacyGateway> {
            coEvery { getOwnPrivacyState() } returns RemotePrivacyState(emptySet(), false)
        }
        val deviceGateway = emptyDeviceGateway()
        val viewModel = RemoteAccountViewModel(
            authenticationGateway = mockk {
                every { this@mockk.authenticationState } returns authenticationState
            },
            privacyGateway = privacyGateway,
            deviceRegistrationGateway = deviceGateway,
            invitationGateway = emptyInvitationGateway(),
            sessionController = activeSessionController(PETER_UID),
        )

        try {
            advanceUntilIdle()

            assertEquals(PETER_UID, viewModel.uiState.value.accountUid)
            assertFalse(viewModel.uiState.value.isRefreshing)
            assertTrue(viewModel.uiState.value.privacyStateVerified)
            coVerify(exactly = 1) { privacyGateway.getOwnPrivacyState() }
            coVerify(exactly = 0) { deviceGateway.listOwnDevices(any()) }

            viewModel.refresh()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.privacyStateVerified)
            coVerify(exactly = 2) { privacyGateway.getOwnPrivacyState() }
            coVerify(exactly = 1) { deviceGateway.listOwnDevices(PETER_UID) }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun blockAndDeletionMutationsUpdateStateWithoutRetainingPassword() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            signedInState(PETER_UID, "peter"),
        )
        val authenticationGateway = mockk<RemoteAuthenticationGateway> {
            every { this@mockk.authenticationState } returns authenticationState
            coEvery { reauthenticate(any()) } returns Unit
        }
        val privacyGateway = mockk<RemotePrivacyGateway> {
            coEvery { getOwnPrivacyState() } returns RemotePrivacyState(emptySet(), false)
            coEvery { setUserBlocked(TRISH_UID, true) } returns RemoteBlockMutationReceipt(
                targetUid = TRISH_UID,
                blocked = true,
            )
            coEvery { requestAccountDeletion() } returns RemoteDeletionRequestReceipt(true)
        }
        val viewModel = RemoteAccountViewModel(
            authenticationGateway = authenticationGateway,
            privacyGateway = privacyGateway,
            deviceRegistrationGateway = emptyDeviceGateway(),
            invitationGateway = emptyInvitationGateway(),
            sessionController = activeSessionController(PETER_UID),
        )
        val password = "private account password"

        try {
            advanceUntilIdle()
            viewModel.setUserBlocked(TRISH_UID, blocked = true)
            advanceUntilIdle()
            assertTrue(TRISH_UID in viewModel.uiState.value.blockedProfileUids)

            viewModel.requestAccountDeletion(password, "peter")
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.deletionRequestPending)
            assertFalse(viewModel.uiState.value.toString().contains(password))
            coVerify(exactly = 1) { authenticationGateway.reauthenticate(password) }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateAccountResponseCannotRepopulateStateAfterSignOut() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            signedInState(PETER_UID, "peter"),
        )
        val delayedPrivacy = CompletableDeferred<RemotePrivacyState>()
        val privacyGateway = mockk<RemotePrivacyGateway> {
            coEvery { getOwnPrivacyState() } coAnswers { delayedPrivacy.await() }
        }
        val viewModel = RemoteAccountViewModel(
            authenticationGateway = mockk {
                every { this@mockk.authenticationState } returns authenticationState
            },
            privacyGateway = privacyGateway,
            deviceRegistrationGateway = emptyDeviceGateway(),
            invitationGateway = emptyInvitationGateway(),
            sessionController = activeSessionController(PETER_UID),
        )

        try {
            runCurrent()
            authenticationState.value = RemoteAuthenticationState.SignedOut
            runCurrent()
            delayedPrivacy.complete(RemotePrivacyState(setOf(TRISH_UID), true))
            advanceUntilIdle()

            assertEquals(RemoteAccountUiState(), viewModel.uiState.value)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun accountSwitchCancelsDeletionBeforeItCanTargetTheNextAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            signedInState(PETER_UID, "peter"),
        )
        val delayedReauthentication = CompletableDeferred<Unit>()
        val authenticationGateway = mockk<RemoteAuthenticationGateway> {
            every { this@mockk.authenticationState } returns authenticationState
            coEvery { reauthenticate(any()) } coAnswers { delayedReauthentication.await() }
        }
        val privacyGateway = mockk<RemotePrivacyGateway> {
            coEvery { getOwnPrivacyState() } returns RemotePrivacyState(emptySet(), false)
        }
        val viewModel = RemoteAccountViewModel(
            authenticationGateway = authenticationGateway,
            privacyGateway = privacyGateway,
            deviceRegistrationGateway = emptyDeviceGateway(),
            invitationGateway = emptyInvitationGateway(),
            sessionController = activeSessionController(PETER_UID),
        )

        try {
            advanceUntilIdle()
            viewModel.requestAccountDeletion("private password", "peter")
            runCurrent()
            authenticationState.value = signedInState(TRISH_ACCOUNT_UID, "trish")
            runCurrent()
            delayedReauthentication.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 0) { privacyGateway.requestAccountDeletion() }
            assertEquals(TRISH_ACCOUNT_UID, viewModel.uiState.value.accountUid)
            assertFalse(viewModel.uiState.value.deletionRequestPending)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun activeUserCreatesOneUseInvitationWithoutRetainingItAfterClearOrSignOut() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            signedInState(TRISH_ACCOUNT_UID, "trish"),
        )
        val receipt = RemoteInvitationCreatedReceipt(
            invitationId = "a".repeat(64),
            invitationCode = "one-use-invitation-code",
            expiresAtMillis = 123_456L,
            maximumUses = 1,
        )
        val invitationGateway = mockk<RemoteInvitationGateway> {
            coEvery { createInvitation(any()) } returns receipt
        }
        val viewModel = RemoteAccountViewModel(
            authenticationGateway = mockk {
                every { this@mockk.authenticationState } returns authenticationState
            },
            privacyGateway = mockk {
                coEvery { getOwnPrivacyState() } returns RemotePrivacyState(emptySet(), false)
            },
            deviceRegistrationGateway = emptyDeviceGateway(),
            invitationGateway = invitationGateway,
            sessionController = activeSessionController(TRISH_ACCOUNT_UID),
        )

        try {
            advanceUntilIdle()
            viewModel.createInvitation()
            advanceUntilIdle()

            assertEquals(receipt, viewModel.uiState.value.generatedInvitation)
            coVerify(exactly = 1) {
                invitationGateway.createInvitation(
                    CreateRemoteInvitationCommand(
                        intendedLabel = null,
                        lifetimeHours = 168,
                        maximumUses = 1,
                    ),
                )
            }

            viewModel.clearGeneratedInvitation()
            assertEquals(null, viewModel.uiState.value.generatedInvitation)

            viewModel.createInvitation()
            advanceUntilIdle()
            authenticationState.value = RemoteAuthenticationState.SignedOut
            advanceUntilIdle()
            assertEquals(RemoteAccountUiState(), viewModel.uiState.value)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun emptyDeviceGateway() = mockk<RemoteDeviceRegistrationGateway> {
        coEvery { listOwnDevices(any()) } returns emptyList()
    }

    private fun emptyInvitationGateway() = mockk<RemoteInvitationGateway>()

    private fun activeSessionController(accountUid: RemoteAccountUid) =
        mockk<RemoteAccountSessionController>(relaxed = true) {
            every { activeSession } returns MutableStateFlow(RemoteAccountSessionToken(accountUid, 1L))
        }

    private fun signedInState(accountUid: RemoteAccountUid, username: String) =
        RemoteAuthenticationState.SignedIn(
            RemoteAuthenticatedAccount(
                accountUid = accountUid,
                usernameNormalized = username,
                role = RemoteAccountRole.USER,
                state = RemoteAccountState.ACTIVE,
                mustChangePassword = false,
            ),
        )

    private companion object {
        val PETER_UID = RemoteAccountUid("peter-uid")
        val TRISH_ACCOUNT_UID = RemoteAccountUid("trish-uid")
        val TRISH_UID = RemoteProfileUid("trish-uid")
    }
}
