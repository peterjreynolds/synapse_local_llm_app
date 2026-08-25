package app.synapse.privatechat.crypto.local

import app.synapse.privatechat.security.storage.Aes256GcmEncryptedStateCipher
import app.synapse.privatechat.security.storage.DeletableEncryptedStateFile
import app.synapse.privatechat.security.storage.EncryptedStateUnavailableException
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateKeySlot
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateStorage

internal interface DeviceLocalEncryptedPayloadCacheStorage {
    fun readDecryptedState(): ByteArray?

    /** Safe only when every byte in the previous snapshot remains retention-eligible. */
    fun replaceEncryptedState(plaintext: ByteArray)

    /**
     * Re-encrypts retained state under a fresh key and destroys the retired key. A null value
     * cryptographically erases the complete cache before physically deleting its file.
     */
    fun replaceAfterPurge(retainedPlaintext: ByteArray?)

    fun deletePhysically()
}

internal class DeviceLocalPayloadCacheUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal typealias DeviceLocalPayloadCacheKeySlot = RotatingAesGcmEncryptedStateKeySlot

internal class RotatingDeviceLocalEncryptedPayloadCacheStorage(
    encryptedStateFile: DeletableEncryptedStateFile,
    primaryKeySlot: DeviceLocalPayloadCacheKeySlot,
    secondaryKeySlot: DeviceLocalPayloadCacheKeySlot,
) : DeviceLocalEncryptedPayloadCacheStorage {
    private val rotatingStorage =
        RotatingAesGcmEncryptedStateStorage(
            encryptedStateFile = encryptedStateFile,
            primaryKeySlot = primaryKeySlot,
            secondaryKeySlot = secondaryKeySlot,
            maximumPlaintextBytes = MAX_PLAINTEXT_BYTES,
        )

    override fun readDecryptedState(): ByteArray? = cacheStorageOperation { rotatingStorage.readDecryptedState() }

    override fun replaceEncryptedState(plaintext: ByteArray) {
        cacheStorageOperation { rotatingStorage.replaceEncryptedState(plaintext) }
    }

    override fun replaceAfterPurge(retainedPlaintext: ByteArray?) {
        cacheStorageOperation { rotatingStorage.replaceAfterCryptographicErasure(retainedPlaintext) }
    }

    override fun deletePhysically() {
        cacheStorageOperation { rotatingStorage.deletePhysically() }
    }

    private inline fun <T> cacheStorageOperation(operation: () -> T): T =
        try {
            operation()
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: DeviceLocalPayloadCacheUnavailableException) {
            throw error
        } catch (error: EncryptedStateUnavailableException) {
            throw DeviceLocalPayloadCacheUnavailableException(
                "Device-local decrypted payload cache is unavailable",
                error,
            )
        } catch (error: Exception) {
            throw DeviceLocalPayloadCacheUnavailableException(
                "Device-local decrypted payload cache operation failed",
                error,
            )
        }

    internal companion object {
        const val MAX_PLAINTEXT_BYTES = 8 * 1_024 * 1_024
        const val MAX_ENCODED_STATE_BYTES =
            5 + MAX_PLAINTEXT_BYTES + Aes256GcmEncryptedStateCipher.MAX_ENVELOPE_OVERHEAD_BYTES
    }
}
