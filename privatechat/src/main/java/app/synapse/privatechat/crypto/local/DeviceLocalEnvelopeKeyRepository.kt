package app.synapse.privatechat.crypto.local

import app.synapse.privatechat.security.storage.CryptographicallyErasableEncryptedStateStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

internal data class DeviceLocalEnvelopeKeyRecord(
    val keyId: UUID,
    val keyBytes: ByteArray,
    val createdAt: Instant,
    val leaseReleasedAt: Instant?,
    val observedAuthoritatively: Boolean,
) {
    init {
        require(keyId != NIL_UUID) { "Local envelope key identifier must be non-zero" }
        require(keyBytes.size == AES_KEY_BYTES) { "Local envelope DEK must be exactly 256 bits" }
        require(createdAt.epochSecond in 1..MAXIMUM_SUPPORTED_EPOCH_SECONDS) {
            "Local envelope key creation time is unsupported"
        }
        require(createdAt.nano in SUPPORTED_NANOSECOND_RANGE) {
            "Local envelope key creation time is not normalized"
        }
        require(leaseReleasedAt == null || !leaseReleasedAt.isBefore(createdAt)) {
            "Local envelope key lease release precedes creation"
        }
        require(
            leaseReleasedAt == null ||
                leaseReleasedAt.epochSecond in 1..MAXIMUM_SUPPORTED_EPOCH_SECONDS,
        ) {
            "Local envelope key lease release time is unsupported"
        }
        require(leaseReleasedAt == null || leaseReleasedAt.nano in SUPPORTED_NANOSECOND_RANGE) {
            "Local envelope key lease release time is not normalized"
        }
        require(!observedAuthoritatively || leaseReleasedAt != null) {
            "An authoritative local envelope key cannot remain creation-leased"
        }
    }

    fun copyForState(): DeviceLocalEnvelopeKeyRecord = copy(keyBytes = keyBytes.copyOf())

    fun destroy() = keyBytes.fill(0)
}

