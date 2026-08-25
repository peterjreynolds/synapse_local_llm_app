package app.synapse.privatechat.data.session

import android.content.Context
import app.synapse.privatechat.security.storage.AndroidAtomicEncryptedStateFile
import app.synapse.privatechat.security.storage.AndroidKeystoreAes256KeyProvider
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateKeySlot
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateStorage
import app.synapse.privatechat.security.storage.RotatingEncryptedStateKeySlotId
import java.io.File

internal object AndroidPrivateSessionRepositoryFactory {
    private const val STATE_FILE_NAME = "private-account-session.enc"

    // Slot A retains the original alias/context so existing single-key vaults migrate in place.
    private const val PRIMARY_KEY_ALIAS = "synapse.private.account-session.v1"
    private const val SECONDARY_KEY_ALIAS = "synapse.private.account-session.slot-b.v1"
    private const val PRIMARY_AUTHENTICATED_CONTEXT = "synapse.private.account-session.v1"
    private const val SECONDARY_AUTHENTICATED_CONTEXT = "synapse.private.account-session.slot-b.v1"

    fun create(context: Context): EncryptedPrivateSessionRepository {
        val stateFile = AndroidAtomicEncryptedStateFile(File(context.noBackupFilesDir, STATE_FILE_NAME))
        return EncryptedPrivateSessionRepository(
            encryptedStateStorage =
                RotatingAesGcmEncryptedStateStorage(
                    encryptedStateFile = stateFile,
                    primaryKeySlot =
                        RotatingAesGcmEncryptedStateKeySlot(
                            keyProvider = AndroidKeystoreAes256KeyProvider(PRIMARY_KEY_ALIAS),
                            authenticatedContext = PRIMARY_AUTHENTICATED_CONTEXT,
                        ),
                    secondaryKeySlot =
                        RotatingAesGcmEncryptedStateKeySlot(
                            keyProvider = AndroidKeystoreAes256KeyProvider(SECONDARY_KEY_ALIAS),
                            authenticatedContext = SECONDARY_AUTHENTICATED_CONTEXT,
                        ),
                    maximumPlaintextBytes = PrivateSessionVaultCodec.MAX_PLAINTEXT_BYTES,
                    legacySingleSlot = RotatingEncryptedStateKeySlotId.PRIMARY,
                ),
        )
    }
}
