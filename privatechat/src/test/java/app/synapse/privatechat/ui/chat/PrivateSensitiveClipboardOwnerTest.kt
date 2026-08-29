package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateMessageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateSensitiveClipboardOwnerTest {
    @Test
    fun `clipboard owner rejects an Android API below the supported application boundary`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivateSensitiveClipboardOwner(
                clipboard = RecordingSensitiveClipboard(),
                clearScheduler = RecordingClipboardClearScheduler(),
                androidSdkInt = 24,
            )
        }
    }

    @Test
    fun `legacy invitation clipboard is cleared after the guarded delay`() {
        val clipboard = RecordingSensitiveClipboard()
        val scheduler = RecordingClipboardClearScheduler()
        val owner =
            PrivateSensitiveClipboardOwner(
                clipboard = clipboard,
                clearScheduler = scheduler,
                androidSdkInt = 25,
            )

        assertEquals(
            PrivateSensitiveClipboardCopyOutcome.COPIED,
            owner.copyInvitationCode("invitation-secret"),
        )
        assertFalse(clipboard.markedSensitive)
        assertEquals(PRIVATE_SENSITIVE_CLIPBOARD_CLEAR_DELAY_MILLIS, scheduler.delayMillis)

        scheduler.runScheduledClear()

        assertTrue(clipboard.cleared)
        assertNull(clipboard.plaintext)
    }

    @Test
    fun `guarded clear preserves a clipboard value copied later`() {
        val clipboard = RecordingSensitiveClipboard()
        val scheduler = RecordingClipboardClearScheduler()
        val owner =
            PrivateSensitiveClipboardOwner(
                clipboard = clipboard,
                clearScheduler = scheduler,
                androidSdkInt = 32,
            )
        owner.copyInvitationCode("invitation-secret")
        clipboard.plaintext = "newer clipboard value"

        scheduler.runScheduledClear()

        assertFalse(clipboard.cleared)
        assertEquals("newer clipboard value", clipboard.plaintext)
    }

    @Test
    fun `modern invitation clipboard is sensitive and receives the guarded clear`() {
        val clipboard = RecordingSensitiveClipboard()
        val scheduler = RecordingClipboardClearScheduler()
        val owner =
            PrivateSensitiveClipboardOwner(
                clipboard = clipboard,
                clearScheduler = scheduler,
                androidSdkInt = 33,
            )

        owner.copyInvitationCode("invitation-secret")

        assertTrue(clipboard.markedSensitive)
        assertTrue(scheduler.hasScheduledClear)
        scheduler.runScheduledClear()
        assertTrue(clipboard.cleared)
    }

    @Test
    fun `message copy schedules an exact-value clear on modern Android`() {
        val clipboard = RecordingSensitiveClipboard()
        val scheduler = RecordingClipboardClearScheduler()
        val owner =
            PrivateSensitiveClipboardOwner(
                clipboard = clipboard,
                clearScheduler = scheduler,
                androidSdkInt = 36,
            )

        owner.copyMessageText(PrivateMessageText("sensitive message"))

        assertTrue(clipboard.markedSensitive)
        assertTrue(scheduler.hasScheduledClear)
        scheduler.runScheduledClear()
        assertTrue(clipboard.cleared)
    }
}

private class RecordingSensitiveClipboard : PrivateSensitiveClipboardGateway {
    var plaintext: String? = null
    var markedSensitive: Boolean = false
    var cleared: Boolean = false

    override fun replacePlainText(
        label: String,
        plaintext: String,
        markSensitive: Boolean,
    ): Boolean {
        this.plaintext = plaintext
        markedSensitive = markSensitive
        cleared = false
        return true
    }

    override fun containsExactPlainText(plaintext: String): Boolean = this.plaintext == plaintext

    override fun clear(): Boolean {
        plaintext = null
        cleared = true
        return true
    }
}

private class RecordingClipboardClearScheduler : PrivateClipboardClearScheduler {
    var delayMillis: Long? = null
    private var scheduledClear: (() -> Unit)? = null
    val hasScheduledClear: Boolean
        get() = scheduledClear != null

    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ) {
        this.delayMillis = delayMillis
        scheduledClear = action
    }

    fun runScheduledClear() {
        requireNotNull(scheduledClear).invoke()
    }
}
