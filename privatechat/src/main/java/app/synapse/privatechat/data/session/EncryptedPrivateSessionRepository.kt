package app.synapse.privatechat.data.session

import app.synapse.privatechat.security.storage.Aes256GcmEncryptedStateCipher
import app.synapse.privatechat.security.storage.EncryptedStateCipher
import app.synapse.privatechat.security.storage.EncryptedStateFile

internal class EncryptedPrivateSessionRepository(
    private val encryptedStateFile: EncryptedStateFile,
    private val stateCipher: EncryptedStateCipher,
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
            val ciphertext = stateCipher.encrypt(plaintext)
            try {
                encryptedStateFile.replace(ciphertext)
            } finally {
                ciphertext.fill(0)
            }
        } catch (error: Exception) {
            throw PrivateSessionStateUnavailableException("Private session state commit failed", error)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun loadState(): PrivateSessionVaultState? {
        val ciphertext =
            try {
                encryptedStateFile.read(MAX_ENCRYPTED_STATE_BYTES)
            } catch (error: Exception) {
                throw PrivateSessionStateUnavailableException("Private session state could not be read", error)
            } ?: return null
        val plaintext =
            try {
                stateCipher.decrypt(ciphertext)
            } catch (error: Exception) {
                throw PrivateSessionStateUnavailableException("Private session state authentication failed", error)
            }
        return try {
            PrivateSessionVaultCodec.decode(plaintext).copyForStorage()
        } finally {
            plaintext.fill(0)
        }
    }

    private companion object {
        const val MAX_ENCRYPTED_STATE_BYTES =
            PrivateSessionVaultCodec.MAX_PLAINTEXT_BYTES + Aes256GcmEncryptedStateCipher.MAX_ENVELOPE_OVERHEAD_BYTES
    }
}
