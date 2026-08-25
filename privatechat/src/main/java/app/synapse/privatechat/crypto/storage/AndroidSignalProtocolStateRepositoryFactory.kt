package app.synapse.privatechat.crypto.storage

import android.content.Context
import app.synapse.privatechat.security.storage.Aes256GcmEncryptedStateCipher
import app.synapse.privatechat.security.storage.AndroidAtomicEncryptedStateFile
import app.synapse.privatechat.security.storage.AndroidKeystoreAes256KeyProvider
import java.io.File

object AndroidSignalProtocolStateRepositoryFactory {
    private const val STATE_FILE_NAME = "signal-protocol-state.enc"
    private const val KEY_ALIAS = "synapse.private.signal-state.v1"
    private const val AUTHENTICATED_CONTEXT = "synapse.private.signal-state.v1"

    fun create(context: Context): EncryptedSignalProtocolStateRepository {
        val stateFile = AndroidAtomicEncryptedStateFile(File(context.noBackupFilesDir, STATE_FILE_NAME))
        return EncryptedSignalProtocolStateRepository(
            encryptedStateFile = stateFile,
            stateCipher =
                Aes256GcmEncryptedStateCipher(
                    keyProvider = AndroidKeystoreAes256KeyProvider(KEY_ALIAS),
                    keyCreationAllowed = stateFile::permitsEncryptionKeyCreation,
                    authenticatedContext = AUTHENTICATED_CONTEXT,
                ),
        )
    }
}
