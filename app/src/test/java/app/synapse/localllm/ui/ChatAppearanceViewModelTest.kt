package app.synapse.localllm.ui

import app.synapse.localllm.domain.appearance.ChatAppearance
import app.synapse.localllm.domain.appearance.ChatAppearanceMutationReceipt
import app.synapse.localllm.domain.appearance.ChatAppearanceRepository
import app.synapse.localllm.domain.appearance.ChatBackground
import app.synapse.localllm.domain.appearance.ChatBubblePalette
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatAppearanceViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun selectedConversationLoadsAndPersistsAppearance() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingChatAppearanceRepository()
        val viewModel = ChatAppearanceViewModel(repository)
        viewModel.selectConversation(PETER_ACCOUNT, ROOM_ID)
        runCurrent()

        viewModel.selectBubblePalette(ChatBubblePalette.VIOLET)
        runCurrent()
        viewModel.selectBackground(ChatBackground.VIOLET_NEBULA)
        runCurrent()

        assertEquals(
            ChatAppearance(ChatBubblePalette.VIOLET, ChatBackground.VIOLET_NEBULA),
            viewModel.uiState.value.appearance,
        )
        assertEquals(PETER_ACCOUNT, viewModel.uiState.value.accountUid)
        assertEquals(ROOM_ID, viewModel.uiState.value.roomId)
    }

    private class RecordingChatAppearanceRepository : ChatAppearanceRepository {
        private val appearances = mutableMapOf<String, MutableStateFlow<ChatAppearance>>()

        override fun observeAppearance(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
        ): Flow<ChatAppearance> = appearances.getOrPut(key(accountUid, roomId)) { MutableStateFlow(ChatAppearance()) }

        override suspend fun saveAppearance(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
            appearance: ChatAppearance,
        ): ChatAppearanceMutationReceipt {
            appearances.getOrPut(key(accountUid, roomId)) { MutableStateFlow(ChatAppearance()) }.value = appearance
            return receipt(accountUid, roomId, appearance)
        }

        override suspend fun resetAppearance(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
        ): ChatAppearanceMutationReceipt {
            val appearance = ChatAppearance()
            appearances.getOrPut(key(accountUid, roomId)) { MutableStateFlow(appearance) }.value = appearance
            return receipt(accountUid, roomId, appearance)
        }

        private fun key(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
        ): String = "${accountUid.raw}:${roomId.raw}"

        private fun receipt(
            accountUid: RemoteAccountUid,
            roomId: RemoteRoomId,
            appearance: ChatAppearance,
        ) = ChatAppearanceMutationReceipt(accountUid, roomId, appearance, NOW)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-17T08:00:00Z")
        val PETER_ACCOUNT = RemoteAccountUid("peter-uid")
        val ROOM_ID = RemoteRoomId("direct_${"a".repeat(64)}")
    }
}
