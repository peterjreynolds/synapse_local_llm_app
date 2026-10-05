package app.synapse.privatechat.data.connection

import app.synapse.privatechat.data.chat.PendingTransportPrivateChatGateway
import app.synapse.privatechat.data.diagnostics.PrivateConnectionDiagnostics
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateAccountSessionOutcome
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.domain.account.PrivateAccountSignOutOutcome
import app.synapse.privatechat.domain.account.PrivateDisplayName
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivatePeopleGateway
import app.synapse.privatechat.domain.chat.UnavailablePrivatePeopleGateway
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateBackgroundConnectionTest {
    @Test
    fun backgroundHeartbeatRequiresExplicitServiceAndYieldsToForegroundOwner() =
        runTest {
            val accounts = Accounts()
            val people = People()
            val connection =
                PrivateBackgroundConnection(
                    accounts,
                    people,
                    PendingTransportPrivateChatGateway,
                    backgroundScope,
                    PrivateConnectionDiagnostics(),
                )
            connection.activateAccount(ACTOR)
            advanceTimeBy(60_000)
            assertEquals(0, people.publications)
            connection.setEnabled(true)
            runCurrent()
            assertEquals(1, people.publications)
            advanceTimeBy(25_001)
            assertEquals(2, people.publications)
            connection.setForeground(true)
            advanceTimeBy(60_000)
            assertEquals(2, people.publications)
            connection.setForeground(false)
            runCurrent()
            assertEquals(3, people.publications)
            connection.setEnabled(false)
            advanceTimeBy(60_000)
            assertEquals(3, people.publications)
        }

    @Test
    fun sessionTransportFailureRetriesButRevocationStopsPublicationAndService() =
        runTest {
            val accounts = Accounts()
            val people = People()
            val connection =
                PrivateBackgroundConnection(
                    accounts,
                    people,
                    PendingTransportPrivateChatGateway,
                    backgroundScope,
                    PrivateConnectionDiagnostics(),
                )
            connection.activateAccount(ACTOR)
            accounts.outcome = PrivateAccountSessionOutcome.TransportUnavailable
            connection.setEnabled(true)
            runCurrent()
            assertEquals(0, people.publications)
            accounts.outcome = PrivateAccountSessionOutcome.Active(RECEIPT)
            advanceTimeBy(25_001)
            assertEquals(1, people.publications)
            accounts.outcome = PrivateAccountSessionOutcome.SignedOut
            advanceTimeBy(25_000)
            assertFalse(connection.enabled.value)
            advanceTimeBy(90_000)
            assertEquals(1, people.publications)
        }

    private class People : PrivatePeopleGateway by UnavailablePrivatePeopleGateway {
        var publications = 0

        override suspend fun publishActivity(accountId: PrivateAccountId): PrivateChatMutationOutcome<Instant> {
            publications++
            return PrivateChatMutationOutcome.Confirmed(Instant.now().plusSeconds(60))
        }
    }

    private class Accounts : PrivateAccountGateway {
        var outcome: PrivateAccountSessionOutcome = PrivateAccountSessionOutcome.Active(RECEIPT)

        override suspend fun restorePrivateAccountSession() = outcome

        override suspend fun refreshPrivateAccountSession() = outcome

        override suspend fun signOutPrivateAccount() = PrivateAccountSignOutOutcome.AlreadySignedOut

        override suspend fun requestPrivateAccountAccess(command: PrivateAccountAccessCommand) =
            PrivateAccountAccessOutcome.TransportUnavailable
    }

    private companion object {
        val ACTOR = PrivateAccountId("81000000-0000-4000-8000-000000000001")
        val RECEIPT = PrivateAccountSessionReceipt.Active(ACTOR, PrivateDisplayName("Person"), Instant.parse("2026-10-05T12:00:00Z"))
    }
}
