package app.synapse.privatechat.ui.account

import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountAccessDraft
import app.synapse.privatechat.domain.account.PrivateAccountAccessMode
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateAccountSessionOutcome
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.domain.account.PrivateAccountSignOutOutcome
import app.synapse.privatechat.domain.account.PrivateDisplayName
import app.synapse.privatechat.domain.account.PrivateRemoteSessionRevocationStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateAccountAccessViewModelTest {
    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun localSignOutRoutesToSignInAndPreservesUnconfirmedRemoteRevocationNotice() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val gateway =
                StubPrivateAccountGateway(
                    signOutOutcome =
                        PrivateAccountSignOutOutcome.LocallySignedOut(
                            PrivateRemoteSessionRevocationStatus.TransportUnavailable,
                        ),
                )
            val viewModel = PrivateAccountAccessViewModel(gateway, TEST_CLOCK)
            runCurrent()

            viewModel.signOutPrivateAccount()
            runCurrent()

            val state = viewModel.uiState.value
            assertSame(PrivateAccountSessionUiState.SignedOut, state.session)
            assertSame(PrivateAccountAccessMode.SIGN_IN, state.mode)
            val signOut = state.signOut as PrivateAccountSignOutUiState.LocallySignedOut
            assertSame(PrivateRemoteSessionRevocationStatus.TransportUnavailable, signOut.remoteRevocation)
        }

    @Test
    fun failedLocalSignOutKeepsActiveSessionVisible() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel =
                PrivateAccountAccessViewModel(
                    StubPrivateAccountGateway(
                        signOutOutcome = PrivateAccountSignOutOutcome.LocalStateUnavailable,
                    ),
                    TEST_CLOCK,
                )
            runCurrent()

            viewModel.signOutPrivateAccount()
            runCurrent()

            assertEquals(PrivateAccountSessionUiState.Active(ACTIVE_RECEIPT), viewModel.uiState.value.session)
            assertSame(PrivateAccountSignOutUiState.LocalStateUnavailable, viewModel.uiState.value.signOut)
        }

    @Test
    fun signOutDisposesActiveChatBeforeLocalStateMutationAndRestoresItOnFailure() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val signOutCompletion = CompletableDeferred<PrivateAccountSignOutOutcome>()
            val events = mutableListOf<String>()
            val gateway =
                StubPrivateAccountGateway(
                    signOutOperation = {
                        events += "gatewaySignOut"
                        signOutCompletion.await()
                    },
                )
            val viewModel = PrivateAccountAccessViewModel(gateway, TEST_CLOCK)
            runCurrent()

            viewModel.signOutPrivateAccount {
                events += "deactivateChat"
                assertSame(PrivateAccountSessionUiState.SigningOut, viewModel.uiState.value.session)
            }

            assertSame(PrivateAccountSessionUiState.SigningOut, viewModel.uiState.value.session)
            assertSame(PrivateAccountSignOutUiState.SigningOut, viewModel.uiState.value.signOut)
            assertEquals(listOf("deactivateChat"), events)
            runCurrent()
            assertEquals(listOf("deactivateChat", "gatewaySignOut"), events)
            signOutCompletion.complete(PrivateAccountSignOutOutcome.LocalStateUnavailable)
            runCurrent()
            assertEquals(PrivateAccountSessionUiState.Active(ACTIVE_RECEIPT), viewModel.uiState.value.session)
            assertSame(PrivateAccountSignOutUiState.LocalStateUnavailable, viewModel.uiState.value.signOut)
        }

    @Test
    fun alreadySignedOutOutcomeRoutesToSignInWithExplicitNotice() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val viewModel =
                PrivateAccountAccessViewModel(
                    StubPrivateAccountGateway(
                        signOutOutcome = PrivateAccountSignOutOutcome.AlreadySignedOut,
                    ),
                    TEST_CLOCK,
                )
            runCurrent()

            viewModel.signOutPrivateAccount()
            runCurrent()

            assertSame(PrivateAccountSessionUiState.SignedOut, viewModel.uiState.value.session)
            assertSame(PrivateAccountSignOutUiState.AlreadySignedOut, viewModel.uiState.value.signOut)
        }

    @Test
    fun activeSessionRefreshesBeforeExpiryWhileAppRemainsForegrounded() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val gateway = StubPrivateAccountGateway()
            val viewModel = PrivateAccountAccessViewModel(gateway, Clock.fixed(INITIAL_NOW, ZoneOffset.UTC))
            runCurrent()

            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(1, gateway.refreshRequestCount)
            assertEquals(PrivateAccountSessionUiState.Active(REFRESHED_RECEIPT), viewModel.uiState.value.session)
        }

    @Test
    fun foregroundAndScheduledRefreshShareOneSerializedRequest() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val clock = MutableClock(INITIAL_NOW)
            val gateway = StubPrivateAccountGateway()
            val viewModel = PrivateAccountAccessViewModel(gateway, clock)
            runCurrent()

            clock.advanceSeconds(1L)
            advanceTimeBy(1_000L)
            viewModel.onAppForegrounded()
            viewModel.onAppForegrounded()
            runCurrent()

            assertEquals(1, gateway.refreshRequestCount)
            assertEquals(PrivateAccountSessionUiState.Active(REFRESHED_RECEIPT), viewModel.uiState.value.session)
        }

    @Test
    fun signOutCancelsScheduledRefresh() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val gateway = StubPrivateAccountGateway()
            val viewModel = PrivateAccountAccessViewModel(gateway, Clock.fixed(INITIAL_NOW, ZoneOffset.UTC))
            runCurrent()

            viewModel.signOutPrivateAccount()
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(0, gateway.refreshRequestCount)
            assertSame(PrivateAccountSessionUiState.SignedOut, viewModel.uiState.value.session)
        }

    @Test
    fun backgroundingCancelsInviteRegistrationAndReleasesItsSubmissionState() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val accessStarted = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            val gateway =
                StubPrivateAccountGateway(
                    restoreOutcome = PrivateAccountSessionOutcome.SignedOut,
                    accountAccessOperation = {
                        accessStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancellationObserved.complete(Unit)
                        }
                    },
                )
            val viewModel = PrivateAccountAccessViewModel(gateway, TEST_CLOCK)
            runCurrent()

            viewModel.submitAccountAccess(
                PrivateAccountAccessDraft.RegisterWithInvite(
                    displayNameInput = "Peter",
                    usernameInput = "peter_01",
                    passwordInput = VALID_PASSWORD,
                    passwordConfirmationInput = VALID_PASSWORD,
                    invitationCodeInput = "A".repeat(43),
                ),
            )
            runCurrent()
            accessStarted.await()

            viewModel.onAppBackgrounded()
            runCurrent()

            assertEquals(true, cancellationObserved.isCompleted)
            assertSame(PrivateAccountSubmissionState.Idle, viewModel.uiState.value.submission)
        }

    private class StubPrivateAccountGateway(
        private val signOutOutcome: PrivateAccountSignOutOutcome =
            PrivateAccountSignOutOutcome.LocallySignedOut(PrivateRemoteSessionRevocationStatus.Confirmed),
        private val restoreOutcome: PrivateAccountSessionOutcome = PrivateAccountSessionOutcome.Active(ACTIVE_RECEIPT),
        private val refreshOutcome: PrivateAccountSessionOutcome = PrivateAccountSessionOutcome.Active(REFRESHED_RECEIPT),
        private val signOutOperation: suspend () -> PrivateAccountSignOutOutcome = { signOutOutcome },
        private val accountAccessOperation: suspend (PrivateAccountAccessCommand) -> PrivateAccountAccessOutcome = {
            error("Account access is not part of this test")
        },
    ) : PrivateAccountGateway {
        var refreshRequestCount: Int = 0
            private set

        override suspend fun requestPrivateAccountAccess(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome =
            accountAccessOperation(command)

        override suspend fun restorePrivateAccountSession(): PrivateAccountSessionOutcome = restoreOutcome

        override suspend fun refreshPrivateAccountSession(): PrivateAccountSessionOutcome {
            refreshRequestCount += 1
            return refreshOutcome
        }

        override suspend fun signOutPrivateAccount(): PrivateAccountSignOutOutcome = signOutOperation()
    }

    private class MutableClock(
        private var currentInstant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(currentInstant, zone)

        override fun instant(): Instant = currentInstant

        fun advanceSeconds(seconds: Long) {
            currentInstant = currentInstant.plusSeconds(seconds)
        }
    }

    private companion object {
        val INITIAL_NOW: Instant = Instant.parse("2026-08-25T12:00:00Z")
        val ACTIVE_RECEIPT =
            PrivateAccountSessionReceipt.Active(
                accountId = PrivateAccountId("10000000-0000-4000-8000-000000000001"),
                displayName = PrivateDisplayName("Peter"),
                expiresAt = INITIAL_NOW.plusSeconds(61L),
            )
        val REFRESHED_RECEIPT =
            PrivateAccountSessionReceipt.Active(
                accountId = ACTIVE_RECEIPT.accountId,
                displayName = ACTIVE_RECEIPT.displayName,
                expiresAt = INITIAL_NOW.plusSeconds(3_600L),
            )
        const val VALID_PASSWORD = "correct horse battery staple"
        val TEST_CLOCK: Clock = Clock.fixed(INITIAL_NOW, ZoneOffset.UTC)
    }
}
