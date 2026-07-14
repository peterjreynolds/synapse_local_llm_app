package app.synapse.localllm.domain.remote

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

data class RemoteAuthenticatedAccount(
    val accountUid: RemoteAccountUid,
    val usernameNormalized: String,
    val role: RemoteAccountRole,
    val state: RemoteAccountState,
    val mustChangePassword: Boolean,
)

enum class RemoteAccountRole {
    OWNER,
    ADMIN,
    USER,
}

enum class RemoteAccountState {
    PENDING_APPROVAL,
    ACTIVE,
    REJECTED,
    DISABLED,
}

data class RemoteAccountClaims(
    val role: RemoteAccountRole,
    val state: RemoteAccountState,
    val mustChangePassword: Boolean,
)

sealed interface RemoteAuthenticationState {
    data object SignedOut : RemoteAuthenticationState

    data object Resolving : RemoteAuthenticationState

    data class InvalidSession(val userMessage: String) : RemoteAuthenticationState

    data class SignedIn(val account: RemoteAuthenticatedAccount) : RemoteAuthenticationState
}

data class RemoteSignInCommand(
    val username: String,
    val password: String,
)

data class RemoteInviteRegistrationCommand(
    val username: String,
    val displayName: String,
    val password: String,
    val invitationCode: String,
)

data class RemotePasswordChangeCommand(
    val currentPassword: String,
    val newPassword: String,
)

interface RemoteAuthenticationGateway {
    val authenticationState: StateFlow<RemoteAuthenticationState>

    suspend fun signIn(command: RemoteSignInCommand): RemoteAuthenticatedAccount

    suspend fun registerWithInvite(command: RemoteInviteRegistrationCommand): RemoteAuthenticatedAccount

    suspend fun refreshAccount(): RemoteAuthenticatedAccount

    suspend fun reauthenticate(password: String)

    suspend fun changePassword(command: RemotePasswordChangeCommand)

    suspend fun signOut()
}

class RemoteChatException(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

fun normalizeRemoteUsername(username: String): String {
    val normalized = Normalizer.normalize(username, Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.US)
    require(REMOTE_USERNAME_PATTERN.matches(normalized)) {
        "Username must contain 3-32 ASCII letters, digits, or underscores."
    }
    return normalized
}

fun buildRemoteSyntheticEmail(username: String): String =
    "${normalizeRemoteUsername(username)}$REMOTE_ACCOUNT_EMAIL_SUFFIX"

fun validateRemoteInviteRegistrationCommand(
    command: RemoteInviteRegistrationCommand,
): RemoteInviteRegistrationCommand {
    normalizeRemoteUsername(command.username)
    val displayName = Normalizer.normalize(command.displayName, Normalizer.Form.NFKC).trim()
    require(displayName.isNotEmpty() && displayName.length <= REMOTE_DISPLAY_NAME_LIMIT) {
        "Display name must contain 1-$REMOTE_DISPLAY_NAME_LIMIT characters."
    }
    require(displayName.none { character -> character.isISOControl() }) {
        "Display name contains unsupported characters."
    }
    require(command.password.length in REMOTE_PASSWORD_LENGTH_RANGE && '\u0000' !in command.password) {
        "Password must contain ${REMOTE_PASSWORD_LENGTH_RANGE.first}-${REMOTE_PASSWORD_LENGTH_RANGE.last} characters."
    }
    val invitationCode = command.invitationCode.trim()
    require(REMOTE_INVITATION_CODE_PATTERN.matches(invitationCode)) {
        "Invitation code is invalid."
    }
    return command.copy(
        username = normalizeRemoteUsername(command.username),
        displayName = displayName,
        invitationCode = invitationCode,
    )
}

fun parseRemoteAccountClaims(claims: Map<*, *>): RemoteAccountClaims? {
    val claimsVersion = (claims["claimsVersion"] as? Number)?.toLong()
    val role = (claims["role"] as? String)?.let { roleName ->
        RemoteAccountRole.entries.firstOrNull { role -> role.name == roleName }
    }
    val state = (claims["accountState"] as? String)?.let { stateName ->
        RemoteAccountState.entries.firstOrNull { state -> state.name == stateName }
    }
    val mustChangePassword = claims["mustChangePassword"] as? Boolean
    if (
        claimsVersion != REMOTE_ACCOUNT_CLAIMS_VERSION ||
        role == null ||
        state == null ||
        mustChangePassword == null
    ) {
        return null
    }
    return RemoteAccountClaims(role, state, mustChangePassword)
}

internal const val REMOTE_ACCOUNT_EMAIL_SUFFIX = "@accounts.synapse.invalid"
internal const val REMOTE_ACCOUNT_CLAIMS_VERSION = 1L
private const val REMOTE_DISPLAY_NAME_LIMIT = 64
private val REMOTE_PASSWORD_LENGTH_RANGE = 12..128
private val REMOTE_USERNAME_PATTERN = Regex("^[a-z][a-z0-9_]{2,31}$")
private val REMOTE_INVITATION_CODE_PATTERN = Regex("^[A-Za-z0-9_-]{32,128}$")
