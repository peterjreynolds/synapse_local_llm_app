package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.data.remote.RemoteAccountSessionCoordinator
import app.synapse.localllm.domain.remote.CreateRemoteGroupRoomCommand
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteGroupGateway
import app.synapse.localllm.domain.remote.RemoteGroupMember
import app.synapse.localllm.domain.remote.RemoteGroupMutation
import app.synapse.localllm.domain.remote.RemoteGroupMutationReceipt
import app.synapse.localllm.domain.remote.RemoteGroupRoomDetails
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGroupViewModelTest {
    @Test
    fun createGroupPublishesAuthorizedRoomNavigation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(signedIn(PETER_ACCOUNT))
        val authenticationGateway = mockk<RemoteAuthenticationGateway> {
            every { this@mockk.authenticationState } returns authenticationState
        }
        val groupGateway = mockk<RemoteGroupGateway>()
        val sessionController = RemoteAccountSessionCoordinator().apply { beginSession(PETER_ACCOUNT) }
        coEvery { groupGateway.createGroupRoom(any<CreateRemoteGroupRoomCommand>()) } returns
            RemoteGroupMutationReceipt(PETER_ACCOUNT, GROUP_ROOM_ID, RemoteGroupMutation.CREATED, 1)
        coEvery { groupGateway.getGroupRoomDetails(PETER_ACCOUNT, GROUP_ROOM_ID) } returns
            groupDetails(PETER_ACCOUNT)
        val viewModel = RemoteGroupViewModel(authenticationGateway, groupGateway, sessionController)
        try {
            runCurrent()

            viewModel.createGroup("Project", setOf(TRISH_PROFILE))
            runCurrent()

            assertEquals(GROUP_ROOM_ID, viewModel.uiState.value.roomToOpen)
            assertEquals("Project", viewModel.uiState.value.details?.title)
            assertEquals("Group created.", viewModel.uiState.value.notice)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun accountSwitchCancelsGroupLoadAndClearsPriorAccountState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(signedIn(PETER_ACCOUNT))
        val authenticationGateway = mockk<RemoteAuthenticationGateway> {
            every { this@mockk.authenticationState } returns authenticationState
        }
        val groupGateway = mockk<RemoteGroupGateway>()
        val sessionController = RemoteAccountSessionCoordinator().apply { beginSession(PETER_ACCOUNT) }
        var peterLoadCancelled = false
        coEvery { groupGateway.getGroupRoomDetails(PETER_ACCOUNT, GROUP_ROOM_ID) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                peterLoadCancelled = true
            }
        }
        val viewModel = RemoteGroupViewModel(authenticationGateway, groupGateway, sessionController)
        try {
            runCurrent()
            viewModel.loadGroupDetails(GROUP_ROOM_ID)
            runCurrent()
            assertTrue(viewModel.uiState.value.isLoading)

            sessionController.beginSession(TRISH_ACCOUNT)
            authenticationState.value = signedIn(TRISH_ACCOUNT)
            runCurrent()

            assertTrue(peterLoadCancelled)
            assertEquals(TRISH_ACCOUNT, viewModel.uiState.value.accountUid)
            assertNull(viewModel.uiState.value.activeRoomId)
            assertNull(viewModel.uiState.value.details)
            assertEquals(false, viewModel.uiState.value.isLoading)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private fun signedIn(accountUid: RemoteAccountUid): RemoteAuthenticationState.SignedIn =
        RemoteAuthenticationState.SignedIn(
            RemoteAuthenticatedAccount(
                accountUid = accountUid,
                usernameNormalized = accountUid.raw.removeSuffix("-uid"),
                role = RemoteAccountRole.USER,
                state = RemoteAccountState.ACTIVE,
                mustChangePassword = false,
            ),
        )

    private fun groupDetails(accountUid: RemoteAccountUid): RemoteGroupRoomDetails =
        RemoteGroupRoomDetails(
            accountUid = accountUid,
            roomId = GROUP_ROOM_ID,
            title = "Project",
            avatarObjectPath = null,
            avatarUrl = null,
            ownerUid = RemoteProfileUid(accountUid.raw),
            revision = 1,
            currentMemberRole = RemoteRoomMemberRole.OWNER,
            isArchived = false,
            isMuted = false,
            isPinned = false,
            members = listOf(
                RemoteGroupMember(RemoteProfileUid(accountUid.raw), RemoteRoomMemberRole.OWNER, NOW),
                RemoteGroupMember(TRISH_PROFILE, RemoteRoomMemberRole.MEMBER, NOW),
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T12:00:00Z")
        val PETER_ACCOUNT = RemoteAccountUid("peter-uid")
        val TRISH_ACCOUNT = RemoteAccountUid("trish-uid")
        val TRISH_PROFILE = RemoteProfileUid("trish-uid")
        val GROUP_ROOM_ID = RemoteRoomId("group_${"a".repeat(32)}")
    }
}
