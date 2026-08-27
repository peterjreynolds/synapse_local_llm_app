package app.synapse.privatechat.domain.account

import java.text.Normalizer
import java.time.Instant
import java.util.Locale

enum class PrivateAccountAccessMode {
    SIGN_IN,
    REGISTER_WITH_INVITE,
}

sealed interface PrivateAccountAccessDraft {
    data class SignIn(
        val usernameInput: String,
        val passwordInput: String,
    ) : PrivateAccountAccessDraft {
        override fun toString(): String = "SignIn(usernameInput=$usernameInput, passwordInput=[REDACTED])"
    }

    data class RegisterWithInvite(
        val displayNameInput: String,
        val usernameInput: String,
        val passwordInput: String,
        val passwordConfirmationInput: String,
        val invitationCodeInput: String,
    ) : PrivateAccountAccessDraft {
        override fun toString(): String =
            "RegisterWithInvite(" +
                "displayNameInput=$displayNameInput, " +
                "usernameInput=$usernameInput, " +
                "passwordInput=[REDACTED], " +
                "passwordConfirmationInput=[REDACTED], " +
                "invitationCodeInput=[REDACTED])"
    }
}

enum class PrivateAccountInputField {
    DISPLAY_NAME,
    USERNAME,
    PASSWORD,
    PASSWORD_CONFIRMATION,
    INVITATION_CODE,
}

sealed interface PrivateAccountAccessValidation {
    data class Accepted(
        val command: PrivateAccountAccessCommand,
    ) : PrivateAccountAccessValidation

    data class Rejected(
        val field: PrivateAccountInputField,
        val userMessage: String,
    ) : PrivateAccountAccessValidation
}

sealed interface PrivateAccountAccessCommand {
    val username: PrivateUsername
    val password: PrivateAccountPassword

    data class SignIn(
        override val username: PrivateUsername,
        override val password: PrivateAccountPassword,
    ) : PrivateAccountAccessCommand

    data class RegisterWithInvite(
        val displayName: PrivateDisplayName,
        override val username: PrivateUsername,
        override val password: PrivateAccountPassword,
        val invitationCode: PrivateInvitationCode,
    ) : PrivateAccountAccessCommand
}

@JvmInline
value class PrivateAccountId internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateUsername internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateDisplayName internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateInvitationCode internal constructor(
    val canonical: String,
) {
    override fun toString(): String = "PrivateInvitationCode([REDACTED])"
}

class PrivateAccountPassword internal constructor(
    private val characters: String,
) {
    internal fun exposeForAuthentication(): String = characters

    override fun toString(): String = "PrivateAccountPassword([REDACTED])"
}

sealed interface PrivateAccountSessionReceipt {
    val accountId: PrivateAccountId
    val displayName: PrivateDisplayName

    data class Active(
        override val accountId: PrivateAccountId,
        override val displayName: PrivateDisplayName,
        val expiresAt: Instant,
    ) : PrivateAccountSessionReceipt

    data class AwaitingApproval(
        override val accountId: PrivateAccountId,
        override val displayName: PrivateDisplayName,
    ) : PrivateAccountSessionReceipt
}

sealed interface PrivateAccountAccessOutcome {
    data class Confirmed(
        val receipt: PrivateAccountSessionReceipt,
    ) : PrivateAccountAccessOutcome

    data class Denied(
        val userMessage: String,
    ) : PrivateAccountAccessOutcome {
        init {
            requireSafePrivateAccountMessage(userMessage, "Account denial")
        }
    }

    data object TransportUnavailable : PrivateAccountAccessOutcome

    data object LocalStateUnavailable : PrivateAccountAccessOutcome

    data object VerificationFailed : PrivateAccountAccessOutcome
}

sealed interface PrivateAccountSessionOutcome {
    data class Active(
        val receipt: PrivateAccountSessionReceipt.Active,
    ) : PrivateAccountSessionOutcome

