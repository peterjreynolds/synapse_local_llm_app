package app.synapse.privatechat.data.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import java.util.UUID

internal class PrivateCachedPayloadEntry(
    val descriptor: PrivateAuthoritativeEncryptedPayload,
    plaintext: ByteArray,
) {
    private val immutablePlaintext = plaintext.copyOf()

    init {
        require(plaintext.size in 1..MAXIMUM_CACHED_PAYLOAD_BYTES) {
            "Cached decrypted payload size is unsupported"
        }
    }

    fun plaintextCopy(): ByteArray = immutablePlaintext.copyOf()

    fun copyForCache(): PrivateCachedPayloadEntry = PrivateCachedPayloadEntry(descriptor, immutablePlaintext)

    fun destroy() {
        immutablePlaintext.fill(0)
    }
}

internal data class PrivateDecryptedPayloadCacheState(
    val accountId: UUID,
    val transportDeviceId: UUID,
    val entries: Map<PrivateCachedPayloadKey, PrivateCachedPayloadEntry>,
) {
    init {
        require(accountId != NIL_UUID && transportDeviceId != NIL_UUID) {
            "Decrypted payload cache identity must not be nil"
        }
        require(entries.size <= MAXIMUM_CACHED_PAYLOAD_ENTRIES) {
            "Decrypted payload cache contains too many entries"
        }
        require(entries.all { (key, entry) -> key == entry.descriptor.key }) {
            "Decrypted payload cache keys are inconsistent"
        }
    }

    fun copyForCache(): PrivateDecryptedPayloadCacheState =
        PrivateDecryptedPayloadCacheState(
            accountId = accountId,
            transportDeviceId = transportDeviceId,
            entries = entries.mapValues { (_, entry) -> entry.copyForCache() },
        )

    fun destroy() {
        entries.values.forEach(PrivateCachedPayloadEntry::destroy)
    }
}

internal object PrivateDecryptedPayloadCacheCodec {
    const val MAXIMUM_STATE_BYTES = 8 * 1_024 * 1_024
    private const val MAGIC = 0x53504331
    private const val VERSION = 1
    private const val SHA_256_BYTES = 32

    fun encode(state: PrivateDecryptedPayloadCacheState): ByteArray {
        val output = PrivatePayloadCacheBoundedOutputStream(MAXIMUM_STATE_BYTES)
        DataOutputStream(output).use { encoded ->
            encoded.writeInt(MAGIC)
            encoded.writeInt(VERSION)
            encoded.writeUuid(state.accountId)
            encoded.writeUuid(state.transportDeviceId)
            encoded.writeInt(state.entries.size)
            state.entries.toSortedMap(CACHED_PAYLOAD_KEY_ORDER).forEach { (key, entry) ->
                val descriptor = entry.descriptor
                encoded.writeInt(key.kind.wireCode)
                encoded.writeUuid(key.recordId)
                encoded.writeInt(key.revision)
                encoded.writeUuid(descriptor.roomId)
                encoded.writeBoolean(descriptor.parentMessageId != null)
                descriptor.parentMessageId?.let(encoded::writeUuid)
                encoded.writeLong(descriptor.expiresAt.epochSecond)
                encoded.write(descriptor.fingerprint.copyBytes())
                val plaintext = entry.plaintextCopy()
                try {
                    encoded.writeInt(plaintext.size)
                    encoded.write(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): PrivateDecryptedPayloadCacheState {
        if (bytes.size !in 1..MAXIMUM_STATE_BYTES) corruptCache("Decrypted payload cache state size is invalid")
        try {
            val input = ByteArrayInputStream(bytes)
            val encoded = DataInputStream(input)
            val constructedEntries = ArrayList<PrivateCachedPayloadEntry>()
            try {
                if (encoded.readInt() != MAGIC) corruptCache("Decrypted payload cache header is invalid")
                if (encoded.readInt() != VERSION) corruptCache("Decrypted payload cache version is unsupported")
                val accountId = encoded.readUuid()
                val transportDeviceId = encoded.readUuid()
                val entryCount = encoded.readInt()
                if (entryCount !in 1..MAXIMUM_CACHED_PAYLOAD_ENTRIES) {
                    corruptCache("Decrypted payload cache entry count is invalid")
                }
                val entries = LinkedHashMap<PrivateCachedPayloadKey, PrivateCachedPayloadEntry>(entryCount)
                repeat(entryCount) {
                    val key =
                        PrivateCachedPayloadKey(
                            kind = PrivateCachedPayloadKind.fromWire(encoded.readInt()),
                            recordId = encoded.readUuid(),
                            revision = encoded.readInt(),
                        )
                    val roomId = encoded.readUuid()
                    val parentMessageId = if (encoded.readBoolean()) encoded.readUuid() else null
                    val expiresAt = Instant.ofEpochSecond(encoded.readLong())
                    val digest = ByteArray(SHA_256_BYTES).also(encoded::readFully)
                    val fingerprint = PrivateEncryptedPayloadFingerprint.fromPersistence(digest)
                    digest.fill(0)
                    val plaintextSize = encoded.readInt()
                    if (plaintextSize !in 1..MAXIMUM_CACHED_PAYLOAD_BYTES) {
                        corruptCache("Cached decrypted payload size is invalid")
                    }
                    val plaintext = ByteArray(plaintextSize).also(encoded::readFully)
                    try {
                        val descriptor =
                            PrivateAuthoritativeEncryptedPayload(
                                key = key,
                                roomId = roomId,
                                parentMessageId = parentMessageId,
                                fingerprint = fingerprint,
                                expiresAt = expiresAt,
                            )
                        val entry = PrivateCachedPayloadEntry(descriptor, plaintext)
                        constructedEntries += entry
                        if (entries.put(key, entry) != null) {
                            corruptCache("Decrypted payload cache contains duplicate entries")
                        }
                    } finally {
                        plaintext.fill(0)
                    }
                }
                if (input.available() != 0) corruptCache("Decrypted payload cache contains trailing bytes")
                return PrivateDecryptedPayloadCacheState(accountId, transportDeviceId, entries)
            } catch (error: Exception) {
                constructedEntries.forEach(PrivateCachedPayloadEntry::destroy)
                throw error
            }
        } catch (failure: PrivateDecryptedPayloadCacheUnavailableException) {
            throw failure
        } catch (error: Exception) {
            throw PrivateDecryptedPayloadCacheUnavailableException("Decrypted payload cache state is malformed", error)
        }
    }
}

private fun DataOutputStream.writeUuid(uuid: UUID) {
    writeLong(uuid.mostSignificantBits)
    writeLong(uuid.leastSignificantBits)
}

private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

private fun corruptCache(message: String): Nothing = throw PrivateDecryptedPayloadCacheUnavailableException(message)

private class PrivatePayloadCacheBoundedOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    @Synchronized
    override fun write(byte: Int) {
        requireCapacityFor(1)
        super.write(byte)
    }

    @Synchronized
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        requireCapacityFor(length)
        super.write(bytes, offset, length)
    }

    private fun requireCapacityFor(additionalBytes: Int) {
        if (additionalBytes < 0 || count > maximumBytes - additionalBytes) {
            throw PrivateDecryptedPayloadCacheUnavailableException("Decrypted payload cache exceeds the size limit")
        }
    }
}

private val CACHED_PAYLOAD_KEY_ORDER =
    compareBy<PrivateCachedPayloadKey>(
        { key -> key.kind.wireCode },
        { key -> key.recordId.toString() },
        PrivateCachedPayloadKey::revision,
    )
private val NIL_UUID = UUID(0L, 0L)
