package app.synapse.privatechat.security.storage

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedStateUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Provides opaque key handles; Android production keys remain inside Android Keystore. */
internal interface EncryptedStateKeyProvider {
    fun loadExistingKey(): SecretKey?

    fun createKeyIfAbsent(): SecretKey
}

internal interface DestructibleEncryptedStateKeyProvider : EncryptedStateKeyProvider {
    /** Irreversibly removes the key and verifies that its alias is no longer present. */
    fun deleteKey()
}

internal interface EncryptedStateCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * Versioned, domain-separated whole-state encryption shared by production Android storage and JVM
 * boundary tests. Key creation is an explicit one-way policy: once durable state may exist, a
 * missing or invalid key is terminal rather than an excuse to overwrite unreadable state.
 */
internal class Aes256GcmEncryptedStateCipher(
    private val keyProvider: EncryptedStateKeyProvider,
    private val keyCreationAllowed: () -> Boolean,
    authenticatedContext: String,
) : EncryptedStateCipher {
    private val authenticatedContextBytes = authenticatedContext.toByteArray(StandardCharsets.US_ASCII)

    init {
        require(AUTHENTICATED_CONTEXT_PATTERN.matches(authenticatedContext)) {
            "Encrypted state context is invalid"
        }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = loadEncryptionKey()
        return cryptographicOperation("Encrypted state encryption failed") {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // Android Keystore must generate the nonce when randomized encryption is required.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv.size != GCM_IV_BYTES) {
                throw EncryptedStateUnavailableException("Encrypted state cipher produced an invalid IV")
            }
            cipher.updateAAD(AUTHENTICATED_HEADER)
            cipher.updateAAD(authenticatedContextBytes)
            val encryptedPayload = cipher.doFinal(plaintext)
            try {
                ByteArray(HEADER_BYTES + iv.size + encryptedPayload.size).also { output ->
                    AUTHENTICATED_HEADER.copyInto(output)
                    output[AUTHENTICATED_HEADER.size] = iv.size.toByte()
                    iv.copyInto(output, HEADER_BYTES)
                    encryptedPayload.copyInto(output, HEADER_BYTES + iv.size)
                }
            } finally {
                encryptedPayload.fill(0)
            }
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        validateEnvelope(ciphertext)
        val key = loadExistingKey()
        val ivSize = ciphertext[AUTHENTICATED_HEADER.size].toInt() and 0xFF
        val iv = ciphertext.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivSize)
        val encryptedPayload = ciphertext.copyOfRange(HEADER_BYTES + ivSize, ciphertext.size)
        return try {
            cryptographicOperation("Encrypted state could not be authenticated") {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(AUTHENTICATED_HEADER)
                cipher.updateAAD(authenticatedContextBytes)
                cipher.doFinal(encryptedPayload)
            }
        } finally {
            encryptedPayload.fill(0)
        }
    }

    private fun loadEncryptionKey(): SecretKey {
        loadExistingKeyOrNull()?.let { return it }
        val mayCreate =
            cryptographicOperation("Encrypted state key-creation policy failed") {
                keyCreationAllowed()
            }
        if (!mayCreate) {
            throw EncryptedStateUnavailableException(
                "Encrypted state key is unavailable while durable state may exist",
            )
        }
        return cryptographicOperation("Encrypted state key could not be created") {
            keyProvider.createKeyIfAbsent()
        }
    }

    private fun loadExistingKey(): SecretKey =
        loadExistingKeyOrNull()
            ?: throw EncryptedStateUnavailableException("Encrypted state key is unavailable")

    private fun loadExistingKeyOrNull(): SecretKey? =
        cryptographicOperation("Encrypted state key could not be loaded") {
            keyProvider.loadExistingKey()
        }

    private fun validateEnvelope(ciphertext: ByteArray) {
        if (ciphertext.size < HEADER_BYTES + GCM_IV_BYTES + GCM_TAG_BYTES) corruptHeader()
        if (!ciphertext.startsWith(AUTHENTICATED_HEADER)) corruptHeader()
        val ivSize = ciphertext[AUTHENTICATED_HEADER.size].toInt() and 0xFF
        if (ivSize != GCM_IV_BYTES || ciphertext.size < HEADER_BYTES + ivSize + GCM_TAG_BYTES) corruptHeader()
    }

    private inline fun <T> cryptographicOperation(
        failureMessage: String,
        operation: () -> T,
    ): T =
        try {
            operation()
        } catch (error: EncryptedStateUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw EncryptedStateUnavailableException(failureMessage, error)
        }

    private fun corruptHeader(): Nothing = throw EncryptedStateUnavailableException("Encrypted state header is invalid")

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    internal companion object {
        const val MAX_ENVELOPE_OVERHEAD_BYTES: Int = 4 + 1 + 1 + 12 + 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private val MAGIC = byteArrayOf(0x53, 0x50, 0x45, 0x31)
        private val AUTHENTICATED_HEADER = MAGIC + FORMAT_VERSION
        private val HEADER_BYTES = AUTHENTICATED_HEADER.size + 1
        private val AUTHENTICATED_CONTEXT_PATTERN = Regex("^[a-z0-9.-]{1,96}$")
    }
}
