package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.data.session.ConfirmedPrivateDeviceRegistration
import app.synapse.privatechat.data.session.EncryptedPrivateSessionRepository
import app.synapse.privatechat.data.session.PrivateInstallationId
import app.synapse.privatechat.data.session.RegisteredPrivateAccountSession
import app.synapse.privatechat.security.storage.CryptographicallyErasableEncryptedStateStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID

class StoredPrivateChatSessionProviderTest {
    @Test
    fun exposesOnlyTheRegisteredDeviceSessionFromTheSharedVault() {
        val repository = sessionRepository()
        val provider = StoredPrivateChatSessionProvider(repository)

        assertNull(provider.loadAuthenticatedSession())

        val installationId = repository.loadOrCreateInstallationId()
        val registration =
            ConfirmedPrivateDeviceRegistration.confirmMatchingReceipt(
                authenticatedAccountId = ACCOUNT_ID,
                requestedInstallationId = installationId,
                allocatedSignalDeviceId = SIGNAL_DEVICE_ID,
                receiptAccountId = ACCOUNT_ID,
                receiptTransportDeviceId = installationId.uuid,
                receiptSignalDeviceId = SIGNAL_DEVICE_ID,
            )
        repository.persistAfterDeviceRegistration(
            RegisteredPrivateAccountSession.afterDeviceRegistration(
                registration = registration,
                accessToken = ACCESS_TOKEN,
                refreshToken = REFRESH_TOKEN,
                expiresAt = EXPIRES_AT,
                authenticationUsername = USERNAME,
                pseudonymousDisplayName = "Peter",
            ),
        )

        val session = requireNotNull(provider.loadAuthenticatedSession())
        assertEquals(ACCOUNT_ID.toString(), session.accountId.canonical)
        assertEquals(INSTALLATION_UUID, session.localSignalAddress.transportDeviceId)
        assertEquals(SIGNAL_DEVICE_ID, session.localSignalAddress.protocolDeviceId)
        assertEquals(USERNAME, session.authenticationUsername)
        assertEquals(ACCESS_TOKEN, session.accessTokenForRequest())
        assertEquals(EXPIRES_AT, session.expiresAt)
    }

    private fun sessionRepository(): EncryptedPrivateSessionRepository =
        EncryptedPrivateSessionRepository(
            encryptedStateStorage = MemorySessionStorage(),
            installationIdGenerator = { PrivateInstallationId.fromGeneratedUuid(INSTALLATION_UUID) },
        )

    private companion object {
        val ACCOUNT_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val INSTALLATION_UUID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val SIGNAL_DEVICE_ID: SignalDeviceId = SignalDeviceId.fromWire(7)
        val EXPIRES_AT: Instant = Instant.parse("2026-08-25T13:00:00Z")
        const val USERNAME = "peter_01"
        const val ACCESS_TOKEN = "header.payload.signature-material"
        const val REFRESH_TOKEN = "refresh_token_material_1234567890"
    }
}

private class MemorySessionStorage : CryptographicallyErasableEncryptedStateStorage {
    private var state: ByteArray? = null

    override fun readDecryptedState(): ByteArray? = state?.copyOf()

    override fun replaceEncryptedState(plaintext: ByteArray) {
        state = plaintext.copyOf()
    }

    override fun replaceAfterCryptographicErasure(retainedPlaintext: ByteArray?) {
        state?.fill(0)
        state = retainedPlaintext?.copyOf()
    }

    override fun deletePhysically() {
        state?.fill(0)
        state = null
    }
}
