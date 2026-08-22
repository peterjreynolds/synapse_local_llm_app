package app.synapse.privatechat.crypto.storage

import app.synapse.privatechat.crypto.SignalProtocolStateCorruptedException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Provides opaque key handles; production keys remain inside Android Keystore. */
internal interface SignalStateKeyProvider {
    fun loadExistingKey(): SecretKey?

    fun createKeyIfAbsent(): SecretKey
}

/**
 * Versioned, authenticated whole-state encryption shared by Android and JVM boundary tests.
 * Key creation is an explicit one-way policy so unreadable durable state can never be overwritten
 * with a newly generated key.
 */
internal class AesGcmSignalStateCipher(
    private val keyProvider: SignalStateKeyProvider,
    private val keyCreationAllowed: () -> Boolean,
) : SignalStateCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = loadEncryptionKey()
        return cryptographicOperation("Signal state encryption failed") {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            // Android Keystore must generate the nonce when randomized encryption is required.
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv.size != GCM_IV_BYTES) {
                throw SignalProtocolStateCorruptedException("Signal state cipher produced an invalid IV")
            }
            cipher.updateAAD(AUTHENTICATED_HEADER)
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
            cryptographicOperation("Encrypted Signal state could not be authenticated") {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD(AUTHENTICATED_HEADER)
                cipher.doFinal(encryptedPayload)
            }
        } finally {
            encryptedPayload.fill(0)
        }
    }

    private fun loadEncryptionKey(): SecretKey {
        loadExistingKeyOrNull()?.let { return it }
        val mayCreate =
            cryptographicOperation("Signal state key-creation policy failed") {
                keyCreationAllowed()
            }
        if (!mayCreate) {
            throw SignalProtocolStateCorruptedException(
                "Signal state key is unavailable while encrypted state may exist",
            )
        }
        return cryptographicOperation("Signal state key could not be created") {
            keyProvider.createKeyIfAbsent()
        }
    }

    private fun loadExistingKey(): SecretKey =
        loadExistingKeyOrNull()
            ?: throw SignalProtocolStateCorruptedException("Signal state key is unavailable")

    private fun loadExistingKeyOrNull(): SecretKey? =
        cryptographicOperation("Signal state key could not be loaded") {
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
        } catch (error: SignalProtocolStateCorruptedException) {
            throw error
        } catch (error: Exception) {
            throw SignalProtocolStateCorruptedException(failureMessage, error)
        }

    private fun corruptHeader(): Nothing = throw SignalProtocolStateCorruptedException("Encrypted Signal state header is invalid")

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
    }
}
