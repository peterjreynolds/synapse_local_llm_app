package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.SignalProtocolStateCorruptedException
import app.synapse.privatechat.data.session.ConfirmedPrivateDeviceRegistration
import app.synapse.privatechat.data.session.EncryptedPrivateSessionRepository
import app.synapse.privatechat.data.session.PrivateSessionStateUnavailableException
import app.synapse.privatechat.data.session.RegisteredPrivateAccountSession
import app.synapse.privatechat.data.supabase.SupabaseTransportException
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateAccountSessionOutcome
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.domain.account.PrivateAccountSignOutOutcome
import app.synapse.privatechat.domain.account.PrivateDisplayName
import app.synapse.privatechat.domain.account.PrivateRemoteSessionRevocationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.UUID

internal class SupabasePrivateAccountGateway(
    private val backend: PrivateAccountBackend,
    private val signalDeviceBootstrapper: PrivateSignalDeviceBootstrapper,
    private val sessionRepository: EncryptedPrivateSessionRepository,
    private val localStateInvalidator: PrivateAccountLocalStateInvalidator = NoStoredPrivateConversationStateInvalidator,
    private val clock: Clock = Clock.systemUTC(),
) : PrivateAccountGateway {
    private val sessionMutationMutex = Mutex()

    override suspend fun requestPrivateAccountAccess(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome =
        sessionMutationMutex.withLock {
            try {
                requestAndPersistBoundAccount(command)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SupabaseTransportException) {
                PrivateAccountAccessOutcome.TransportUnavailable
            } catch (_: PrivateSessionStateUnavailableException) {
                PrivateAccountAccessOutcome.LocalStateUnavailable
            } catch (_: SignalProtocolStateCorruptedException) {
                PrivateAccountAccessOutcome.LocalStateUnavailable
            } catch (_: PrivateDeviceIdentityConflictException) {
                PrivateAccountAccessOutcome.LocalStateUnavailable
            } catch (_: SupabaseAccountResponseException) {
                PrivateAccountAccessOutcome.VerificationFailed
            } catch (_: IllegalArgumentException) {
                PrivateAccountAccessOutcome.VerificationFailed
            }
        }

    override suspend fun restorePrivateAccountSession(): PrivateAccountSessionOutcome =
        sessionMutationMutex.withLock {
            guardSessionOperation {
                val session =
                    sessionRepository.loadRegisteredSession()
                        ?: return@guardSessionOperation signedOutAfterPurgingLocalState()
                if (session.expiresAt.isAfter(clock.instant().plusSeconds(SESSION_REFRESH_WINDOW_SECONDS))) {
                    session.toActiveOutcome()
                } else {
                    refreshSession(session)
                }
            }
        }

    override suspend fun refreshPrivateAccountSession(): PrivateAccountSessionOutcome =
        sessionMutationMutex.withLock {
            guardSessionOperation {
                val session =
                    sessionRepository.loadRegisteredSession()
                        ?: return@guardSessionOperation signedOutAfterPurgingLocalState()
                refreshSession(session)
            }
        }

    override suspend fun signOutPrivateAccount(): PrivateAccountSignOutOutcome =
        sessionMutationMutex.withLock {
            try {
                val storedSession =
                    sessionRepository.loadRegisteredSession()
                        ?: run {
                            purgeLocalConversationState()
                            return@withLock PrivateAccountSignOutOutcome.AlreadySignedOut
                        }
                purgeLocalConversationState()
                sessionRepository.clearAuthenticatedSession()
                PrivateAccountSignOutOutcome.LocallySignedOut(
                    remoteRevocation = bestEffortRemoteSessionRevocation(storedSession),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: PrivateAccountLocalStateUnavailableException) {
                PrivateAccountSignOutOutcome.LocalStateUnavailable
            } catch (_: PrivateSessionStateUnavailableException) {
                PrivateAccountSignOutOutcome.LocalStateUnavailable
            } catch (_: Exception) {
                PrivateAccountSignOutOutcome.VerificationFailed
            }
        }

    private suspend fun signedOutAfterPurgingLocalState(): PrivateAccountSessionOutcome {
        purgeLocalConversationState()
        return PrivateAccountSessionOutcome.SignedOut
    }

    private suspend fun purgeLocalConversationState(): PrivateAccountLocalStatePurgeReceipt =
        try {
            localStateInvalidator.purgeForSessionInvalidation()
        } catch (error: PrivateAccountLocalStateUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw PrivateAccountLocalStateUnavailableException(
                "Local conversation state could not be purged before account invalidation",
                error,
            )
        }

    private suspend fun bestEffortRemoteSessionRevocation(
        storedSession: RegisteredPrivateAccountSession,
    ): PrivateRemoteSessionRevocationStatus =
        try {
            val expectedAccountId = PrivateAccountId(storedSession.accountId.toString())
            val accessToken =
                if (storedSession.expiresAt.isAfter(clock.instant().plusSeconds(SESSION_REFRESH_WINDOW_SECONDS))) {
                    storedSession.accessTokenForAuthorization()
                } else {
                    when (
                        val refreshOutcome =
                            backend.refreshSession(
                                PrivateSessionRefreshCommand(
                                    expectedAccountId = expectedAccountId,
                                    refreshToken = storedSession.refreshTokenForRenewal(),
                                ),
                            )
                    ) {
                        is PrivateAccountBackendOutcome.Confirmed -> {
                            val refreshed = refreshOutcome.receipt
                            if (refreshed.accountId != expectedAccountId) {
                                return PrivateRemoteSessionRevocationStatus.VerificationFailed
                            }
                            refreshed.tokens.exposeAccessTokenForRequest()
                        }

                        is PrivateAccountBackendOutcome.Rejected ->
                            return refreshOutcome.toRemoteRevocationStatus()
                    }
                }

            when (
                val signOutOutcome =
                    backend.signOut(
                        PrivateSessionSignOutCommand(
                            expectedAccountId = expectedAccountId,
                            accessToken = accessToken,
                        ),
                    )
            ) {
                is PrivateAccountBackendOutcome.Confirmed -> PrivateRemoteSessionRevocationStatus.Confirmed
                is PrivateAccountBackendOutcome.Rejected -> signOutOutcome.toRemoteRevocationStatus()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SupabaseTransportException) {
            PrivateRemoteSessionRevocationStatus.TransportUnavailable
        } catch (_: SupabaseAccountResponseException) {
            PrivateRemoteSessionRevocationStatus.VerificationFailed
        } catch (_: IllegalArgumentException) {
            PrivateRemoteSessionRevocationStatus.VerificationFailed
        } catch (_: Exception) {
            PrivateRemoteSessionRevocationStatus.VerificationFailed
        }

    private suspend fun requestAndPersistBoundAccount(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome {
        if (sessionRepository.loadRegisteredSession() != null) {
            return PrivateAccountAccessOutcome.Denied(ACCOUNT_SWITCH_REQUIRES_SIGN_OUT_MESSAGE)
        }
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
                authenticationUsername = command.username.canonical,
                pseudonymousDisplayName = deviceBinding.displayName.canonical,
            )
        sessionRepository.persistAfterDeviceRegistration(registeredSession)
        return PrivateAccountAccessOutcome.Confirmed(registeredSession.toActiveReceipt())
    }

    private suspend fun refreshSession(session: RegisteredPrivateAccountSession): PrivateAccountSessionOutcome {
        val expectedAccountId = PrivateAccountId(session.accountId.toString())
        return when (
            val outcome =
                backend.refreshSession(
                    PrivateSessionRefreshCommand(
                        expectedAccountId = expectedAccountId,
                        refreshToken = session.refreshTokenForRenewal(),
                    ),
                )
        ) {
            is PrivateAccountBackendOutcome.Confirmed -> {
                val refreshed = outcome.receipt
                if (
                    refreshed.accountId != expectedAccountId ||
                    !refreshed.tokens.expiresAt.isAfter(session.expiresAt)
                ) {
                    return PrivateAccountSessionOutcome.VerificationFailed
                }
                val replacement =
                    session.withRefreshedTokens(
                        receiptAccountId = UUID.fromString(refreshed.accountId.canonical),
                        accessToken = refreshed.tokens.exposeAccessTokenForRequest(),
                        refreshToken = refreshed.tokens.exposeRefreshTokenForRequest(),
                        expiresAt = refreshed.tokens.expiresAt,
                    )
                sessionRepository.persistRefreshedSession(replacement)
                replacement.toActiveOutcome()
            }

            is PrivateAccountBackendOutcome.Rejected ->
                if (outcome.reason == PrivateBackendRejectionReason.ACCESS_DENIED) {
                    purgeLocalConversationState()
                    sessionRepository.clearAuthenticatedSession()
                    PrivateAccountSessionOutcome.SignedOut
                } else {
                    PrivateAccountSessionOutcome.VerificationRejected(outcome.userMessage)
                }
        }
    }

    private suspend fun guardSessionOperation(operation: suspend () -> PrivateAccountSessionOutcome): PrivateAccountSessionOutcome =
        try {
            operation()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: SupabaseTransportException) {
            PrivateAccountSessionOutcome.TransportUnavailable
        } catch (_: PrivateSessionStateUnavailableException) {
            PrivateAccountSessionOutcome.LocalStateUnavailable
        } catch (_: PrivateAccountLocalStateUnavailableException) {
            PrivateAccountSessionOutcome.LocalStateUnavailable
        } catch (_: SupabaseAccountResponseException) {
            PrivateAccountSessionOutcome.VerificationFailed
        } catch (_: IllegalArgumentException) {
            PrivateAccountSessionOutcome.VerificationFailed
        }

    private fun RegisteredPrivateAccountSession.toActiveOutcome(): PrivateAccountSessionOutcome.Active =
        PrivateAccountSessionOutcome.Active(toActiveReceipt())

    private fun RegisteredPrivateAccountSession.toActiveReceipt(): PrivateAccountSessionReceipt.Active =
        PrivateAccountSessionReceipt.Active(
            accountId = PrivateAccountId(accountId.toString()),
            displayName = PrivateDisplayName(pseudonymousDisplayName),
            expiresAt = expiresAt,
        )

    private companion object {
        const val ACCOUNT_SWITCH_REQUIRES_SIGN_OUT_MESSAGE = "Sign out before accessing another account."
        const val SESSION_REFRESH_WINDOW_SECONDS = 60L
    }
}

private fun PrivateAccountBackendOutcome.Rejected.toRemoteRevocationStatus(): PrivateRemoteSessionRevocationStatus =
    if (reason == PrivateBackendRejectionReason.ACCESS_DENIED) {
        PrivateRemoteSessionRevocationStatus.AlreadyInactive
    } else {
        PrivateRemoteSessionRevocationStatus.Rejected(userMessage)
    }
