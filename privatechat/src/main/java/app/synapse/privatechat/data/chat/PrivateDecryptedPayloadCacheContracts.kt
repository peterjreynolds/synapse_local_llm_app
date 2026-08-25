package app.synapse.privatechat.data.chat

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal enum class PrivateCachedPayloadKind(
    val wireCode: Int,
) {
    MESSAGE(1),
    MESSAGE_REVISION(2),
    REACTION(3),
    ROOM_METADATA(4),
    ;

    companion object {
        fun fromWire(wireCode: Int): PrivateCachedPayloadKind =
            entries.firstOrNull { kind -> kind.wireCode == wireCode }
                ?: throw PrivateDecryptedPayloadCacheUnavailableException("Cached payload kind is unsupported")
    }
}

internal data class PrivateCachedPayloadKey(
    val kind: PrivateCachedPayloadKind,
    val recordId: UUID,
    val revision: Int,
) {
    init {
        require(recordId != NIL_UUID) { "Cached payload record ID must not be nil" }
        require(revision in 0..MAXIMUM_PAYLOAD_REVISION) { "Cached payload revision is unsupported" }
    }
}

internal class PrivateEncryptedPayloadFingerprint private constructor(
    digest: ByteArray,
) {
    private val immutableDigest = digest.copyOf()

    fun copyBytes(): ByteArray = immutableDigest.copyOf()

    fun matches(other: PrivateEncryptedPayloadFingerprint): Boolean = MessageDigest.isEqual(immutableDigest, other.immutableDigest)

    override fun toString(): String = "PrivateEncryptedPayloadFingerprint([REDACTED])"

    companion object {
        fun fromCiphertext(ciphertext: ByteArray): PrivateEncryptedPayloadFingerprint =
            PrivateEncryptedPayloadFingerprint(
                MessageDigest.getInstance("SHA-256").digest(ciphertext),
            )

        fun fromPersistence(digest: ByteArray): PrivateEncryptedPayloadFingerprint {
            require(digest.size == SHA_256_BYTES) { "Cached payload fingerprint is malformed" }
            return PrivateEncryptedPayloadFingerprint(digest)
        }
    }
}

internal data class PrivateAuthoritativeEncryptedPayload(
    val key: PrivateCachedPayloadKey,
    val roomId: UUID,
    val parentMessageId: UUID?,
    val fingerprint: PrivateEncryptedPayloadFingerprint,
    val expiresAt: Instant,
) {
    init {
        require(roomId != NIL_UUID) { "Cached payload room ID must not be nil" }
        require(parentMessageId == null || parentMessageId != NIL_UUID) {
            "Cached payload parent message ID must not be nil"
        }
        require(expiresAt.nano == 0 && expiresAt.epochSecond in 1..MAXIMUM_CACHE_EXPIRY_EPOCH_SECONDS) {
            "Cached payload expiry is unsupported"
        }
        when (key.kind) {
            PrivateCachedPayloadKind.MESSAGE -> require(parentMessageId == key.recordId)
            PrivateCachedPayloadKind.MESSAGE_REVISION,
            PrivateCachedPayloadKind.REACTION,
            -> require(parentMessageId != null)

            PrivateCachedPayloadKind.ROOM_METADATA -> require(parentMessageId == null && roomId == key.recordId)
        }
    }

    fun matchesAuthoritativePayload(other: PrivateAuthoritativeEncryptedPayload): Boolean =
        key == other.key &&
            roomId == other.roomId &&
            parentMessageId == other.parentMessageId &&
            expiresAt == other.expiresAt &&
            fingerprint.matches(other.fingerprint)
}

internal class PrivateDecryptedPayloadCacheUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal const val MAXIMUM_CACHE_EXPIRY_EPOCH_SECONDS = 253_402_300_799L
internal const val MAXIMUM_CACHED_PAYLOAD_BYTES = 64 * 1_024
internal const val MAXIMUM_CACHED_PAYLOAD_ENTRIES = 2_000
private const val MAXIMUM_PAYLOAD_REVISION = 2_147_483_647
private const val SHA_256_BYTES = 32
private val NIL_UUID = UUID(0L, 0L)
