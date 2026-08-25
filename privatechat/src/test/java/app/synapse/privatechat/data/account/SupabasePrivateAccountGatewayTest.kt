package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.InMemorySignalProtocolStateRepository
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.data.session.EncryptedPrivateSessionRepository
import app.synapse.privatechat.data.session.PrivateInstallationId
import app.synapse.privatechat.data.supabase.SupabaseTransportException
import app.synapse.privatechat.data.supabase.SupabaseTransportFailure
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateAccountPassword
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.domain.account.PrivateDisplayName
import app.synapse.privatechat.domain.account.PrivateInvitationCode
import app.synapse.privatechat.domain.account.PrivateUsername
import app.synapse.privatechat.security.storage.EncryptedStateCipher
import app.synapse.privatechat.security.storage.EncryptedStateFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SupabasePrivateAccountGatewayTest {
    @Test
    fun inviteAccessBindsSignalDeviceBeforePersistingAndConfirmingSession() {
        val sessionRepository = sessionRepository()
        val signalRepository = InMemorySignalProtocolStateRepository()
        val backend =
            RecordingPrivateAccountBackend(
                authenticationOutcome = confirmedAuthentication(),
                deviceBindingOutcome = confirmedDeviceBinding(),
            )
        val gateway = gateway(backend, signalRepository, sessionRepository)

        val outcome = runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

        val confirmed = outcome as PrivateAccountAccessOutcome.Confirmed
        val receipt = confirmed.receipt as PrivateAccountSessionReceipt.Active
        assertEquals(ACCOUNT_ID.canonical, receipt.accountId.canonical)
        assertEquals(DISPLAY_NAME.canonical, receipt.displayName.canonical)
        assertEquals(INSTALLATION_ID.uuid, backend.authenticatedTransportDeviceId)
        assertEquals(
            PrivateRegistrationRedemptionIdFactory.derive(INSTALLATION_ID, INVITATION_CODE),
            backend.registrationRedemptionId,
        )
        assertEquals(RESERVATION, backend.deviceBindingCommand?.reservation)
        assertNotNull(signalRepository.loadLocalIdentity())
        val persistedSession = requireNotNull(sessionRepository.loadRegisteredSession())
        assertEquals(ACCOUNT_UUID, persistedSession.accountId)
        assertEquals(INSTALLATION_ID, persistedSession.installationId)
        assertEquals(SIGNAL_DEVICE_ID, persistedSession.signalDeviceId)
        assertEquals(ACCESS_TOKEN, persistedSession.accessTokenForAuthorization())
        assertEquals(REFRESH_TOKEN, persistedSession.refreshTokenForRenewal())
    }

    @Test
    fun rejectedAuthenticationDoesNotCreateSignalIdentityOrAuthenticatedSession() {
        val sessionRepository = sessionRepository()
        val signalRepository = InMemorySignalProtocolStateRepository()
        val backend =
            RecordingPrivateAccountBackend(
                authenticationOutcome = PrivateAccountBackendOutcome.Rejected("Invite is invalid or expired."),
                deviceBindingOutcome = confirmedDeviceBinding(),
            )
        val gateway = gateway(backend, signalRepository, sessionRepository)

        val outcome = runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

        assertEquals(
            PrivateAccountAccessOutcome.Denied("Invite is invalid or expired."),
            outcome,
        )
        assertNull(backend.deviceBindingCommand)
        assertNull(signalRepository.loadLocalIdentity())
        assertNull(sessionRepository.loadRegisteredSession())
    }

    @Test
    fun rejectedDeviceBindingNeverPersistsAuthenticationTokens() {
        val sessionRepository = sessionRepository()
        val signalRepository = InMemorySignalProtocolStateRepository()
        val backend =
            RecordingPrivateAccountBackend(
                authenticationOutcome = confirmedAuthentication(),
                deviceBindingOutcome = PrivateAccountBackendOutcome.Rejected("Device reservation expired."),
            )
        val gateway = gateway(backend, signalRepository, sessionRepository)

        val outcome = runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

        assertEquals(
            PrivateAccountAccessOutcome.Denied("Device reservation expired."),
            outcome,
        )
        assertNotNull(signalRepository.loadLocalIdentity())
        assertNull(sessionRepository.loadRegisteredSession())
    }

    @Test
    fun transportFailureProducesAnExplicitUnavailableOutcome() {
        val backend =
            RecordingPrivateAccountBackend(
                authenticationOutcome = confirmedAuthentication(),
                deviceBindingOutcome = confirmedDeviceBinding(),
                authenticationFailure =
                    SupabaseTransportException(
                        failure = SupabaseTransportFailure.NETWORK_UNAVAILABLE,
                        message = "simulated network failure",
                    ),
            )
        val gateway = gateway(backend, InMemorySignalProtocolStateRepository(), sessionRepository())

        val outcome = runBlocking { gateway.requestPrivateAccountAccess(signInCommand()) }

        assertSame(PrivateAccountAccessOutcome.TransportUnavailable, outcome)
    }

    private fun gateway(
        backend: PrivateAccountBackend,
        signalRepository: InMemorySignalProtocolStateRepository,
        sessionRepository: EncryptedPrivateSessionRepository,
    ): SupabasePrivateAccountGateway =
        SupabasePrivateAccountGateway(
            backend = backend,
            signalDeviceBootstrapper = PrivateSignalDeviceBootstrapper(signalRepository),
            sessionRepository = sessionRepository,
        )

    private fun sessionRepository(): EncryptedPrivateSessionRepository =
        EncryptedPrivateSessionRepository(
            encryptedStateFile = MemoryEncryptedStateFile(),
            stateCipher = CopyingEncryptedStateCipher,
            installationIdGenerator = { INSTALLATION_ID },
        )

    private fun registrationCommand(): PrivateAccountAccessCommand.RegisterWithInvite =
        PrivateAccountAccessCommand.RegisterWithInvite(
            displayName = DISPLAY_NAME,
            username = USERNAME,
            password = PASSWORD,
            invitationCode = INVITATION_CODE,
        )

    private fun signInCommand(): PrivateAccountAccessCommand.SignIn =
        PrivateAccountAccessCommand.SignIn(
            username = USERNAME,
            password = PASSWORD,
        )

    private fun confirmedAuthentication(): PrivateAccountBackendOutcome<UnboundPrivateAccountSession> =
        PrivateAccountBackendOutcome.Confirmed(
            UnboundPrivateAccountSession(
                reservation = RESERVATION,
                tokens =
                    PrivateBackendSessionTokens(
                        expiresAt = EXPIRES_AT,
                        accessToken = ACCESS_TOKEN,
                        refreshToken = REFRESH_TOKEN,
                    ),
            ),
        )

    private fun confirmedDeviceBinding(): PrivateAccountBackendOutcome<PrivateDeviceBindingReceipt> =
        PrivateAccountBackendOutcome.Confirmed(
            PrivateDeviceBindingReceipt(
                accountId = ACCOUNT_ID,
                transportDeviceId = INSTALLATION_ID.uuid,
                signalDeviceId = SIGNAL_DEVICE_ID,
                displayName = DISPLAY_NAME,
                boundAt = BOUND_AT,
            ),
        )

    private class RecordingPrivateAccountBackend(
        private val authenticationOutcome: PrivateAccountBackendOutcome<UnboundPrivateAccountSession>,
        private val deviceBindingOutcome: PrivateAccountBackendOutcome<PrivateDeviceBindingReceipt>,
        private val authenticationFailure: SupabaseTransportException? = null,
    ) : PrivateAccountBackend {
        var authenticatedTransportDeviceId: UUID? = null
            private set
        var registrationRedemptionId: UUID? = null
            private set
        var deviceBindingCommand: PrivateDeviceBindingCommand? = null
            private set

        override suspend fun authenticate(
            command: PrivateAccountAccessCommand,
            transportDeviceId: UUID,
            registrationRedemptionId: UUID?,
        ): PrivateAccountBackendOutcome<UnboundPrivateAccountSession> {
            authenticationFailure?.let { throw it }
            authenticatedTransportDeviceId = transportDeviceId
            this.registrationRedemptionId = registrationRedemptionId
            return authenticationOutcome
        }

        override suspend fun registerDevice(
            command: PrivateDeviceBindingCommand,
        ): PrivateAccountBackendOutcome<PrivateDeviceBindingReceipt> {
            deviceBindingCommand = command
            return deviceBindingOutcome
        }
    }

    private class MemoryEncryptedStateFile : EncryptedStateFile {
        private var committedCiphertext: ByteArray? = null

        override fun read(maximumBytes: Int): ByteArray? = committedCiphertext?.copyOf()

        override fun replace(ciphertext: ByteArray) {
            committedCiphertext = ciphertext.copyOf()
        }
    }

    private object CopyingEncryptedStateCipher : EncryptedStateCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.copyOf()

        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.copyOf()
    }

    private companion object {
        val ACCOUNT_UUID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val ACCOUNT_ID = PrivateAccountId(ACCOUNT_UUID.toString())
        val INSTALLATION_ID =
            PrivateInstallationId.fromGeneratedUuid(
                UUID.fromString("20000000-0000-4000-8000-000000000002"),
            )
        val SIGNAL_DEVICE_ID = SignalDeviceId.fromWire(7)
        val EXPIRES_AT: Instant = Instant.parse("2026-08-25T13:00:00Z")
        val BOUND_AT: Instant = Instant.parse("2026-08-25T12:00:00Z")
        val RESERVATION =
            PrivateDeviceRegistrationReservation(
                accountId = ACCOUNT_ID,
                transportDeviceId = INSTALLATION_ID.uuid,
                signalDeviceId = SIGNAL_DEVICE_ID,
                expiresAt = Instant.parse("2026-08-25T12:05:00Z"),
            )
        val DISPLAY_NAME = PrivateDisplayName("Peter")
        val USERNAME = PrivateUsername("peter_01")
        val PASSWORD = PrivateAccountPassword("correct-horse-battery")
        val INVITATION_CODE = PrivateInvitationCode("A".repeat(43))
        const val ACCESS_TOKEN = "header.payload.signature-material"
        const val REFRESH_TOKEN = "refresh_token_material_1234567890"
    }
}
