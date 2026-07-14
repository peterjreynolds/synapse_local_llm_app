package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.RemoteChatSessionSynchronizer
import app.synapse.localllm.application.RemoteRoomVisibilityTracker
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDirectoryGateway
import app.synapse.localllm.domain.time.SynapseClock
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatViewModelAccountAccessTest {
    @Test
    fun pendingAccountDoesNotStartRemoteChatResources() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pendingAccount = RemoteAuthenticatedAccount(
            accountUid = RemoteAccountUid("pending-uid"),
            usernameNormalized = "pending_user",
            role = RemoteAccountRole.USER,
            state = RemoteAccountState.PENDING_APPROVAL,
            mustChangePassword = false,
        )
        val authenticationGateway = mockk<RemoteAuthenticationGateway> {
            every { authenticationState } returns MutableStateFlow(
                RemoteAuthenticationState.SignedIn(pendingAccount),
            )
        }
        val cacheRepository = mockk<RemoteChatCacheRepository>(relaxed = true)
        val directoryGateway = mockk<RemoteDirectoryGateway>(relaxed = true)
        val conversationGateway = mockk<RemoteConversationGateway>(relaxed = true)
        val deviceRegistrationGateway = mockk<RemoteDeviceRegistrationGateway>(relaxed = true)
        val sessionSynchronizer = mockk<RemoteChatSessionSynchronizer>(relaxed = true)
        val viewModel = RemoteChatViewModel(
            authenticationGateway = authenticationGateway,
            directoryGateway = directoryGateway,
            conversationGateway = conversationGateway,
            deviceRegistrationGateway = deviceRegistrationGateway,
            cacheRepository = cacheRepository,
            sessionSynchronizer = sessionSynchronizer,
            roomVisibilityTracker = RemoteRoomVisibilityTracker(),
            idFactory = SynapseIdFactory(),
            clock = object : SynapseClock {
                override fun now(): Instant = Instant.EPOCH
            },
        )

        try {
            runCurrent()

            assertEquals(pendingAccount, viewModel.uiState.value.account)
            coVerify(exactly = 0) { cacheRepository.activateAccount(any()) }
            coVerify(exactly = 0) { directoryGateway.updatePresence(any(), any()) }
            coVerify(exactly = 0) { deviceRegistrationGateway.registerCurrentDevice(any()) }
            coVerify(exactly = 0) { sessionSynchronizer.synchronize(any(), any(), any()) }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
