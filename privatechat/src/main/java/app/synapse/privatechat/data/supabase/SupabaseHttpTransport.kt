package app.synapse.privatechat.data.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SupabaseHttpMethod {
    GET,
    POST,
    PATCH,
    DELETE,
}

data class SupabaseHttpRequest(
    val method: SupabaseHttpMethod,
    val pathSegments: List<String>,
    val queryParameters: Map<String, String> = emptyMap(),
    val accessToken: String? = null,
    val jsonBody: JsonElement? = null,
    val preferHeader: String? = null,
) {
    init {
        require(pathSegments.isNotEmpty() && pathSegments.all(SAFE_PATH_SEGMENT::matches)) {
            "Supabase request paths require explicit safe segments"
        }
        require(queryParameters.keys.all(SAFE_QUERY_NAME::matches)) {
            "Supabase request query names are invalid"
        }
        require(accessToken == null || SAFE_BEARER_TOKEN.matches(accessToken)) {
            "Supabase access token is malformed"
        }
        require(preferHeader == null || SAFE_PREFER_HEADER.matches(preferHeader)) {
            "Supabase Prefer header is invalid"
        }
        require(method == SupabaseHttpMethod.POST || method == SupabaseHttpMethod.PATCH || jsonBody == null) {
            "Only POST and PATCH requests may contain JSON bodies"
        }
    }

    override fun toString(): String =
        "SupabaseHttpRequest(" +
            "method=$method, " +
            "pathSegments=$pathSegments, " +
            "queryParameterNames=${queryParameters.keys}, " +
            "accessToken=${if (accessToken == null) "ABSENT" else "[REDACTED]"}, " +
            "jsonBody=${if (jsonBody == null) "ABSENT" else "[REDACTED]"}, " +
            "preferHeader=$preferHeader)"
}

data class SupabaseHttpResponse(
    val statusCode: Int,
    val jsonBody: JsonElement?,
) {
    override fun toString(): String =
        "SupabaseHttpResponse(statusCode=$statusCode, jsonBody=${if (jsonBody == null) "ABSENT" else "[REDACTED]"})"
}