    data object SignedOut : PrivateAccountSessionOutcome

    data class VerificationRejected(
        val userMessage: String,
    ) : PrivateAccountSessionOutcome {
        init {
            requireSafePrivateAccountMessage(userMessage, "Session rejection")
        }
    }

    data object TransportUnavailable : PrivateAccountSessionOutcome

    data object LocalStateUnavailable : PrivateAccountSessionOutcome

    data object VerificationFailed : PrivateAccountSessionOutcome
}

sealed interface PrivateAccountSignOutOutcome {
    data class LocallySignedOut(
        val remoteRevocation: PrivateRemoteSessionRevocationStatus,
    ) : PrivateAccountSignOutOutcome

    data object AlreadySignedOut : PrivateAccountSignOutOutcome

    data class Rejected(
        val userMessage: String,
    ) : PrivateAccountSignOutOutcome {
        init {
            requireSafePrivateAccountMessage(userMessage, "Sign-out rejection")
        }
    }

    data object TransportUnavailable : PrivateAccountSignOutOutcome

    data object LocalStateUnavailable : PrivateAccountSignOutOutcome

    data object VerificationFailed : PrivateAccountSignOutOutcome
}

sealed interface PrivateRemoteSessionRevocationStatus {
    data object Confirmed : PrivateRemoteSessionRevocationStatus

    data object AlreadyInactive : PrivateRemoteSessionRevocationStatus

    data object TransportUnavailable : PrivateRemoteSessionRevocationStatus

    data class Rejected(
        val userMessage: String,
    ) : PrivateRemoteSessionRevocationStatus {
        init {
            requireSafePrivateAccountMessage(userMessage, "Remote session revocation rejection")
        }
    }

    data object VerificationFailed : PrivateRemoteSessionRevocationStatus
}

