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

    fun registrationSeed(): PrivateInstallationId =
        synchronized(monitor) {
            loadOrCreateInstallationId()
            requireNotNull(state).registrationSeed
        }

    fun pendingUsername(): String? = synchronized(monitor) { state?.pendingUsername }

    // Retire the old device durably BEFORE erasing its Signal keys. A crash leaves a
    // signed-out state that must finish erasure before a new account attempt can begin.
    fun retirePendingIdentity() =
        synchronized(monitor) {
            val existing = requireNotNull(state)
            check(existing.registeredSession == null)
            val replacement = PrivateSessionVaultState(installationIdGenerator(), null, existing.registrationSeed)
            persistState(replacement)
            state = replacement
        }

    fun beginAccountAccess(username: String) =
        synchronized(monitor) {
            loadOrCreateInstallationId()
            val existing = requireNotNull(state)
            check(existing.registeredSession == null && existing.pendingUsername == null)
            val replacement = existing.copy(pendingUsername = username)
            persistState(replacement)
            state = replacement
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
            val replacementState = existingState.copy(registeredSession = session.copyForStorage(), pendingUsername = null)
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
            val clearedState = PrivateSessionVaultState(installationIdGenerator(), registeredSession = null)
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
            val migrated =
                if (decoded.migrationRequired && decoded.state.registeredSession == null) {
                    // Preserve the old invite redemption seed, but never reuse its transport UUID.
                    decoded.state.copy(installationId = installationIdGenerator())
                } else {
                    decoded.state
                }
            if (decoded.migrationRequired) persistState(migrated)
            migrated.copyForStorage()
        } finally {
            plaintext.fill(0)
        }
    }
}
