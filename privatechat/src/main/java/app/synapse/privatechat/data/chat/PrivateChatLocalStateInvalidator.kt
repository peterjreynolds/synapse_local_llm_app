package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.account.PrivateAccountLocalStateInvalidator
import app.synapse.privatechat.data.account.PrivateAccountLocalStatePurgeReceipt

/**
 * Destroys account-bound chat state in dependency order before the session vault is cleared.
 * Pending Signal mutations must be removed before their device-local envelope keys are erased.
 */
internal class PrivateChatLocalStateInvalidator(
    private val pollingRepository: PrivateChatPollingRepository,
    private val envelopeCipher: PrivateChatEnvelopeCipher,
) : PrivateAccountLocalStateInvalidator {
    override suspend fun purgeForSessionInvalidation(): PrivateAccountLocalStatePurgeReceipt {
        pollingRepository.clearForSessionInvalidation()
        envelopeCipher.clearPendingOutboundForSessionInvalidation()
        envelopeCipher.clearLocalEnvelopeKeysForSessionInvalidation()
        return PrivateAccountLocalStatePurgeReceipt.PURGED
    }
}
