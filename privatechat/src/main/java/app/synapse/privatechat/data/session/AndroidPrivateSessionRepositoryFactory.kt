package app.synapse.privatechat.data.session

import android.content.Context
import app.synapse.privatechat.security.storage.Aes256GcmEncryptedStateCipher
import app.synapse.privatechat.security.storage.AndroidAtomicEncryptedStateFile
import app.synapse.privatechat.security.storage.AndroidKeystoreAes256KeyProvider
import java.io.File

internal object AndroidPrivateSessionRepositoryFactory {
    private const val STATE_FILE_NAME = "private-account-session.enc"
    private const val KEY_ALIAS = "synapse.private.account-session.v1"
    private const val AUTHENTICATED_CONTEXT = "synapse.private.account-session.v1"

    fun create(context: Context): EncryptedPrivateSessionRepository {
        val stateFile = AndroidAtomicEncryptedStateFile(File(context.noBackupFilesDir, STATE_FILE_NAME))
        return EncryptedPrivateSessionRepository(
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
