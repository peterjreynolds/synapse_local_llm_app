package app.synapse.privatechat.data.account

import app.synapse.privatechat.data.session.ConfirmedPrivateDeviceRegistration
import app.synapse.privatechat.data.session.EncryptedPrivateSessionRepository
import app.synapse.privatechat.data.session.RegisteredPrivateAccountSession
import app.synapse.privatechat.data.supabase.SupabaseTransportException
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import java.util.UUID

internal class SupabasePrivateAccountGateway(
    private val backend: PrivateAccountBackend,
    private val signalDeviceBootstrapper: PrivateSignalDeviceBootstrapper,
    private val sessionRepository: EncryptedPrivateSessionRepository,
) : PrivateAccountGateway {
    override suspend fun requestPrivateAccountAccess(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome =
        try {
            requestAndPersistBoundAccount(command)
        } catch (_: SupabaseTransportException) {
            PrivateAccountAccessOutcome.TransportUnavailable
        }

    private suspend fun requestAndPersistBoundAccount(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome {
        val installationId = sessionRepository.loadOrCreateInstallationId()
        val registrationRedemptionId =
            when (command) {
                is PrivateAccountAccessCommand.RegisterWithInvite ->
                    PrivateRegistrationRedemptionIdFactory.derive(installationId, command.invitationCode)

                is PrivateAccountAccessCommand.SignIn -> null
            }
        val authentication =
            when (
                val outcome =
                    backend.authenticate(
                        command = command,
                        transportDeviceId = installationId.uuid,
                        registrationRedemptionId = registrationRedemptionId,
                    )
            ) {
                is PrivateAccountBackendOutcome.Confirmed -> outcome.receipt
                is PrivateAccountBackendOutcome.Rejected -> return PrivateAccountAccessOutcome.Denied(outcome.userMessage)
            }
        val publicBundle = signalDeviceBootstrapper.preparePublicBundle(authentication.reservation)
        val deviceBinding =
            when (
                val outcome =
                    backend.registerDevice(
                        PrivateDeviceBindingCommand(
                            reservation = authentication.reservation,
                            tokens = authentication.tokens,
                            publicPreKeyBundle = publicBundle,
                        ),
                    )
            ) {
                is PrivateAccountBackendOutcome.Confirmed -> outcome.receipt
                is PrivateAccountBackendOutcome.Rejected -> return PrivateAccountAccessOutcome.Denied(outcome.userMessage)
            }
        val authenticatedAccountId = UUID.fromString(authentication.reservation.accountId.canonical)
        val confirmedRegistration =
            ConfirmedPrivateDeviceRegistration.confirmMatchingReceipt(
                authenticatedAccountId = authenticatedAccountId,
                requestedInstallationId = installationId,
                allocatedSignalDeviceId = authentication.reservation.signalDeviceId,
                receiptAccountId = UUID.fromString(deviceBinding.accountId.canonical),
                receiptTransportDeviceId = deviceBinding.transportDeviceId,
                receiptSignalDeviceId = deviceBinding.signalDeviceId,
            )
        val registeredSession =
            RegisteredPrivateAccountSession.afterDeviceRegistration(
                registration = confirmedRegistration,
                accessToken = authentication.tokens.exposeAccessTokenForRequest(),
                refreshToken = authentication.tokens.exposeRefreshTokenForRequest(),
                expiresAt = authentication.tokens.expiresAt,
                pseudonymousDisplayName = deviceBinding.displayName.canonical,
            )
        sessionRepository.persistAfterDeviceRegistration(registeredSession)
        return PrivateAccountAccessOutcome.Confirmed(
            PrivateAccountSessionReceipt.Active(
                accountId = authentication.reservation.accountId,
                displayName = deviceBinding.displayName,
            ),
        )
    }
}
