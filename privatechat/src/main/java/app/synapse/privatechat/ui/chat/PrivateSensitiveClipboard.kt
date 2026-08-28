package app.synapse.privatechat.ui.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import app.synapse.privatechat.domain.chat.PrivateMessageText

internal enum class PrivateSensitiveClipboardCopyOutcome {
    COPIED,
    CLIPBOARD_UNAVAILABLE,
}

internal interface PrivateSensitiveClipboardGateway {
    fun replacePlainText(
        label: String,
        plaintext: String,
        markSensitive: Boolean,
    ): Boolean

    fun containsExactPlainText(plaintext: String): Boolean

    fun clear(): Boolean
}

internal fun interface PrivateClipboardClearScheduler {
    fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    )
}

internal class PrivateSensitiveClipboardOwner(
    private val clipboard: PrivateSensitiveClipboardGateway,
    private val clearScheduler: PrivateClipboardClearScheduler,
    private val androidSdkInt: Int,
) {
    init {
        require(androidSdkInt >= PRIVATE_MINIMUM_ANDROID_SDK) { "Android clipboard API is unsupported." }
    }

    fun copyInvitationCode(invitationCode: String): PrivateSensitiveClipboardCopyOutcome =
        copyPlainText(
            label = PRIVATE_INVITATION_CLIPBOARD_LABEL,
            plaintext = invitationCode,
        )

    fun copyMessageText(messageText: PrivateMessageText): PrivateSensitiveClipboardCopyOutcome =
        copyPlainText(
            label = PRIVATE_MESSAGE_CLIPBOARD_LABEL,
            plaintext = messageText.plaintext,
        )

    private fun copyPlainText(
        label: String,
        plaintext: String,
    ): PrivateSensitiveClipboardCopyOutcome {
        val copied =
            clipboard.replacePlainText(
                label = label,
                plaintext = plaintext,
                markSensitive = androidSdkInt >= PRIVATE_ANDROID_SENSITIVE_CLIP_API,
            )
        if (!copied) return PrivateSensitiveClipboardCopyOutcome.CLIPBOARD_UNAVAILABLE

        clearScheduler.schedule(PRIVATE_SENSITIVE_CLIPBOARD_CLEAR_DELAY_MILLIS) {
            // Never erase a newer clipboard value owned by the user or another app.
            if (clipboard.containsExactPlainText(plaintext)) {
                clipboard.clear()
            }
        }
        return PrivateSensitiveClipboardCopyOutcome.COPIED
    }
}

internal fun privateSensitiveClipboardOwner(context: Context): PrivateSensitiveClipboardOwner? {
    val clipboardManager =
        context.applicationContext.getSystemService(ClipboardManager::class.java)
            ?: return null
    return PrivateSensitiveClipboardOwner(
        clipboard = AndroidPrivateSensitiveClipboardGateway(clipboardManager),
        clearScheduler = AndroidPrivateClipboardClearScheduler,
        androidSdkInt = Build.VERSION.SDK_INT,
    )
}

private class AndroidPrivateSensitiveClipboardGateway(
    private val clipboardManager: ClipboardManager,
) : PrivateSensitiveClipboardGateway {
    override fun replacePlainText(
        label: String,
        plaintext: String,
        markSensitive: Boolean,
    ): Boolean =
        try {
            val clip = ClipData.newPlainText(label, plaintext)
            if (markSensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras =
                    PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
            }
            clipboardManager.setPrimaryClip(clip)
            true
        } catch (_: SecurityException) {
            false
        }

    override fun containsExactPlainText(plaintext: String): Boolean =
        try {
            val clip = clipboardManager.primaryClip
            clip != null &&
                clip.itemCount == 1 &&
                clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                clip.getItemAt(0).text?.toString() == plaintext
        } catch (_: SecurityException) {
            false
        }

    override fun clear(): Boolean =
        try {
            clipboardManager.clearPrimaryClip()
            true
        } catch (_: SecurityException) {
            false
        }
}

private object AndroidPrivateClipboardClearScheduler : PrivateClipboardClearScheduler {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun schedule(
        delayMillis: Long,
        action: () -> Unit,
    ) {
        mainHandler.postDelayed({ action() }, delayMillis)
    }
}

internal const val PRIVATE_SENSITIVE_CLIPBOARD_CLEAR_DELAY_MILLIS = 60_000L
private const val PRIVATE_MINIMUM_ANDROID_SDK = 28
private const val PRIVATE_ANDROID_SENSITIVE_CLIP_API = 33
private const val PRIVATE_INVITATION_CLIPBOARD_LABEL = "Synapse Private invitation code"
private const val PRIVATE_MESSAGE_CLIPBOARD_LABEL = "Synapse Private message"
