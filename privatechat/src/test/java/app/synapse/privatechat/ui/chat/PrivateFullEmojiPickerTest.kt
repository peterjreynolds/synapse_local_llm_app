package app.synapse.privatechat.ui.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateFullEmojiPickerTest {
    @Test
    fun `emoji picker provider never retains selections`() =
        runTest {
            PrivateNonPersistingRecentEmojiProvider.recordSelection("👍")

            assertTrue(PrivateNonPersistingRecentEmojiProvider.getRecentEmojiList().isEmpty())
        }
}
