package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.InMemorySignalProtocolStateRepository
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.data.session.EncryptedPrivateSessionRepository
import app.synapse.privatechat.data.session.PrivateInstallationId
import app.synapse.privatechat.data.supabase.SupabaseTransportException
import app.synapse.privatechat.data.supabase.SupabaseTransportFailure
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateAccountPassword
import app.synapse.privatechat.domain.account.PrivateAccountSessionOutcome
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.domain.account.PrivateAccountSignOutOutcome
import app.synapse.privatechat.domain.account.PrivateDisplayName
import app.synapse.privatechat.domain.account.PrivateInvitationCode
import app.synapse.privatechat.domain.account.PrivateRemoteSessionRevocationStatus
import app.synapse.privatechat.domain.account.PrivateUsername
import app.synapse.privatechat.security.storage.CryptographicallyErasableEncryptedStateStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

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
        assertEquals(USERNAME.canonical, persistedSession.authenticationUsername)
    }

    @Test
    fun accountAccessWhileRegisteredIsRejectedBeforeBackendBootstrapOrPersistence() {
        val events = mutableListOf<String>()
        val sessionRepository = sessionRepository(events)
        val signalRepository = InMemorySignalProtocolStateRepository()
        val backend = RecordingPrivateAccountBackend(events = events)
        val gateway = gateway(backend, signalRepository, sessionRepository)
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }
        val originalSession = requireNotNull(sessionRepository.loadRegisteredSession())
        val originalIdentity = requireNotNull(signalRepository.loadLocalIdentity())
        events.clear()

        val outcome = runBlocking { gateway.requestPrivateAccountAccess(signInCommand()) }

        assertEquals(
            PrivateAccountAccessOutcome.Denied("Sign out before accessing another account."),
            outcome,
        )
        assertEquals(emptyList<String>(), events)
        val retainedSession = requireNotNull(sessionRepository.loadRegisteredSession())
        assertEquals(originalSession.accountId, retainedSession.accountId)
        assertEquals(originalSession.accessTokenForAuthorization(), retainedSession.accessTokenForAuthorization())
        val retainedIdentity = requireNotNull(signalRepository.loadLocalIdentity())
        assertEquals(originalIdentity.address, retainedIdentity.address)
        assertEquals(originalIdentity.registrationId, retainedIdentity.registrationId)
        assertArrayEquals(originalIdentity.serializedIdentityKeyPair, retainedIdentity.serializedIdentityKeyPair)
    }

    @Test
    fun freshPersistedSessionRestoresWithoutRotatingRefreshToken() {
        val sessionRepository = sessionRepository()
        val backend = RecordingPrivateAccountBackend()
        val gateway = gateway(backend, InMemorySignalProtocolStateRepository(), sessionRepository)
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

        val outcome = runBlocking { gateway.restorePrivateAccountSession() }

        val active = outcome as PrivateAccountSessionOutcome.Active
        assertEquals(ACCOUNT_ID, active.receipt.accountId)
        assertEquals(0, backend.refreshCount)
    }

    @Test
    fun expiringSessionRefreshesAndPersistsRotatedTokensAndLocalUsername() {
        val sessionRepository = sessionRepository()
        val backend =
            RecordingPrivateAccountBackend(
                authenticationOutcome = confirmedAuthentication(expiresAt = FIXED_NOW.plusSeconds(30)),
            )
        val gateway = gateway(backend, InMemorySignalProtocolStateRepository(), sessionRepository)
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

        val outcome = runBlocking { gateway.restorePrivateAccountSession() }

        val active = outcome as PrivateAccountSessionOutcome.Active
        assertEquals(ACCOUNT_ID, active.receipt.accountId)
        assertEquals(1, backend.refreshCount)
        assertEquals(ACCOUNT_ID, backend.refreshCommand?.expectedAccountId)
        val persisted = requireNotNull(sessionRepository.loadRegisteredSession())
        assertEquals(REFRESHED_ACCESS_TOKEN, persisted.accessTokenForAuthorization())
        assertEquals(REFRESHED_REFRESH_TOKEN, persisted.refreshTokenForRenewal())
        assertEquals(USERNAME.canonical, persisted.authenticationUsername)
    }

    @Test
    fun mismatchedRefreshAccountFailsClosedWithoutReplacingDurableSession() {
        val sessionRepository = sessionRepository()
        val backend =
            RecordingPrivateAccountBackend(
                refreshOutcome =
                    PrivateAccountBackendOutcome.Confirmed(
                        refreshedBackendSession(accountId = OTHER_ACCOUNT_ID),
                    ),
            )
        val gateway = gateway(backend, InMemorySignalProtocolStateRepository(), sessionRepository)
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

        val outcome = runBlocking { gateway.refreshPrivateAccountSession() }

        assertSame(
            PrivateAccountSessionOutcome.VerificationFailed,
            outcome,
        )
        assertEquals(
            ACCESS_TOKEN,
            sessionRepository.loadRegisteredSession()?.accessTokenForAuthorization(),
        )
    }

    @Test
    fun terminalRefreshRejectionPurgesConversationStateBeforeClearingAuthenticatedSession() {
        val events = mutableListOf<String>()
        val sessionRepository = sessionRepository(events)
        val backend =
            RecordingPrivateAccountBackend(
                refreshOutcome =
                    PrivateAccountBackendOutcome.Rejected(
                        userMessage = "Session is no longer valid.",
                        reason = PrivateBackendRejectionReason.ACCESS_DENIED,
                    ),
                events = events,
            )
        val gateway =
            gateway(
                backend,
                InMemorySignalProtocolStateRepository(),
                sessionRepository,
                RecordingLocalStateInvalidator(events),
            )
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }
        events.clear()

        val outcome = runBlocking { gateway.refreshPrivateAccountSession() }

        assertSame(PrivateAccountSessionOutcome.SignedOut, outcome)
        assertEquals(listOf("refreshSession", "purgeLocalState", "replaceSessionState"), events)
        assertNull(sessionRepository.loadRegisteredSession())
        assertEquals(INSTALLATION_ID, sessionRepository.loadOrCreateInstallationId())
    }

    @Test
    fun signOutPurgesAndClearsLocallyBeforeBestEffortRemoteRevocation() {
        val events = mutableListOf<String>()
        val sessionRepository = sessionRepository(events)
        val backend = RecordingPrivateAccountBackend(events = events)
        val gateway =
            gateway(
                backend,
                InMemorySignalProtocolStateRepository(),
                sessionRepository,
                RecordingLocalStateInvalidator(events),
            )
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }
        events.clear()

        val outcome = runBlocking { gateway.signOutPrivateAccount() }

        val signedOut = outcome as PrivateAccountSignOutOutcome.LocallySignedOut
        assertSame(PrivateRemoteSessionRevocationStatus.Confirmed, signedOut.remoteRevocation)
        assertEquals(listOf("purgeLocalState", "replaceSessionState", "signOut"), events)
        assertEquals(ACCESS_TOKEN, backend.signOutCommand?.exposeAccessTokenForRequest())
        assertNull(sessionRepository.loadRegisteredSession())
    }

    @Test
    fun signOutTransportFailureStillReturnsTruthfulLocallySignedOutOutcome() {
        val events = mutableListOf<String>()
        val sessionRepository = sessionRepository(events)
        val backend =
            RecordingPrivateAccountBackend(
                authenticationOutcome = confirmedAuthentication(expiresAt = FIXED_NOW.plusSeconds(30)),
                refreshFailure =
                    SupabaseTransportException(
                        failure = SupabaseTransportFailure.NETWORK_UNAVAILABLE,
                        message = "simulated refresh outage",
                    ),
                events = events,
            )
        val gateway =
            gateway(
                backend,
                InMemorySignalProtocolStateRepository(),
                sessionRepository,
                RecordingLocalStateInvalidator(events),
            )
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }
        events.clear()

        val outcome = runBlocking { gateway.signOutPrivateAccount() }

        val signedOut = outcome as PrivateAccountSignOutOutcome.LocallySignedOut
        assertSame(PrivateRemoteSessionRevocationStatus.TransportUnavailable, signedOut.remoteRevocation)
        assertEquals(listOf("purgeLocalState", "replaceSessionState", "refreshSession"), events)
        assertNull(sessionRepository.loadRegisteredSession())
        assertNull(backend.signOutCommand)
    }

    @Test
    fun remoteLogoutRejectionStillReturnsTruthfulLocallySignedOutOutcome() {
        val sessionRepository = sessionRepository()
        val backend =
            RecordingPrivateAccountBackend(
                signOutOutcome =
                    PrivateAccountBackendOutcome.Rejected(
                        userMessage = "Server could not revoke this session.",
                        reason = PrivateBackendRejectionReason.REMOTE_FAILURE,
                    ),
            )
        val gateway = gateway(backend, InMemorySignalProtocolStateRepository(), sessionRepository)
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

        val outcome = runBlocking { gateway.signOutPrivateAccount() }

        val signedOut = outcome as PrivateAccountSignOutOutcome.LocallySignedOut
        assertEquals(
            PrivateRemoteSessionRevocationStatus.Rejected("Server could not revoke this session."),
            signedOut.remoteRevocation,
        )
        assertNull(sessionRepository.loadRegisteredSession())
    }

    @Test
    fun localConversationPurgeFailureKeepsSessionAndSkipsRemoteLogout() {
        val sessionRepository = sessionRepository()
        val backend = RecordingPrivateAccountBackend()
        val gateway =
            gateway(
                backend,
                InMemorySignalProtocolStateRepository(),
                sessionRepository,
                RecordingLocalStateInvalidator(failure = IllegalStateException("simulated cache purge failure")),
            )
        runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

        val outcome = runBlocking { gateway.signOutPrivateAccount() }

        assertSame(PrivateAccountSignOutOutcome.LocalStateUnavailable, outcome)
        assertNotNull(sessionRepository.loadRegisteredSession())
        assertNull(backend.signOutCommand)
    }

    @Test
    fun restoreWithoutSessionPurgesConversationStateBeforeReportingSignedOut() {
        val events = mutableListOf<String>()
        val gateway =
            gateway(
                RecordingPrivateAccountBackend(events = events),
                InMemorySignalProtocolStateRepository(),
                sessionRepository(events),
                RecordingLocalStateInvalidator(events),
            )

        val outcome = runBlocking { gateway.restorePrivateAccountSession() }

        assertSame(PrivateAccountSessionOutcome.SignedOut, outcome)
        assertEquals(listOf("purgeLocalState"), events)
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

    @Test
    fun accountBootstrapRunsOnTheDedicatedOperationDispatcher() {
        val backend = RecordingPrivateAccountBackend()
        val executor =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, ACCOUNT_OPERATION_THREAD_NAME)
            }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val gateway =
                gateway(
                    backend = backend,
                    signalRepository = InMemorySignalProtocolStateRepository(),
                    sessionRepository = sessionRepository(),
                    operationDispatcher = dispatcher,
                )

            val outcome = runBlocking { gateway.requestPrivateAccountAccess(registrationCommand()) }

            assertTrue(outcome is PrivateAccountAccessOutcome.Confirmed)
            assertTrue(backend.authenticationThreadName?.startsWith(ACCOUNT_OPERATION_THREAD_NAME) == true)
            assertTrue(backend.deviceRegistrationThreadName?.startsWith(ACCOUNT_OPERATION_THREAD_NAME) == true)
        } finally {
            dispatcher.close()
        }
    }

    private fun gateway(
        backend: PrivateAccountBackend,
        signalRepository: InMemorySignalProtocolStateRepository,
        sessionRepository: EncryptedPrivateSessionRepository,
        localStateInvalidator: PrivateAccountLocalStateInvalidator = NoStoredPrivateConversationStateInvalidator,
        operationDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): SupabasePrivateAccountGateway =
        SupabasePrivateAccountGateway(
            backend = backend,
            signalDeviceBootstrapper =
                PrivateSignalDeviceBootstrapper(
                    SignalProtocolAdapterOwner(signalRepository),
                ),
            sessionRepository = sessionRepository,
            localStateInvalidator = localStateInvalidator,
            operationDispatcher = operationDispatcher,
            clock = FIXED_CLOCK,
        )

    private fun sessionRepository(events: MutableList<String>? = null): EncryptedPrivateSessionRepository =
        EncryptedPrivateSessionRepository(
            encryptedStateStorage = MemoryCryptographicallyErasableStateStorage(events),
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

    private fun confirmedAuthentication(expiresAt: Instant = EXPIRES_AT): PrivateAccountBackendOutcome<UnboundPrivateAccountSession> =
        PrivateAccountBackendOutcome.Confirmed(
            UnboundPrivateAccountSession(
                reservation = RESERVATION,
                tokens =
                    PrivateBackendSessionTokens(
                        expiresAt = expiresAt,
                        accessToken = ACCESS_TOKEN,
                        refreshToken = REFRESH_TOKEN,
                    ),
            ),
        )

    private fun refreshedBackendSession(accountId: PrivateAccountId = ACCOUNT_ID): RefreshedPrivateAccountSession =
        RefreshedPrivateAccountSession(
            accountId = accountId,
            tokens =
                PrivateBackendSessionTokens(
                    expiresAt = FIXED_NOW.plusSeconds(7_200),
                    accessToken = REFRESHED_ACCESS_TOKEN,
                    refreshToken = REFRESHED_REFRESH_TOKEN,
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

    private inner class RecordingPrivateAccountBackend(
        private val authenticationOutcome: PrivateAccountBackendOutcome<UnboundPrivateAccountSession> =
            confirmedAuthentication(),
        private val deviceBindingOutcome: PrivateAccountBackendOutcome<PrivateDeviceBindingReceipt> =
            confirmedDeviceBinding(),
        private val refreshOutcome: PrivateAccountBackendOutcome<RefreshedPrivateAccountSession> =
            PrivateAccountBackendOutcome.Confirmed(refreshedBackendSession()),
        private val signOutOutcome: PrivateAccountBackendOutcome<PrivateBackendSignOutReceipt> =
            PrivateAccountBackendOutcome.Confirmed(PrivateBackendSignOutReceipt),
        private val authenticationFailure: SupabaseTransportException? = null,
        private val refreshFailure: SupabaseTransportException? = null,
        val events: MutableList<String> = mutableListOf(),
    ) : PrivateAccountBackend {
        var authenticatedTransportDeviceId: UUID? = null
            private set
        var registrationRedemptionId: UUID? = null
            private set
        var deviceBindingCommand: PrivateDeviceBindingCommand? = null
            private set
        var refreshCommand: PrivateSessionRefreshCommand? = null
            private set
        var signOutCommand: PrivateSessionSignOutCommand? = null
            private set
        var refreshCount = 0
            private set
        var authenticationThreadName: String? = null
            private set
        var deviceRegistrationThreadName: String? = null
            private set

        override suspend fun authenticate(
            command: PrivateAccountAccessCommand,
            transportDeviceId: UUID,
            registrationRedemptionId: UUID?,
        ): PrivateAccountBackendOutcome<UnboundPrivateAccountSession> {
            authenticationFailure?.let { throw it }
            events += "authenticate"
            authenticationThreadName = Thread.currentThread().name
            authenticatedTransportDeviceId = transportDeviceId
            this.registrationRedemptionId = registrationRedemptionId
            return authenticationOutcome
        }

        override suspend fun registerDevice(
            command: PrivateDeviceBindingCommand,
        ): PrivateAccountBackendOutcome<PrivateDeviceBindingReceipt> {
            events += "registerDevice"
            deviceRegistrationThreadName = Thread.currentThread().name
            deviceBindingCommand = command
            return deviceBindingOutcome
        }

        override suspend fun refreshSession(
            command: PrivateSessionRefreshCommand,
        ): PrivateAccountBackendOutcome<RefreshedPrivateAccountSession> {
            events += "refreshSession"
            refreshFailure?.let { throw it }
            refreshCount += 1
            refreshCommand = command
            return refreshOutcome
        }

        override suspend fun signOut(command: PrivateSessionSignOutCommand): PrivateAccountBackendOutcome<PrivateBackendSignOutReceipt> {
            events += "signOut"
            signOutCommand = command
            return signOutOutcome
        }
    }

    private class RecordingLocalStateInvalidator(
        private val events: MutableList<String>? = null,
        private val failure: Exception? = null,
    ) : PrivateAccountLocalStateInvalidator {
        override suspend fun purgeForSessionInvalidation(): PrivateAccountLocalStatePurgeReceipt {
            events?.add("purgeLocalState")
            failure?.let { throw it }
            return PrivateAccountLocalStatePurgeReceipt.PURGED
        }
    }

    private class MemoryCryptographicallyErasableStateStorage(
        private val events: MutableList<String>? = null,
    ) : CryptographicallyErasableEncryptedStateStorage {
        private var committedState: ByteArray? = null

        override fun readDecryptedState(): ByteArray? = committedState?.copyOf()

        override fun replaceEncryptedState(plaintext: ByteArray) {
            committedState = plaintext.copyOf()
        }

        override fun replaceAfterCryptographicErasure(retainedPlaintext: ByteArray?) {
            events?.add("replaceSessionState")
            committedState = retainedPlaintext?.copyOf()
        }

        override fun deletePhysically() {
            committedState = null
        }
    }

    private companion object {
        val ACCOUNT_UUID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val ACCOUNT_ID = PrivateAccountId(ACCOUNT_UUID.toString())
        val OTHER_ACCOUNT_ID =
            PrivateAccountId(UUID.fromString("10000000-0000-4000-8000-000000000099").toString())
        val INSTALLATION_ID =
            PrivateInstallationId.fromGeneratedUuid(
                UUID.fromString("20000000-0000-4000-8000-000000000002"),
            )
        val SIGNAL_DEVICE_ID = SignalDeviceId.fromWire(7)
        val EXPIRES_AT: Instant = Instant.parse("2026-08-25T13:00:00Z")
        val FIXED_NOW: Instant = Instant.parse("2026-08-25T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
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
        const val REFRESHED_ACCESS_TOKEN = "header.payload.refreshed-signature-material"
        const val REFRESHED_REFRESH_TOKEN = "refreshed_token_material_1234567890"
        const val ACCOUNT_OPERATION_THREAD_NAME = "private-account-operation"
    }
}
