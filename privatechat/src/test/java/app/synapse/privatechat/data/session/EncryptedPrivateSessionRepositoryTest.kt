package app.synapse.privatechat.data.session

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.security.storage.Aes256GcmEncryptedStateCipher
import app.synapse.privatechat.security.storage.EncryptedStateFile
import app.synapse.privatechat.security.storage.EncryptedStateKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class EncryptedPrivateSessionRepositoryTest {
    @Test
    fun installationIdentityIsEncryptedStableAndGeneratedOnlyOnce() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(1))
        var generationCount = 0
        val repository =
            repository(file, keyProvider) {
                generationCount += 1
                INSTALLATION_ID
            }

        assertEquals(INSTALLATION_ID, repository.loadOrCreateInstallationId())
        assertEquals(INSTALLATION_ID, repository.loadOrCreateInstallationId())
        assertEquals(1, generationCount)
        assertEquals(1, file.replaceCount)
        assertFalse(requireNotNull(file.bytes).containsSubsequence(INSTALLATION_ID.uuid.toString().encodeToByteArray()))

        val reloaded = repository(file, keyProvider) { error("Persisted installation identity must be reused") }
        assertEquals(INSTALLATION_ID, reloaded.loadOrCreateInstallationId())
        assertNull(reloaded.loadRegisteredSession())
    }

    @Test
    fun authenticatedSessionCannotPersistBeforeInstallationIdentity() {
        val file = MemoryEncryptedStateFile()
        val repository = repository(file, MemoryEncryptedStateKeyProvider(key(2))) { INSTALLATION_ID }

        assertThrows(PrivateSessionStateUnavailableException::class.java) {
            repository.persistAfterDeviceRegistration(registeredSession())
        }
        assertNull(file.bytes)
    }

    @Test
    fun deviceRegistrationConfirmationRejectsMismatchedReceipts() {
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmedPrivateDeviceRegistration.confirmMatchingReceipt(
                authenticatedAccountId = ACCOUNT_ID,
                requestedInstallationId = INSTALLATION_ID,
                allocatedSignalDeviceId = SIGNAL_DEVICE_ID,
                receiptAccountId = OTHER_ACCOUNT_ID,
                receiptTransportDeviceId = INSTALLATION_ID.uuid,
                receiptSignalDeviceId = SIGNAL_DEVICE_ID,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmedPrivateDeviceRegistration.confirmMatchingReceipt(
                authenticatedAccountId = ACCOUNT_ID,
                requestedInstallationId = INSTALLATION_ID,
                allocatedSignalDeviceId = SIGNAL_DEVICE_ID,
                receiptAccountId = ACCOUNT_ID,
                receiptTransportDeviceId = OTHER_INSTALLATION_ID.uuid,
                receiptSignalDeviceId = SIGNAL_DEVICE_ID,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmedPrivateDeviceRegistration.confirmMatchingReceipt(
                authenticatedAccountId = ACCOUNT_ID,
                requestedInstallationId = INSTALLATION_ID,
                allocatedSignalDeviceId = SIGNAL_DEVICE_ID,
                receiptAccountId = ACCOUNT_ID,
                receiptTransportDeviceId = INSTALLATION_ID.uuid,
                receiptSignalDeviceId = SignalDeviceId.fromWire(8),
            )
        }
    }

    @Test
    fun registeredSessionSurvivesReloadWithoutPersistingReadableSecrets() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(3))
        val repository = repository(file, keyProvider) { INSTALLATION_ID }
        repository.loadOrCreateInstallationId()

        val receipt = repository.persistAfterDeviceRegistration(registeredSession())
        assertEquals(PrivateSessionPersistenceOutcome.STORED, receipt.outcome)
        assertFalse(receipt.toString().contains(ACCOUNT_ID.toString()))
        val ciphertext = requireNotNull(file.bytes)
        assertFalse(ciphertext.containsSubsequence(ACCESS_TOKEN.encodeToByteArray()))
        assertFalse(ciphertext.containsSubsequence(REFRESH_TOKEN.encodeToByteArray()))
        assertFalse(ciphertext.containsSubsequence(DISPLAY_NAME.encodeToByteArray()))

        val reloaded = repository(file, keyProvider) { error("Persisted installation identity must be reused") }
        val restored = requireNotNull(reloaded.loadRegisteredSession())
        assertNotSame(registeredSession(), restored)
        assertEquals(ACCOUNT_ID, restored.accountId)
        assertEquals(INSTALLATION_ID, restored.installationId)
        assertEquals(SIGNAL_DEVICE_ID, restored.signalDeviceId)
        assertEquals(ACCESS_TOKEN, restored.accessTokenForAuthorization())
        assertEquals(REFRESH_TOKEN, restored.refreshTokenForRenewal())
        assertEquals(EXPIRES_AT, restored.expiresAt)
        assertEquals(DISPLAY_NAME, restored.pseudonymousDisplayName)
        assertEquals("RegisteredPrivateAccountSession([REDACTED])", restored.toString())
    }

    @Test
    fun replacingRegisteredSessionProducesADurableReplacementReceipt() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(4))
        val repository = repository(file, keyProvider) { INSTALLATION_ID }
        repository.loadOrCreateInstallationId()
        repository.persistAfterDeviceRegistration(registeredSession())
        val replacement = registeredSession(accessToken = REPLACEMENT_ACCESS_TOKEN)

        val receipt = repository.persistAfterDeviceRegistration(replacement)

        assertEquals(PrivateSessionPersistenceOutcome.REPLACED, receipt.outcome)
        assertEquals(
            REPLACEMENT_ACCESS_TOKEN,
            repository(file, keyProvider) { error("Identity must not regenerate") }
                .loadRegisteredSession()
                ?.accessTokenForAuthorization(),
        )
    }

    @Test
    fun rejectsTamperedTruncatedWrongKeyAndWrongContextState() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(5))
        val repository = repository(file, keyProvider) { INSTALLATION_ID }
        repository.loadOrCreateInstallationId()
        repository.persistAfterDeviceRegistration(registeredSession())
        val valid = requireNotNull(file.bytes)

        val tampered = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertStateCannotLoad(tampered, key(5), SESSION_CONTEXT)
        assertStateCannotLoad(valid.copyOf(7), key(5), SESSION_CONTEXT)
        assertStateCannotLoad(valid, key(6), SESSION_CONTEXT)
        assertStateCannotLoad(valid, key(5), "synapse.private.different-state.v1")
    }

    @Test
    fun missingOrInvalidatedExistingKeyNeverCreatesAReplacement() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(7))
        val repository = repository(file, keyProvider) { INSTALLATION_ID }
        repository.loadOrCreateInstallationId()
        keyProvider.existingKey = null

        assertStateUnavailable { repository(file, keyProvider) { OTHER_INSTALLATION_ID } }
        assertEquals(0, keyProvider.creationCount)

        keyProvider.loadFailure = IllegalStateException("simulated invalidated key")
        assertStateUnavailable { repository(file, keyProvider) { OTHER_INSTALLATION_ID } }
        assertEquals(0, keyProvider.creationCount)
    }

    @Test
    fun failedIdentityWriteCanRetryWithTheExistingKeyAndSameGeneratedIdentity() {
        val file = MemoryEncryptedStateFile().apply { failNextReplace = true }
        val keyProvider = MemoryEncryptedStateKeyProvider()
        var generatedId = INSTALLATION_ID
        val repository = repository(file, keyProvider) { generatedId }

        assertStateUnavailable { repository.loadOrCreateInstallationId() }
        assertNull(file.bytes)
        assertEquals(1, keyProvider.creationCount)

        generatedId = INSTALLATION_ID
        assertEquals(INSTALLATION_ID, repository.loadOrCreateInstallationId())
        assertEquals(1, keyProvider.creationCount)
        assertEquals(1, file.replaceCount)
    }

    @Test
    fun failedSessionWriteRollsBackMemoryAndDurableState() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(8))
        val repository = repository(file, keyProvider) { INSTALLATION_ID }
        repository.loadOrCreateInstallationId()
        file.failNextReplace = true

        assertStateUnavailable { repository.persistAfterDeviceRegistration(registeredSession()) }
        assertNull(repository.loadRegisteredSession())
        assertNull(repository(file, keyProvider) { error("Identity must not regenerate") }.loadRegisteredSession())
    }

    @Test
    fun clearIsAtomicAndPreservesInstallationIdentity() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(9))
        val repository = repository(file, keyProvider) { INSTALLATION_ID }
        repository.loadOrCreateInstallationId()
        repository.persistAfterDeviceRegistration(registeredSession())
        file.failNextReplace = true

        assertStateUnavailable { repository.clearAuthenticatedSession() }
        assertEquals(ACCESS_TOKEN, repository.loadRegisteredSession()?.accessTokenForAuthorization())
        val durablyLoadedSession =
            repository(file, keyProvider) { error("Identity must not regenerate") }
                .loadRegisteredSession()
        assertEquals(ACCESS_TOKEN, durablyLoadedSession?.accessTokenForAuthorization())

        assertEquals(PrivateSessionClearReceipt.CLEARED, repository.clearAuthenticatedSession())
        assertEquals(PrivateSessionClearReceipt.ALREADY_EMPTY, repository.clearAuthenticatedSession())
        val reloaded = repository(file, keyProvider) { error("Identity must not regenerate") }
        assertEquals(INSTALLATION_ID, reloaded.loadOrCreateInstallationId())
        assertNull(reloaded.loadRegisteredSession())
    }

    @Test
    fun codecRejectsTruncationTrailingBytesOversizedInputAndInvalidTextLengths() {
        val state = PrivateSessionVaultState(INSTALLATION_ID, registeredSession())
        val valid = PrivateSessionVaultCodec.encode(state)

        assertStateUnavailable { PrivateSessionVaultCodec.decode(valid.copyOf(valid.size - 1)) }
        assertStateUnavailable { PrivateSessionVaultCodec.decode(valid + 1) }
        assertStateUnavailable { PrivateSessionVaultCodec.decode(ByteArray(PrivateSessionVaultCodec.MAX_PLAINTEXT_BYTES + 1)) }
        assertStateUnavailable { PrivateSessionVaultCodec.decode(encodeInvalidAccessTokenLength()) }
    }

    @Test
    fun sessionContractsRejectMalformedSecretsExpiryAndDisplayName() {
        assertThrows(IllegalArgumentException::class.java) {
            registeredSession(accessToken = "not-a-jwt-access-token")
        }
        assertThrows(IllegalArgumentException::class.java) {
            registeredSession(refreshToken = "contains whitespace and is invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            registeredSession(expiresAt = Instant.ofEpochSecond(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            registeredSession(displayName = " display name ")
        }
    }

    private fun registeredSession(
        accessToken: String = ACCESS_TOKEN,
        refreshToken: String = REFRESH_TOKEN,
        expiresAt: Instant = EXPIRES_AT,
        displayName: String = DISPLAY_NAME,
    ): RegisteredPrivateAccountSession =
        RegisteredPrivateAccountSession.afterDeviceRegistration(
            registration = confirmedRegistration(),
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            pseudonymousDisplayName = displayName,
        )

    private fun confirmedRegistration(): ConfirmedPrivateDeviceRegistration =
        ConfirmedPrivateDeviceRegistration.confirmMatchingReceipt(
            authenticatedAccountId = ACCOUNT_ID,
            requestedInstallationId = INSTALLATION_ID,
            allocatedSignalDeviceId = SIGNAL_DEVICE_ID,
            receiptAccountId = ACCOUNT_ID,
            receiptTransportDeviceId = INSTALLATION_ID.uuid,
            receiptSignalDeviceId = SIGNAL_DEVICE_ID,
        )

    private fun repository(
        file: MemoryEncryptedStateFile,
        keyProvider: MemoryEncryptedStateKeyProvider,
        installationIdGenerator: () -> PrivateInstallationId,
    ): EncryptedPrivateSessionRepository =
        EncryptedPrivateSessionRepository(
            encryptedStateFile = file,
            stateCipher = cipher(file, keyProvider, SESSION_CONTEXT),
            installationIdGenerator = installationIdGenerator,
        )

    private fun cipher(
        file: MemoryEncryptedStateFile,
        keyProvider: MemoryEncryptedStateKeyProvider,
        context: String,
    ): Aes256GcmEncryptedStateCipher =
        Aes256GcmEncryptedStateCipher(
            keyProvider = keyProvider,
            keyCreationAllowed = file::permitsEncryptionKeyCreation,
            authenticatedContext = context,
        )

    private fun assertStateCannotLoad(
        bytes: ByteArray,
        key: SecretKey,
        context: String,
    ) {
        val file = MemoryEncryptedStateFile(bytes)
        assertStateUnavailable {
            repository(
                file = file,
                keyProvider = MemoryEncryptedStateKeyProvider(key),
                installationIdGenerator = { OTHER_INSTALLATION_ID },
                context = context,
            )
        }
    }

    private fun repository(
        file: MemoryEncryptedStateFile,
        keyProvider: MemoryEncryptedStateKeyProvider,
        installationIdGenerator: () -> PrivateInstallationId,
        context: String,
    ): EncryptedPrivateSessionRepository =
        EncryptedPrivateSessionRepository(
            encryptedStateFile = file,
            stateCipher = cipher(file, keyProvider, context),
            installationIdGenerator = installationIdGenerator,
        )

    private fun assertStateUnavailable(block: () -> Unit) {
        assertThrows(PrivateSessionStateUnavailableException::class.java, block)
    }

    private fun encodeInvalidAccessTokenLength(): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { encoded ->
                encoded.writeInt(0x53504131)
                encoded.writeInt(1)
                encoded.writeUuid(INSTALLATION_ID.uuid)
                encoded.writeBoolean(true)
                encoded.writeUuid(ACCOUNT_ID)
                encoded.writeInt(SIGNAL_DEVICE_ID.raw)
                encoded.writeLong(EXPIRES_AT.epochSecond)
                encoded.writeInt(0)
            }
            output.toByteArray()
        }

    private fun DataOutputStream.writeUuid(uuid: UUID) {
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
    }

    private fun key(seed: Int): SecretKey = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")

    private class MemoryEncryptedStateFile(
        initialBytes: ByteArray? = null,
    ) : EncryptedStateFile {
        var bytes: ByteArray? = initialBytes?.copyOf()
            private set
        var failNextReplace = false
        var replaceCount = 0
            private set
        private var encryptedStateMayExist = initialBytes != null

        override fun read(maximumBytes: Int): ByteArray? {
            val persisted = bytes ?: return null
            encryptedStateMayExist = true
            if (persisted.size > maximumBytes) {
                throw PrivateSessionStateUnavailableException("Encrypted state exceeds the size limit")
            }
            return persisted.copyOf()
        }

        override fun replace(ciphertext: ByteArray) {
            encryptedStateMayExist = true
            if (failNextReplace) {
                failNextReplace = false
                throw IllegalStateException("forced atomic replace failure")
            }
            bytes = ciphertext.copyOf()
            replaceCount += 1
        }

        fun permitsEncryptionKeyCreation(): Boolean = !encryptedStateMayExist
    }

    private class MemoryEncryptedStateKeyProvider(
        var existingKey: SecretKey? = null,
    ) : EncryptedStateKeyProvider {
        var creationCount = 0
            private set
        var loadFailure: Exception? = null

        override fun loadExistingKey(): SecretKey? {
            loadFailure?.let { throw it }
            return existingKey
        }

        override fun createKeyIfAbsent(): SecretKey {
            existingKey?.let { return it }
            creationCount += 1
            return key(99).also { existingKey = it }
        }

        private fun key(seed: Int): SecretKey = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        indices.any { start ->
            start + candidate.size <= size && copyOfRange(start, start + candidate.size).contentEquals(candidate)
        }

    private companion object {
        val INSTALLATION_ID =
            PrivateInstallationId.fromGeneratedUuid(
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
            )
        val OTHER_INSTALLATION_ID =
            PrivateInstallationId.fromGeneratedUuid(
                UUID.fromString("30000000-0000-4000-8000-000000000002"),
            )
        val ACCOUNT_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000001")
        val OTHER_ACCOUNT_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000002")
        val SIGNAL_DEVICE_ID: SignalDeviceId = SignalDeviceId.fromWire(7)
        val EXPIRES_AT: Instant = Instant.ofEpochSecond(2_000_000_000)
        const val ACCESS_TOKEN = "header.payload.signature-material"
        const val REPLACEMENT_ACCESS_TOKEN = "header.payload.replacement-material"
        const val REFRESH_TOKEN = "refresh_token_material_1234567890"
        const val DISPLAY_NAME = "Private Person"
        const val SESSION_CONTEXT = "synapse.private.account-session.v1"
    }
}
