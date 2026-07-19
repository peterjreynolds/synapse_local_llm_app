package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.RemoteChatSessionSynchronizer
import app.synapse.localllm.application.RemoteRoomVisibilityTracker
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAiParticipantGateway
import app.synapse.localllm.domain.remote.RemoteAiProvenance
import app.synapse.localllm.domain.remote.RemoteAiResponsePolicy
import app.synapse.localllm.domain.remote.RemoteAssistantAvailability
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteCinderParticipantState
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteDeviceMutation
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationReceipt
import app.synapse.localllm.domain.remote.RemoteNotificationPreferences
import app.synapse.localllm.domain.remote.RemoteMessageSendReceipt
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.remote.UpdateRemoteCinderParticipantCommand
import app.synapse.localllm.domain.remote.toCachedRoom
import app.synapse.localllm.domain.time.SynapseClock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCinderConversationViewModelTest {
    @Test
    fun cinderDoorSelectsTheNormalMessageThreadWithoutLoadingSynapseAiConfiguration() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val endpoint = RemoteAssistantConversationCatalog.cinder
        val availability = RemoteAssistantAvailability.Unavailable(
            "Cinder is not connected yet. An authenticated Cinder chat backend must be configured.",
        )
        val authenticationGateway = mockk<RemoteAuthenticationGateway>(relaxed = true) {
            every { authenticationState } returns MutableStateFlow(
                RemoteAuthenticationState.SignedIn(ACTIVE_ACCOUNT),
            )
        }
        val cacheRepository = mockk<RemoteChatCacheRepository>(relaxed = true) {
            every { observeProfiles() } returns emptyFlow()
            every { observeRooms() } returns flowOf(listOf(endpoint.toCachedRoom(ACTIVE_ACCOUNT.accountUid)))
            every { observeMessages(any()) } returns emptyFlow()
            every { observePendingOutbox() } returns emptyFlow()
            every { observeDraft(any()) } returns flowOf(null)
        }
        val conversationGateway = mockk<RemoteConversationGateway>(relaxed = true) {
            every { assistantAvailability(endpoint.roomId) } returns availability
            every { observeAssistantAvailability(ACTIVE_ACCOUNT.accountUid, endpoint.roomId) } returns
                flowOf(availability)
            every { observeOwnReactionSelections(any(), any()) } returns emptyFlow()
            every { observeTypingParticipants(any(), any()) } returns emptyFlow()
            coEvery { getNotificationPreferences(any()) } returns RemoteNotificationPreferences()
        }
        val remoteAiParticipantGateway = mockk<RemoteAiParticipantGateway>(relaxed = true)
        val deviceRegistrationGateway = mockk<RemoteDeviceRegistrationGateway>(relaxed = true) {
            coEvery { registerCurrentDevice(ACTIVE_ACCOUNT.accountUid) } returns RemoteDeviceRegistrationReceipt(
                accountUid = ACTIVE_ACCOUNT.accountUid,
                deviceId = RemoteDeviceId("cinder-test-device"),
                mutation = RemoteDeviceMutation.REGISTERED,
                affectedDevices = 1,
            )
        }
        val sessionSynchronizer = mockk<RemoteChatSessionSynchronizer> {
            coEvery { synchronize(any(), any(), any()) } coAnswers { awaitCancellation() }
        }
        val viewModel = RemoteChatViewModel(
            authenticationGateway = authenticationGateway,
            attachmentGateway = mockk(relaxed = true),
            directoryGateway = mockk(relaxed = true),
            conversationGateway = conversationGateway,
            remoteAiParticipantGateway = remoteAiParticipantGateway,
            deviceRegistrationGateway = deviceRegistrationGateway,
            cacheRepository = cacheRepository,
            sessionSynchronizer = sessionSynchronizer,
            roomVisibilityTracker = RemoteRoomVisibilityTracker(),
            remoteLocalAiResponseHost = IdleRemoteLocalAiResponseHost,
            voiceNoteRecorder = mockk(relaxed = true),
            idFactory = SynapseIdFactory(),
            clock = FixedClock,
        )
        try {
            runCurrent()

            assertTrue(viewModel.uiState.value.rooms.any { room -> room.roomId == endpoint.roomId })
            coVerify(exactly = 1) { cacheRepository.ensureAssistantConversation(any()) }

            viewModel.selectRoom(endpoint.roomId)
            runCurrent()

            assertEquals(endpoint.roomId, viewModel.uiState.value.selectedRoomId)
            assertEquals(endpoint, viewModel.uiState.value.selectedAssistantEndpoint)
            assertEquals(availability, viewModel.uiState.value.selectedAssistantAvailability)
            assertEquals(RemoteChatPaneRoute.MESSAGE_THREAD, remoteChatPaneRoute(viewModel.uiState.value))
            verify(exactly = 1) { cacheRepository.observeMessages(endpoint.roomId) }
            coVerify(exactly = 0) {
                remoteAiParticipantGateway.getRoomConfiguration(ACTIVE_ACCOUNT.accountUid, endpoint.roomId)
            }

            viewModel.updateComposerText("Hello Cinder")
            runCurrent()
            viewModel.sendMessage(viewModel.uiState.value.composerText)
            runCurrent()

            assertEquals("Hello Cinder", viewModel.uiState.value.composerText)
            assertEquals(availability.userMessage, viewModel.uiState.value.notice)
            coVerify(exactly = 0) { cacheRepository.enqueueMessage(any()) }
            coVerify(exactly = 0) { cacheRepository.clearDraft(any(), any()) }
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun availableCinderSubmitsBeforeAnyLocalMessageAndClearsDraftOnlyAfterAcceptance() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val endpoint = RemoteAssistantConversationCatalog.cinder
        val authenticationGateway = mockk<RemoteAuthenticationGateway>(relaxed = true) {
            every { authenticationState } returns MutableStateFlow(
                RemoteAuthenticationState.SignedIn(ACTIVE_ACCOUNT),
            )
        }
        val cacheRepository = mockk<RemoteChatCacheRepository>(relaxed = true) {
            every { observeProfiles() } returns emptyFlow()
            every { observeRooms() } returns flowOf(listOf(endpoint.toCachedRoom(ACTIVE_ACCOUNT.accountUid)))
            every { observeMessages(any()) } returns emptyFlow()
            every { observePendingOutbox() } returns emptyFlow()
            every { observeDraft(any()) } returns flowOf(null)
        }
        val conversationGateway = mockk<RemoteConversationGateway>(relaxed = true) {
            every { assistantAvailability(endpoint.roomId) } returns RemoteAssistantAvailability.Available
            every { observeAssistantAvailability(ACTIVE_ACCOUNT.accountUid, endpoint.roomId) } returns
                flowOf(RemoteAssistantAvailability.Available)
            every { observeOwnReactionSelections(any(), any()) } returns emptyFlow()
            every { observeTypingParticipants(any(), any()) } returns emptyFlow()
            coEvery { getNotificationPreferences(any()) } returns RemoteNotificationPreferences()
            coEvery { sendMessage(any()) } coAnswers {
                val command = firstArg<app.synapse.localllm.domain.remote.SendRemoteMessageCommand>()
                RemoteMessageSendReceipt(
                    command.message.accountUid,
                    command.message.roomId,
                    command.message.messageId,
                )
            }
        }
        val deviceRegistrationGateway = mockk<RemoteDeviceRegistrationGateway>(relaxed = true) {
            coEvery { registerCurrentDevice(ACTIVE_ACCOUNT.accountUid) } returns RemoteDeviceRegistrationReceipt(
                accountUid = ACTIVE_ACCOUNT.accountUid,
                deviceId = RemoteDeviceId("cinder-submit-device"),
                mutation = RemoteDeviceMutation.REGISTERED,
                affectedDevices = 1,
            )
        }
        val sessionSynchronizer = mockk<RemoteChatSessionSynchronizer> {
            coEvery { synchronize(any(), any(), any()) } coAnswers { awaitCancellation() }
        }
        val viewModel = RemoteChatViewModel(
            authenticationGateway = authenticationGateway,
            attachmentGateway = mockk(relaxed = true),
            directoryGateway = mockk(relaxed = true),
            conversationGateway = conversationGateway,
            remoteAiParticipantGateway = mockk(relaxed = true),
            deviceRegistrationGateway = deviceRegistrationGateway,
            cacheRepository = cacheRepository,
            sessionSynchronizer = sessionSynchronizer,
            roomVisibilityTracker = RemoteRoomVisibilityTracker(),
            remoteLocalAiResponseHost = IdleRemoteLocalAiResponseHost,
            voiceNoteRecorder = mockk(relaxed = true),
            idFactory = SynapseIdFactory(),
            clock = FixedClock,
        )
        try {
            runCurrent()
            viewModel.selectRoom(endpoint.roomId)
            viewModel.updateComposerText("Hello Cinder")
            runCurrent()

            viewModel.sendMessage("Hello Cinder")
            runCurrent()

            coVerify(exactly = 1) { conversationGateway.sendMessage(any()) }
            coVerify(exactly = 0) { cacheRepository.enqueueMessage(any()) }
            coVerify(exactly = 1) { cacheRepository.clearDraft(ACTIVE_ACCOUNT.accountUid, endpoint.roomId) }
            assertEquals("", viewModel.uiState.value.composerText)
            assertEquals(null, viewModel.uiState.value.notice)
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun humanRoomSummonsMentionsAndRemovesCinderThroughParticipantState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationGateway = mockk<RemoteAuthenticationGateway>(relaxed = true) {
            every { authenticationState } returns MutableStateFlow(
                RemoteAuthenticationState.SignedIn(ACTIVE_ACCOUNT),
            )
        }
        val cacheRepository = mockk<RemoteChatCacheRepository>(relaxed = true) {
            every { observeProfiles() } returns emptyFlow()
            every { observeRooms() } returns flowOf(listOf(humanRoom()))
            every { observeMessages(any()) } returns emptyFlow()
            every { observePendingOutbox() } returns emptyFlow()
            every { observeDraft(any()) } returns flowOf(null)
        }
        val conversationGateway = mockk<RemoteConversationGateway>(relaxed = true) {
            every { observeOwnReactionSelections(any(), any()) } returns emptyFlow()
            every { observeTypingParticipants(any(), any()) } returns emptyFlow()
            coEvery { getNotificationPreferences(any()) } returns RemoteNotificationPreferences()
        }
        val remoteAiParticipantGateway = mockk<RemoteAiParticipantGateway>(relaxed = true) {
            coEvery { getRoomConfiguration(any(), any()) } coAnswers { awaitCancellation() }
            coEvery { getCinderParticipant(ACTIVE_ACCOUNT.accountUid, HUMAN_ROOM_ID) } returns
                cinderParticipant(active = false)
            coEvery { updateCinderParticipant(any()) } coAnswers {
                cinderParticipant(firstArg<UpdateRemoteCinderParticipantCommand>().active)
            }
        }
        val deviceRegistrationGateway = mockk<RemoteDeviceRegistrationGateway>(relaxed = true) {
            coEvery { registerCurrentDevice(ACTIVE_ACCOUNT.accountUid) } returns RemoteDeviceRegistrationReceipt(
                accountUid = ACTIVE_ACCOUNT.accountUid,
                deviceId = RemoteDeviceId("cinder-participant-device"),
                mutation = RemoteDeviceMutation.REGISTERED,
                affectedDevices = 1,
            )
        }
        val sessionSynchronizer = mockk<RemoteChatSessionSynchronizer> {
            coEvery { synchronize(any(), any(), any()) } coAnswers { awaitCancellation() }
        }
        val viewModel = RemoteChatViewModel(
            authenticationGateway = authenticationGateway,
            attachmentGateway = mockk(relaxed = true),
            directoryGateway = mockk(relaxed = true),
            conversationGateway = conversationGateway,
            remoteAiParticipantGateway = remoteAiParticipantGateway,
            deviceRegistrationGateway = deviceRegistrationGateway,
            cacheRepository = cacheRepository,
            sessionSynchronizer = sessionSynchronizer,
            roomVisibilityTracker = RemoteRoomVisibilityTracker(),
            remoteLocalAiResponseHost = IdleRemoteLocalAiResponseHost,
            voiceNoteRecorder = mockk(relaxed = true),
            idFactory = SynapseIdFactory(),
            clock = FixedClock,
        )
        try {
            runCurrent()
            viewModel.selectRoom(HUMAN_ROOM_ID)
            runCurrent()

            assertFalse(requireNotNull(viewModel.uiState.value.cinderParticipant).active)

            viewModel.updateCinderParticipation(active = true)
            runCurrent()

            assertTrue(requireNotNull(viewModel.uiState.value.cinderParticipant).active)
            assertEquals("Cinder added to this conversation.", viewModel.uiState.value.notice)
            viewModel.insertCinderMention()
            runCurrent()
            assertEquals("@Cinder ", viewModel.uiState.value.composerText)

            viewModel.updateCinderParticipation(active = false)
            runCurrent()

            assertFalse(requireNotNull(viewModel.uiState.value.cinderParticipant).active)
            assertEquals("Cinder removed from this conversation.", viewModel.uiState.value.notice)
            coVerify(exactly = 1) {
                remoteAiParticipantGateway.updateCinderParticipant(
                    match { command -> command.roomId == HUMAN_ROOM_ID && command.active },
                )
            }
            coVerify(exactly = 1) {
                remoteAiParticipantGateway.updateCinderParticipant(
                    match { command -> command.roomId == HUMAN_ROOM_ID && !command.active },
                )
            }
        } finally {
            viewModel.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unavailableAssistantDisablesSubmissionWhileKeepingTheComposerEditable() {
        val unavailable = RemoteAssistantAvailability.Unavailable(
            "Cinder is not connected yet. An authenticated Cinder chat backend must be configured.",
        )

        assertFalse(remoteComposerSubmissionEnabled(unavailable))
        assertTrue(remoteComposerSubmissionEnabled(RemoteAssistantAvailability.Available))
        assertTrue(remoteComposerSubmissionEnabled(null))
        assertFalse(
            remoteComposerCanSend(
                composerText = "Keep this draft",
                attachmentStates = emptyList(),
                isRecordingVoiceNote = false,
                isActionRunning = false,
                submissionEnabled = false,
            ),
        )
    }

    private object FixedClock : SynapseClock {
        override fun now(): Instant = Instant.EPOCH
    }

    private fun cinderParticipant(active: Boolean) = RemoteCinderParticipantState(
        roomId = HUMAN_ROOM_ID,
        participantId = RemoteAssistantConversationCatalog.cinder.participantId,
        displayName = "Cinder",
        active = active,
        canManage = true,
        provenance = RemoteAiProvenance.REMOTE_HOSTED,
        provider = "OPENCLAW_CINDER",
        responsePolicy = RemoteAiResponsePolicy.MENTION_ONLY,
    )

    private fun humanRoom() = RemoteCachedRoom(
        accountUid = ACTIVE_ACCOUNT.accountUid,
        roomId = HUMAN_ROOM_ID,
        kind = RemoteRoomKind.DIRECT,
        directKey = "peter-uid:trish-uid",
        peerUid = RemoteProfileUid("trish-uid"),
        title = "Trish",
        avatarObjectPath = null,
        unreadCount = 0,
        latestMessagePreview = null,
        latestMessageSenderUid = null,
        currentMemberRole = RemoteRoomMemberRole.MEMBER,
        notificationsEnabled = true,
        isMuted = false,
        isArchived = false,
        isPinned = false,
        joinedAt = Instant.EPOCH,
        lastReadAt = null,
        remoteUpdatedAt = Instant.EPOCH,
    )

    private companion object {
        val HUMAN_ROOM_ID = RemoteRoomId("direct_${"a".repeat(64)}")
        val ACTIVE_ACCOUNT = RemoteAuthenticatedAccount(
            accountUid = RemoteAccountUid("peter-uid"),
            usernameNormalized = "peter",
            role = RemoteAccountRole.OWNER,
            state = RemoteAccountState.ACTIVE,
            mustChangePassword = false,
        )
    }
}
