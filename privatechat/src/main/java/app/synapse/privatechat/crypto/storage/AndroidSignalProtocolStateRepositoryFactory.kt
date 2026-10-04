package app.synapse.privatechat.crypto.storage

import android.content.Context
import app.synapse.privatechat.security.storage.AndroidAtomicEncryptedStateFile
import app.synapse.privatechat.security.storage.AndroidKeystoreAes256KeyProvider
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateKeySlot
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateStorage
import app.synapse.privatechat.security.storage.RotatingEncryptedStateKeySlotId
import java.io.File

object AndroidSignalProtocolStateRepositoryFactory {
    private const val STATE_FILE_NAME = "signal-protocol-state.enc"
    private const val KEY_ALIAS = "synapse.private.signal-state.v1"
    private const val AUTHENTICATED_CONTEXT = "synapse.private.signal-state.v1"

    fun create(context: Context): EncryptedSignalProtocolStateRepository {
        val stateFile = AndroidAtomicEncryptedStateFile(File(context.noBackupFilesDir, STATE_FILE_NAME))
        return EncryptedSignalProtocolStateRepository(
            encryptedStateStorage =
                RotatingAesGcmEncryptedStateStorage(
                    encryptedStateFile = stateFile,
                    primaryKeySlot =
                        RotatingAesGcmEncryptedStateKeySlot(
                            keyProvider = AndroidKeystoreAes256KeyProvider(KEY_ALIAS),
                            authenticatedContext = AUTHENTICATED_CONTEXT,
                        ),
                    secondaryKeySlot =
                        RotatingAesGcmEncryptedStateKeySlot(
                            keyProvider = AndroidKeystoreAes256KeyProvider("synapse.private.signal-state.slot-b.v1"),
                            authenticatedContext = "synapse.private.signal-state.slot-b.v1",
                        ),
                    maximumPlaintextBytes = SignalStateCodec.MAX_TOTAL_PLAINTEXT_BYTES,
                    legacySingleSlot = RotatingEncryptedStateKeySlotId.PRIMARY,
                ),
        )
    }
}
