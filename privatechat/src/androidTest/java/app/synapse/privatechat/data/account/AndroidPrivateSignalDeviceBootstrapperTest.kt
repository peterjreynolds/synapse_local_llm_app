package app.synapse.privatechat.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.crypto.storage.AndroidSignalProtocolStateRepositoryFactory
import app.synapse.privatechat.domain.account.PrivateAccountId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPrivateSignalDeviceBootstrapperTest {
    @Test
    fun generatesAndReloadsCompleteEncryptedDeviceBundle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val initialRepository = AndroidSignalProtocolStateRepositoryFactory.create(context)
        val initialOwner = SignalProtocolAdapterOwner(initialRepository)
        val initialBundle = PrivateSignalDeviceBootstrapper(initialOwner).preparePublicBundle(RESERVATION)

        assertEquals(UUID.fromString(RESERVATION.accountId.canonical), initialBundle.address.accountId)
        assertEquals(RESERVATION.transportDeviceId, initialBundle.address.transportDeviceId)
        assertEquals(RESERVATION.signalDeviceId, initialBundle.address.protocolDeviceId)
        assertTrue(initialBundle.identityKeyBytes.isNotEmpty())
        assertTrue(requireNotNull(initialBundle.oneTimePreKey).publicKeyBytes.isNotEmpty())
        assertTrue(initialBundle.signedPreKey.publicKeyBytes.isNotEmpty())
        assertTrue(initialBundle.signedPreKey.signatureBytes.isNotEmpty())
        assertTrue(initialBundle.kyberPreKey.publicKeyBytes.isNotEmpty())
        assertTrue(initialBundle.kyberPreKey.signatureBytes.isNotEmpty())

        val reloadedRepository = AndroidSignalProtocolStateRepositoryFactory.create(context)
        val reloadedIdentity = reloadedRepository.loadLocalIdentity()
        assertNotNull(reloadedIdentity)
        assertEquals(initialBundle.address, reloadedIdentity?.address)
        assertTrue(reloadedRepository.listSignedPreKeys().isNotEmpty())
        assertTrue(reloadedRepository.listKyberPreKeys().isNotEmpty())

        val rotatedBundle =
            PrivateSignalDeviceBootstrapper(
                SignalProtocolAdapterOwner(reloadedRepository),
            ).preparePublicBundle(RESERVATION)
        assertArrayEquals(initialBundle.identityKeyBytes, rotatedBundle.identityKeyBytes)
        assertEquals(initialBundle.registrationId, rotatedBundle.registrationId)
        assertNotEquals(initialBundle.signedPreKey.id, rotatedBundle.signedPreKey.id)
        assertNotEquals(initialBundle.kyberPreKey.id, rotatedBundle.kyberPreKey.id)
    }

    private companion object {
        val RESERVATION =
            PrivateDeviceRegistrationReservation(
                accountId = PrivateAccountId("10000000-0000-4000-8000-000000000001"),
                transportDeviceId = UUID.fromString("20000000-0000-4000-8000-000000000002"),
                signalDeviceId = SignalDeviceId.fromWire(7),
                expiresAt = Instant.parse("2026-08-30T07:00:00Z"),
            )
    }
}
