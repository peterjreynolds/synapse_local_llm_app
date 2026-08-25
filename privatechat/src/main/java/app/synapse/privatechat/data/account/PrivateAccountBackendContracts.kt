package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateDisplayName
import java.time.Instant
import java.util.UUID

internal data class PrivateDeviceRegistrationReservation(
    val accountId: PrivateAccountId,
    val transportDeviceId: UUID,
    val signalDeviceId: SignalDeviceId,
    val expiresAt: Instant,
)

internal class PrivateBackendSessionTokens(
    val expiresAt: Instant,
    accessToken: String,
    refreshToken: String,
) {
    private val accessTokenCharacters = accessToken
    private val refreshTokenCharacters = refreshToken

    init {
        require(SAFE_SESSION_TOKEN.matches(accessToken)) { "Supabase access token is malformed" }
        require(SAFE_SESSION_TOKEN.matches(refreshToken)) { "Supabase refresh token is malformed" }
    }

    fun exposeAccessTokenForRequest(): String = accessTokenCharacters

    fun exposeRefreshTokenForRequest(): String = refreshTokenCharacters

    override fun toString(): String = "PrivateBackendSessionTokens(expiresAt=$expiresAt, tokens=[REDACTED])"
}

internal data class UnboundPrivateAccountSession(
    val reservation: PrivateDeviceRegistrationReservation,
    val tokens: PrivateBackendSessionTokens,
)

internal data class RefreshedPrivateAccountSession(
    val accountId: PrivateAccountId,
    val tokens: PrivateBackendSessionTokens,
)

internal class PrivateSessionRefreshCommand(
    val expectedAccountId: PrivateAccountId,
    refreshToken: String,
) {
    private val refreshTokenCharacters = refreshToken

    init {
        require(SAFE_SESSION_TOKEN.matches(refreshToken)) { "Supabase refresh token is malformed" }
    }

    fun exposeRefreshTokenForRequest(): String = refreshTokenCharacters

    override fun toString(): String = "PrivateSessionRefreshCommand(accountId=$expectedAccountId, refreshToken=[REDACTED])"
}

internal class PrivateSessionSignOutCommand(
    val expectedAccountId: PrivateAccountId,
    accessToken: String,
) {
    private val accessTokenCharacters = accessToken

    init {
        require(SAFE_SESSION_TOKEN.matches(accessToken)) { "Supabase access token is malformed" }
    }

    fun exposeAccessTokenForRequest(): String = accessTokenCharacters

    override fun toString(): String = "PrivateSessionSignOutCommand(accountId=$expectedAccountId, accessToken=[REDACTED])"
}

internal data object PrivateBackendSignOutReceipt

internal data class PrivateDeviceBindingCommand(
    val reservation: PrivateDeviceRegistrationReservation,
    val tokens: PrivateBackendSessionTokens,
    val publicPreKeyBundle: SignalPublicPreKeyBundle,
)

internal data class PrivateDeviceBindingReceipt(
    val accountId: PrivateAccountId,
    val transportDeviceId: UUID,
    val signalDeviceId: SignalDeviceId,
    val displayName: PrivateDisplayName,
    val boundAt: Instant,
)

internal sealed interface PrivateAccountBackendOutcome<out Receipt> {
    data class Confirmed<Receipt>(
        val receipt: Receipt,
    ) : PrivateAccountBackendOutcome<Receipt>

    data class Rejected(
        val userMessage: String,
        val reason: PrivateBackendRejectionReason = PrivateBackendRejectionReason.REMOTE_FAILURE,
    ) : PrivateAccountBackendOutcome<Nothing> {
        init {
            require(
                userMessage.isNotBlank() &&
                    userMessage.length <= PRIVATE_BACKEND_REJECTION_MESSAGE_LIMIT &&
                    userMessage.none(Char::isISOControl),
            ) {
                "Backend rejection requires a bounded user-facing reason."
            }
        }
    }
}

internal enum class PrivateBackendRejectionReason {
    ACCESS_DENIED,
    RATE_LIMITED,
    REMOTE_FAILURE,
}

internal interface PrivateAccountBackend {
    suspend fun authenticate(
        command: PrivateAccountAccessCommand,
        transportDeviceId: UUID,
        registrationRedemptionId: UUID?,
    ): PrivateAccountBackendOutcome<UnboundPrivateAccountSession>

    suspend fun registerDevice(command: PrivateDeviceBindingCommand): PrivateAccountBackendOutcome<PrivateDeviceBindingReceipt>

    suspend fun refreshSession(command: PrivateSessionRefreshCommand): PrivateAccountBackendOutcome<RefreshedPrivateAccountSession>

    suspend fun signOut(command: PrivateSessionSignOutCommand): PrivateAccountBackendOutcome<PrivateBackendSignOutReceipt>
}

private val SAFE_SESSION_TOKEN = Regex("^[A-Za-z0-9._~-]{20,8192}$")
private const val PRIVATE_BACKEND_REJECTION_MESSAGE_LIMIT = 200
