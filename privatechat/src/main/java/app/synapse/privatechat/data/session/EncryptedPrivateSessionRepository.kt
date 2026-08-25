package app.synapse.privatechat.data.session

import app.synapse.privatechat.security.storage.CryptographicallyErasableEncryptedStateStorage

internal class EncryptedPrivateSessionRepository(
    private val encryptedStateStorage: CryptographicallyErasableEncryptedStateStorage,
    private val installationIdGenerator: () -> PrivateInstallationId = PrivateInstallationId::generate,
) {
    private val monitor = Any()
    private var state = loadState()

    fun loadOrCreateInstallationId(): PrivateInstallationId =
        synchronized(monitor) {
            state?.installationId ?: persistNewInstallationIdentity()
        }

    fun loadRegisteredSession(): RegisteredPrivateAccountSession? =
        synchronized(monitor) {
            state?.registeredSession?.copyForStorage()
        }

    fun persistAfterDeviceRegistration(session: RegisteredPrivateAccountSession): PrivateSessionPersistenceReceipt =
        persistRegisteredSession(session)

    fun persistRefreshedSession(session: RegisteredPrivateAccountSession): PrivateSessionPersistenceReceipt =
        persistRegisteredSession(session)

    private fun persistRegisteredSession(session: RegisteredPrivateAccountSession): PrivateSessionPersistenceReceipt =
        synchronized(monitor) {
            val existingState =
                state ?: throw PrivateSessionStateUnavailableException(
                    "Installation identity must be persisted before device registration",
                )
            require(session.installationId == existingState.installationId) {
                "Registered session belongs to a different installation"
            }
            val outcome =
                if (existingState.registeredSession == null) {
                    PrivateSessionPersistenceOutcome.STORED
                } else {
                    PrivateSessionPersistenceOutcome.REPLACED
                }
            val replacementState = PrivateSessionVaultState(existingState.installationId, session.copyForStorage())
            persistState(replacementState)
            state = replacementState
            PrivateSessionPersistenceReceipt(
                accountId = session.accountId,
                installationId = session.installationId,
                outcome = outcome,
            )
        }

    fun clearAuthenticatedSession(): PrivateSessionClearReceipt =
        synchronized(monitor) {
            val existingState = state
            if (existingState?.registeredSession == null) return@synchronized PrivateSessionClearReceipt.ALREADY_EMPTY
            val clearedState = PrivateSessionVaultState(existingState.installationId, registeredSession = null)
            persistState(clearedState)
            state = clearedState
            PrivateSessionClearReceipt.CLEARED
        }

    private fun persistNewInstallationIdentity(): PrivateInstallationId {
        val installationId = installationIdGenerator()
        val initialState = PrivateSessionVaultState(installationId, registeredSession = null)
        persistState(initialState)
        state = initialState
        return installationId
    }

    private fun persistState(replacementState: PrivateSessionVaultState) {
        val plaintext = PrivateSessionVaultCodec.encode(replacementState)
        try {
            // Every vault mutation may supersede credentials, including refresh and signed-out
            // migration, so it must rotate the at-rest key rather than reuse the active slot.
            encryptedStateStorage.replaceAfterCryptographicErasure(plaintext)
        } catch (error: Exception) {
            throw PrivateSessionStateUnavailableException("Private session state commit failed", error)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun loadState(): PrivateSessionVaultState? {
        val plaintext =
            try {
                encryptedStateStorage.readDecryptedState()
            } catch (error: Exception) {
                throw PrivateSessionStateUnavailableException("Private session state could not be read", error)
            } ?: return null
        return try {
            val decoded = PrivateSessionVaultCodec.decodeVersioned(plaintext)
            if (decoded.migrationRequired) {
                persistState(decoded.state)
            }
            decoded.state.copyForStorage()
        } finally {
            plaintext.fill(0)
        }
    }
}
