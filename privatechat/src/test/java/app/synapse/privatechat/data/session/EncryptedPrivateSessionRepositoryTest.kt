package app.synapse.privatechat.data.session

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.security.storage.Aes256GcmEncryptedStateCipher
import app.synapse.privatechat.security.storage.DeletableEncryptedStateFile
import app.synapse.privatechat.security.storage.DestructibleEncryptedStateKeyProvider
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateKeySlot
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateStorage
import app.synapse.privatechat.security.storage.RotatingEncryptedStateKeySlotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.IdentityHashMap
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class EncryptedPrivateSessionRepositoryTest {
    private val secondaryKeyProviders =
        IdentityHashMap<MemoryEncryptedStateKeyProvider, MemoryEncryptedStateKeyProvider>()

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
        assertFalse(ciphertext.containsSubsequence(USERNAME.encodeToByteArray()))
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
        assertEquals(USERNAME, restored.authenticationUsername)
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
    fun registrationRefreshAndClearRotateSlotsAndDestroySupersededKeys() {
        val file = MemoryEncryptedStateFile()
        val primaryKeys = MemoryEncryptedStateKeyProvider()
        val secondaryKeys = secondaryKeyProvider(primaryKeys)
        val repository = repository(file, primaryKeys) { INSTALLATION_ID }

        repository.loadOrCreateInstallationId()
        val installationStateKey = requireNotNull(primaryKeys.existingKey)
        assertNull(secondaryKeys.existingKey)

        repository.persistAfterDeviceRegistration(registeredSession())
        assertNull(primaryKeys.existingKey)
        assertTrue(primaryKeys.deletionCount >= 1)
        assertNotNull(secondaryKeys.existingKey)

        repository.persistRefreshedSession(registeredSession(accessToken = REPLACEMENT_ACCESS_TOKEN))
        val refreshedStateKey = requireNotNull(primaryKeys.existingKey)
        assertFalse(installationStateKey == refreshedStateKey)
        assertNull(secondaryKeys.existingKey)

        repository.clearAuthenticatedSession()
        assertNull(primaryKeys.existingKey)
        assertNotNull(secondaryKeys.existingKey)
        assertNull(repository.loadRegisteredSession())
    }

    @Test
    fun legacyVaultWithoutUsernameIsAtomicallyMigratedToSignedOutState() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(41))
        val stateCipher = cipher(file, keyProvider, SESSION_CONTEXT)
        file.replace(stateCipher.encrypt(encodeLegacyVault()))

        val repository = repository(file, keyProvider) { error("Legacy identity must be preserved") }

        assertEquals(INSTALLATION_ID, repository.loadOrCreateInstallationId())
        assertNull(repository.loadRegisteredSession())
        assertEquals(3, file.replaceCount)
        assertTrue(keyProvider.deletionCount >= 2)
        val reloaded = repository(file, keyProvider) { error("Migrated identity must be preserved") }
        assertEquals(INSTALLATION_ID, reloaded.loadOrCreateInstallationId())
        assertNull(reloaded.loadRegisteredSession())
    }

    @Test
    fun rejectsTamperedTruncatedWrongKeyAndWrongContextState() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(5))
        val repository = repository(file, keyProvider) { INSTALLATION_ID }
        repository.loadOrCreateInstallationId()
        repository.persistAfterDeviceRegistration(registeredSession())
        val valid = requireNotNull(file.bytes)
        val secondaryKey = requireNotNull(secondaryKeyProvider(keyProvider).existingKey)

        val tampered = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertStateCannotLoad(tampered, secondaryKey = secondaryKey)
        assertStateCannotLoad(valid.copyOf(7), secondaryKey = secondaryKey)
        assertStateCannotLoad(valid, secondaryKey = key(6))
        assertStateCannotLoad(
            valid,
            secondaryKey = secondaryKey,
            secondaryContext = "synapse.private.different-state.v1",
        )
    }

    @Test
    fun missingOrInvalidatedExistingKeyNeverCreatesAReplacement() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemoryEncryptedStateKeyProvider(key(7))
        val repository = repository(file, keyProvider) { INSTALLATION_ID }
        repository.loadOrCreateInstallationId()
        val creationCountBeforeMissingKey = keyProvider.creationCount
        keyProvider.existingKey = null

        assertStateUnavailable { repository(file, keyProvider) { OTHER_INSTALLATION_ID } }
        assertEquals(creationCountBeforeMissingKey, keyProvider.creationCount)
        assertNull(file.bytes)

        val invalidatedFile = MemoryEncryptedStateFile()
        val invalidatedKeyProvider = MemoryEncryptedStateKeyProvider(key(71))
        repository(invalidatedFile, invalidatedKeyProvider) { INSTALLATION_ID }.loadOrCreateInstallationId()
        val creationCountBeforeInvalidation = invalidatedKeyProvider.creationCount
        invalidatedKeyProvider.loadFailure = IllegalStateException("simulated invalidated key")
        assertStateUnavailable { repository(invalidatedFile, invalidatedKeyProvider) { OTHER_INSTALLATION_ID } }
        assertEquals(creationCountBeforeInvalidation, invalidatedKeyProvider.creationCount)
        assertNull(invalidatedFile.bytes)
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
        assertEquals(2, keyProvider.creationCount)
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
        assertThrows(IllegalArgumentException::class.java) {
            registeredSession(authenticationUsername = "Not_Normalized")
        }
        assertThrows(IllegalArgumentException::class.java) {
            registeredSession().withRefreshedTokens(
                receiptAccountId = OTHER_ACCOUNT_ID,
                accessToken = REPLACEMENT_ACCESS_TOKEN,
                refreshToken = REFRESH_TOKEN,
                expiresAt = EXPIRES_AT,
            )
        }
    }

    private fun registeredSession(
        accessToken: String = ACCESS_TOKEN,
        refreshToken: String = REFRESH_TOKEN,
        expiresAt: Instant = EXPIRES_AT,
        authenticationUsername: String = USERNAME,
        displayName: String = DISPLAY_NAME,
    ): RegisteredPrivateAccountSession =
        RegisteredPrivateAccountSession.afterDeviceRegistration(
            registration = confirmedRegistration(),
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            authenticationUsername = authenticationUsername,
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
        repository(
            file = file,
            primaryKeyProvider = keyProvider,
            secondaryKeyProvider = secondaryKeyProvider(keyProvider),
            installationIdGenerator = installationIdGenerator,
            primaryContext = SESSION_CONTEXT,
            secondaryContext = SECONDARY_SESSION_CONTEXT,
        )

    private fun secondaryKeyProvider(primaryKeyProvider: MemoryEncryptedStateKeyProvider): MemoryEncryptedStateKeyProvider =
        secondaryKeyProviders.getOrPut(primaryKeyProvider) {
            MemoryEncryptedStateKeyProvider(creationSeedBase = 199)
        }

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
        primaryKey: SecretKey? = null,
        secondaryKey: SecretKey? = null,
        primaryContext: String = SESSION_CONTEXT,
        secondaryContext: String = SECONDARY_SESSION_CONTEXT,
    ) {
        val file = MemoryEncryptedStateFile(bytes)
        assertStateUnavailable {
            repository(
                file = file,
                primaryKeyProvider = MemoryEncryptedStateKeyProvider(primaryKey),
                secondaryKeyProvider = MemoryEncryptedStateKeyProvider(secondaryKey),
                installationIdGenerator = { OTHER_INSTALLATION_ID },
                primaryContext = primaryContext,
                secondaryContext = secondaryContext,
            )
        }
        assertNull(file.bytes)
    }

    private fun repository(
        file: MemoryEncryptedStateFile,
        primaryKeyProvider: MemoryEncryptedStateKeyProvider,
        secondaryKeyProvider: MemoryEncryptedStateKeyProvider,
        installationIdGenerator: () -> PrivateInstallationId,
        primaryContext: String,
        secondaryContext: String,
    ): EncryptedPrivateSessionRepository =
        EncryptedPrivateSessionRepository(
            encryptedStateStorage =
                RotatingAesGcmEncryptedStateStorage(
                    encryptedStateFile = file,
                    primaryKeySlot =
                        RotatingAesGcmEncryptedStateKeySlot(
                            keyProvider = primaryKeyProvider,
                            authenticatedContext = primaryContext,
                        ),
                    secondaryKeySlot =
                        RotatingAesGcmEncryptedStateKeySlot(
                            keyProvider = secondaryKeyProvider,
                            authenticatedContext = secondaryContext,
                        ),
                    maximumPlaintextBytes = PrivateSessionVaultCodec.MAX_PLAINTEXT_BYTES,
                    legacySingleSlot = RotatingEncryptedStateKeySlotId.PRIMARY,
                ),
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

    private fun encodeLegacyVault(): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { encoded ->
                encoded.writeInt(0x53504131)
                encoded.writeInt(1)
                encoded.writeUuid(INSTALLATION_ID.uuid)
                encoded.writeBoolean(true)
                encoded.writeUuid(ACCOUNT_ID)
                encoded.writeInt(SIGNAL_DEVICE_ID.raw)
                encoded.writeLong(EXPIRES_AT.epochSecond)
                encoded.writeLegacyUtf8(ACCESS_TOKEN)
                encoded.writeLegacyUtf8(REFRESH_TOKEN)
                encoded.writeLegacyUtf8(DISPLAY_NAME)
            }
            output.toByteArray()
        }

    private fun DataOutputStream.writeLegacyUtf8(text: String) {
        val bytes = text.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
        bytes.fill(0)
    }

    private fun DataOutputStream.writeUuid(uuid: UUID) {
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
    }

    private fun key(seed: Int): SecretKey = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")

    private class MemoryEncryptedStateFile(
        initialBytes: ByteArray? = null,
    ) : DeletableEncryptedStateFile {
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

        override fun deletePhysically() {
            bytes = null
            encryptedStateMayExist = false
        }

        fun permitsEncryptionKeyCreation(): Boolean = !encryptedStateMayExist
    }

    private class MemoryEncryptedStateKeyProvider(
        var existingKey: SecretKey? = null,
        private val creationSeedBase: Int = 99,
    ) : DestructibleEncryptedStateKeyProvider {
        var creationCount = 0
            private set
        var deletionCount = 0
            private set
        var loadFailure: Exception? = null

        override fun loadExistingKey(): SecretKey? {
            loadFailure?.let { throw it }
            return existingKey
        }

        override fun createKeyIfAbsent(): SecretKey {
            existingKey?.let { return it }
            creationCount += 1
            return key(creationSeedBase + creationCount).also { existingKey = it }
        }

        override fun deleteKey() {
            deletionCount += 1
            existingKey = null
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
        const val USERNAME = "peter_01"
        const val DISPLAY_NAME = "Private Person"
        const val SESSION_CONTEXT = "synapse.private.account-session.v1"
        const val SECONDARY_SESSION_CONTEXT = "synapse.private.account-session.slot-b.v1"
    }
}
