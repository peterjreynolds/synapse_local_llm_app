package app.synapse.privatechat.data.chat

/**
 * Keep the durable ciphertext and mutation ID across authentication-refresh, timeout, throttling,
 * and server failures. A failed response is not a durable rejection receipt. This policy does not
 * bypass authorization, mark delivery confirmed, or introduce immediate retries. Existing expiry
 * and account/device binding checks still apply when the normal recovery path runs again.
 */
internal fun shouldRetainPrivateMutationAfterHttpFailure(statusCode: Int): Boolean =
    statusCode == 401 ||
        statusCode == 408 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode in 500..599
