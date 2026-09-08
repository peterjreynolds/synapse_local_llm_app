package app.synapse.privatechat.crypto.local

import app.synapse.privatechat.security.storage.CryptographicallyErasableEncryptedStateStorage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class PerEnvelopeDeviceLocalContentEnvelopeCipherTest {
    @Test
    fun purgeErasesOnlyObsoleteEnvelopeKeyAndRetainsActiveEnvelope() {
        val storage = MemoryRotatingStateStorage()
        val clock = MutableClock(Instant.parse("2026-08-25T00:00:00Z"))
        val cipher = cipher(storage, clock)
        val obsoletePlaintext = "obsolete private payload".encodeToByteArray()
        val retainedPlaintext = "retained private payload".encodeToByteArray()
        val obsolete = cipher.encryptLocalEnvelope(obsoletePlaintext)
        cipher.markEnvelopeDurablyReferenced(obsolete)
        val retained = cipher.encryptLocalEnvelope(retainedPlaintext)
        cipher.markEnvelopeDurablyReferenced(retained)
        cipher.reconcileRetainedEnvelopeKeys(
            authoritativeCiphertexts = listOf(obsolete, retained),
            pendingCiphertexts = emptyList(),
            observedAt = clock.instant(),
        )

        clock.advanceSeconds(1)
        cipher.reconcileRetainedEnvelopeKeys(
            authoritativeCiphertexts = listOf(retained),
            pendingCiphertexts = emptyList(),
            observedAt = clock.instant(),
        )

        assertThrows(DeviceLocalContentEnvelopeUnavailableException::class.java) {
            cipher.decryptLocalEnvelope(obsolete)
        }
        assertArrayEquals(retainedPlaintext, cipher.decryptLocalEnvelope(retained))
        assertEquals(1, storage.cryptographicErasureCount)
    }

    @Test
    fun pendingEnvelopeSurvivesReconcileAndExactRetryAcrossRestart() {
        val storage = MemoryRotatingStateStorage()
        val clock = MutableClock(Instant.parse("2026-08-25T01:00:00Z"))
        val firstProcess = cipher(storage, clock)
        val plaintext = "pending exact request".encodeToByteArray()
        val pending = firstProcess.encryptLocalEnvelope(plaintext)
        firstProcess.markEnvelopeDurablyReferenced(pending)
        firstProcess.reconcileRetainedEnvelopeKeys(
            authoritativeCiphertexts = emptyList(),
            pendingCiphertexts = listOf(pending),
            observedAt = clock.instant(),
        )

        val restarted = cipher(storage, clock)
        assertArrayEquals(plaintext, restarted.decryptLocalEnvelope(pending))
        restarted.reconcileRetainedEnvelopeKeys(
            authoritativeCiphertexts = emptyList(),
            pendingCiphertexts = listOf(pending),
            observedAt = clock.instant(),
        )
        assertArrayEquals(plaintext, restarted.decryptLocalEnvelope(pending))
    }

    @Test
    fun erasedAuthoritativeKeyDoesNotHideAnEnvelopeWhoseKeyStillExists() {
        val retainedStorage = MemoryRotatingStateStorage()
        val erasedStorage = MemoryRotatingStateStorage()
        val clock = MutableClock(Instant.parse("2026-08-25T01:30:00Z"))
        val retainedCipher = cipher(retainedStorage, clock)
        val erasedCipher = cipher(erasedStorage, clock)
        val retainedPlaintext = "retained room title".encodeToByteArray()
        val retainedEnvelope = retainedCipher.encryptLocalEnvelope(retainedPlaintext)
        retainedCipher.markEnvelopeDurablyReferenced(retainedEnvelope)
        val erasedEnvelope = erasedCipher.encryptLocalEnvelope("erased room title".encodeToByteArray())
        erasedCipher.markEnvelopeDurablyReferenced(erasedEnvelope)

        retainedCipher.reconcileRetainedEnvelopeKeys(
            authoritativeCiphertexts = listOf(retainedEnvelope, erasedEnvelope),
            pendingCiphertexts = emptyList(),
            observedAt = clock.instant(),
        )

        assertArrayEquals(retainedPlaintext, retainedCipher.decryptLocalEnvelope(retainedEnvelope))
        assertThrows(DeviceLocalContentEnvelopeUnavailableException::class.java) {
            retainedCipher.decryptLocalEnvelope(erasedEnvelope)
        }
        assertThrows(DeviceLocalContentEnvelopeUnavailableException::class.java) {
            retainedCipher.reconcileRetainedEnvelopeKeys(
                authoritativeCiphertexts = listOf(retainedEnvelope),
                pendingCiphertexts = listOf(erasedEnvelope),
                observedAt = clock.instant(),
            )
        }
    }

    @Test
    fun pollSnapshotTakenBeforePendingCommitCannotEraseCreationLease() {
        val storage = MemoryRotatingStateStorage()
        val clock = MutableClock(Instant.parse("2026-08-25T02:00:00Z"))
        val cipher = cipher(storage, clock)
        val observedAt = clock.instant()
        val plaintext = "interleaved pending request".encodeToByteArray()
        val pending = cipher.encryptLocalEnvelope(plaintext)

        cipher.reconcileRetainedEnvelopeKeys(
            authoritativeCiphertexts = emptyList(),
            pendingCiphertexts = emptyList(),
            observedAt = observedAt,
        )
        cipher.markEnvelopeDurablyReferenced(pending)
        cipher.reconcileRetainedEnvelopeKeys(
            authoritativeCiphertexts = emptyList(),
            pendingCiphertexts = listOf(pending),
            observedAt = clock.instant(),
        )

        assertArrayEquals(plaintext, cipher.decryptLocalEnvelope(pending))
    }

    @Test
    fun sessionInvalidationDestroysEveryEnvelopeKey() {
        val storage = MemoryRotatingStateStorage()
        val cipher = cipher(storage, MutableClock(Instant.parse("2026-08-25T03:00:00Z")))
        val envelope = cipher.encryptLocalEnvelope("session-bound".encodeToByteArray())
        cipher.markEnvelopeDurablyReferenced(envelope)

        cipher.clearForSessionInvalidation()

        assertThrows(DeviceLocalContentEnvelopeUnavailableException::class.java) {
            cipher.decryptLocalEnvelope(envelope)
        }
        assertEquals(1, storage.cryptographicErasureCount)
    }

    @Test
    fun malformedTrailingStateZeroesEveryPreviouslyDecodedDek() {
        val createdAt = Instant.parse("2026-08-25T04:00:00Z")
        val sourceRecords =
            listOf(
                DeviceLocalEnvelopeKeyRecord(
                    keyId = UUID.fromString("10000000-0000-0000-0000-000000000001"),
                    keyBytes = ByteArray(32) { 1 },
                    createdAt = createdAt,
                    leaseReleasedAt = createdAt.plusSeconds(1),
                    observedAuthoritatively = true,
                ),
                DeviceLocalEnvelopeKeyRecord(
                    keyId = UUID.fromString("10000000-0000-0000-0000-000000000002"),
                    keyBytes = ByteArray(32) { 2 },
                    createdAt = createdAt,
                    leaseReleasedAt = createdAt.plusSeconds(2),
                    observedAuthoritatively = true,
                ),
            )
        val encoded =
            DeviceLocalEnvelopeKeyCodec.encode(
                sourceRecords.associateBy(DeviceLocalEnvelopeKeyRecord::keyId),
            ) + byteArrayOf(1)
        val decodedRecords = mutableListOf<DeviceLocalEnvelopeKeyRecord>()

        assertThrows(DeviceLocalContentEnvelopeUnavailableException::class.java) {
            DeviceLocalEnvelopeKeyCodec.decode(encoded, decodedRecords::add)
        }

        assertEquals(2, decodedRecords.size)
        assertTrue(decodedRecords.all { record -> record.keyBytes.all { byte -> byte == 0.toByte() } })
        sourceRecords.forEach(DeviceLocalEnvelopeKeyRecord::destroy)
        encoded.fill(0)
    }

    @Test
    fun persistedKeyRecordRejectsImpossibleAuthenticatedLifecycleState() {
        val createdAt = Instant.parse("2026-08-25T05:00:00Z")

        assertThrows(IllegalArgumentException::class.java) {
            DeviceLocalEnvelopeKeyRecord(
                keyId = UUID(0, 0),
                keyBytes = ByteArray(32),
                createdAt = createdAt,
                leaseReleasedAt = null,
                observedAuthoritatively = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceLocalEnvelopeKeyRecord(
                keyId = UUID.randomUUID(),
                keyBytes = ByteArray(31),
                createdAt = createdAt,
                leaseReleasedAt = null,
                observedAuthoritatively = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceLocalEnvelopeKeyRecord(
                keyId = UUID.randomUUID(),
                keyBytes = ByteArray(32),
                createdAt = createdAt,
                leaseReleasedAt = createdAt.minusNanos(1),
                observedAuthoritatively = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceLocalEnvelopeKeyRecord(
                keyId = UUID.randomUUID(),
                keyBytes = ByteArray(32),
                createdAt = createdAt,
                leaseReleasedAt = null,
                observedAuthoritatively = true,
            )
        }
    }

    private fun cipher(
        storage: MemoryRotatingStateStorage,
        clock: Clock,
    ): PerEnvelopeDeviceLocalContentEnvelopeCipher =
        PerEnvelopeDeviceLocalContentEnvelopeCipher(
            keyRepository = DeviceLocalEnvelopeKeyRepository(storage),
            clock = clock,
        )

    private class MemoryRotatingStateStorage : CryptographicallyErasableEncryptedStateStorage {
        private var state: ByteArray? = null
        var cryptographicErasureCount = 0
            private set

        override fun readDecryptedState(): ByteArray? = state?.copyOf()

        override fun replaceEncryptedState(plaintext: ByteArray) {
            state = plaintext.copyOf()
        }

        override fun replaceAfterCryptographicErasure(retainedPlaintext: ByteArray?) {
            cryptographicErasureCount += 1
            state?.fill(0)
            state = retainedPlaintext?.copyOf()
        }

        override fun deletePhysically() {
            replaceAfterCryptographicErasure(null)
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
