package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.domain.remote.CreateOwnerAccountCommand
import app.synapse.localllm.domain.remote.OwnerAccountMutationReceipt
import app.synapse.localllm.domain.remote.OwnerAccountSummary
import app.synapse.localllm.domain.remote.OwnerAdminGateway
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerAdminViewModelTest {
    @Test
    fun sensitiveInputsArePassedToBoundariesButNeverRetainedInUiState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ownerUid = RemoteAccountUid("owner-uid")
        val authenticationGateway = mockk<RemoteAuthenticationGateway> {
            every { authenticationState } returns MutableStateFlow(
                RemoteAuthenticationState.SignedIn(
                    RemoteAuthenticatedAccount(
                        accountUid = ownerUid,
                        usernameNormalized = "peter",
                        role = RemoteAccountRole.OWNER,
                        state = RemoteAccountState.ACTIVE,
                        mustChangePassword = false,
                    ),
                ),
            )
            coEvery { reauthenticate(any()) } returns Unit
        }
        val ownerAdminGateway = mockk<OwnerAdminGateway> {
            coEvery { listAccounts(any()) } returns emptyList()
            coEvery { listInvitations() } returns emptyList()
            coEvery { listAuditEvents(any()) } returns emptyList()
            coEvery { getRegistrationApprovalRequired() } returns true
            coEvery { createAccount(any()) } returns OwnerAccountMutationReceipt(RemoteAccountUid("target-uid"))
        }
        val viewModel = OwnerAdminViewModel(authenticationGateway, ownerAdminGateway)
        val ownerPassword = "private owner credential"
        val temporaryPassword = "one time family credential"
        var created = false

        try {
            advanceUntilIdle()
            viewModel.createAccount(
                ownerPassword = ownerPassword,
                command = CreateOwnerAccountCommand(
                    username = "josh",
                    displayName = "Josh",
                    temporaryPassword = temporaryPassword,
                    requirePasswordChange = true,
                ),
                onCreated = { created = true },
            )
            advanceUntilIdle()

            assertTrue(created)
            assertFalse(viewModel.uiState.value.toString().contains(ownerPassword))
            assertFalse(viewModel.uiState.value.toString().contains(temporaryPassword))
            coVerify(exactly = 1) { authenticationGateway.reauthenticate(ownerPassword) }
            coVerify(exactly = 1) {
                ownerAdminGateway.createAccount(
                    match { command -> command.temporaryPassword == temporaryPassword },
                )
            }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateOwnerResponsesCannotRepopulateStateAfterSignOut() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ownerUid = RemoteAccountUid("owner-uid")
        val authenticationStateFlow = MutableStateFlow<RemoteAuthenticationState>(
            RemoteAuthenticationState.SignedIn(
                RemoteAuthenticatedAccount(
                    accountUid = ownerUid,
                    usernameNormalized = "peter",
                    role = RemoteAccountRole.OWNER,
                    state = RemoteAccountState.ACTIVE,
                    mustChangePassword = false,
                ),
            ),
        )
        val delayedAccounts = CompletableDeferred<List<OwnerAccountSummary>>()
        val authenticationGateway = mockk<RemoteAuthenticationGateway> {
            every { authenticationState } returns authenticationStateFlow
        }
        val ownerAdminGateway = mockk<OwnerAdminGateway> {
            coEvery { listAccounts(any()) } coAnswers { delayedAccounts.await() }
            coEvery { listInvitations() } returns emptyList()
            coEvery { listAuditEvents(any()) } returns emptyList()
            coEvery { getRegistrationApprovalRequired() } returns true
        }
        val viewModel = OwnerAdminViewModel(authenticationGateway, ownerAdminGateway)

        try {
            runCurrent()
            authenticationStateFlow.value = RemoteAuthenticationState.SignedOut
            runCurrent()
            delayedAccounts.complete(
                listOf(
                    OwnerAccountSummary(
                        accountUid = RemoteAccountUid("target-uid"),
                        usernameNormalized = "trish",
                        displayName = "Trish",
                        role = RemoteAccountRole.USER,
                        state = RemoteAccountState.ACTIVE,
                        mustChangePassword = false,
                        createdAtMillis = null,
                        lastSeenAtMillis = null,
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(emptyList<OwnerAccountSummary>(), viewModel.uiState.value.accounts)
            assertFalse(viewModel.uiState.value.isActionRunning)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
