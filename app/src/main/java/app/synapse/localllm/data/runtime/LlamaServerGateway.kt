package app.synapse.localllm.data.runtime

import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.runtime.ChatCompletionRequest
import app.synapse.localllm.domain.runtime.ChatStreamEvent
import app.synapse.localllm.domain.runtime.RuntimeStatus
import app.synapse.localllm.domain.time.SynapseClock
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LlamaServerGateway(
    private val httpClient: OkHttpClient,
    private val clock: SynapseClock,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun checkStatus(baseUrl: String): RuntimeStatus =
        withContext(Dispatchers.IO) {
            val normalizedBaseUrl = baseUrl.removeSuffix("/")
            val request = Request.Builder()
                .url("$normalizedBaseUrl/v1/models")
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        RuntimeStatus.Ready(
                            baseUrl = normalizedBaseUrl,
                            checkedAt = clock.now(),
                        )
                    } else {
                        RuntimeStatus.Unreachable(
                            baseUrl = normalizedBaseUrl,
                            checkedAt = clock.now(),
                            reason = "llama-server returned HTTP ${response.code}.",
                        )
                    }
                }
            } catch (exception: IOException) {
                RuntimeStatus.Unreachable(
                    baseUrl = normalizedBaseUrl,
                    checkedAt = clock.now(),
                    reason = exception.message ?: "llama-server is unreachable.",
                )
            }
        }

    fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatStreamEvent> =
        flow {
            val normalizedBaseUrl = request.baseUrl.removeSuffix("/")
            val openAiRequest = OpenAiChatCompletionRequest(
                model = request.model,
                stream = true,
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                messages = request.messages.map { message ->
                    OpenAiChatMessage(
                        role = message.role.toOpenAiRole(),
                        content = message.content,
                    )
                },
            )
            val httpRequest = Request.Builder()
                .url("$normalizedBaseUrl/v1/chat/completions")
                .post(json.encodeToString(openAiRequest).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                httpClient.newCall(httpRequest).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful) {
                        emit(
                            ChatStreamEvent.Failed(
                                "llama-server chat request failed with HTTP ${response.code}.",
                            ),
                        )
                        return@use
                    }

                    while (true) {
                        val line = body.source().readUtf8Line() ?: break
                        val payload = line.removePrefix("data:").trim()
                        if (!line.startsWith("data:") || payload.isBlank()) continue
                        if (payload == "[DONE]") break

                        val token = parseToken(payload)
                        if (!token.isNullOrBlank()) {
                            emit(ChatStreamEvent.Token(token))
                        }
                    }
                    emit(ChatStreamEvent.Completed(clock.now()))
                }
            } catch (exception: IOException) {
                emit(ChatStreamEvent.Failed(exception.message ?: "llama-server stream failed."))
            }
        }.flowOn(Dispatchers.IO)

    private fun parseToken(payload: String): String? =
        runCatching {
            json.decodeFromString<OpenAiChatCompletionChunk>(payload)
                .choices
                .firstOrNull()
                ?.delta
                ?.content
        }.getOrNull()

    private fun ConversationRole.toOpenAiRole(): String =
        when (this) {
            ConversationRole.SYSTEM -> "system"
            ConversationRole.USER -> "user"
            ConversationRole.ASSISTANT -> "assistant"
        }

    @Serializable
    private data class OpenAiChatCompletionRequest(
        val model: String,
        val stream: Boolean,
        val temperature: Double,
        @SerialName("max_tokens") val maxTokens: Int,
        val messages: List<OpenAiChatMessage>,
    )

    @Serializable
    private data class OpenAiChatMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class OpenAiChatCompletionChunk(
        val choices: List<OpenAiChatChoice> = emptyList(),
    )

    @Serializable
    private data class OpenAiChatChoice(
        val delta: OpenAiDelta? = null,
    )

    @Serializable
    private data class OpenAiDelta(
        val content: String? = null,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