class SupabaseTransportException(
    val failure: SupabaseTransportFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

enum class SupabaseTransportFailure {
    NETWORK_UNAVAILABLE,
    RESPONSE_TOO_LARGE,
    MALFORMED_RESPONSE,
    REDIRECT_REJECTED,
}

interface SupabaseHttpTransport {
    suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse
}

class UrlConnectionSupabaseHttpTransport(
    private val config: SynapsePrivateBackendConfig,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maximumResponseBytes: Int = 2 * 1_024 * 1_024,
    private val connectionFactory: (URI) -> HttpsURLConnection = ::openHttpsConnection,
) : SupabaseHttpTransport {
    init {
        require(connectTimeoutMillis in 1_000..60_000) { "Connect timeout is outside the supported range" }
        require(readTimeoutMillis in 1_000..120_000) { "Read timeout is outside the supported range" }
        require(maximumResponseBytes in 1_024..8 * 1_024 * 1_024) {
            "Maximum response size is outside the supported range"
        }
    }

    override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
        val preparedRequest = prepareRequest(request)
        return suspendCancellableCoroutine { continuation ->
            val activeConnection = AtomicReference<HttpsURLConnection?>()
            continuation.invokeOnCancellation {
                activeConnection.getAndSet(null)?.disconnect()
            }
            Dispatchers.IO.dispatch(
                continuation.context,
                Runnable {
                    var connection: HttpsURLConnection? = null
                    try {
                        if (!continuation.isActive) return@Runnable
                        connection = connectionFactory(preparedRequest.uri)
                        activeConnection.set(connection)
                        if (!continuation.isActive) return@Runnable
                        val response = executePreparedRequest(connection, preparedRequest)
                        continuation.resume(response)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error.toTransportFailure())
                    } finally {
                        connection?.let { openedConnection ->
                            activeConnection.compareAndSet(openedConnection, null)
                            openedConnection.disconnect()
                        }
                        preparedRequest.clearEncodedBody()
                    }
                },
            )
        }
    }

    private fun prepareRequest(request: SupabaseHttpRequest): PreparedSupabaseHttpRequest {
        val encodedBody = request.jsonBody?.toString()?.toByteArray(StandardCharsets.UTF_8)
        require(encodedBody == null || encodedBody.size <= MAXIMUM_REQUEST_BYTES) {
            encodedBody?.fill(0)
            "Supabase request body exceeds the transport limit"
        }
        return PreparedSupabaseHttpRequest(
            uri = buildRequestUri(config.projectUri, request),
            method = request.method,
            accessToken = request.accessToken,
            preferHeader = request.preferHeader,
            encodedBody = encodedBody,
        )
    }

    private fun executePreparedRequest(
        connection: HttpsURLConnection,
        request: PreparedSupabaseHttpRequest,
    ): SupabaseHttpResponse {
        configureConnection(connection, request)
        request.encodedBody?.let { encodedBody -> writeRequestBody(connection, encodedBody) }
        val statusCode = connection.responseCode
        if (statusCode in 300..399) {
            throw SupabaseTransportException(
                failure = SupabaseTransportFailure.REDIRECT_REJECTED,
                message = "Supabase transport rejected an unexpected redirect",
            )
        }
        val responseBytes = readResponseBytes(connection, statusCode)
        return SupabaseHttpResponse(
            statusCode = statusCode,
            jsonBody = parseResponseJson(responseBytes),
        )
    }

    private fun configureConnection(
        connection: HttpsURLConnection,
        request: PreparedSupabaseHttpRequest,
    ) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = request.method.name
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("apikey", config.publishableKey)
        request.accessToken?.let { token ->
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
        request.preferHeader?.let { preference ->
            connection.setRequestProperty("Prefer", preference)
        }
        if (request.encodedBody != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }

    private fun writeRequestBody(
        connection: HttpsURLConnection,
        encodedBody: ByteArray,
    ) {
        connection.setFixedLengthStreamingMode(encodedBody.size)
        try {
            connection.outputStream.use { output -> output.write(encodedBody) }
        } finally {
            encodedBody.fill(0)
        }
    }

    private fun readResponseBytes(
        connection: HttpURLConnection,
        statusCode: Int,
    ): ByteArray {
        val responseStream =
            if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: return ByteArray(0)
            }
        return responseStream.use { input ->
            val output = ByteArrayOutputStream(minOf(maximumResponseBytes, DEFAULT_BUFFER_SIZE))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                totalBytes += bytesRead
                if (totalBytes > maximumResponseBytes) {
                    throw SupabaseTransportException(
                        failure = SupabaseTransportFailure.RESPONSE_TOO_LARGE,
                        message = "Supabase response exceeds the transport limit",
                    )
                }
                output.write(buffer, 0, bytesRead)
            }
            output.toByteArray()
        }
    }

    private fun parseResponseJson(responseBytes: ByteArray): JsonElement? {
        if (responseBytes.isEmpty()) return null
        return try {
            STRICT_JSON.parseToJsonElement(responseBytes.toString(StandardCharsets.UTF_8))
        } catch (error: IllegalArgumentException) {
            throw SupabaseTransportException(
                failure = SupabaseTransportFailure.MALFORMED_RESPONSE,
                message = "Supabase returned malformed JSON",
                cause = error,
            )
        }
    }
}

private class PreparedSupabaseHttpRequest(
    val uri: URI,
    val method: SupabaseHttpMethod,
    val accessToken: String?,
    val preferHeader: String?,
    val encodedBody: ByteArray?,
) {
    fun clearEncodedBody() {
        encodedBody?.fill(0)
    }
}

private fun openHttpsConnection(uri: URI): HttpsURLConnection =
    uri.toURL().openConnection().let { openedConnection ->
        require(openedConnection is HttpsURLConnection) { "Supabase transport requires HTTPS" }
        openedConnection
    }

private fun Throwable.toTransportFailure(): Throwable =
    when (this) {
        is SupabaseTransportException -> this
        is IOException ->
            SupabaseTransportException(
                failure = SupabaseTransportFailure.NETWORK_UNAVAILABLE,
                message = "Supabase transport failed",
                cause = this,
            )

        else -> this
    }

internal fun buildRequestUri(
    projectUri: URI,
    request: SupabaseHttpRequest,
): URI {
    val path = request.pathSegments.joinToString(separator = "/", prefix = "/")
    val query =
        request.queryParameters.entries
            .sortedBy(Map.Entry<String, String>::key)
            .joinToString(separator = "&") { (name, value) ->
                "$name=${encodeQueryValue(value)}"
            }.ifEmpty { null }
    val querySuffix = query?.let { encodedQuery -> "?$encodedQuery" }.orEmpty()
    return URI.create(projectUri.toASCIIString() + path + querySuffix)
}

private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private const val MAXIMUM_REQUEST_BYTES = 512 * 1_024
private val SAFE_PATH_SEGMENT = Regex("^[A-Za-z0-9._~-]+$")
private val SAFE_QUERY_NAME = Regex("^[a-z][a-z0-9_]*$")
private val SAFE_BEARER_TOKEN = Regex("^[A-Za-z0-9._~-]{20,8192}$")
private val SAFE_PREFER_HEADER = Regex("^[A-Za-z0-9 =,.-]{1,200}$")
private val STRICT_JSON = Json { ignoreUnknownKeys = false }
