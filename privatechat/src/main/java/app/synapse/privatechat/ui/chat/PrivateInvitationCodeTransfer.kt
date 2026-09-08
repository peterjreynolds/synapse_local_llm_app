package app.synapse.privatechat.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
    val clipboardOwner =
        privateSensitiveClipboardOwner(context)
            ?: return PrivateInvitationCopyOutcome.CLIPBOARD_UNAVAILABLE
    return when (clipboardOwner.copyInvitationCode(transferContent.exposeCodeForUserAction())) {
        PrivateSensitiveClipboardCopyOutcome.COPIED -> PrivateInvitationCopyOutcome.COPIED
        PrivateSensitiveClipboardCopyOutcome.CLIPBOARD_UNAVAILABLE ->
            PrivateInvitationCopyOutcome.CLIPBOARD_UNAVAILABLE
    }
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
