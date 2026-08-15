package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.domain.remote.CreateOwnerAccountCommand
import app.synapse.localllm.domain.remote.OwnerAccountMutationReceipt
import app.synapse.localllm.domain.remote.OwnerAccountSummary
import app.synapse.localllm.domain.remote.OwnerAdminGateway
import app.synapse.localllm.domain.remote.OwnerCleanupJobSummary
import app.synapse.localllm.domain.remote.OwnerCleanupState
import app.synapse.localllm.domain.remote.OwnerOperationsSummary
import app.synapse.localllm.domain.remote.OwnerRoomIntegritySummary
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteInvitationGateway
import app.synapse.localllm.domain.remote.RemoteMessageOutboxOperation
import app.synapse.localllm.domain.remote.RemoteOutboxState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
    fun ownerSignInDefersAdminCallsUntilTheAdminSurfaceIsVisible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val ownerUid = RemoteAccountUid("owner-uid")
        val ownerAdminGateway = mockk<OwnerAdminGateway>(relaxed = true)
        val viewModel = OwnerAdminViewModel(
            authenticationGateway = ownerAuthenticationGateway(ownerUid),
            ownerAdminGateway = ownerAdminGateway,
            remoteInvitationGateway = mockk(),
            remoteChatCacheRepository = emptyOutboxRepository(),
        )

        try {
            advanceUntilIdle()

            coVerify(exactly = 0) { ownerAdminGateway.listAccounts(any()) }
            coVerify(exactly = 0) { ownerAdminGateway.listInvitations() }
            coVerify(exactly = 0) { ownerAdminGateway.listAuditEvents(any()) }
            coVerify(exactly = 0) { ownerAdminGateway.getRegistrationApprovalRequired() }
            coVerify(exactly = 0) { ownerAdminGateway.getOperationsSummary() }

            viewModel.setAdminSurfaceVisible(true)
            advanceUntilIdle()

            coVerify(exactly = 1) { ownerAdminGateway.listAccounts(null) }
            coVerify(exactly = 1) { ownerAdminGateway.listInvitations() }
            coVerify(exactly = 1) { ownerAdminGateway.listAuditEvents() }
            coVerify(exactly = 1) { ownerAdminGateway.getRegistrationApprovalRequired() }
            coVerify(exactly = 1) { ownerAdminGateway.getOperationsSummary() }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun localOutboxSummaryCountsOnlyTheActiveOwnerScope() {
        val ownerUid = RemoteAccountUid("owner-uid")
        val otherUid = RemoteAccountUid("other-uid")
        fun operation(
            accountUid: RemoteAccountUid,
            state: RemoteOutboxState,
        ): RemoteMessageOutboxOperation {
            val operation = mockk<RemoteMessageOutboxOperation>()
            every { operation.accountUid } returns accountUid
            every { operation.state } returns state
            return operation
        }

        assertEquals(
            OwnerLocalOutboxSummary(pendingCount = 1, inFlightCount = 1, failedCount = 1),
            summarizeOwnerOutbox(
                ownerUid,
                listOf(
                    operation(ownerUid, RemoteOutboxState.PENDING),
                    operation(ownerUid, RemoteOutboxState.IN_FLIGHT),
                    operation(ownerUid, RemoteOutboxState.FAILED),
                    operation(otherUid, RemoteOutboxState.FAILED),
                ),
            ),
        )
    }

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
            coEvery { getOperationsSummary() } returns ownerOperationsSummary()
            coEvery { listAccounts(any()) } returns emptyList()
            coEvery { listInvitations() } returns emptyList()
            coEvery { listAuditEvents(any()) } returns emptyList()
            coEvery { getRegistrationApprovalRequired() } returns true
            coEvery { createAccount(any()) } returns OwnerAccountMutationReceipt(RemoteAccountUid("target-uid"))
        }
        val remoteChatCacheRepository = mockk<RemoteChatCacheRepository> {
            every { observePendingOutbox() } returns emptyFlow()
        }
        val viewModel = OwnerAdminViewModel(
            authenticationGateway = authenticationGateway,
            ownerAdminGateway = ownerAdminGateway,
            remoteInvitationGateway = mockk<RemoteInvitationGateway>(),
            remoteChatCacheRepository = remoteChatCacheRepository,
        )
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
            coEvery { getOperationsSummary() } returns ownerOperationsSummary()
            coEvery { listAccounts(any()) } coAnswers { delayedAccounts.await() }
            coEvery { listInvitations() } returns emptyList()
            coEvery { listAuditEvents(any()) } returns emptyList()
            coEvery { getRegistrationApprovalRequired() } returns true
        }
        val remoteChatCacheRepository = mockk<RemoteChatCacheRepository> {
            every { observePendingOutbox() } returns emptyFlow()
        }
        val viewModel = OwnerAdminViewModel(
            authenticationGateway = authenticationGateway,
            ownerAdminGateway = ownerAdminGateway,
            remoteInvitationGateway = mockk<RemoteInvitationGateway>(),
            remoteChatCacheRepository = remoteChatCacheRepository,
        )

        try {
            runCurrent()
            viewModel.setAdminSurfaceVisible(true)
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

private fun ownerAuthenticationGateway(ownerUid: RemoteAccountUid) = mockk<RemoteAuthenticationGateway> {
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
}

private fun emptyOutboxRepository() = mockk<RemoteChatCacheRepository> {
    every { observePendingOutbox() } returns emptyFlow()
}

private fun ownerOperationsSummary() =
    OwnerOperationsSummary(
        backendRevision = "test-revision",
        generatedAtMillis = 1_000L,
        totalDeviceCount = 0,
        activeDeviceCount = 0,
        activeRoomCount = 0,
        pendingNotificationDeliveryCount = 0,
        failedNotificationDeliveryCount = 0,
        integrity = OwnerRoomIntegritySummary(
            checkedRoomCount = 0,
            issueCount = 0,
            issueCodes = emptyList(),
            sampleLimit = 25,
            sampleLimitReached = false,
        ),
        attachmentCleanup = OwnerCleanupJobSummary(
            state = OwnerCleanupState.NEVER_RUN,
            affectedDocumentCount = null,
            lastStartedAtMillis = null,
            lastCompletedAtMillis = null,
        ),
        operationalDataCleanup = OwnerCleanupJobSummary(
            state = OwnerCleanupState.NEVER_RUN,
            affectedDocumentCount = null,
            lastStartedAtMillis = null,
            lastCompletedAtMillis = null,
        ),
    )
