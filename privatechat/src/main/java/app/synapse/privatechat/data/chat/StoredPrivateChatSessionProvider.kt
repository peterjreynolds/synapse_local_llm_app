package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.session.EncryptedPrivateSessionRepository

/** Adapts the single encrypted account vault into the minimum authenticated chat session. */
internal class StoredPrivateChatSessionProvider(
    private val sessionRepository: EncryptedPrivateSessionRepository,
) : PrivateChatAuthenticatedSessionProvider {
    override fun loadAuthenticatedSession(): PrivateChatAuthenticatedSession? =
        sessionRepository.loadRegisteredSession()?.let { session ->
            PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
                accountId = session.accountId,
                transportDeviceId = session.installationId.uuid,
                signalDeviceId = session.signalDeviceId,
                authenticationUsername = session.authenticationUsername,
                accessToken = session.accessTokenForAuthorization(),
                expiresAt = session.expiresAt,
            )
        }
}
