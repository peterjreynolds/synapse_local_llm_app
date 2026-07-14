package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.RemoteChatSessionSynchronizer
import app.synapse.localllm.application.RemoteRoomVisibilityTracker
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteDeviceMutation
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationReceipt
import app.synapse.localllm.domain.remote.RemoteDirectoryGateway
import app.synapse.localllm.domain.time.SynapseClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatViewModelLogoutTest {
    @Test
    fun successfulCleanupPrecedesLocalAuthenticationTermination() = runTest {
        val harness = createHarness()

        try {
            runCurrent()
            harness.viewModel.signOut()
            runCurrent()

            coVerifyOrder {
                harness.deviceRegistrationGateway.removeCurrentDevice(ACCOUNT_UID)
                harness.authenticationGateway.signOut()
            }
            assertEquals(RemoteAuthenticationState.SignedOut, harness.authenticationState.value)
        } finally {
            harness.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cleanupFailureStillTerminatesLocalAuthentication() = runTest {
        val harness = createHarness(
            cleanup = {
                throw RemoteChatException("Network unavailable during notification cleanup.")
            },
        )

        try {
            runCurrent()
            harness.viewModel.signOut()
            runCurrent()

            coVerify(exactly = 1) { harness.deviceRegistrationGateway.removeCurrentDevice(ACCOUNT_UID) }
            coVerify(exactly = 1) { harness.authenticationGateway.signOut() }
            assertEquals(RemoteAuthenticationState.SignedOut, harness.authenticationState.value)
        } finally {
            harness.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stalledCleanupIsBoundedBeforeLocalAuthenticationTermination() = runTest {
        val harness = createHarness(
            cleanup = { awaitCancellation() },
            cleanupTimeoutMillis = 1_000L,
        )

        try {
            runCurrent()
            harness.viewModel.signOut()
            runCurrent()
            coVerify(exactly = 0) { harness.authenticationGateway.signOut() }

            advanceTimeBy(1_000L)
            runCurrent()

            coVerify(exactly = 1) { harness.authenticationGateway.signOut() }
            assertEquals(RemoteAuthenticationState.SignedOut, harness.authenticationState.value)
        } finally {
            harness.close()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.createHarness(
        cleanup: suspend () -> Unit = {},
        cleanupTimeoutMillis: Long = 10_000L,
    ): LogoutHarness {
        val mainDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val authenticatedAccount = RemoteAuthenticatedAccount(
            accountUid = ACCOUNT_UID,
            usernameNormalized = "peter",
            role = RemoteAccountRole.OWNER,
            state = RemoteAccountState.ACTIVE,
            mustChangePassword = false,
        )
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            RemoteAuthenticationState.SignedIn(authenticatedAccount),
        )
        val authenticationGateway = mockk<RemoteAuthenticationGateway>()
        every { authenticationGateway.authenticationState } returns authenticationState
        coEvery { authenticationGateway.signOut() } answers {
            authenticationState.value = RemoteAuthenticationState.SignedOut
        }
        val deviceRegistrationGateway = mockk<RemoteDeviceRegistrationGateway>(relaxed = true)
        coEvery { deviceRegistrationGateway.removeCurrentDevice(ACCOUNT_UID) } coAnswers {
            cleanup()
            DEVICE_REMOVAL_RECEIPT
        }
        val cacheRepository = mockk<RemoteChatCacheRepository>(relaxed = true) {
            every { observeProfiles() } returns emptyFlow()
            every { observeRooms() } returns emptyFlow()
            every { observeMessages(any()) } returns emptyFlow()
            every { observePendingOutbox() } returns emptyFlow()
        }
        val sessionSynchronizer = mockk<RemoteChatSessionSynchronizer> {
            coEvery { synchronize(ACCOUNT_UID, any(), any()) } coAnswers { awaitCancellation() }
        }
        val viewModel = RemoteChatViewModel(
            authenticationGateway = authenticationGateway,
            attachmentGateway = mockk(relaxed = true),
            directoryGateway = mockk<RemoteDirectoryGateway>(relaxed = true),
            conversationGateway = mockk<RemoteConversationGateway>(relaxed = true),
            deviceRegistrationGateway = deviceRegistrationGateway,
            cacheRepository = cacheRepository,
            sessionSynchronizer = sessionSynchronizer,
            roomVisibilityTracker = RemoteRoomVisibilityTracker(),
            voiceNoteRecorder = mockk(relaxed = true),
            idFactory = SynapseIdFactory(),
            clock = object : SynapseClock {
                override fun now(): Instant = Instant.EPOCH
            },
            remoteLogoutCleanupTimeoutMillis = cleanupTimeoutMillis,
        )
        return LogoutHarness(
            authenticationGateway = authenticationGateway,
            authenticationState = authenticationState,
            deviceRegistrationGateway = deviceRegistrationGateway,
            viewModel = viewModel,
        )
    }

    private data class LogoutHarness(
        val authenticationGateway: RemoteAuthenticationGateway,
        val authenticationState: MutableStateFlow<RemoteAuthenticationState>,
        val deviceRegistrationGateway: RemoteDeviceRegistrationGateway,
        val viewModel: RemoteChatViewModel,
    ) {
        fun close() {
            viewModel.viewModelScope.cancel()
        }
    }

    private companion object {
        val ACCOUNT_UID = RemoteAccountUid("peter-uid")
        val DEVICE_REMOVAL_RECEIPT = RemoteDeviceRegistrationReceipt(
            accountUid = ACCOUNT_UID,
            deviceId = RemoteDeviceId("device-id"),
            mutation = RemoteDeviceMutation.REMOVED,
            affectedDevices = 1,
        )
    }
}
