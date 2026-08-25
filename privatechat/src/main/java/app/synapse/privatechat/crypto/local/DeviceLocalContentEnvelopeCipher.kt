package app.synapse.privatechat.crypto.local

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal interface DeviceLocalContentEnvelopeCipher {
    fun encryptLocalEnvelope(plaintext: ByteArray): ByteArray

    fun decryptLocalEnvelope(ciphertext: ByteArray): ByteArray

    fun markEnvelopeDurablyReferenced(ciphertext: ByteArray)

    fun reconcileRetainedEnvelopeKeys(
        authoritativeCiphertexts: Collection<ByteArray>,
        pendingCiphertexts: Collection<ByteArray>,
        observedAt: Instant,
    )

    fun clearForSessionInvalidation()
}

internal class DeviceLocalContentEnvelopeUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class PerEnvelopeDeviceLocalContentEnvelopeCipher(
    private val keyRepository: DeviceLocalEnvelopeKeyRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom(),
) : DeviceLocalContentEnvelopeCipher {
    override fun encryptLocalEnvelope(plaintext: ByteArray): ByteArray {
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES) {
            "Device-local content envelope plaintext exceeds the supported range"
        }
        val keyId = UUID.randomUUID()
        val keyBytes = ByteArray(AES_KEY_BYTES).also(secureRandom::nextBytes)
        val createdAt = clock.instant()
        try {
            keyRepository.insertLeasedKey(
                DeviceLocalEnvelopeKeyRecord(
                    keyId = keyId,
                    keyBytes = keyBytes,
                    createdAt = createdAt,
                    leaseReleasedAt = null,
                    observedAuthoritatively = false,
                ),
            )
            val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
            val encrypted = encryptWithKey(keyId, keyBytes, nonce, plaintext)
            return ByteBuffer
                .allocate(HEADER_BYTES + encrypted.size)
                .run {
                    putInt(ENVELOPE_MAGIC)
                    put(ENVELOPE_VERSION)
                    putLong(keyId.mostSignificantBits)
                    putLong(keyId.leastSignificantBits)
                    put(nonce)
                    put(encrypted)
                    array()
                }.also { envelope ->
                    nonce.fill(0)
                    encrypted.fill(0)
                    require(envelope.size <= MAX_CIPHERTEXT_BYTES) {
                        "Device-local content envelope ciphertext exceeds the supported range"
                    }
                }
        } catch (error: Exception) {
            if (error is DeviceLocalContentEnvelopeUnavailableException) throw error
            throw DeviceLocalContentEnvelopeUnavailableException("Device-local content envelope encryption failed", error)
        } finally {
            keyBytes.fill(0)
        }
    }

    override fun decryptLocalEnvelope(ciphertext: ByteArray): ByteArray {
        val parsed = parseEnvelope(ciphertext)
        val keyBytes =
            keyRepository.loadKey(parsed.keyId)
                ?: throw DeviceLocalContentEnvelopeUnavailableException("Device-local content envelope key was erased")
        return try {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, parsed.nonce))
            cipher.updateAAD(authenticatedContext(parsed.keyId))
            cipher.doFinal(parsed.encryptedPayload).also { plaintext ->
                if (plaintext.size !in 1..MAX_PLAINTEXT_BYTES) {
                    plaintext.fill(0)
                    throw DeviceLocalContentEnvelopeUnavailableException(
                        "Device-local content envelope plaintext exceeds the supported range",
                    )
                }
            }
        } catch (error: DeviceLocalContentEnvelopeUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw DeviceLocalContentEnvelopeUnavailableException(
                "Device-local content envelope authentication failed",
                error,
            )
        } finally {
            keyBytes.fill(0)
            parsed.destroy()
        }
    }

    override fun markEnvelopeDurablyReferenced(ciphertext: ByteArray) {
        val parsed = parseEnvelope(ciphertext)
        try {
            keyRepository.releaseLease(parsed.keyId, clock.instant())
        } finally {
            parsed.destroy()
        }
    }

    override fun reconcileRetainedEnvelopeKeys(
        authoritativeCiphertexts: Collection<ByteArray>,
        pendingCiphertexts: Collection<ByteArray>,
        observedAt: Instant,
    ) {
        val authoritativeKeyIds = authoritativeCiphertexts.parseKeyIds()
        val pendingKeyIds = pendingCiphertexts.parseKeyIds()
        keyRepository.reconcileRetainedKeys(authoritativeKeyIds, pendingKeyIds, observedAt)
    }

    private fun Collection<ByteArray>.parseKeyIds(): Set<UUID> =
        mapTo(LinkedHashSet()) { ciphertext ->
            parseEnvelope(ciphertext).let { parsed ->
                try {
                    parsed.keyId
                } finally {
                    parsed.destroy()
                }
            }
        }

    override fun clearForSessionInvalidation() = keyRepository.clearForSessionInvalidation()

    private fun encryptWithKey(
        keyId: UUID,
        keyBytes: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(authenticatedContext(keyId))
        return cipher.doFinal(plaintext)
    }

    private fun parseEnvelope(ciphertext: ByteArray): ParsedLocalEnvelope {
        if (ciphertext.size !in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            throw DeviceLocalContentEnvelopeUnavailableException(
                "Device-local content envelope ciphertext exceeds the supported range",
            )
        }
        try {
            val input = ByteBuffer.wrap(ciphertext)
            if (input.int != ENVELOPE_MAGIC || input.get() != ENVELOPE_VERSION) {
                throw DeviceLocalContentEnvelopeUnavailableException("Device-local content envelope version is unsupported")
            }
            val keyId = UUID(input.long, input.long)
            if (keyId == NIL_UUID) {
                throw DeviceLocalContentEnvelopeUnavailableException("Device-local content envelope key identifier is invalid")
            }
            val nonce = ByteArray(GCM_NONCE_BYTES).also(input::get)
            val encryptedPayload = ByteArray(input.remaining()).also(input::get)
            return ParsedLocalEnvelope(keyId, nonce, encryptedPayload)
        } catch (error: DeviceLocalContentEnvelopeUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw DeviceLocalContentEnvelopeUnavailableException("Device-local content envelope is malformed", error)
        }
    }

    private fun authenticatedContext(keyId: UUID): ByteArray =
        ByteBuffer.allocate(AUTHENTICATED_CONTEXT_BYTES.size + 16).run {
            put(AUTHENTICATED_CONTEXT_BYTES)
            putLong(keyId.mostSignificantBits)
            putLong(keyId.leastSignificantBits)
            array()
        }

    private data class ParsedLocalEnvelope(
        val keyId: UUID,
        val nonce: ByteArray,
        val encryptedPayload: ByteArray,
    ) {
        fun destroy() {
            nonce.fill(0)
            encryptedPayload.fill(0)
        }
    }

    internal companion object {
        const val MAX_PLAINTEXT_BYTES = 64 * 1_024
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 49
        private const val MIN_CIPHERTEXT_BYTES = 1 + 49
        private const val ENVELOPE_MAGIC = 0x534c4531
        private const val ENVELOPE_VERSION: Byte = 1
        private const val AES_KEY_BYTES = 32
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val HEADER_BYTES = 4 + 1 + 16 + GCM_NONCE_BYTES
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private val AUTHENTICATED_CONTEXT_BYTES =
            "synapse.private.local-message-envelope.v2".toByteArray(StandardCharsets.UTF_8)
        private val NIL_UUID = UUID(0L, 0L)
    }
}
