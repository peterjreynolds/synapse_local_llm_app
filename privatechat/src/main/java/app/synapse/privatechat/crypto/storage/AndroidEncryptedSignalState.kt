package app.synapse.privatechat.crypto.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.synapse.privatechat.crypto.SignalProtocolStateCorruptedException
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidEncryptedSignalStateFile(
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
                    throw SignalProtocolStateCorruptedException("Encrypted Signal state exceeds the size limit")
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

internal class AndroidKeystoreSignalStateKeyProvider(
    private val keyAlias: String,
) : SignalStateKeyProvider {
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
            requireNonExportable(generator.generateKey())
        }

    private fun loadExistingKeyLocked(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) return null
        val key = keyStore.getKey(keyAlias, null)
        if (key !is SecretKey) {
            throw SignalProtocolStateCorruptedException("Signal state key alias contains an unexpected key type")
        }
        return requireNonExportable(key)
    }

    private fun requireNonExportable(key: SecretKey): SecretKey {
        if (!key.algorithm.equals(KeyProperties.KEY_ALGORITHM_AES, ignoreCase = true)) {
            throw SignalProtocolStateCorruptedException("Signal state key alias contains a non-AES key")
        }
        if (key.encoded != null) {
            throw SignalProtocolStateCorruptedException("Signal state key unexpectedly permits export")
        }
        return key
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        val KEYSTORE_MONITOR = Any()
    }
}

object AndroidSignalProtocolStateRepositoryFactory {
    private const val STATE_FILE_NAME = "signal-protocol-state.enc"
    private const val KEY_ALIAS = "synapse.private.signal-state.v1"

    fun create(context: Context): EncryptedSignalProtocolStateRepository {
        val file = File(context.noBackupFilesDir, STATE_FILE_NAME)
        val stateFile = AndroidEncryptedSignalStateFile(file)
        return EncryptedSignalProtocolStateRepository(
            encryptedStateFile = stateFile,
            stateCipher =
                AesGcmSignalStateCipher(
                    keyProvider = AndroidKeystoreSignalStateKeyProvider(KEY_ALIAS),
                    keyCreationAllowed = stateFile::permitsEncryptionKeyCreation,
                ),
        )
    }
}