internal class DeviceLocalEnvelopeKeyRepository(
    private val storage: CryptographicallyErasableEncryptedStateStorage,
) {
    private val monitor = Any()
    private var loaded = false
    private var records = mutableMapOf<UUID, DeviceLocalEnvelopeKeyRecord>()

    fun insertLeasedKey(record: DeviceLocalEnvelopeKeyRecord) {
        synchronized(monitor) {
            require(record.leaseReleasedAt == null) { "A new local envelope key must begin leased" }
            require(record.keyId !in requireRecords()) { "Local envelope key identifier was reused" }
            require(records.size < MAXIMUM_KEY_RECORDS) { "Local envelope key record limit is reached" }
            val replacement = records.copyRecords()
            replacement[record.keyId] = record.copyForState()
            replaceState(replacement, cryptographicErasure = false)
        }
    }

    fun loadKey(keyId: UUID): ByteArray? = synchronized(monitor) { requireRecords()[keyId]?.keyBytes?.copyOf() }

    fun releaseLease(
        keyId: UUID,
        releasedAt: Instant,
    ) {
        synchronized(monitor) {
            val current =
                requireRecords()[keyId]
                    ?: throw DeviceLocalContentEnvelopeUnavailableException("Device-local envelope key is unavailable")
            if (current.leaseReleasedAt != null) return
            require(releasedAt.epochSecond in 1..MAXIMUM_SUPPORTED_EPOCH_SECONDS) {
                "Local envelope key lease release time is unsupported"
            }
            require(!releasedAt.isBefore(current.createdAt)) {
                "Local envelope key lease release precedes creation"
            }
            val replacement = records.copyRecords()
            replacement[keyId]?.let { leased ->
                replacement[keyId] = leased.copy(leaseReleasedAt = releasedAt)
            }
            replaceState(replacement, cryptographicErasure = false)
        }
    }

    fun reconcileRetainedKeys(
        authoritativeKeyIds: Set<UUID>,
        pendingKeyIds: Set<UUID>,
        observedAt: Instant,
    ) {
        synchronized(monitor) {
            requireRecords()
            require(observedAt.epochSecond in 1..MAXIMUM_SUPPORTED_EPOCH_SECONDS) {
                "Local envelope key observation time is unsupported"
            }
            val unknownPendingKeyIds = pendingKeyIds - records.keys
            if (unknownPendingKeyIds.isNotEmpty()) {
                throw DeviceLocalContentEnvelopeUnavailableException(
                    "A pending device-local envelope key is unavailable",
                )
            }
            // Authoritative envelopes can outlive keys that were intentionally erased at sign-out.
            // They remain unreadable, but must not hide other rooms whose device-local keys survive.
            val retainedKeyIds = (authoritativeKeyIds intersect records.keys) + pendingKeyIds
            val replacement = LinkedHashMap<UUID, DeviceLocalEnvelopeKeyRecord>()
            records.values.forEach { record ->
                val retained = record.keyId in retainedKeyIds
                val creationLeaseMayStillCommit =
                    record.leaseReleasedAt == null &&
                        observedAt.isBefore(record.createdAt.plusSeconds(MAXIMUM_CREATION_LEASE_SECONDS))
                val releasedAfterObservation = record.leaseReleasedAt?.isAfter(observedAt) == true
                val awaitingFirstAuthoritativeObservation =
                    !record.observedAuthoritatively &&
                        record.leaseReleasedAt
                            ?.plusSeconds(MAXIMUM_AUTHORITATIVE_VISIBILITY_GRACE_SECONDS)
                            ?.isAfter(observedAt) == true
                if (retained || creationLeaseMayStillCommit || releasedAfterObservation || awaitingFirstAuthoritativeObservation) {
                    val observationCanBind = !observedAt.isBefore(record.createdAt)
                    replacement[record.keyId] =
                        record.copyForState().let { copy ->
                            copy.copy(
                                leaseReleasedAt =
                                    copy.leaseReleasedAt
                                        ?: observedAt.takeIf { retained && observationCanBind },
                                observedAuthoritatively =
                                    copy.observedAuthoritatively ||
                                        (copy.keyId in authoritativeKeyIds && observationCanBind),
                            )
                        }
                }
            }
            if (!records.haveSameContentAs(replacement)) {
                val removesEnvelopeKeyMaterial = replacement.keys != records.keys
                replaceState(replacement, cryptographicErasure = removesEnvelopeKeyMaterial)
            } else {
                replacement.destroyRecords()
            }
        }
    }

    fun clearForSessionInvalidation() {
        synchronized(monitor) {
            records.destroyRecords()
            records = mutableMapOf()
            loaded = true
            storage.replaceAfterCryptographicErasure(null)
        }
    }

    private fun requireRecords(): MutableMap<UUID, DeviceLocalEnvelopeKeyRecord> {
        if (loaded) return records
        val plaintext = storage.readDecryptedState()
        records =
            plaintext?.let { encoded ->
                try {
                    DeviceLocalEnvelopeKeyCodec.decode(encoded)
                } finally {
                    encoded.fill(0)
                }
            } ?: mutableMapOf()
        loaded = true
        return records
    }

    private fun replaceState(
        replacement: MutableMap<UUID, DeviceLocalEnvelopeKeyRecord>,
        cryptographicErasure: Boolean,
    ) {
        val encoded =
            replacement.takeIf(Map<UUID, DeviceLocalEnvelopeKeyRecord>::isNotEmpty)?.let {
                DeviceLocalEnvelopeKeyCodec.encode(it)
            }
        if (cryptographicErasure) {
            records.destroyRecords()
            records = mutableMapOf()
            loaded = true
            try {
                storage.replaceAfterCryptographicErasure(encoded)
            } catch (error: Exception) {
                replacement.destroyRecords()
                try {
                    storage.replaceAfterCryptographicErasure(null)
                } catch (clearError: Exception) {
                    error.addSuppressed(clearError)
                }
                throw DeviceLocalContentEnvelopeUnavailableException(
                    "Device-local envelope key erasure commit failed",
                    error,
                )
            } finally {
                encoded?.fill(0)
            }
            records = replacement
            return
        }
        try {
            try {
                storage.replaceEncryptedState(requireNotNull(encoded))
            } catch (error: Exception) {
                replacement.destroyRecords()
                throw DeviceLocalContentEnvelopeUnavailableException(
                    "Device-local envelope key state commit failed",
                    error,
                )
            }
        } finally {
            encoded?.fill(0)
        }
        records.destroyRecords()
        records = replacement
        loaded = true
    }
}

