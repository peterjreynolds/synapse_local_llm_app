package app.synapse.privatechat.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal interface PrivateUpdateTransferSource {
    suspend fun readMetadata(metadataUrl: String): String

    fun openApk(apkUrl: String): PrivateUpdateApkResponse
}

internal class PrivateUpdateApkResponse(
    val contentLength: Long?,
    val inputStream: InputStream,
    private val closeConnection: () -> Unit,
) : Closeable {
    override fun close() {
        try {
            inputStream.close()
        } finally {
            closeConnection()
        }
    }
}

internal class UrlConnectionPrivateUpdateSource(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
    private val connectionFactory: (URI) -> HttpsURLConnection = ::openPrivateUpdateHttpsConnection,
) : PrivateUpdateTransferSource {
    init {
        require(connectTimeoutMillis in 1_000..60_000) { "Update connect timeout is invalid." }
        require(readTimeoutMillis in 1_000..120_000) { "Update read timeout is invalid." }
    }

    override suspend fun readMetadata(metadataUrl: String): String =
        withContext(Dispatchers.IO) {
            require(metadataUrl == SynapsePrivateUpdateTrust.METADATA_URL) { "Update metadata URL is untrusted." }
            val connection = openSuccessfulConnection(URI.create(metadataUrl), "application/json")
            try {
                val declaredLength = connection.contentLengthLong
                if (declaredLength > SynapsePrivateUpdateTrust.MAXIMUM_METADATA_BYTES) {
                    throw IOException("Update metadata exceeds the supported size.")
                }
                connection.inputStream.use { input ->
                    readBoundedBytes(input, SynapsePrivateUpdateTrust.MAXIMUM_METADATA_BYTES)
                        .toString(Charsets.UTF_8)
                }
            } finally {
                connection.disconnect()
            }
        }

    override fun openApk(apkUrl: String): PrivateUpdateApkResponse {
        require(apkUrl == SynapsePrivateUpdateTrust.APK_URL) { "Update APK URL is untrusted." }
        val connection = openSuccessfulConnection(URI.create(apkUrl), APK_MIME_TYPE)
        return try {
            PrivateUpdateApkResponse(
                contentLength = connection.contentLengthLong.takeIf { length -> length >= 0L },
                inputStream = connection.inputStream,
                closeConnection = connection::disconnect,
            )
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private fun openSuccessfulConnection(
        initialUri: URI,
        accept: String,
    ): HttpsURLConnection {
        var requestUri = initialUri
        repeat(MAXIMUM_REDIRECTS + 1) { redirectCount ->
            assertTrustedUpdateRequestUri(initialUri, requestUri, redirectCount)
            val connection = connectionFactory(requestUri)
            configureConnection(connection, accept)
            val statusCode = connection.responseCode
            if (statusCode == HttpsURLConnection.HTTP_OK) return connection
            if (statusCode !in TRUSTED_REDIRECT_STATUS_CODES || redirectCount == MAXIMUM_REDIRECTS) {
                connection.disconnect()
                throw IOException("Update request failed with HTTP $statusCode.")
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw IOException("Update redirect did not include a destination.")
            requestUri = requestUri.resolve(location)
        }
        throw IOException("Update request exceeded the redirect limit.")
    }

    private fun configureConnection(
        connection: HttpsURLConnection,
        accept: String,
    ) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", "Synapse-Private-Android")
    }

    private fun readBoundedBytes(
        input: InputStream,
        maximumBytes: Int,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            totalBytes += bytesRead
            if (totalBytes > maximumBytes) throw IOException("Update metadata exceeds the supported size.")
            output.write(buffer, 0, bytesRead)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAXIMUM_REDIRECTS = 3
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        val TRUSTED_REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal fun assertTrustedUpdateRequestUri(
    initialUri: URI,
    requestUri: URI,
    redirectCount: Int,
) {
    require(requestUri.scheme == "https" && requestUri.userInfo == null && requestUri.fragment == null) {
        "Update request must use trusted HTTPS."
    }
    if (redirectCount == 0) {
        require(requestUri == initialUri && requestUri.host == "github.com") { "Initial update URL is untrusted." }
        return
    }
    require(
        requestUri.host == "release-assets.githubusercontent.com" &&
            requestUri.path.startsWith(TRUSTED_RELEASE_ASSET_PATH_PREFIX),
    ) { "Update redirect destination is untrusted." }
}

private fun openPrivateUpdateHttpsConnection(uri: URI): HttpsURLConnection =
    uri.toURL().openConnection().let { connection ->
        require(connection is HttpsURLConnection) { "Update transport requires HTTPS." }
        connection
    }

private const val TRUSTED_RELEASE_ASSET_PATH_PREFIX = "/github-production-release-asset/1271086817/"
