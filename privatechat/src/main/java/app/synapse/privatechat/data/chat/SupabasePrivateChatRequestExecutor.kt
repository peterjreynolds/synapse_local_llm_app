package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import app.synapse.privatechat.data.supabase.SupabaseTransportException
import app.synapse.privatechat.data.supabase.SupabaseTransportFailure
import kotlinx.coroutines.delay

internal enum class PrivateChatRequestRepeatability {
    IDEMPOTENT,
    NON_IDEMPOTENT,
}

/** Retries only requests whose externally visible mutation identity is stable. */
internal class SupabasePrivateChatRequestExecutor(
    private val transport: SupabaseHttpTransport,
    private val retryDelay: suspend (Long) -> Unit = { delayMillis -> delay(delayMillis) },
) {
    suspend fun execute(
        request: SupabaseHttpRequest,
        repeatability: PrivateChatRequestRepeatability,
    ): SupabaseHttpResponse {
        var attempt = 1
        while (true) {
            try {
                val response = transport.execute(request)
                if (!shouldRetryStatus(response.statusCode, repeatability, attempt)) return response
            } catch (failure: SupabaseTransportException) {
                if (!shouldRetryFailure(failure, repeatability, attempt)) throw failure
            }
            retryDelay(RETRY_DELAYS_MILLIS[attempt - 1])
            attempt += 1
        }
    }
}

private fun shouldRetryStatus(
    statusCode: Int,
    repeatability: PrivateChatRequestRepeatability,
    attempt: Int,
): Boolean =
    repeatability == PrivateChatRequestRepeatability.IDEMPOTENT &&
        attempt <= RETRY_DELAYS_MILLIS.size &&
        statusCode in RETRYABLE_HTTP_STATUS_CODES

private fun shouldRetryFailure(
    failure: SupabaseTransportException,
    repeatability: PrivateChatRequestRepeatability,
    attempt: Int,
): Boolean =
    repeatability == PrivateChatRequestRepeatability.IDEMPOTENT &&
        attempt <= RETRY_DELAYS_MILLIS.size &&
        failure.failure == SupabaseTransportFailure.NETWORK_UNAVAILABLE

private val RETRY_DELAYS_MILLIS = longArrayOf(100L, 300L)
private val RETRYABLE_HTTP_STATUS_CODES = setOf(502, 503, 504)
