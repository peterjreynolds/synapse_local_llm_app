package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.local.DeviceLocalEncryptedPayloadCacheStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateDecryptedPayloadCacheRepositoryTest {
    @Test
    fun restartLoadsPersistedPlaintextWithoutRepeatingSignalDecryption() {
        val storage = RecordingPayloadCacheStorage()
        val session = authenticatedSession()
        val descriptor = messageDescriptor(MESSAGE_ID, expiresAt = NOW.plusSeconds(300))
        val plaintext = "restart-safe encrypted history".encodeToByteArray()
        PrivateDecryptedPayloadCacheRepository(storage).persistPlaintext(session, descriptor, plaintext, NOW)

        val restartedRepository = PrivateDecryptedPayloadCacheRepository(storage)

        assertArrayEquals(plaintext, restartedRepository.loadPlaintext(session, descriptor, NOW.plusSeconds(1)))
        assertEquals(1, storage.additiveReplaceCount)
        assertEquals(0, storage.purgeReplaceCount)
    }

    @Test
    fun authoritativeAbsenceRotatesTheCacheKeyBeforeRetainingOtherContent() {
        val storage = RecordingPayloadCacheStorage()
        val repository = PrivateDecryptedPayloadCacheRepository(storage)
        val session = authenticatedSession()
        val removed = messageDescriptor(MESSAGE_ID, expiresAt = NOW.plusSeconds(300))
        val retained = messageDescriptor(SECOND_MESSAGE_ID, expiresAt = NOW.plusSeconds(600))
        repository.persistPlaintext(session, removed, "remove".encodeToByteArray(), NOW)
        repository.persistPlaintext(session, retained, "retain".encodeToByteArray(), NOW)

        repository.reconcileAuthoritativePayloads(session, listOf(retained), NOW.plusSeconds(1))

        assertNull(repository.loadPlaintext(session, removed, NOW.plusSeconds(1)))
        assertArrayEquals(
            "retain".encodeToByteArray(),
            repository.loadPlaintext(session, retained, NOW.plusSeconds(1)),
        )
        assertEquals(1, storage.purgeReplaceCount)
        assertEquals(1, PrivateDecryptedPayloadCacheCodec.decode(requireNotNull(storage.encodedState)).entries.size)
    }

    @Test
    fun expiredSessionCryptographicallyErasesStateAndCannotWriteReplacement() {
        val storage = RecordingPayloadCacheStorage()
        val repository = PrivateDecryptedPayloadCacheRepository(storage)
        val session = authenticatedSession(expiresAt = NOW.plusSeconds(10))
        val descriptor = messageDescriptor(MESSAGE_ID, expiresAt = NOW.plusSeconds(300))
        repository.persistPlaintext(session, descriptor, "temporary".encodeToByteArray(), NOW)

        assertNull(repository.loadPlaintext(session, descriptor, NOW.plusSeconds(10)))
        assertNull(storage.encodedState)
        assertThrows(PrivateDecryptedPayloadCacheUnavailableException::class.java) {
            repository.persistPlaintext(session, descriptor, "forbidden".encodeToByteArray(), NOW.plusSeconds(10))
        }
        assertEquals(2, storage.purgeReplaceCount)
    }

    @Test
    fun preCommitPurgeFailureDropsMemoryAndForcesCompleteCryptographicClear() {
        assertPurgeFailureDropsAllPlaintext(PurgeFailureTiming.BEFORE_COMMIT)
    }

    @Test
    fun postCommitCleanupFailureDropsMemoryAndForcesCompleteCryptographicClear() {
        assertPurgeFailureDropsAllPlaintext(PurgeFailureTiming.AFTER_COMMIT)
    }

    private fun assertPurgeFailureDropsAllPlaintext(failureTiming: PurgeFailureTiming) {
        val storage = RecordingPayloadCacheStorage()
        val repository = PrivateDecryptedPayloadCacheRepository(storage)
        val session = authenticatedSession()
        val removed = messageDescriptor(MESSAGE_ID, expiresAt = NOW.plusSeconds(300))
        val retained = messageDescriptor(SECOND_MESSAGE_ID, expiresAt = NOW.plusSeconds(600))
        repository.persistPlaintext(session, removed, "remove".encodeToByteArray(), NOW)
        repository.persistPlaintext(session, retained, "retain".encodeToByteArray(), NOW)
        storage.nextPurgeFailure = failureTiming

        assertThrows(IllegalStateException::class.java) {
            repository.reconcileAuthoritativePayloads(session, listOf(retained), NOW.plusSeconds(1))
        }

        assertNull(repository.loadPlaintext(session, removed, NOW.plusSeconds(1)))
        assertNull(repository.loadPlaintext(session, retained, NOW.plusSeconds(1)))
        assertNull(storage.encodedState)
        assertEquals(2, storage.purgeReplaceCount)
    }

    private fun authenticatedSession(expiresAt: Instant = NOW.plusSeconds(3_600)): PrivateChatAuthenticatedSession =
        PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
            accountId = ACCOUNT_ID,
            transportDeviceId = DEVICE_ID,
            signalDeviceId = SignalDeviceId.fromWire(7),
            authenticationUsername = "peter_01",
            accessToken = "header.payload.signature-material",
            expiresAt = expiresAt,
        )

    private fun messageDescriptor(
        messageId: UUID,
        expiresAt: Instant,
    ): PrivateAuthoritativeEncryptedPayload =
        PrivateAuthoritativeEncryptedPayload(
            key = PrivateCachedPayloadKey(PrivateCachedPayloadKind.MESSAGE, messageId, revision = 0),
            roomId = ROOM_ID,
            parentMessageId = messageId,
            fingerprint = PrivateEncryptedPayloadFingerprint.fromCiphertext(messageId.toString().encodeToByteArray()),
            expiresAt = expiresAt,
        )

    private class RecordingPayloadCacheStorage : DeviceLocalEncryptedPayloadCacheStorage {
        var encodedState: ByteArray? = null
        var additiveReplaceCount = 0
        var purgeReplaceCount = 0
        var nextPurgeFailure: PurgeFailureTiming? = null

        override fun readDecryptedState(): ByteArray? = encodedState?.copyOf()

        override fun replaceEncryptedState(plaintext: ByteArray) {
            additiveReplaceCount += 1
            encodedState = plaintext.copyOf()
        }

        override fun replaceAfterPurge(retainedPlaintext: ByteArray?) {
            purgeReplaceCount += 1
            val failureTiming = nextPurgeFailure
            nextPurgeFailure = null
            if (failureTiming == PurgeFailureTiming.BEFORE_COMMIT) {
                error("simulated pre-commit purge failure")
            }
            encodedState = retainedPlaintext?.copyOf()
            if (failureTiming == PurgeFailureTiming.AFTER_COMMIT) {
                error("simulated post-commit cleanup failure")
            }
        }

        override fun deletePhysically() {
            error("Repository purges must use key rotation")
        }
    }

    private enum class PurgeFailureTiming {
        BEFORE_COMMIT,
        AFTER_COMMIT,
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-25T12:00:00Z")
        val ACCOUNT_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val DEVICE_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val ROOM_ID: UUID = UUID.fromString("30000000-0000-4000-8000-000000000003")
        val MESSAGE_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000004")
        val SECOND_MESSAGE_ID: UUID = UUID.fromString("50000000-0000-4000-8000-000000000005")
    }
}
