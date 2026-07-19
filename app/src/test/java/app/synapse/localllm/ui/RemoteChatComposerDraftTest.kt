package app.synapse.localllm.ui

import androidx.lifecycle.viewModelScope
import app.synapse.localllm.application.RemoteChatSessionSynchronizer
import app.synapse.localllm.application.RemoteRoomVisibilityTracker
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.remote.EnqueueRemoteMessageCommand
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteCacheMutation
import app.synapse.localllm.domain.remote.RemoteCacheMutationReceipt
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteChatCacheRepository
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceId
import app.synapse.localllm.domain.remote.RemoteDeviceMutation
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationGateway
import app.synapse.localllm.domain.remote.RemoteDeviceRegistrationReceipt
import app.synapse.localllm.domain.remote.RemoteMessageDraft
import app.synapse.localllm.domain.remote.RemoteNotificationPreferences
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.time.SynapseClock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChatComposerDraftTest {
    @Test
    fun `send before draft debounce queues once and cannot rehydrate the composer`() = runTest {
        val harness = createHarness()
        try {
            runCurrent()
            harness.viewModel.selectRoom(ROOM_A)
            runCurrent()

            harness.viewModel.updateComposerText("Message Trish once")
            harness.viewModel.sendMessage("Message Trish once")
            harness.viewModel.sendMessage("Message Trish once")
            runCurrent()

            advanceTimeBy(500L)
            runCurrent()

            assertEquals(1, harness.enqueuedMessages.size)
            val queued = harness.enqueuedMessages.single()
            assertEquals(queued.message.messageId.raw, queued.message.idempotencyKey.raw)
            assertEquals(queued.message.messageId.raw, queued.outboxOperation.idempotencyKey.raw)
            assertNull(harness.draft.value)
            assertEquals("", harness.viewModel.uiState.value.composerText)
            assertEquals(false, harness.viewModel.uiState.value.isActionRunning)
        } finally {
            harness.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `room switch invalidates the old room debounce before it can save`() = runTest {
        val harness = createHarness()
        try {
            runCurrent()
            harness.viewModel.selectRoom(ROOM_A)
            runCurrent()
            harness.viewModel.updateComposerText("Old room draft")
            harness.viewModel.selectRoom(ROOM_B)

            advanceTimeBy(500L)
            runCurrent()

            assertTrue(harness.savedDrafts.isEmpty())
            assertEquals("", harness.viewModel.uiState.value.composerText)

            harness.viewModel.updateComposerText("New room draft")
            advanceTimeBy(301L)
            runCurrent()

            assertEquals(listOf(ROOM_B), harness.savedDrafts.map(RemoteMessageDraft::roomId))
            assertEquals("New room draft", harness.draft.value?.body)
        } finally {
            harness.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed local enqueue keeps the typed draft and composer text`() = runTest {
        val harness = createHarness(enqueueFailure = RemoteChatException("Local message queue is unavailable."))
        try {
            runCurrent()
            harness.viewModel.selectRoom(ROOM_A)
            runCurrent()
            harness.viewModel.updateComposerText("Keep this message")
            harness.viewModel.sendMessage("Keep this message")
            runCurrent()

            assertTrue(harness.enqueuedMessages.isEmpty())
            assertEquals("Keep this message", harness.draft.value?.body)
            assertEquals("Keep this message", harness.viewModel.uiState.value.composerText)
            assertEquals("Local message queue is unavailable.", harness.viewModel.uiState.value.notice)
        } finally {
            harness.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.createHarness(enqueueFailure: Exception? = null): ComposerHarness {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val authenticationState = MutableStateFlow<RemoteAuthenticationState>(
            RemoteAuthenticationState.SignedIn(ACTIVE_ACCOUNT),
        )
        val authenticationGateway = mockk<RemoteAuthenticationGateway>(relaxed = true) {
            every { this@mockk.authenticationState } returns authenticationState
        }
        val draft = MutableStateFlow<RemoteMessageDraft?>(null)
        val savedDrafts = mutableListOf<RemoteMessageDraft>()
        val enqueuedMessages = mutableListOf<EnqueueRemoteMessageCommand>()
        val cacheRepository = mockk<RemoteChatCacheRepository>(relaxed = true) {
            every { observeProfiles() } returns emptyFlow()
            every { observeRooms() } returns flowOf(listOf(room(ROOM_A), room(ROOM_B)))
            every { observeMessages(any()) } returns emptyFlow()
            every { observePendingOutbox() } returns emptyFlow()
            every { observeDraft(any()) } returns draft
            coEvery { saveDraft(any()) } coAnswers {
                val savedDraft = firstArg<RemoteMessageDraft>()
                savedDrafts += savedDraft
                draft.value = savedDraft
                receipt(RemoteCacheMutation.DRAFT_SAVED)
            }
            coEvery { clearDraft(any(), any()) } coAnswers {
                draft.value = null
                receipt(RemoteCacheMutation.DRAFT_CLEARED)
            }
            coEvery { enqueueMessage(any()) } coAnswers {
                enqueueFailure?.let { throw it }
                enqueuedMessages += firstArg<EnqueueRemoteMessageCommand>()
                receipt(RemoteCacheMutation.MESSAGE_ENQUEUED)
            }
        }
        val conversationGateway = mockk<RemoteConversationGateway>(relaxed = true) {
            every { observeOwnReactionSelections(any(), any()) } returns flowOf(emptyMap())
            every { observeTypingParticipants(any(), any()) } returns flowOf(emptyList())
            coEvery { getNotificationPreferences(any()) } returns RemoteNotificationPreferences()
        }
        val deviceRegistrationGateway = mockk<RemoteDeviceRegistrationGateway>(relaxed = true) {
            coEvery { registerCurrentDevice(ACTIVE_ACCOUNT.accountUid) } returns RemoteDeviceRegistrationReceipt(
                accountUid = ACTIVE_ACCOUNT.accountUid,
                deviceId = RemoteDeviceId("composer-test-device"),
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
            remoteAiParticipantGateway = NoOpRemoteAiParticipantGateway,
            deviceRegistrationGateway = deviceRegistrationGateway,
            cacheRepository = cacheRepository,
            sessionSynchronizer = sessionSynchronizer,
            roomVisibilityTracker = RemoteRoomVisibilityTracker(),
            remoteLocalAiResponseHost = IdleRemoteLocalAiResponseHost,
            voiceNoteRecorder = mockk(relaxed = true),
            idFactory = SynapseIdFactory(),
            clock = object : SynapseClock {
                override fun now(): Instant = Instant.EPOCH
            },
        )
        return ComposerHarness(viewModel, draft, savedDrafts, enqueuedMessages)
    }

    private fun room(roomId: RemoteRoomId) = RemoteCachedRoom(
        accountUid = ACTIVE_ACCOUNT.accountUid,
        roomId = roomId,
        kind = RemoteRoomKind.GROUP,
        directKey = null,
        peerUid = null,
        title = "Test room",
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

    private fun receipt(mutation: RemoteCacheMutation) = RemoteCacheMutationReceipt(
        accountUid = ACTIVE_ACCOUNT.accountUid,
        mutation = mutation,
        affectedRows = 1,
    )

    private data class ComposerHarness(
        val viewModel: RemoteChatViewModel,
        val draft: MutableStateFlow<RemoteMessageDraft?>,
        val savedDrafts: List<RemoteMessageDraft>,
        val enqueuedMessages: List<EnqueueRemoteMessageCommand>,
    ) {
        fun close() {
            viewModel.viewModelScope.cancel()
        }
    }

    private companion object {
        val ACTIVE_ACCOUNT = RemoteAuthenticatedAccount(
            accountUid = RemoteAccountUid("peter-uid"),
            usernameNormalized = "peter",
            role = RemoteAccountRole.OWNER,
            state = RemoteAccountState.ACTIVE,
            mustChangePassword = false,
        )
        val ROOM_A = RemoteRoomId("group_${"a".repeat(32)}")
        val ROOM_B = RemoteRoomId("group_${"b".repeat(32)}")
    }
}
