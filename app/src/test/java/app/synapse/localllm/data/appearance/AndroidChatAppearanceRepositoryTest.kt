package app.synapse.localllm.data.appearance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.appearance.ChatAppearance
import app.synapse.localllm.domain.appearance.ChatBackground
import app.synapse.localllm.domain.appearance.ChatBubblePalette
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidChatAppearanceRepositoryTest {
    @Test
    fun appearanceIsScopedByAccountAndRoomAndCanBeReset() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AndroidChatAppearanceRepository(
            context = context,
            clock = FixedClock,
            storageFileName = "chat-appearance-${UUID.randomUUID()}.preferences_pb",
        )
        val selectedAppearance = ChatAppearance(
            bubblePalette = ChatBubblePalette.OCEAN,
            background = ChatBackground.OCEAN_CAUSTICS,
            messageScale = 1.2f,
        )

        val saveReceipt = repository.saveAppearance(PETER_ACCOUNT, ROOM_ID, selectedAppearance)

        assertEquals(selectedAppearance, saveReceipt.appearance)
        assertEquals(FixedClock.now(), saveReceipt.persistedAt)
        assertEquals(selectedAppearance, repository.observeAppearance(PETER_ACCOUNT, ROOM_ID).first())
        assertEquals(ChatAppearance(), repository.observeAppearance(TRISH_ACCOUNT, ROOM_ID).first())
        assertEquals(ChatAppearance(), repository.observeAppearance(PETER_ACCOUNT, SECOND_ROOM_ID).first())

        val paletteReceipt = repository.saveAccountBubblePalette(PETER_ACCOUNT, ChatBubblePalette.ROSE)

        assertEquals(ChatBubblePalette.ROSE, paletteReceipt.bubblePalette)
        assertEquals(ChatBubblePalette.ROSE, repository.observeAccountBubblePalette(PETER_ACCOUNT).first())
        assertEquals(
            selectedAppearance.copy(bubblePalette = ChatBubblePalette.ROSE),
            repository.observeAppearance(PETER_ACCOUNT, ROOM_ID).first(),
        )
        assertEquals(
            ChatAppearance(bubblePalette = ChatBubblePalette.ROSE),
            repository.observeAppearance(PETER_ACCOUNT, SECOND_ROOM_ID).first(),
        )
        val secondRoomReceipt = repository.saveAppearance(
            PETER_ACCOUNT,
            SECOND_ROOM_ID,
            ChatAppearance(
                bubblePalette = ChatBubblePalette.AMBER,
                background = ChatBackground.FOREST_MIST,
                messageScale = 0.9f,
            ),
        )
        assertEquals(ChatBubblePalette.ROSE, secondRoomReceipt.appearance.bubblePalette)

        val resetReceipt = repository.resetAppearance(PETER_ACCOUNT, ROOM_ID)

        assertEquals(ChatAppearance(bubblePalette = ChatBubblePalette.ROSE), resetReceipt.appearance)
        assertEquals(
            ChatAppearance(bubblePalette = ChatBubblePalette.ROSE),
            repository.observeAppearance(PETER_ACCOUNT, ROOM_ID).first(),
        )
    }

    private object FixedClock : SynapseClock {
        override fun now(): Instant = Instant.parse("2026-07-17T08:00:00Z")
    }

    private companion object {
        val PETER_ACCOUNT = RemoteAccountUid("peter-uid")
        val TRISH_ACCOUNT = RemoteAccountUid("trish-uid")
        val ROOM_ID = RemoteRoomId("direct_${"a".repeat(64)}")
        val SECOND_ROOM_ID = RemoteRoomId("direct_${"b".repeat(64)}")
    }
}
