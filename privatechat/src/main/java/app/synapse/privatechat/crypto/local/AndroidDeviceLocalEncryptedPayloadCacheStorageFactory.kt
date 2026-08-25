package app.synapse.privatechat.crypto.local

import android.content.Context
import app.synapse.privatechat.security.storage.AndroidAtomicEncryptedStateFile
import app.synapse.privatechat.security.storage.AndroidKeystoreAes256KeyProvider
import java.io.File

internal object AndroidDeviceLocalEncryptedPayloadCacheStorageFactory {
    private const val STATE_FILE_NAME = "decrypted-payload-cache.enc"
    private const val PRIMARY_KEY_ALIAS = "synapse.private.decrypted-payload-cache.slot-a.v1"
    private const val SECONDARY_KEY_ALIAS = "synapse.private.decrypted-payload-cache.slot-b.v1"
    private const val PRIMARY_AUTHENTICATED_CONTEXT = "synapse.private.decrypted-payload-cache.slot-a.v1"
    private const val SECONDARY_AUTHENTICATED_CONTEXT = "synapse.private.decrypted-payload-cache.slot-b.v1"

    fun create(context: Context): DeviceLocalEncryptedPayloadCacheStorage =
        RotatingDeviceLocalEncryptedPayloadCacheStorage(
            encryptedStateFile =
                AndroidAtomicEncryptedStateFile(
                    File(context.noBackupFilesDir, STATE_FILE_NAME),
                ),
            primaryKeySlot =
                DeviceLocalPayloadCacheKeySlot(
                    keyProvider = AndroidKeystoreAes256KeyProvider(PRIMARY_KEY_ALIAS),
                    authenticatedContext = PRIMARY_AUTHENTICATED_CONTEXT,
                ),
            secondaryKeySlot =
                DeviceLocalPayloadCacheKeySlot(
                    keyProvider = AndroidKeystoreAes256KeyProvider(SECONDARY_KEY_ALIAS),
                    authenticatedContext = SECONDARY_AUTHENTICATED_CONTEXT,
                ),
        )
}
