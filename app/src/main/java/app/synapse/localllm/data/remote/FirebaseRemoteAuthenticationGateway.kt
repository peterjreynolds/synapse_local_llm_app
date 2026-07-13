package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.REMOTE_ACCOUNT_EMAIL_SUFFIX
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemotePasswordChangeCommand
import app.synapse.localllm.domain.remote.RemoteSignInCommand
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.buildRemoteSyntheticEmail
import app.synapse.localllm.domain.remote.normalizeRemoteUsername
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

class FirebaseRemoteAuthenticationGateway(
    private val firebaseAuth: FirebaseAuth,
) : RemoteAuthenticationGateway {
    private val mutableAuthenticationState = MutableStateFlow(resolveAuthenticationState(firebaseAuth.currentUser))

    override val authenticationState: StateFlow<RemoteAuthenticationState> =
        mutableAuthenticationState.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        mutableAuthenticationState.value = resolveAuthenticationState(auth.currentUser)
    }

    init {
        firebaseAuth.addAuthStateListener(authStateListener)
    }

    override suspend fun signIn(command: RemoteSignInCommand): RemoteAuthenticatedAccount {
        require(command.password.isNotEmpty()) { "Password cannot be empty." }
        val email = buildRemoteSyntheticEmail(command.username)
        return try {
            val authResult = firebaseAuth.signInWithEmailAndPassword(email, command.password).await()
            resolveAccount(authResult.user) ?: throw RemoteChatException(
                "Firebase returned an unusable account session.",
            )
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteAuthenticationFailure("sign in")
        }
    }

    override suspend fun changePassword(command: RemotePasswordChangeCommand) {
        require(command.currentPassword.isNotEmpty()) { "Current password cannot be empty." }
        require(command.newPassword.length >= MINIMUM_REMOTE_PASSWORD_LENGTH) {
            "New password must contain at least $MINIMUM_REMOTE_PASSWORD_LENGTH characters."
        }
        val currentUser = firebaseAuth.currentUser
            ?: throw RemoteChatException("Sign in before changing the password.")
        val email = currentUser.email
            ?: throw RemoteChatException("The signed-in account has no password identity.")
        try {
            currentUser.reauthenticate(
                EmailAuthProvider.getCredential(email, command.currentPassword),
            ).await()
            currentUser.updatePassword(command.newPassword).await()
        } catch (exception: Exception) {
            throw exception.toRemoteAuthenticationFailure("change the password")
        }
    }

    override suspend fun signOut() {
        firebaseAuth.signOut()
    }

    private fun resolveAuthenticationState(user: FirebaseUser?): RemoteAuthenticationState =
        resolveAccount(user)
            ?.let(RemoteAuthenticationState::SignedIn)
            ?: RemoteAuthenticationState.SignedOut

    private fun resolveAccount(user: FirebaseUser?): RemoteAuthenticatedAccount? {
        val uid = user?.uid?.takeIf(String::isNotBlank) ?: return null
        val email = user.email ?: return null
        if (!email.endsWith(REMOTE_ACCOUNT_EMAIL_SUFFIX)) return null
        val usernameNormalized = email.removeSuffix(REMOTE_ACCOUNT_EMAIL_SUFFIX)
        if (runCatching { normalizeRemoteUsername(usernameNormalized) }.getOrNull() != usernameNormalized) {
            return null
        }
        return RemoteAuthenticatedAccount(
            accountUid = RemoteAccountUid(uid),
            usernameNormalized = usernameNormalized,
        )
    }

    private fun Exception.toRemoteAuthenticationFailure(action: String): RemoteChatException {
        val authErrorCode = (this as? FirebaseAuthException)?.errorCode
        val message = when (authErrorCode) {
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_USER_NOT_FOUND",
            "ERROR_WRONG_PASSWORD",
            -> "Username or password is incorrect."
            "ERROR_NETWORK_REQUEST_FAILED" -> "Network unavailable. Check the connection and try again."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait a moment and try again."
            "ERROR_USER_DISABLED" -> "This Synapse Chat account is disabled."
            "ERROR_REQUIRES_RECENT_LOGIN" -> "Sign in again before changing the password."
            "ERROR_WEAK_PASSWORD" -> "The new password does not meet Firebase password requirements."
            else -> "Could not $action. Try again."
        }
        return RemoteChatException(message, this)
    }

    private companion object {
        const val MINIMUM_REMOTE_PASSWORD_LENGTH = 8
    }
}
