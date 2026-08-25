package app.synapse.privatechat.data.session

import app.synapse.privatechat.crypto.SignalDeviceId
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

@JvmInline
internal value class PrivateInstallationId private constructor(
    val uuid: UUID,
) {
    override fun toString(): String = "PrivateInstallationId([REDACTED])"

    companion object {
        fun generate(): PrivateInstallationId = fromGeneratedUuid(UUID.randomUUID())

        fun fromGeneratedUuid(uuid: UUID): PrivateInstallationId {
            require(uuid != NIL_UUID && uuid.version() == 4 && uuid.variant() == 2) {
                "Installation identity must be a random RFC 4122 version 4 UUID"
            }
            return PrivateInstallationId(uuid)
        }

        fun fromPersistence(uuid: UUID): PrivateInstallationId = fromGeneratedUuid(uuid)

        private val NIL_UUID = UUID(0L, 0L)
    }
}

/** A server device-registration receipt matched to the authenticated account and requested device. */
internal class ConfirmedPrivateDeviceRegistration private constructor(
    val accountId: UUID,
    val installationId: PrivateInstallationId,
    val signalDeviceId: SignalDeviceId,
) {
    override fun toString(): String = "ConfirmedPrivateDeviceRegistration([REDACTED])"

    companion object {
        fun confirmMatchingReceipt(
            authenticatedAccountId: UUID,
            requestedInstallationId: PrivateInstallationId,
            allocatedSignalDeviceId: SignalDeviceId,
            receiptAccountId: UUID,
            receiptTransportDeviceId: UUID,
            receiptSignalDeviceId: SignalDeviceId,
        ): ConfirmedPrivateDeviceRegistration {
            require(authenticatedAccountId != NIL_UUID) { "Authenticated account ID must not be nil" }
            require(receiptAccountId == authenticatedAccountId) {
                "Device-registration receipt belongs to a different account"
            }
            require(receiptTransportDeviceId == requestedInstallationId.uuid) {
                "Device-registration receipt belongs to a different installation"
            }
            require(receiptSignalDeviceId == allocatedSignalDeviceId) {
                "Device-registration receipt contains a different Signal device ID"
            }
            return ConfirmedPrivateDeviceRegistration(
                accountId = authenticatedAccountId,
                installationId = requestedInstallationId,
                signalDeviceId = allocatedSignalDeviceId,
            )
        }

        private val NIL_UUID = UUID(0L, 0L)
    }
}

