package app.synapse.localllm.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.synapse.localllm.domain.security.AppLockConfiguration
import app.synapse.localllm.domain.security.AppLockPin
import app.synapse.localllm.domain.security.AppLockRepository
import app.synapse.localllm.domain.security.AppLockVerificationOutcome
import app.synapse.localllm.domain.security.AppLockVerificationReceipt
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidAppLockRepository private constructor(
    private val dataStore: DataStore<Preferences>,
    private val credentialHasher: AppLockCredentialHasher,
    private val nowEpochMillis: () -> Long,
    private val createSalt: () -> ByteArray,
) : AppLockRepository {
    constructor(context: Context) : this(
        dataStore = createAppLockDataStore(context.applicationContext),
        credentialHasher = AndroidKeystoreAppLockCredentialHasher(),
        nowEpochMillis = System::currentTimeMillis,
        createSalt = {
            ByteArray(SALT_BYTE_COUNT).also(SecureRandom()::nextBytes)
        },
    )

    internal constructor(
        context: Context,
        credentialHasher: AppLockCredentialHasher,
        nowEpochMillis: () -> Long,
        createSalt: () -> ByteArray,
        storageFileName: String,
    ) : this(
        dataStore = createAppLockDataStore(context.applicationContext, storageFileName),
        credentialHasher = credentialHasher,
        nowEpochMillis = nowEpochMillis,
        createSalt = createSalt,
    )

    private val mutationMutex = Mutex()

    override val configuration: Flow<AppLockConfiguration> =
        dataStore.data
            .catch { emit(mutablePreferencesOf(STORAGE_UNAVAILABLE to true)) }
            .map(::configurationFromPreferences)

    override suspend fun enable(pin: AppLockPin) {
        mutationMutex.withLock {
            val currentConfiguration = configurationFromPreferences(dataStore.data.first())
            check(!currentConfiguration.enabled) { "PIN lock is already enabled." }
            persistCredential(pin)
        }
    }

    override suspend fun verify(pin: AppLockPin): AppLockVerificationReceipt =
        mutationMutex.withLock { verifyLocked(pin) }

    override suspend fun changePin(
        currentPin: AppLockPin,
        newPin: AppLockPin,
    ): AppLockVerificationReceipt = mutationMutex.withLock {
        val verification = verifyLocked(currentPin)
        if (verification.outcome == AppLockVerificationOutcome.VERIFIED) persistCredential(newPin)
        verification
    }

    override suspend fun replaceCredentialAfterAccountReauthentication(newPin: AppLockPin) {
        mutationMutex.withLock { persistCredential(newPin) }
    }

    override suspend fun disable(pin: AppLockPin): AppLockVerificationReceipt = mutationMutex.withLock {
        val verification = verifyLocked(pin)
        if (verification.outcome == AppLockVerificationOutcome.VERIFIED) {
            dataStore.edit { preferences ->
                preferences.remove(PIN_SALT)
                preferences.remove(PIN_DIGEST)
                preferences.remove(FAILED_ATTEMPTS)
                preferences.remove(BLOCKED_UNTIL_EPOCH_MILLIS)
            }
        }
        verification
    }

    private suspend fun verifyLocked(pin: AppLockPin): AppLockVerificationReceipt {
        val preferences = dataStore.data.first()
        val configuration = configurationFromPreferences(preferences)
        if (!configuration.enabled) {
            return AppLockVerificationReceipt(AppLockVerificationOutcome.NOT_ENABLED)
        }
        if (!configuration.credentialAvailable) {
            return AppLockVerificationReceipt(AppLockVerificationOutcome.CREDENTIAL_UNAVAILABLE)
        }
        val now = nowEpochMillis()
        val blockedUntil = preferences[BLOCKED_UNTIL_EPOCH_MILLIS] ?: 0L
        if (blockedUntil > now) {
            return AppLockVerificationReceipt(
                outcome = AppLockVerificationOutcome.TEMPORARILY_BLOCKED,
                retryAfterMillis = blockedUntil - now,
            )
        }
        val salt = preferences[PIN_SALT]?.decodeBase64OrNull()
            ?: return AppLockVerificationReceipt(AppLockVerificationOutcome.CREDENTIAL_UNAVAILABLE)
        val expectedDigest = preferences[PIN_DIGEST]?.decodeBase64OrNull()
            ?: return AppLockVerificationReceipt(AppLockVerificationOutcome.CREDENTIAL_UNAVAILABLE)
        val suppliedDigest = runCatching { credentialHasher.digest(salt, pin) }.getOrNull()
            ?: return AppLockVerificationReceipt(AppLockVerificationOutcome.CREDENTIAL_UNAVAILABLE)
        if (MessageDigest.isEqual(expectedDigest, suppliedDigest)) {
            dataStore.edit { updatedPreferences ->
                updatedPreferences.remove(FAILED_ATTEMPTS)
                updatedPreferences.remove(BLOCKED_UNTIL_EPOCH_MILLIS)
            }
            return AppLockVerificationReceipt(AppLockVerificationOutcome.VERIFIED)
        }
        val failedAttempts = (preferences[FAILED_ATTEMPTS] ?: 0) + 1
        val shouldBlock = failedAttempts >= MAXIMUM_FAILED_ATTEMPTS
        val nextBlockedUntil = if (shouldBlock) now + FAILED_ATTEMPT_BLOCK_MILLIS else 0L
        dataStore.edit { updatedPreferences ->
            updatedPreferences[FAILED_ATTEMPTS] = if (shouldBlock) 0 else failedAttempts
            if (shouldBlock) {
                updatedPreferences[BLOCKED_UNTIL_EPOCH_MILLIS] = nextBlockedUntil
            } else {
                updatedPreferences.remove(BLOCKED_UNTIL_EPOCH_MILLIS)
            }
        }
        return AppLockVerificationReceipt(
            outcome = if (shouldBlock) {
                AppLockVerificationOutcome.TEMPORARILY_BLOCKED
            } else {
                AppLockVerificationOutcome.INVALID_PIN
            },
            retryAfterMillis = if (shouldBlock) FAILED_ATTEMPT_BLOCK_MILLIS else 0,
        )
    }

    private suspend fun persistCredential(pin: AppLockPin) {
        val salt = createSalt().also { generatedSalt ->
            require(generatedSalt.size == SALT_BYTE_COUNT) { "PIN credential salt has an invalid size." }
        }
        val digest = credentialHasher.digest(salt, pin)
        require(digest.isNotEmpty()) { "PIN credential digest is empty." }
        dataStore.edit { preferences ->
            preferences[PIN_SALT] = Base64.getEncoder().encodeToString(salt)
            preferences[PIN_DIGEST] = Base64.getEncoder().encodeToString(digest)
            preferences.remove(FAILED_ATTEMPTS)
            preferences.remove(BLOCKED_UNTIL_EPOCH_MILLIS)
        }
    }

    private fun configurationFromPreferences(preferences: Preferences): AppLockConfiguration {
        if (preferences[STORAGE_UNAVAILABLE] == true) {
            return AppLockConfiguration(enabled = true, credentialAvailable = false)
        }
        val hasSalt = preferences[PIN_SALT] != null
        val hasDigest = preferences[PIN_DIGEST] != null
        return AppLockConfiguration(
            enabled = hasSalt || hasDigest,
            credentialAvailable = hasSalt && hasDigest,
        )
    }

    private fun String.decodeBase64OrNull(): ByteArray? =
        runCatching { Base64.getDecoder().decode(this) }.getOrNull()

    private companion object {
        const val SALT_BYTE_COUNT = 32
        const val MAXIMUM_FAILED_ATTEMPTS = 5
        const val FAILED_ATTEMPT_BLOCK_MILLIS = 30_000L
        val PIN_SALT = stringPreferencesKey("pin_salt_v1")
        val PIN_DIGEST = stringPreferencesKey("pin_digest_v1")
        val FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
        val BLOCKED_UNTIL_EPOCH_MILLIS = longPreferencesKey("blocked_until_epoch_millis")
        val STORAGE_UNAVAILABLE = booleanPreferencesKey("storage_unavailable")
    }
}

internal fun interface AppLockCredentialHasher {
    fun digest(
        salt: ByteArray,
        pin: AppLockPin,
    ): ByteArray
}

private class AndroidKeystoreAppLockCredentialHasher : AppLockCredentialHasher {
    private val keyAccessLock = Any()

    override fun digest(
        salt: ByteArray,
        pin: AppLockPin,
    ): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(loadOrCreateSecretKey())
        mac.update(CREDENTIAL_DOMAIN_SEPARATOR)
        mac.update(salt)
        return mac.doFinal(pin.digits.toByteArray(Charsets.UTF_8))
    }

    private fun loadOrCreateSecretKey(): SecretKey = synchronized(keyAccessLock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            ANDROID_KEY_STORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                ).setDigests(KeyProperties.DIGEST_SHA256).build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "synapse_app_lock_hmac_v1"
        val CREDENTIAL_DOMAIN_SEPARATOR = "synapse-app-lock-v1\u0000".toByteArray(Charsets.UTF_8)
    }
}

private fun createAppLockDataStore(
    context: Context,
    storageFileName: String = "synapse_app_lock.preferences_pb",
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    produceFile = {
        File(context.noBackupFilesDir, storageFileName)
    },
)
