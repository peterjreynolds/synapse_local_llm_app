package app.synapse.localllm.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteAttachmentPolicyTest {
    @Test
    fun `normalizes filename extension from trusted MIME`() {
        val decision = RemoteAttachmentPolicy.validate(
            displayName = "../Quarterly report.EXE",
            mimeType = "application/pdf",
            byteCount = 1_024,
        )

        assertEquals("Quarterly report.pdf", decision.displayName)
        assertEquals(RemoteAttachmentKind.DOCUMENT, decision.kind)
    }

    @Test
    fun `rejects executable MIME and oversized content`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteAttachmentPolicy.validate("payload.apk", "application/vnd.android.package-archive", 1_024)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemoteAttachmentPolicy.validate("report.pdf", "application/pdf", 26L * 1024 * 1024)
        }
    }

    @Test
    fun `requires a bounded measured voice note duration`() {
        val decision = RemoteAttachmentPolicy.validate(
            displayName = "Voice note.m4a",
            mimeType = "audio/mp4",
            byteCount = 4_096,
            audioDurationMillis = 12_500,
            isVoiceNote = true,
        )

        assertEquals(RemoteAttachmentKind.VOICE_NOTE, decision.kind)
        assertEquals(12_500L, decision.durationMillis)
    }

    @Test
    fun `accepts GIF images with a normalized extension`() {
        val decision = RemoteAttachmentPolicy.validate(
            displayName = "reaction.not-really-a-jpg",
            mimeType = "image/gif",
            byteCount = 2_048,
        )

        assertEquals("reaction.gif", decision.displayName)
        assertEquals(RemoteAttachmentKind.IMAGE, decision.kind)
    }
}