interface PrivateAccountGateway {
    suspend fun requestPrivateAccountAccess(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome

    suspend fun restorePrivateAccountSession(): PrivateAccountSessionOutcome

    suspend fun refreshPrivateAccountSession(): PrivateAccountSessionOutcome

    suspend fun signOutPrivateAccount(): PrivateAccountSignOutOutcome
}

fun validatePrivateAccountAccessDraft(draft: PrivateAccountAccessDraft): PrivateAccountAccessValidation {
    val username =
        normalizePrivateUsername(
            when (draft) {
                is PrivateAccountAccessDraft.SignIn -> draft.usernameInput
                is PrivateAccountAccessDraft.RegisterWithInvite -> draft.usernameInput
            },
        ) ?: return PrivateAccountAccessValidation.Rejected(
            field = PrivateAccountInputField.USERNAME,
            userMessage = "Use 3–32 lowercase letters, numbers, or underscores, starting with a letter.",
        )

    val passwordInput =
        when (draft) {
            is PrivateAccountAccessDraft.SignIn -> draft.passwordInput
            is PrivateAccountAccessDraft.RegisterWithInvite -> draft.passwordInput
        }
    if (!isPrivateAccountPasswordShapeValid(passwordInput)) {
        return PrivateAccountAccessValidation.Rejected(
            field = PrivateAccountInputField.PASSWORD,
            userMessage =
                "Use ${PRIVATE_PASSWORD_LENGTH_RANGE.first}–${PRIVATE_PASSWORD_LENGTH_RANGE.last} " +
                    "password characters without control characters.",
        )
    }
    val password = PrivateAccountPassword(passwordInput)

    return when (draft) {
        is PrivateAccountAccessDraft.SignIn ->
            PrivateAccountAccessValidation.Accepted(
                PrivateAccountAccessCommand.SignIn(
                    username = username,
                    password = password,
                ),
            )

        is PrivateAccountAccessDraft.RegisterWithInvite ->
            validateInviteRegistrationDraft(
                draft = draft,
                username = username,
                password = password,
            )
    }
}

private fun validateInviteRegistrationDraft(
    draft: PrivateAccountAccessDraft.RegisterWithInvite,
    username: PrivateUsername,
    password: PrivateAccountPassword,
): PrivateAccountAccessValidation {
    val normalizedDisplayName = Normalizer.normalize(draft.displayNameInput, Normalizer.Form.NFKC).trim()
    if (
        normalizedDisplayName.isEmpty() ||
        normalizedDisplayName.length > PRIVATE_DISPLAY_NAME_LIMIT ||
        normalizedDisplayName.any(Char::isISOControl)
    ) {
        return PrivateAccountAccessValidation.Rejected(
            field = PrivateAccountInputField.DISPLAY_NAME,
            userMessage = "Enter a display name with 1–$PRIVATE_DISPLAY_NAME_LIMIT supported characters.",
        )
    }
    if (draft.passwordInput != draft.passwordConfirmationInput) {
        return PrivateAccountAccessValidation.Rejected(
            field = PrivateAccountInputField.PASSWORD_CONFIRMATION,
            userMessage = "The passwords do not match.",
        )
    }
    val normalizedInvitationCode = draft.invitationCodeInput.trim()
    if (!PRIVATE_INVITATION_CODE_PATTERN.matches(normalizedInvitationCode)) {
        return PrivateAccountAccessValidation.Rejected(
            field = PrivateAccountInputField.INVITATION_CODE,
            userMessage = "Enter the complete invitation code you received.",
        )
    }
    return PrivateAccountAccessValidation.Accepted(
        PrivateAccountAccessCommand.RegisterWithInvite(
            displayName = PrivateDisplayName(normalizedDisplayName),
            username = username,
            password = password,
            invitationCode = PrivateInvitationCode(normalizedInvitationCode),
        ),
    )
}

private fun normalizePrivateUsername(usernameInput: String): PrivateUsername? {
    val normalizedUsername =
        Normalizer
            .normalize(usernameInput, Normalizer.Form.NFKC)
            .trim()
            .lowercase(Locale.US)
    return normalizedUsername
        .takeIf(PRIVATE_USERNAME_PATTERN::matches)
        ?.let(::PrivateUsername)
}

private fun isPrivateAccountPasswordShapeValid(passwordInput: String): Boolean {
    var utf16Index = 0
    var codePointCount = 0
    var utf8ByteCount = 0
    while (utf16Index < passwordInput.length) {
        val codePoint = Character.codePointAt(passwordInput, utf16Index)
        if (codePoint <= 0x1F || codePoint == 0x7F) return false
        codePointCount += 1
        utf8ByteCount +=
            when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
        if (
            codePointCount > PRIVATE_PASSWORD_LENGTH_RANGE.last ||
            utf8ByteCount > PRIVATE_PASSWORD_LENGTH_RANGE.last
        ) {
            return false
        }
        utf16Index += Character.charCount(codePoint)
    }
    return codePointCount >= PRIVATE_PASSWORD_LENGTH_RANGE.first &&
        utf8ByteCount >= PRIVATE_PASSWORD_LENGTH_RANGE.first
}

private const val PRIVATE_DISPLAY_NAME_LIMIT = 64
private val PRIVATE_PASSWORD_LENGTH_RANGE = 12..128
private val PRIVATE_USERNAME_PATTERN = Regex("^[a-z][a-z0-9_]{2,31}$")
private val PRIVATE_INVITATION_CODE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
private const val PRIVATE_ACCOUNT_DENIAL_MESSAGE_LIMIT = 200

private fun requireSafePrivateAccountMessage(
    userMessage: String,
    messageOwner: String,
) {
    require(
        userMessage.isNotBlank() &&
            userMessage.length <= PRIVATE_ACCOUNT_DENIAL_MESSAGE_LIMIT &&
            userMessage.none(Char::isISOControl),
    ) {
        "$messageOwner requires a bounded user-facing reason."
    }
}