internal object DeviceLocalEnvelopeKeyCodec {
    fun encode(records: Map<UUID, DeviceLocalEnvelopeKeyRecord>): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                require(records.size in 1..MAXIMUM_KEY_RECORDS) {
                    "Local envelope key state record count is unsupported"
                }
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(records.size)
                records.toSortedMap().forEach { (keyId, record) ->
                    require(keyId == record.keyId) { "Local envelope key state is indexed by the wrong identifier" }
                    data.writeLong(record.keyId.mostSignificantBits)
                    data.writeLong(record.keyId.leastSignificantBits)
                    data.writeInt(record.keyBytes.size)
                    data.write(record.keyBytes)
                    data.writeLong(record.createdAt.epochSecond)
                    data.writeInt(record.createdAt.nano)
                    data.writeBoolean(record.leaseReleasedAt != null)
                    record.leaseReleasedAt?.let { releasedAt ->
                        data.writeLong(releasedAt.epochSecond)
                        data.writeInt(releasedAt.nano)
                    }
                    data.writeBoolean(record.observedAuthoritatively)
                }
            }
            output.toByteArray().also { encoded ->
                require(encoded.size <= MAXIMUM_ENCODED_STATE_BYTES) { "Local envelope key state is too large" }
            }
        }

    fun decode(
        encoded: ByteArray,
        onRecordDecoded: (DeviceLocalEnvelopeKeyRecord) -> Unit = {},
    ): MutableMap<UUID, DeviceLocalEnvelopeKeyRecord> {
        if (encoded.isEmpty() || encoded.size > MAXIMUM_ENCODED_STATE_BYTES) malformedKeyState()
        val records = LinkedHashMap<UUID, DeviceLocalEnvelopeKeyRecord>()
        try {
            val input = ByteArrayInputStream(encoded)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC || data.readInt() != VERSION) malformedKeyState()
            val count = data.readInt()
            if (count !in 1..MAXIMUM_KEY_RECORDS) malformedKeyState()
            repeat(count) {
                val record = data.readLocalEnvelopeKeyRecord()
                if (records.containsKey(record.keyId)) {
                    record.destroy()
                    malformedKeyState()
                }
                records[record.keyId] = record
                onRecordDecoded(record)
            }
            if (input.available() != 0) malformedKeyState()
            return records
        } catch (error: DeviceLocalContentEnvelopeUnavailableException) {
            records.destroyRecords()
            throw error
        } catch (error: Exception) {
            records.destroyRecords()
            throw DeviceLocalContentEnvelopeUnavailableException("Device-local envelope key state is malformed", error)
        }
    }

    private fun DataInputStream.readLocalEnvelopeKeyRecord(): DeviceLocalEnvelopeKeyRecord {
        val keyId = UUID(readLong(), readLong())
        val keySize = readInt()
        if (keyId == NIL_UUID || keySize != AES_KEY_BYTES) malformedKeyState()
        val keyBytes = ByteArray(keySize)
        try {
            readFully(keyBytes)
            return DeviceLocalEnvelopeKeyRecord(
                keyId = keyId,
                keyBytes = keyBytes,
                createdAt = readNormalizedTimestamp(),
                leaseReleasedAt = if (readBoolean()) readNormalizedTimestamp() else null,
                observedAuthoritatively = readBoolean(),
            )
        } catch (error: Exception) {
            keyBytes.fill(0)
            throw error
        }
    }

    private fun DataInputStream.readNormalizedTimestamp(): Instant {
        val epochSecond = readLong()
        val nano = readInt()
        if (
            epochSecond !in 1..MAXIMUM_SUPPORTED_EPOCH_SECONDS ||
            nano !in SUPPORTED_NANOSECOND_RANGE
        ) {
            malformedKeyState()
        }
        return Instant.ofEpochSecond(epochSecond, nano.toLong())
    }
}

private fun Map<UUID, DeviceLocalEnvelopeKeyRecord>.copyRecords(): MutableMap<UUID, DeviceLocalEnvelopeKeyRecord> =
    entries.associateTo(LinkedHashMap()) { (keyId, record) -> keyId to record.copyForState() }

private fun MutableMap<UUID, DeviceLocalEnvelopeKeyRecord>.destroyRecords() {
    values.forEach(DeviceLocalEnvelopeKeyRecord::destroy)
    clear()
}

private fun Map<UUID, DeviceLocalEnvelopeKeyRecord>.haveSameContentAs(other: Map<UUID, DeviceLocalEnvelopeKeyRecord>): Boolean =
    size == other.size &&
        all { (keyId, record) ->
            other[keyId]?.let { candidate ->
                record.createdAt == candidate.createdAt &&
                    record.leaseReleasedAt == candidate.leaseReleasedAt &&
                    record.observedAuthoritatively == candidate.observedAuthoritatively &&
                    record.keyBytes.contentEquals(candidate.keyBytes)
            } == true
        }

private fun malformedKeyState(): Nothing =
    throw DeviceLocalContentEnvelopeUnavailableException("Device-local envelope key state is malformed")

internal const val DEVICE_LOCAL_ENVELOPE_KEY_STATE_MAXIMUM_BYTES = 512 * 1_024
private const val MAGIC = 0x534c454b
private const val VERSION = 1
private const val AES_KEY_BYTES = 32
private const val MAXIMUM_KEY_RECORDS = 2_500
private const val MAXIMUM_ENCODED_STATE_BYTES = DEVICE_LOCAL_ENVELOPE_KEY_STATE_MAXIMUM_BYTES
private const val MAXIMUM_CREATION_LEASE_SECONDS = 5 * 60L
private const val MAXIMUM_AUTHORITATIVE_VISIBILITY_GRACE_SECONDS = 5 * 60L
private const val MAXIMUM_SUPPORTED_EPOCH_SECONDS = 253_402_300_799L
private val SUPPORTED_NANOSECOND_RANGE = 0..999_999_999
private val NIL_UUID = UUID(0L, 0L)
