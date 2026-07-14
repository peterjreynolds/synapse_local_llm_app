package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.REMOTE_ACCOUNT_EMAIL_SUFFIX
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAuthenticatedAccount
import app.synapse.localllm.domain.remote.RemoteAuthenticationGateway
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteInviteRegistrationCommand
import app.synapse.localllm.domain.remote.RemotePasswordChangeCommand
import app.synapse.localllm.domain.remote.RemoteSignInCommand
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.buildRemoteSyntheticEmail
import app.synapse.localllm.domain.remote.normalizeRemoteUsername
import app.synapse.localllm.domain.remote.parseRemoteAccountClaims
import app.synapse.localllm.domain.remote.validateRemoteInviteRegistrationCommand
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FirebaseRemoteAuthenticationGateway(
    private val firebaseAuth: FirebaseAuth,
    private val firebaseFunctions: FirebaseFunctions,
    private val applicationScope: CoroutineScope,
) : RemoteAuthenticationGateway {
    private val mutableAuthenticationState = MutableStateFlow(
        firebaseAuth.currentUser?.let { RemoteAuthenticationState.Resolving }
            ?: RemoteAuthenticationState.SignedOut,
    )
    private var accountResolutionJob: Job? = null

    override val authenticationState: StateFlow<RemoteAuthenticationState> =
        mutableAuthenticationState.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        resolveAuthenticationState(auth.currentUser)
    }

    init {
        firebaseAuth.addAuthStateListener(authStateListener)
    }

    override suspend fun signIn(command: RemoteSignInCommand): RemoteAuthenticatedAccount {
        require(command.password.isNotEmpty()) { "Password cannot be empty." }
        val email = buildRemoteSyntheticEmail(command.username)
        return try {
            val authResult = firebaseAuth.signInWithEmailAndPassword(email, command.password).await()
            val account = resolveAccount(authResult.user, forceRefresh = true) ?: throw RemoteChatException(
                "Firebase returned an unusable account session.",
            )
            mutableAuthenticationState.value = RemoteAuthenticationState.SignedIn(account)
            account
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteAuthenticationFailure("sign in")
        }
    }

    override suspend fun registerWithInvite(
        command: RemoteInviteRegistrationCommand,
    ): RemoteAuthenticatedAccount {
        val validated = validateRemoteInviteRegistrationCommand(command)
        try {
            val result = firebaseFunctions.getHttpsCallable("registerWithInvite")
                .call(
                    mapOf(
                        "displayName" to validated.displayName,
                        "invitationCode" to validated.invitationCode,
                        "password" to validated.password,
                        "username" to validated.username,
                    ),
                ).await()
            val receipt = result.data as? Map<*, *>
                ?: throw RemoteChatException(REGISTRATION_FAILURE_MESSAGE)
            if (
                receipt["usernameNormalized"] != validated.username ||
                receipt["accountState"] !in REMOTE_ACCOUNT_STATE_NAMES
            ) {
                throw RemoteChatException(REGISTRATION_FAILURE_MESSAGE)
            }
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteRegistrationFailure()
        }
        return signIn(RemoteSignInCommand(validated.username, validated.password))
    }

    override suspend fun refreshAccount(): RemoteAuthenticatedAccount {
        val user = firebaseAuth.currentUser
            ?: throw RemoteChatException("Sign in before refreshing account access.")
        mutableAuthenticationState.value = RemoteAuthenticationState.Resolving
        return try {
            val account = resolveAccount(user, forceRefresh = true)
                ?: throw RemoteChatException("The account session is invalid. Sign out and try again.")
            mutableAuthenticationState.value = RemoteAuthenticationState.SignedIn(account)
            account
        } catch (exception: RemoteChatException) {
            mutableAuthenticationState.value = RemoteAuthenticationState.InvalidSession(exception.userMessage)
            throw exception
        } catch (exception: Exception) {
            val failure = exception.toRemoteAuthenticationFailure("refresh account access")
            mutableAuthenticationState.value = RemoteAuthenticationState.InvalidSession(failure.userMessage)
            throw failure
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

    private fun resolveAuthenticationState(user: FirebaseUser?) {
        accountResolutionJob?.cancel()
        if (user == null) {
            mutableAuthenticationState.value = RemoteAuthenticationState.SignedOut
            return
        }
        mutableAuthenticationState.value = RemoteAuthenticationState.Resolving
        accountResolutionJob = applicationScope.launch {
            mutableAuthenticationState.value = try {
                resolveAccount(user, forceRefresh = false)
                    ?.let(RemoteAuthenticationState::SignedIn)
                    ?: RemoteAuthenticationState.InvalidSession(
                        "The account session is invalid. Sign out and try again.",
                    )
            } catch (_: Exception) {
                RemoteAuthenticationState.InvalidSession(
                    "Could not verify account access. Check the connection and try again.",
                )
            }
        }
    }

    private suspend fun resolveAccount(
        user: FirebaseUser?,
        forceRefresh: Boolean,
    ): RemoteAuthenticatedAccount? {
        val uid = user?.uid?.takeIf(String::isNotBlank) ?: return null
        val email = user.email ?: return null
        if (!email.endsWith(REMOTE_ACCOUNT_EMAIL_SUFFIX)) return null
        val usernameNormalized = email.removeSuffix(REMOTE_ACCOUNT_EMAIL_SUFFIX)
        if (runCatching { normalizeRemoteUsername(usernameNormalized) }.getOrNull() != usernameNormalized) {
            return null
        }
        val claims = user.getIdToken(forceRefresh).await().claims
        val accountClaims = parseRemoteAccountClaims(claims) ?: return null
        return RemoteAuthenticatedAccount(
            accountUid = RemoteAccountUid(uid),
            usernameNormalized = usernameNormalized,
            role = accountClaims.role,
            state = accountClaims.state,
            mustChangePassword = accountClaims.mustChangePassword,
        )
    }

    private fun Exception.toRemoteRegistrationFailure(): RemoteChatException {
        val functionsCode = (this as? FirebaseFunctionsException)?.code
        val message = when (functionsCode) {
            FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                "Check the registration details and try again."
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ->
                "Too many registration attempts. Try again later."
            else -> REGISTRATION_FAILURE_MESSAGE
        }
        return RemoteChatException(message, this)
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
        const val MINIMUM_REMOTE_PASSWORD_LENGTH = 12
        const val REGISTRATION_FAILURE_MESSAGE =
            "Registration could not be completed. Check the invitation and account details."
        val REMOTE_ACCOUNT_STATE_NAMES = RemoteAccountState.entries.mapTo(mutableSetOf()) { it.name }
    }
}
