package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.supabase.SupabaseHttpMethod
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class SupabasePrivateChatMutationTransport(
    private val requestExecutor: SupabasePrivateChatRequestExecutor,
) {
    suspend fun rpc(
        session: PrivateChatAuthenticatedSession,
        functionName: String,
        body: JsonObject,
    ): SupabaseHttpResponse =
        execute(
            session = session,
            method = SupabaseHttpMethod.POST,
            pathSegments = listOf("rest", "v1", "rpc", functionName),
            body = body,
        )

    suspend fun edgeFunction(
        session: PrivateChatAuthenticatedSession,
        functionName: String,
        body: JsonObject,
    ): SupabaseHttpResponse =
        execute(
            session = session,
            method = SupabaseHttpMethod.POST,
            pathSegments = listOf("functions", "v1", functionName),
            body = body,
        )

    suspend fun tableMutation(
        session: PrivateChatAuthenticatedSession,
        method: SupabaseHttpMethod,
        tableName: String,
        body: kotlinx.serialization.json.JsonElement? = null,
        queryParameters: Map<String, String> = emptyMap(),
        preferHeader: String? = null,
    ): SupabaseHttpResponse =
        execute(
            session = session,
            method = method,
            pathSegments = listOf("rest", "v1", tableName),
            body = body,
            queryParameters = queryParameters,
            preferHeader = preferHeader,
        )

    private suspend fun execute(
        session: PrivateChatAuthenticatedSession,
        method: SupabaseHttpMethod,
        pathSegments: List<String>,
        body: kotlinx.serialization.json.JsonElement?,
        queryParameters: Map<String, String> = emptyMap(),
        preferHeader: String? = null,
    ): SupabaseHttpResponse =
        requestExecutor.execute(
            request =
                SupabaseHttpRequest(
                    method = method,
                    pathSegments = pathSegments,
                    queryParameters = queryParameters,
                    accessToken = session.accessTokenForRequest(),
                    jsonBody = body,
                    preferHeader = preferHeader,
                ),
            repeatability = PrivateChatRequestRepeatability.IDEMPOTENT,
        )
}

internal fun List<PrivateChatEncryptedEnvelope>.toSupabaseEnvelopeRows(): JsonArray =
    buildJsonArray {
        this@toSupabaseEnvelopeRows.forEach { envelope ->
            val ciphertext = envelope.ciphertextCopy()
            try {
                add(
                    buildJsonObject {
                        put("recipient_device_id", envelope.recipientDeviceId.toString())
                        put("protocol_adapter_version", envelope.protocolAdapterVersion)
                        put("signal_message_type", envelope.kind.wireName)
                        put("ciphertext_hex", ciphertext.toLowerHex())
                    },
                )
            } finally {
                ciphertext.fill(0)
            }
        }
    }

internal fun SupabaseHttpResponse.requireAcceptedChatMutation(operation: String): SupabaseHttpResponse {
    if (statusCode !in 200..299) throw requireChatMutationRejection()
    if (jsonBody == null && statusCode != 204) {
        throw SupabasePrivateChatResponseException("Supabase $operation response is empty")
    }
    return this
}
