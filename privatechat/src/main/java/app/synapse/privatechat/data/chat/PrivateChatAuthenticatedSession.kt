package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.domain.account.PrivateAccountId
import java.time.Instant
import java.util.UUID

/** The minimum authenticated device state needed by chat transport. */
internal class PrivateChatAuthenticatedSession private constructor(
    val accountId: PrivateAccountId,
    val localSignalAddress: SignalDeviceAddress,
    val authenticationUsername: String,
    private val accessToken: String,
    val expiresAt: Instant,
) {
    fun accessTokenForRequest(): String = accessToken

    fun isUsableAt(now: Instant): Boolean = expiresAt.isAfter(now)

    fun hasSameAuthenticatedDeviceAs(other: PrivateChatAuthenticatedSession): Boolean =
        accountId == other.accountId &&
            localSignalAddress == other.localSignalAddress &&
            expiresAt == other.expiresAt &&
            accessToken == other.accessToken

    override fun toString(): String = "PrivateChatAuthenticatedSession([REDACTED])"

    companion object {
        fun fromAuthenticatedDevice(
            accountId: UUID,
            transportDeviceId: UUID,
            signalDeviceId: SignalDeviceId,
            authenticationUsername: String,
            accessToken: String,
            expiresAt: Instant,
        ): PrivateChatAuthenticatedSession {
            require(PRIVATE_AUTHENTICATION_USERNAME_PATTERN.matches(authenticationUsername)) {
                "Authentication username is malformed"
            }
            require(PRIVATE_ACCESS_TOKEN_PATTERN.matches(accessToken)) {
                "Chat access token is malformed"
            }
            require(expiresAt.nano == 0 && expiresAt.epochSecond in 1..MAXIMUM_SESSION_EXPIRY_EPOCH_SECONDS) {
                "Chat session expiry is outside the supported range"
            }
            return PrivateChatAuthenticatedSession(
                accountId = PrivateAccountId(accountId.toString()),
                localSignalAddress =
                    SignalDeviceAddress(
                        accountId = accountId,
                        transportDeviceId = transportDeviceId,
                        protocolDeviceId = signalDeviceId,
                    ),
                authenticationUsername = authenticationUsername,
                accessToken = accessToken,
                expiresAt = expiresAt,
            )
        }
    }
}

internal fun interface PrivateChatAuthenticatedSessionProvider {
    fun loadAuthenticatedSession(): PrivateChatAuthenticatedSession?
}

private val PRIVATE_AUTHENTICATION_USERNAME_PATTERN = Regex("^[a-z][a-z0-9_]{2,31}$")
private val PRIVATE_ACCESS_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+[.][A-Za-z0-9_-]+$")
private const val MAXIMUM_SESSION_EXPIRY_EPOCH_SECONDS = 253_402_300_799L
