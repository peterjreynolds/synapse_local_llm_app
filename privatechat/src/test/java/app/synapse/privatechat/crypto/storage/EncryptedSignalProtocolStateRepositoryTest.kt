package app.synapse.privatechat.crypto.storage

import app.synapse.privatechat.crypto.RemoteIdentityWriteOutcome
import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalPreKeyId
import app.synapse.privatechat.crypto.SignalProtocolStateAddress
import app.synapse.privatechat.crypto.SignalProtocolStateCorruptedException
import app.synapse.privatechat.crypto.StoredLocalSignalIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class EncryptedSignalProtocolStateRepositoryTest {
    @Test
    fun survivesProcessReloadWithoutPersistingPlaintext() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemorySignalStateKeyProvider(key(1))
        val cipher = productionCipher(file, keyProvider)
        val repository = EncryptedSignalProtocolStateRepository(file, cipher)
        val session = "private session marker".encodeToByteArray()
        repository.insertLocalIdentityIfAbsent(localIdentity())
        repository.storeSession(REMOTE_ADDRESS, session)

        val persisted = requireNotNull(file.bytes)
        assertFalse(persisted.containsSubsequence(session))
        val reloaded = EncryptedSignalProtocolStateRepository(file, cipher)

        assertArrayEquals(session, reloaded.loadSession(REMOTE_ADDRESS))
        assertEquals(LOCAL_ADDRESS, reloaded.loadLocalIdentity()?.address)
    }

    @Test
    fun wholeSnapshotReloadsEverySignalRecordCategory() {
        val file = MemoryEncryptedStateFile()
        val cipher = productionCipher(file, MemorySignalStateKeyProvider(key(14)))
        val repository = EncryptedSignalProtocolStateRepository(file, cipher)
        val preKeyId = SignalPreKeyId.fromWire(14)

        repository.writeTransaction {
            repository.insertLocalIdentityIfAbsent(localIdentity())
            repository.storeRemoteIdentityWithoutReplacement(REMOTE_ADDRESS, byteArrayOf(1))
            repository.storeSession(REMOTE_ADDRESS, byteArrayOf(2))
            repository.storePreKey(preKeyId, byteArrayOf(3))
            repository.storeSignedPreKey(preKeyId, byteArrayOf(4))
            repository.storeKyberPreKey(preKeyId, byteArrayOf(5))
            repository.recordKyberPreKeyUse(preKeyId, preKeyId, byteArrayOf(6))
        }

        val reloaded = EncryptedSignalProtocolStateRepository(file, cipher)
        assertEquals(LOCAL_ADDRESS, reloaded.loadLocalIdentity()?.address)
        assertArrayEquals(byteArrayOf(1), reloaded.loadRemoteIdentity(REMOTE_ADDRESS))
        assertArrayEquals(byteArrayOf(2), reloaded.loadSession(REMOTE_ADDRESS))
        assertArrayEquals(byteArrayOf(3), reloaded.loadPreKey(preKeyId))
        assertArrayEquals(byteArrayOf(4), reloaded.loadSignedPreKey(preKeyId))
        assertArrayEquals(byteArrayOf(5), reloaded.loadKyberPreKey(preKeyId))
        assertFalse(reloaded.recordKyberPreKeyUse(preKeyId, preKeyId, byteArrayOf(6)))
    }

    @Test
    fun rejectsTamperedTruncatedAndWrongKeyState() {
        val file = MemoryEncryptedStateFile()
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, MemorySignalStateKeyProvider(key(2))))
        repository.storePreKey(SignalPreKeyId.fromWire(1), byteArrayOf(1, 2, 3))
        val valid = requireNotNull(file.bytes)

        val tampered = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertStateCannotLoad(tampered, key(2))
        assertStateCannotLoad(valid.copyOf(7), key(2))
        assertStateCannotLoad(valid, key(3))
    }

    @Test
    fun failedAtomicWriteRollsBackMemoryAndDurableState() {
        val file = MemoryEncryptedStateFile()
        val cipher = productionCipher(file, MemorySignalStateKeyProvider(key(4)))
        val repository = EncryptedSignalProtocolStateRepository(file, cipher)
        val original = byteArrayOf(4, 5, 6)
        repository.storeSession(REMOTE_ADDRESS, original)
        file.failNextReplace = true

        assertCorrupted { repository.storeSession(REMOTE_ADDRESS, byteArrayOf(7, 8, 9)) }
        assertArrayEquals(original, repository.loadSession(REMOTE_ADDRESS))
        assertArrayEquals(
            original,
            EncryptedSignalProtocolStateRepository(file, cipher).loadSession(REMOTE_ADDRESS),
        )
    }

    @Test
    fun transactionFailureAndCommitFailureBothRestoreThePriorSnapshot() {
        val file = MemoryEncryptedStateFile()
        val cipher = productionCipher(file, MemorySignalStateKeyProvider(key(5)))
        val repository = EncryptedSignalProtocolStateRepository(file, cipher)
        val preKeyId = SignalPreKeyId.fromWire(9)

        assertThrows(IllegalStateException::class.java) {
            repository.writeTransaction {
                repository.storePreKey(preKeyId, byteArrayOf(1))
                error("abort")
            }
        }
        assertFalse(repository.containsPreKey(preKeyId))

        file.failNextReplace = true
        assertCorrupted {
            repository.writeTransaction { repository.storePreKey(preKeyId, byteArrayOf(2)) }
        }
        assertFalse(repository.containsPreKey(preKeyId))
        assertFalse(EncryptedSignalProtocolStateRepository(file, cipher).containsPreKey(preKeyId))
    }

    @Test
    fun remoteIdentityCompareAndStoreRejectsACompetingIdentity() {
        val file = MemoryEncryptedStateFile()
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, MemorySignalStateKeyProvider(key(6))))
        val first = byteArrayOf(1, 2, 3)
        val competing = byteArrayOf(4, 5, 6)

        assertEquals(
            RemoteIdentityWriteOutcome.STORED_NEW,
            repository.storeRemoteIdentityWithoutReplacement(REMOTE_ADDRESS, first),
        )
        assertEquals(
            RemoteIdentityWriteOutcome.REPLACEMENT_REJECTED,
            repository.storeRemoteIdentityWithoutReplacement(REMOTE_ADDRESS, competing),
        )
        assertArrayEquals(first, repository.loadRemoteIdentity(REMOTE_ADDRESS))
    }

    @Test
    fun enforcesLengthBoundsBeforeMutation() {
        val file = MemoryEncryptedStateFile()
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, MemorySignalStateKeyProvider(key(7))))
        val tooLargeSession = ByteArray(1024 * 1_024 + 1)

        assertThrows(IllegalArgumentException::class.java) {
            repository.storeSession(REMOTE_ADDRESS, tooLargeSession)
        }
        assertNull(repository.loadSession(REMOTE_ADDRESS))
        assertThrows(IllegalArgumentException::class.java) {
            repository.recordKyberPreKeyUse(
                SignalPreKeyId.fromWire(1),
                SignalPreKeyId.fromWire(2),
                ByteArray(257),
            )
        }
    }

    @Test
    fun copiesMutableRecordsAtBothBoundaries() {
        val file = MemoryEncryptedStateFile()
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, MemorySignalStateKeyProvider(key(8))))
        val source = byteArrayOf(1, 2, 3)
        repository.storeSession(REMOTE_ADDRESS, source)
        source[0] = 9
        val loaded = requireNotNull(repository.loadSession(REMOTE_ADDRESS))
        loaded[1] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), repository.loadSession(REMOTE_ADDRESS))
    }

    @Test
    fun copiesListedKeysAndConsumedKyberBaseKeysAtBothBoundaries() {
        val file = MemoryEncryptedStateFile()
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, MemorySignalStateKeyProvider(key(9))))
        val signedPreKeyId = SignalPreKeyId.fromWire(4)
        val signedPreKey = byteArrayOf(1, 2, 3)
        repository.storeSignedPreKey(signedPreKeyId, signedPreKey)
        signedPreKey[0] = 9
        val listedSignedPreKey = repository.listSignedPreKeys().single()
        listedSignedPreKey[1] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), repository.loadSignedPreKey(signedPreKeyId))

        val baseKey = byteArrayOf(4, 5, 6)
        assertTrue(repository.recordKyberPreKeyUse(SignalPreKeyId.fromWire(5), signedPreKeyId, baseKey))
        baseKey[0] = 9
        assertFalse(
            repository.recordKyberPreKeyUse(
                SignalPreKeyId.fromWire(5),
                signedPreKeyId,
                byteArrayOf(4, 5, 6),
            ),
        )
    }

    @Test
    fun transactionCommitsOneWholeSnapshotAndNestedFailureRollsBackLocally() {
        val file = MemoryEncryptedStateFile()
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, MemorySignalStateKeyProvider(key(10))))

        repository.writeTransaction {
            repository.storePreKey(SignalPreKeyId.fromWire(1), byteArrayOf(1))
            try {
                repository.writeTransaction {
                    repository.storePreKey(SignalPreKeyId.fromWire(2), byteArrayOf(2))
                    error("abort nested transaction")
                }
            } catch (_: IllegalStateException) {
                // The outer transaction deliberately continues after proving nested rollback.
            }
            repository.storePreKey(SignalPreKeyId.fromWire(3), byteArrayOf(3))
        }

        assertEquals(1, file.replaceCount)
        assertTrue(repository.containsPreKey(SignalPreKeyId.fromWire(1)))
        assertFalse(repository.containsPreKey(SignalPreKeyId.fromWire(2)))
        assertTrue(repository.containsPreKey(SignalPreKeyId.fromWire(3)))
    }

    @Test
    fun missingOrInvalidatedExistingKeyNeverCreatesAReplacement() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemorySignalStateKeyProvider(key(11))
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, keyProvider))
        repository.storeSession(REMOTE_ADDRESS, byteArrayOf(1))

        keyProvider.existingKey = null
        assertCorrupted {
            EncryptedSignalProtocolStateRepository(file, productionCipher(file, keyProvider))
        }
        assertEquals(0, keyProvider.creationCount)

        keyProvider.loadFailure = IllegalStateException("simulated invalidated key")
        assertCorrupted {
            EncryptedSignalProtocolStateRepository(file, productionCipher(file, keyProvider))
        }
        assertEquals(0, keyProvider.creationCount)
    }

    @Test
    fun observedStateDeletionDoesNotReopenKeyCreation() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemorySignalStateKeyProvider(key(12))
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, keyProvider))
        repository.storeSession(REMOTE_ADDRESS, byteArrayOf(1))
        file.simulateExternalDeletion()
        keyProvider.existingKey = null

        assertCorrupted { repository.storeSession(REMOTE_ADDRESS, byteArrayOf(2)) }
        assertEquals(0, keyProvider.creationCount)
        assertArrayEquals(byteArrayOf(1), repository.loadSession(REMOTE_ADDRESS))
    }

    @Test
    fun writeTimeKeyInvalidationRollsBackMemoryAndPreservesDurableState() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemorySignalStateKeyProvider(key(13))
        val cipher = productionCipher(file, keyProvider)
        val repository = EncryptedSignalProtocolStateRepository(file, cipher)
        repository.storeSession(REMOTE_ADDRESS, byteArrayOf(1))
        keyProvider.loadFailure = IllegalStateException("simulated invalidated key")

        assertCorrupted { repository.storeSession(REMOTE_ADDRESS, byteArrayOf(2)) }
        assertArrayEquals(byteArrayOf(1), repository.loadSession(REMOTE_ADDRESS))
        assertEquals(0, keyProvider.creationCount)

        keyProvider.loadFailure = null
        assertArrayEquals(
            byteArrayOf(1),
            EncryptedSignalProtocolStateRepository(file, cipher).loadSession(REMOTE_ADDRESS),
        )
    }

    @Test
    fun emptyStoreCreatesOneKeyAndReusesItForLaterSnapshots() {
        val file = MemoryEncryptedStateFile()
        val keyProvider = MemorySignalStateKeyProvider()
        val repository = EncryptedSignalProtocolStateRepository(file, productionCipher(file, keyProvider))

        repository.storePreKey(SignalPreKeyId.fromWire(1), byteArrayOf(1))
        repository.storePreKey(SignalPreKeyId.fromWire(2), byteArrayOf(2))

        assertEquals(1, keyProvider.creationCount)
        assertEquals(2, file.replaceCount)
    }

    @Test
    fun codecRejectsTruncationTrailingBytesInvalidSizesAndCountMismatches() {
        val validEmptyState = encodeEmptyState(declaredEntries = 0)
        assertCorrupted { SignalStateCodec.decode(validEmptyState.copyOf(validEmptyState.size - 1)) }
        assertCorrupted { SignalStateCodec.decode(validEmptyState + 1) }
        assertCorrupted { SignalStateCodec.decode(encodeEmptyState(declaredEntries = 100_001)) }
        assertCorrupted { SignalStateCodec.decode(encodeEmptyState(declaredEntries = 1)) }
        assertCorrupted { SignalStateCodec.decode(encodeInvalidFirstRecordSize()) }
    }

    private fun localIdentity(): StoredLocalSignalIdentity =
        StoredLocalSignalIdentity.fromPersistence(
            address = LOCAL_ADDRESS,
            serializedIdentityKeyPair = ByteArray(64) { it.toByte() },
            registrationId = 42,
        )

    private fun assertCorrupted(block: () -> Unit) {
        assertThrows(SignalProtocolStateCorruptedException::class.java, block)
    }

    private fun assertStateCannotLoad(
        persistedState: ByteArray,
        key: SecretKey,
    ) {
        val file = MemoryEncryptedStateFile(persistedState)
        assertCorrupted {
            EncryptedSignalProtocolStateRepository(
                file,
                productionCipher(file, MemorySignalStateKeyProvider(key)),
            )
        }
    }

    private fun productionCipher(
        file: MemoryEncryptedStateFile,
        keyProvider: MemorySignalStateKeyProvider,
    ): AesGcmSignalStateCipher =
        AesGcmSignalStateCipher(
            keyProvider = keyProvider,
            keyCreationAllowed = file::permitsEncryptionKeyCreation,
        )

    private fun key(seed: Int): SecretKey = SecretKeySpec(ByteArray(32) { (it + seed).toByte() }, "AES")

    private fun encodeEmptyState(declaredEntries: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { state ->
                state.writeInt(0x53505331)
                state.writeInt(2)
                state.writeInt(declaredEntries)
                state.writeBoolean(false)
                repeat(6) { state.writeInt(0) }
            }
            output.toByteArray()
        }

    private fun encodeInvalidFirstRecordSize(): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { state ->
                state.writeInt(0x53505331)
                state.writeInt(2)
                state.writeInt(1)
                state.writeBoolean(false)
                state.writeInt(1)
                state.writeLong(REMOTE_ADDRESS.accountId.mostSignificantBits)
                state.writeLong(REMOTE_ADDRESS.accountId.leastSignificantBits)
                state.writeInt(REMOTE_ADDRESS.protocolDeviceId.raw)
                state.writeInt(0)
            }
            output.toByteArray()
        }

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
                throw SignalProtocolStateCorruptedException("Encrypted Signal state exceeds the size limit")
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

        fun simulateExternalDeletion() {
            bytes = null
        }
    }

    private class MemorySignalStateKeyProvider(
        var existingKey: SecretKey? = null,
    ) : SignalStateKeyProvider {
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
        val LOCAL_ADDRESS =
            SignalDeviceAddress.fromWire(
                "10000000-0000-4000-8000-000000000001",
                "10000000-0000-4000-8000-000000000002",
                1,
            )
        val REMOTE_ADDRESS =
            SignalProtocolStateAddress(
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                app.synapse.privatechat.crypto.SignalDeviceId
                    .fromWire(1),
            )
    }
}