/** Auth material that can exist durably only after a matching device-registration receipt. */
internal class RegisteredPrivateAccountSession private constructor(
    val accountId: UUID,
    val installationId: PrivateInstallationId,
    val signalDeviceId: SignalDeviceId,
    private val accessToken: String,
    private val refreshToken: String,
    val expiresAt: Instant,
    val authenticationUsername: String,
    val pseudonymousDisplayName: String,
) {
    fun accessTokenForAuthorization(): String = accessToken

    fun refreshTokenForRenewal(): String = refreshToken

    fun withRefreshedTokens(
        receiptAccountId: UUID,
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
    ): RegisteredPrivateAccountSession {
        require(receiptAccountId == accountId) {
            "Refreshed session belongs to a different account"
        }
        return validatedSession(
            accountId = accountId,
            installationId = installationId,
            signalDeviceId = signalDeviceId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            authenticationUsername = authenticationUsername,
            pseudonymousDisplayName = pseudonymousDisplayName,
        )
    }

    override fun toString(): String = "RegisteredPrivateAccountSession([REDACTED])"

    internal fun copyForStorage(): RegisteredPrivateAccountSession =
        fromPersistence(
            accountId = accountId,
            installationId = installationId,
            signalDeviceId = signalDeviceId,
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            authenticationUsername = authenticationUsername,
            pseudonymousDisplayName = pseudonymousDisplayName,
        )

    companion object {
        fun afterDeviceRegistration(
            registration: ConfirmedPrivateDeviceRegistration,
            accessToken: String,
            refreshToken: String,
            expiresAt: Instant,
            authenticationUsername: String,
            pseudonymousDisplayName: String,
        ): RegisteredPrivateAccountSession =
            validatedSession(
                accountId = registration.accountId,
                installationId = registration.installationId,
                signalDeviceId = registration.signalDeviceId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                authenticationUsername = authenticationUsername,
                pseudonymousDisplayName = pseudonymousDisplayName,
            )

        internal fun fromPersistence(
            accountId: UUID,
            installationId: PrivateInstallationId,
            signalDeviceId: SignalDeviceId,
            accessToken: String,
            refreshToken: String,
            expiresAt: Instant,
            authenticationUsername: String,
            pseudonymousDisplayName: String,
        ): RegisteredPrivateAccountSession =
            validatedSession(
                accountId = accountId,
                installationId = installationId,
                signalDeviceId = signalDeviceId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                authenticationUsername = authenticationUsername,
                pseudonymousDisplayName = pseudonymousDisplayName,
            )

        internal fun validateLegacyPersistence(
            accountId: UUID,
            installationId: PrivateInstallationId,
            signalDeviceId: SignalDeviceId,
            accessToken: String,
            refreshToken: String,
            expiresAt: Instant,
            pseudonymousDisplayName: String,
        ) {
            validatedSession(
                accountId = accountId,
                installationId = installationId,
                signalDeviceId = signalDeviceId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                authenticationUsername = LEGACY_VALIDATION_USERNAME,
                pseudonymousDisplayName = pseudonymousDisplayName,
            )
        }

        private fun validatedSession(
            accountId: UUID,
            installationId: PrivateInstallationId,
            signalDeviceId: SignalDeviceId,
            accessToken: String,
            refreshToken: String,
            expiresAt: Instant,
            authenticationUsername: String,
            pseudonymousDisplayName: String,
        ): RegisteredPrivateAccountSession {
            require(accountId != NIL_UUID) { "Account ID must not be nil" }
            require(accessToken.length in TOKEN_LENGTH_RANGE && ACCESS_TOKEN_PATTERN.matches(accessToken)) {
                "Access token is malformed"
            }
            require(refreshToken.length in TOKEN_LENGTH_RANGE && REFRESH_TOKEN_PATTERN.matches(refreshToken)) {
                "Refresh token is malformed"
            }
            require(expiresAt.nano == 0 && expiresAt.epochSecond in 1..MAX_EXPIRY_EPOCH_SECONDS) {
                "Session expiry is outside the supported range"
            }
            require(AUTHENTICATION_USERNAME_PATTERN.matches(authenticationUsername)) {
                "Authentication username is malformed"
            }
            require(
                pseudonymousDisplayName ==
                    Normalizer.normalize(pseudonymousDisplayName, Normalizer.Form.NFKC).trim(),
            ) {
                "Pseudonymous display name must be normalized and trimmed"
            }
            require(
                pseudonymousDisplayName.isNotEmpty() &&
                    pseudonymousDisplayName.length <= MAX_DISPLAY_NAME_CHARACTERS &&
                    pseudonymousDisplayName.none(Char::isISOControl),
            ) {
                "Pseudonymous display name is invalid"
            }
            return RegisteredPrivateAccountSession(
                accountId = accountId,
                installationId = installationId,
                signalDeviceId = signalDeviceId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                authenticationUsername = authenticationUsername,
                pseudonymousDisplayName = pseudonymousDisplayName,
            )
        }

        private val NIL_UUID = UUID(0L, 0L)
        private val TOKEN_LENGTH_RANGE = 20..8_192
        private val ACCESS_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
        private val REFRESH_TOKEN_PATTERN = Regex("^[A-Za-z0-9._~-]+$")
        private val AUTHENTICATION_USERNAME_PATTERN = Regex("^[a-z][a-z0-9_]{2,31}$")
        private const val LEGACY_VALIDATION_USERNAME = "legacy_session"
        private const val MAX_DISPLAY_NAME_CHARACTERS = 64
        private const val MAX_EXPIRY_EPOCH_SECONDS = 253_402_300_799L
    }
}

internal enum class PrivateSessionPersistenceOutcome {
    STORED,
    REPLACED,
}

internal class PrivateSessionPersistenceReceipt(
    val accountId: UUID,
    val installationId: PrivateInstallationId,
    val outcome: PrivateSessionPersistenceOutcome,
) {
    override fun toString(): String = "PrivateSessionPersistenceReceipt(outcome=$outcome, identity=[REDACTED])"
}

internal enum class PrivateSessionClearReceipt {
    CLEARED,
    ALREADY_EMPTY,
}

internal class PrivateSessionStateUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
