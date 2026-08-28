package app.synapse.privatechat.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import app.synapse.privatechat.domain.account.PrivateInvitationCode
import app.synapse.privatechat.domain.chat.PrivateRoomInvitationCode

internal class PrivateInvitationTransferContent private constructor(
    private val invitationCode: String,
    private val purpose: PrivateInvitationPurpose,
) {
    fun exposeCodeForUserAction(): String = invitationCode

    fun buildShareTextForUserAction(): String =
        when (purpose) {
            PrivateInvitationPurpose.ACCOUNT_REGISTRATION ->
                "Join me on Synapse Private with this one-use account invitation code: $invitationCode"

            PrivateInvitationPurpose.CONVERSATION ->
                "Join my Synapse Private conversation with this one-use invitation code: $invitationCode"
        }

    override fun toString(): String = "PrivateInvitationTransferContent(purpose=$purpose, code=[REDACTED])"

    companion object {
        fun forAccount(invitationCode: PrivateInvitationCode): PrivateInvitationTransferContent =
            PrivateInvitationTransferContent(
                invitationCode = invitationCode.canonical,
                purpose = PrivateInvitationPurpose.ACCOUNT_REGISTRATION,
            )

        fun forConversation(invitationCode: PrivateRoomInvitationCode): PrivateInvitationTransferContent =
            PrivateInvitationTransferContent(
                invitationCode = invitationCode.secret,
                purpose = PrivateInvitationPurpose.CONVERSATION,
            )
    }
}

internal enum class PrivateInvitationCopyOutcome {
    COPIED,
    CLIPBOARD_UNAVAILABLE,
}

internal enum class PrivateInvitationShareOutcome {
    SHARE_SHEET_OPENED,
    SHARE_UNAVAILABLE,
}

internal fun copyPrivateInvitationCode(
    context: Context,
    transferContent: PrivateInvitationTransferContent,
): PrivateInvitationCopyOutcome {
    val clipboard =
        context.getSystemService(ClipboardManager::class.java)
            ?: return PrivateInvitationCopyOutcome.CLIPBOARD_UNAVAILABLE
    val primaryClip =
        ClipData.newPlainText(
            "Synapse Private invitation code",
            transferContent.exposeCodeForUserAction(),
        )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        primaryClip.description.extras =
            PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
    }
    clipboard.setPrimaryClip(primaryClip)
    return PrivateInvitationCopyOutcome.COPIED
}

internal fun sharePrivateInvitationCode(
    context: Context,
    transferContent: PrivateInvitationTransferContent,
): PrivateInvitationShareOutcome {
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transferContent.buildShareTextForUserAction())
        }
    return try {
        context.startActivity(Intent.createChooser(sendIntent, "Share Synapse Private invitation"))
        PrivateInvitationShareOutcome.SHARE_SHEET_OPENED
    } catch (_: ActivityNotFoundException) {
        PrivateInvitationShareOutcome.SHARE_UNAVAILABLE
    }
}

private enum class PrivateInvitationPurpose {
    ACCOUNT_REGISTRATION,
    CONVERSATION,
}
