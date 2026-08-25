package app.synapse.privatechat.crypto.local

import app.synapse.privatechat.security.storage.Aes256GcmEncryptedStateCipher
import app.synapse.privatechat.security.storage.DeletableEncryptedStateFile
import app.synapse.privatechat.security.storage.DestructibleEncryptedStateKeyProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class DeviceLocalEncryptedPayloadCacheStorageTest {
    @Test
    fun wholeStateRoundTripsWithoutPersistingReadablePlaintext() {
        val file = MemoryDeletableEncryptedStateFile()
        val primaryKeys = MemoryDestructibleKeyProvider(seedBase = 10)
        val storage = storage(file, primaryKeys, MemoryDestructibleKeyProvider(seedBase = 20))
        val plaintext = "bounded decrypted payload cache".encodeToByteArray()

        storage.replaceEncryptedState(plaintext)

        assertFalse(requireNotNull(file.bytes).containsSubsequence(plaintext))
        val firstRead = requireNotNull(storage.readDecryptedState())
        assertArrayEquals(plaintext, firstRead)
        firstRead.fill(0)
        assertArrayEquals(plaintext, storage.readDecryptedState())
        assertEquals(1, primaryKeys.creationCount)
    }

    @Test
    fun purgeRotationCommitsFreshSlotAndDestroysRetiredKey() {
        val file = MemoryDeletableEncryptedStateFile()
        val primaryKeys = MemoryDestructibleKeyProvider(seedBase = 30)
        val secondaryKeys = MemoryDestructibleKeyProvider(seedBase = 40)
        val storage = storage(file, primaryKeys, secondaryKeys)
        storage.replaceEncryptedState("content to purge".encodeToByteArray())
        val retiredEncodedState = requireNotNull(file.bytes)
        val retiredKey = requireNotNull(primaryKeys.existingKey)

        storage.replaceAfterPurge("retained content only".encodeToByteArray())

        assertNull(primaryKeys.existingKey)
        assertNotNull(secondaryKeys.existingKey)
        assertArrayEquals("retained content only".encodeToByteArray(), storage.readDecryptedState())
        assertThrows(IllegalStateException::class.java) {
            decryptEnvelopeWithKey(requireNotNull(file.bytes), retiredKey, PRIMARY_CONTEXT)
        }

        val staleFile = MemoryDeletableEncryptedStateFile(retiredEncodedState)
        val missingRetiredKey = MemoryDestructibleKeyProvider(seedBase = 50)
        assertThrows(DeviceLocalPayloadCacheUnavailableException::class.java) {
            storage(
                staleFile,
                missingRetiredKey,
                MemoryDestructibleKeyProvider(seedBase = 60),
            ).readDecryptedState()
        }
        assertEquals(0, missingRetiredKey.creationCount)
        assertNull(staleFile.bytes)
    }

    @Test
    fun tamperTruncationAndInvalidSlotCryptographicallyPurgeState() {
        val validState = committedState("authenticated cache state")
        val tampered = validState.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() }
        val invalidSlot = validState.copyOf().also { bytes -> bytes[4] = 99 }

        assertReadFailurePurges(tampered)
        assertReadFailurePurges(validState.copyOf(8))
        assertReadFailurePurges(invalidSlot)
    }

    @Test
    fun wrongActiveKeyNeverCreatesReplacementAndPurgesUnreadableState() {
        val validState = committedState("unreadable cache state")
        val file = MemoryDeletableEncryptedStateFile(validState)
        val wrongPrimaryKeys = MemoryDestructibleKeyProvider(seedBase = 70, existingKey = key(777))
        val secondaryKeys = MemoryDestructibleKeyProvider(seedBase = 80)

        assertThrows(DeviceLocalPayloadCacheUnavailableException::class.java) {
            storage(file, wrongPrimaryKeys, secondaryKeys).readDecryptedState()
        }

        assertEquals(0, wrongPrimaryKeys.creationCount)
        assertNull(wrongPrimaryKeys.existingKey)
        assertNull(secondaryKeys.existingKey)
        assertNull(file.bytes)
    }

    @Test
    fun failedAtomicRotationPreservesPreviousStateAndDestroysUncommittedKey() {
        val file = MemoryDeletableEncryptedStateFile()
        val primaryKeys = MemoryDestructibleKeyProvider(seedBase = 90)
        val secondaryKeys = MemoryDestructibleKeyProvider(seedBase = 100)
        val storage = storage(file, primaryKeys, secondaryKeys)
        storage.replaceEncryptedState("previous cache snapshot".encodeToByteArray())
        val committedCiphertext = requireNotNull(file.bytes)
        val replacement = "retained replacement snapshot".encodeToByteArray()
        file.failNextReplace = true

        assertThrows(DeviceLocalPayloadCacheUnavailableException::class.java) {
            storage.replaceAfterPurge(replacement)
        }

        assertArrayEquals(committedCiphertext, file.bytes)
        assertNotNull(primaryKeys.existingKey)
        assertNull(secondaryKeys.existingKey)
        assertArrayEquals("retained replacement snapshot".encodeToByteArray(), replacement)
        assertTrue(requireNotNull(file.failedReplacementReference).all { it == 0.toByte() })
        assertArrayEquals("previous cache snapshot".encodeToByteArray(), storage.readDecryptedState())
    }

    @Test
    fun restartCompletesRetiredKeyCleanupAfterCommittedRotation() {
        val file = MemoryDeletableEncryptedStateFile()
        val primaryKeys = MemoryDestructibleKeyProvider(seedBase = 110)
        val secondaryKeys = MemoryDestructibleKeyProvider(seedBase = 120)
        val storage = storage(file, primaryKeys, secondaryKeys)
        storage.replaceEncryptedState("old snapshot".encodeToByteArray())
        primaryKeys.failNextDelete = true

        assertThrows(DeviceLocalPayloadCacheUnavailableException::class.java) {
            storage.replaceAfterPurge("new snapshot".encodeToByteArray())
        }

        assertNotNull(primaryKeys.existingKey)
        assertNotNull(secondaryKeys.existingKey)
        val restarted = storage(file, primaryKeys, secondaryKeys)
        assertArrayEquals("new snapshot".encodeToByteArray(), restarted.readDecryptedState())
        assertNull(primaryKeys.existingKey)
        assertNotNull(secondaryKeys.existingKey)
    }

    @Test
    fun explicitDeleteDestroysBothKeysBeforePhysicalFileDeletion() {
        val file = MemoryDeletableEncryptedStateFile()
        val primaryKeys = MemoryDestructibleKeyProvider(seedBase = 130)
        val secondaryKeys = MemoryDestructibleKeyProvider(seedBase = 140, existingKey = key(141))
        val storage = storage(file, primaryKeys, secondaryKeys)
        storage.replaceEncryptedState("delete me".encodeToByteArray())

        storage.deletePhysically()
        storage.deletePhysically()

        assertNull(primaryKeys.existingKey)
        assertNull(secondaryKeys.existingKey)
        assertNull(file.bytes)
        assertNull(storage.readDecryptedState())
        assertTrue(file.deleteCount >= 2)
    }

    @Test
    fun keyDeletionFailureStillAttemptsPhysicalDeletionWithoutReportingSuccess() {
        val file = MemoryDeletableEncryptedStateFile()
        val primaryKeys = MemoryDestructibleKeyProvider(seedBase = 150)
        val secondaryKeys = MemoryDestructibleKeyProvider(seedBase = 160)
        val storage = storage(file, primaryKeys, secondaryKeys)
        storage.replaceEncryptedState("must remain until erasure retries".encodeToByteArray())
        val deletionCountBeforeFailure = file.deleteCount
        primaryKeys.failNextDelete = true

        assertThrows(DeviceLocalPayloadCacheUnavailableException::class.java) {
            storage.deletePhysically()
        }

        assertNull(file.bytes)
        assertEquals(deletionCountBeforeFailure + 1, file.deleteCount)
        assertNotNull(primaryKeys.existingKey)

        storage.deletePhysically()
        assertNull(primaryKeys.existingKey)
    }

    @Test
    fun plaintextAndCiphertextBoundsFailClosed() {
        val primaryKeys = MemoryDestructibleKeyProvider(seedBase = 170)
        val storage =
            storage(
                MemoryDeletableEncryptedStateFile(),
                primaryKeys,
                MemoryDestructibleKeyProvider(seedBase = 180),
            )

        assertThrows(IllegalArgumentException::class.java) {
            storage.replaceEncryptedState(ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.replaceAfterPurge(ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.replaceEncryptedState(
                ByteArray(RotatingDeviceLocalEncryptedPayloadCacheStorage.MAX_PLAINTEXT_BYTES + 1),
            )
        }
        assertEquals(0, primaryKeys.creationCount)

        val oversizedFile =
            MemoryDeletableEncryptedStateFile(
                ByteArray(RotatingDeviceLocalEncryptedPayloadCacheStorage.MAX_ENCODED_STATE_BYTES + 1),
            )
        val oversizedPrimaryKeys = MemoryDestructibleKeyProvider(seedBase = 190, existingKey = key(191))
        assertThrows(DeviceLocalPayloadCacheUnavailableException::class.java) {
            storage(
                oversizedFile,
                oversizedPrimaryKeys,
                MemoryDestructibleKeyProvider(seedBase = 200),
            ).readDecryptedState()
        }
        assertNull(oversizedPrimaryKeys.existingKey)
        assertNull(oversizedFile.bytes)
    }

    private fun assertReadFailurePurges(encodedState: ByteArray) {
        val file = MemoryDeletableEncryptedStateFile(encodedState)
        val primaryKeys = MemoryDestructibleKeyProvider(seedBase = 210, existingKey = key(COMMITTED_KEY_SEED))
        val secondaryKeys = MemoryDestructibleKeyProvider(seedBase = 220, existingKey = key(221))

        assertThrows(DeviceLocalPayloadCacheUnavailableException::class.java) {
            storage(file, primaryKeys, secondaryKeys).readDecryptedState()
        }

        assertNull(primaryKeys.existingKey)
        assertNull(secondaryKeys.existingKey)
        assertNull(file.bytes)
    }

    private fun committedState(plaintext: String): ByteArray {
        val file = MemoryDeletableEncryptedStateFile()
        val primaryKeys =
            MemoryDestructibleKeyProvider(
                seedBase = COMMITTED_KEY_SEED,
                firstCreatedKeySeed = COMMITTED_KEY_SEED,
            )
        storage(file, primaryKeys, MemoryDestructibleKeyProvider(seedBase = 230))
            .replaceEncryptedState(plaintext.encodeToByteArray())
        return requireNotNull(file.bytes)
    }

    private fun decryptEnvelopeWithKey(
        encodedState: ByteArray,
        key: SecretKey,
        authenticatedContext: String,
    ): ByteArray =
        Aes256GcmEncryptedStateCipher(
            keyProvider = MemoryFixedKeyProvider(key),
            keyCreationAllowed = { false },
            authenticatedContext = authenticatedContext,
        ).decrypt(encodedState.copyOfRange(OUTER_HEADER_BYTES, encodedState.size))

    private fun storage(
        file: MemoryDeletableEncryptedStateFile,
        primaryKeys: MemoryDestructibleKeyProvider,
        secondaryKeys: MemoryDestructibleKeyProvider,
    ): DeviceLocalEncryptedPayloadCacheStorage =
        RotatingDeviceLocalEncryptedPayloadCacheStorage(
            encryptedStateFile = file,
            primaryKeySlot = DeviceLocalPayloadCacheKeySlot(primaryKeys, PRIMARY_CONTEXT),
            secondaryKeySlot = DeviceLocalPayloadCacheKeySlot(secondaryKeys, SECONDARY_CONTEXT),
        )

    private class MemoryDeletableEncryptedStateFile(
        initialBytes: ByteArray? = null,
    ) : DeletableEncryptedStateFile {
        var bytes: ByteArray? = initialBytes?.copyOf()
            private set
        var deleteCount: Int = 0
            private set
        var failNextReplace: Boolean = false
        var failedReplacementReference: ByteArray? = null
            private set

        override fun read(maximumBytes: Int): ByteArray? {
            val snapshot = bytes ?: return null
            if (snapshot.size > maximumBytes) error("simulated bounded read rejection")
            return snapshot.copyOf()
        }

        override fun replace(ciphertext: ByteArray) {
            if (failNextReplace) {
                failNextReplace = false
                failedReplacementReference = ciphertext
                error("simulated atomic replace failure")
            }
            bytes = ciphertext.copyOf()
        }

        override fun deletePhysically() {
            bytes = null
            deleteCount += 1
        }
    }

    private class MemoryDestructibleKeyProvider(
        private val seedBase: Int,
        existingKey: SecretKey? = null,
        private val firstCreatedKeySeed: Int = seedBase,
    ) : DestructibleEncryptedStateKeyProvider {
        var existingKey: SecretKey? = existingKey
            private set
        var creationCount: Int = 0
            private set
        var deletionCount: Int = 0
            private set
        var failNextDelete: Boolean = false

        override fun loadExistingKey(): SecretKey? = existingKey

        override fun createKeyIfAbsent(): SecretKey {
            existingKey?.let { return it }
            val seed = if (creationCount == 0) firstCreatedKeySeed else seedBase + creationCount
            creationCount += 1
            return key(seed).also { existingKey = it }
        }

        override fun deleteKey() {
            deletionCount += 1
            if (failNextDelete) {
                failNextDelete = false
                error("simulated key deletion failure")
            }
            existingKey = null
        }
    }

    private class MemoryFixedKeyProvider(
        private val key: SecretKey,
    ) : app.synapse.privatechat.security.storage.EncryptedStateKeyProvider {
        override fun loadExistingKey(): SecretKey = key

        override fun createKeyIfAbsent(): SecretKey = error("Fixed key must not be regenerated")
    }

    private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
        indices.any { start ->
            start + candidate.size <= size && copyOfRange(start, start + candidate.size).contentEquals(candidate)
        }

    private companion object {
        const val PRIMARY_CONTEXT = "synapse.private.cache-test.slot-a.v1"
        const val SECONDARY_CONTEXT = "synapse.private.cache-test.slot-b.v1"
        const val OUTER_HEADER_BYTES = 5
        const val COMMITTED_KEY_SEED = 240

        fun key(seed: Int): SecretKey =
            SecretKeySpec(
                ByteArray(32) { index -> (index + seed).toByte() },
                "AES",
            )
    }
}
