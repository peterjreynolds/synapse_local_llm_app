package app.synapse.privatechat.security.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal interface EncryptedStateFile {
    /** Returns an owned ciphertext copy or null when no committed state exists. */
    fun read(maximumBytes: Int): ByteArray?

    fun replace(ciphertext: ByteArray)
}

internal class AndroidAtomicEncryptedStateFile(
    private val file: File,
) : EncryptedStateFile {
    private val atomicFile = AtomicFile(file)
    private val legacyBackupFile = File("${file.path}.bak")

    @Volatile
    private var encryptedStateMayExist = file.exists() || legacyBackupFile.exists()

    override fun read(maximumBytes: Int): ByteArray? {
        require(maximumBytes > 0) { "Encrypted state read limit must be positive" }
        if (!file.exists() && !legacyBackupFile.exists()) return null
        encryptedStateMayExist = true
        return atomicFile.openRead().use { input ->
            val output = ByteArrayOutputStream(minOf(input.available().coerceAtLeast(0), maximumBytes))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                totalBytes += bytesRead
                if (totalBytes > maximumBytes) {
                    throw EncryptedStateUnavailableException("Encrypted state exceeds the size limit")
                }
                output.write(buffer, 0, bytesRead)
            }
            output.toByteArray()
        }
    }

    override fun replace(ciphertext: ByteArray) {
        encryptedStateMayExist = true
        val output = atomicFile.startWrite()
        try {
            output.write(ciphertext)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            try {
                atomicFile.failWrite(output)
            } catch (rollbackError: Throwable) {
                error.addSuppressed(rollbackError)
            }
            throw error
        }
    }

    fun permitsEncryptionKeyCreation(): Boolean = !encryptedStateMayExist
}

internal class AndroidKeystoreAes256KeyProvider(
    private val keyAlias: String,
) : EncryptedStateKeyProvider {
    init {
        require(KEY_ALIAS_PATTERN.matches(keyAlias)) { "Encrypted state key alias is invalid" }
    }

    override fun loadExistingKey(): SecretKey? = synchronized(KEYSTORE_MONITOR) { loadExistingKeyLocked() }

    override fun createKeyIfAbsent(): SecretKey =
        synchronized(KEYSTORE_MONITOR) {
            loadExistingKeyLocked()?.let { return@synchronized it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            requireNonExportableAesKey(generator.generateKey())
        }

    private fun loadExistingKeyLocked(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) return null
        val key = keyStore.getKey(keyAlias, null)
        if (key !is SecretKey) {
            throw EncryptedStateUnavailableException("Encrypted state key alias contains an unexpected key type")
        }
        return requireNonExportableAesKey(key)
    }

    private fun requireNonExportableAesKey(key: SecretKey): SecretKey {
        if (!key.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, ignoreCase = true)) {
            throw EncryptedStateUnavailableException("Encrypted state key alias contains a non-AES key")
        }
        if (key.encoded != null) {
            throw EncryptedStateUnavailableException("Encrypted state key unexpectedly permits export")
        }
        return key
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        val KEYSTORE_MONITOR = Any()
        val KEY_ALIAS_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
