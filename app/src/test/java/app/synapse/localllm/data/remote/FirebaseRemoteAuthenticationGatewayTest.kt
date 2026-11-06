package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseRemoteAuthenticationGatewayTest {
    @Test
    fun signingOutDuringRestoredTokenFetchDoesNotPublishInvalidSession() = runTest {
        val token = TaskCompletionSource<GetTokenResult>()
        val user = mockk<FirebaseUser> {
            every { uid } returns "test-uid"
            every { email } returns "test_user@accounts.synapse.invalid"
            every { getIdToken(false) } returns token.task
        }
        val listener = slot<FirebaseAuth.AuthStateListener>()
        val auth = mockk<FirebaseAuth> {
            every { currentUser } returns user
            every { addAuthStateListener(capture(listener)) } just Runs
        }
        val gateway = FirebaseRemoteAuthenticationGateway(auth, mockk(), backgroundScope)
        listener.captured.onAuthStateChanged(auth)
        runCurrent()
        assertEquals(RemoteAuthenticationState.Resolving, gateway.authenticationState.value)

        every { auth.currentUser } returns null
        listener.captured.onAuthStateChanged(auth)
        runCurrent()

        assertEquals(RemoteAuthenticationState.SignedOut, gateway.authenticationState.value)
    }

    @Test
    fun tokenFailureRemainsClosedAndExplicitRefreshCanRecover() = runTest {
        val token = TaskCompletionSource<GetTokenResult>()
        val user = mockk<FirebaseUser> {
            every { uid } returns "test-uid"
            every { email } returns "test_user@accounts.synapse.invalid"
            every { getIdToken(false) } returns token.task
        }
        val listener = slot<FirebaseAuth.AuthStateListener>()
        val auth = mockk<FirebaseAuth> {
            every { currentUser } returns user
            every { addAuthStateListener(capture(listener)) } just Runs
        }
        val gateway = FirebaseRemoteAuthenticationGateway(auth, mockk(), backgroundScope)
        listener.captured.onAuthStateChanged(auth)
        runCurrent()
        token.setException(IOException("test transport failure"))
        runCurrent()
        assertTrue(gateway.authenticationState.value is RemoteAuthenticationState.InvalidSession)

        val refreshedToken = TaskCompletionSource<GetTokenResult>()
        every { user.getIdToken(true) } returns refreshedToken.task
        val claims = mockk<GetTokenResult> {
            every { claims } returns mapOf(
                "claimsVersion" to 1L,
                "role" to "USER",
                "accountState" to "ACTIVE",
                "mustChangePassword" to false,
            )
        }
        refreshedToken.setResult(claims)
        val account = gateway.refreshAccount()
        assertEquals(RemoteAuthenticationState.SignedIn(account), gateway.authenticationState.value)
        assertEquals("test-uid", account.accountUid.raw)
    }

    @Test
    fun missingClaimsNeverGrantRestoredAccountAccess() = runTest {
        val token = TaskCompletionSource<GetTokenResult>()
        val user = mockk<FirebaseUser> {
            every { uid } returns "test-uid"
            every { email } returns "test_user@accounts.synapse.invalid"
            every { getIdToken(false) } returns token.task
        }
        val listener = slot<FirebaseAuth.AuthStateListener>()
        val auth = mockk<FirebaseAuth> {
            every { currentUser } returns user
            every { addAuthStateListener(capture(listener)) } just Runs
        }
        val gateway = FirebaseRemoteAuthenticationGateway(auth, mockk(), backgroundScope)
        listener.captured.onAuthStateChanged(auth)
        runCurrent()
        token.setResult(mockk { every { claims } returns emptyMap() })
        runCurrent()
        assertTrue(gateway.authenticationState.value is RemoteAuthenticationState.InvalidSession)
    }

    @Test
    fun cancelledRefreshPropagatesCancellationWithoutPublishingAccountFailure() = runTest {
        val token = TaskCompletionSource<GetTokenResult>()
        val user = mockk<FirebaseUser> {
            every { uid } returns "test-uid"
            every { email } returns "test_user@accounts.synapse.invalid"
            every { getIdToken(true) } returns token.task
        }
        val auth = mockk<FirebaseAuth> {
            every { currentUser } returns user
            every { addAuthStateListener(any()) } just Runs
        }
        val gateway = FirebaseRemoteAuthenticationGateway(auth, mockk(), backgroundScope)
        var cancellationPropagated = false
        val refresh = launch {
            try {
                gateway.refreshAccount()
                fail("Pending refresh must not succeed")
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }
        }
        runCurrent()
        refresh.cancel()
        runCurrent()
        assertTrue(cancellationPropagated)
        assertEquals(RemoteAuthenticationState.Resolving, gateway.authenticationState.value)
        coVerify(exactly = 0) { auth.signOut() }
    }
}
