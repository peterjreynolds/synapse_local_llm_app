package app.synapse.privatechat.crypto.local

import android.content.Context
import app.synapse.privatechat.security.storage.AndroidAtomicEncryptedStateFile
import app.synapse.privatechat.security.storage.AndroidKeystoreAes256KeyProvider
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateKeySlot
import app.synapse.privatechat.security.storage.RotatingAesGcmEncryptedStateStorage
import java.io.File

internal object AndroidDeviceLocalContentEnvelopeCipherFactory {
    private const val STATE_FILE_NAME = "local-envelope-keys.enc"
    private const val PRIMARY_KEY_ALIAS = "synapse.private.local-envelope-keys.slot-a.v2"
    private const val SECONDARY_KEY_ALIAS = "synapse.private.local-envelope-keys.slot-b.v2"
    private const val PRIMARY_CONTEXT = "synapse.private.local-envelope-keys.slot-a.v2"
    private const val SECONDARY_CONTEXT = "synapse.private.local-envelope-keys.slot-b.v2"

    fun create(context: Context): DeviceLocalContentEnvelopeCipher =
        PerEnvelopeDeviceLocalContentEnvelopeCipher(
            DeviceLocalEnvelopeKeyRepository(
                RotatingAesGcmEncryptedStateStorage(
                    encryptedStateFile =
                        AndroidAtomicEncryptedStateFile(
                            File(context.noBackupFilesDir, STATE_FILE_NAME),
                        ),
                    primaryKeySlot =
                        RotatingAesGcmEncryptedStateKeySlot(
                            AndroidKeystoreAes256KeyProvider(PRIMARY_KEY_ALIAS),
                            PRIMARY_CONTEXT,
                        ),
                    secondaryKeySlot =
                        RotatingAesGcmEncryptedStateKeySlot(
                            AndroidKeystoreAes256KeyProvider(SECONDARY_KEY_ALIAS),
                            SECONDARY_CONTEXT,
                        ),
                    maximumPlaintextBytes = DEVICE_LOCAL_ENVELOPE_KEY_STATE_MAXIMUM_BYTES,
                ),
            ),
        )
}
