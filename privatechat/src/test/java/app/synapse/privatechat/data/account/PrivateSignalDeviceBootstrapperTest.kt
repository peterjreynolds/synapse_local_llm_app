package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.InMemorySignalProtocolStateRepository
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.domain.account.PrivateAccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateSignalDeviceBootstrapperTest {
    @Test
    fun initializesOnceThenRotatesOnlyPublicPreKeysForTheSameReservation() {
        val repository = InMemorySignalProtocolStateRepository()
        val adapterOwner = SignalProtocolAdapterOwner(repository)
        val bootstrapper = PrivateSignalDeviceBootstrapper(adapterOwner)

        val initial = bootstrapper.preparePublicBundle(RESERVATION)
        val refreshed = bootstrapper.preparePublicBundle(RESERVATION)

        assertEquals(initial.address, refreshed.address)
        assertEquals(initial.identityKeyBytes.toList(), refreshed.identityKeyBytes.toList())
        assertEquals(initial.registrationId, refreshed.registrationId)
        assertNotEquals(initial.signedPreKey.id, refreshed.signedPreKey.id)
        assertNotEquals(initial.kyberPreKey.id, refreshed.kyberPreKey.id)
        assertSame(
            adapterOwner.adapterFor(initial.address),
            adapterOwner.requireAdapterForStoredIdentity(),
        )
    }

    @Test
    fun failsClosedBeforeMutationWhenServerReservationChangesAccountOrDevice() {
        val repository = InMemorySignalProtocolStateRepository()
        val bootstrapper = PrivateSignalDeviceBootstrapper(SignalProtocolAdapterOwner(repository))
        bootstrapper.preparePublicBundle(RESERVATION)
        val originalIdentity = requireNotNull(repository.loadLocalIdentity())
        val conflictingReservation =
            RESERVATION.copy(
                accountId = PrivateAccountId("30000000-0000-4000-8000-000000000003"),
            )

        val error =
            assertThrows(PrivateDeviceIdentityConflictException::class.java) {
                bootstrapper.preparePublicBundle(conflictingReservation)
            }

        assertEquals(originalIdentity.address.accountId, error.existingAccountId)
        assertEquals(
            "30000000-0000-4000-8000-000000000003",
            error.requestedAccountId.toString(),
        )
        assertEquals(originalIdentity.address, repository.loadLocalIdentity()?.address)
    }

    private companion object {
        val RESERVATION =
            PrivateDeviceRegistrationReservation(
                accountId = PrivateAccountId("10000000-0000-4000-8000-000000000001"),
                transportDeviceId = UUID.fromString("20000000-0000-4000-8000-000000000002"),
                signalDeviceId = SignalDeviceId.fromWire(7),
                expiresAt = Instant.parse("2026-08-22T13:00:00Z"),
            )
    }
}
