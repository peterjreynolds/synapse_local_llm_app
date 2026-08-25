package app.synapse.privatechat.data.account

/** Account-owned boundary for destroying locally decrypted conversation state before auth changes. */
internal fun interface PrivateAccountLocalStateInvalidator {
    suspend fun purgeForSessionInvalidation(): PrivateAccountLocalStatePurgeReceipt
}

internal enum class PrivateAccountLocalStatePurgeReceipt {
    PURGED,
    ALREADY_EMPTY,
}

/** Valid only while no live chat adapter has created a decrypted payload cache. */
internal object NoStoredPrivateConversationStateInvalidator : PrivateAccountLocalStateInvalidator {
    override suspend fun purgeForSessionInvalidation(): PrivateAccountLocalStatePurgeReceipt =
        PrivateAccountLocalStatePurgeReceipt.ALREADY_EMPTY
}

internal class PrivateAccountLocalStateUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
