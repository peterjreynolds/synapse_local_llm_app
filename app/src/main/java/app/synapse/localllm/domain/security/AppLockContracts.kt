package app.synapse.localllm.domain.security

import kotlinx.coroutines.flow.Flow

@JvmInline
value class AppLockPin private constructor(val digits: String) {
    companion object {
        fun parse(rawValue: String): AppLockPin {
            require(rawValue.length == PIN_LENGTH && rawValue.all(Char::isDigit)) {
                "PIN must contain exactly four digits."
            }
            return AppLockPin(rawValue)
        }

        const val PIN_LENGTH = 4
    }
}

data class AppLockConfiguration(
    val enabled: Boolean,
    val credentialAvailable: Boolean,
)

enum class AppLockVerificationOutcome {
    VERIFIED,
    INVALID_PIN,
    TEMPORARILY_BLOCKED,
    NOT_ENABLED,
    CREDENTIAL_UNAVAILABLE,
}

data class AppLockVerificationReceipt(
    val outcome: AppLockVerificationOutcome,
    val retryAfterMillis: Long = 0,
)

interface AppLockRepository {
    val configuration: Flow<AppLockConfiguration>

    suspend fun enable(pin: AppLockPin)

    suspend fun verify(pin: AppLockPin): AppLockVerificationReceipt

    suspend fun changePin(
        currentPin: AppLockPin,
        newPin: AppLockPin,
    ): AppLockVerificationReceipt

    suspend fun replaceCredentialAfterAccountReauthentication(newPin: AppLockPin)

    suspend fun disable(pin: AppLockPin): AppLockVerificationReceipt
}
