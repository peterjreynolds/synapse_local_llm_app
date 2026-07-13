package app.synapse.localllm.domain.remote

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

data class RemoteAuthenticatedAccount(
    val accountUid: RemoteAccountUid,
    val usernameNormalized: String,
)

sealed interface RemoteAuthenticationState {
    data object SignedOut : RemoteAuthenticationState

    data class SignedIn(val account: RemoteAuthenticatedAccount) : RemoteAuthenticationState
}

data class RemoteSignInCommand(
    val username: String,
    val password: String,
)

data class RemotePasswordChangeCommand(
    val currentPassword: String,
    val newPassword: String,
)

interface RemoteAuthenticationGateway {
    val authenticationState: StateFlow<RemoteAuthenticationState>

    suspend fun signIn(command: RemoteSignInCommand): RemoteAuthenticatedAccount

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

internal const val REMOTE_ACCOUNT_EMAIL_SUFFIX = "@accounts.synapse.invalid"
private val REMOTE_USERNAME_PATTERN = Regex("^[a-z][a-z0-9_]{2,31}$")
