package app.synapse.privatechat.data.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

class UrlConnectionSupabaseHttpTransportTest {
    @Test
    fun cancellationDisconnectsAnActiveRequestBeforeTheReadTimeout() =
        runBlocking {
            val connection = BlockingHttpsURLConnection(URL("https://example.supabase.co"))
            val transport =
                UrlConnectionSupabaseHttpTransport(
                    config = TEST_CONFIG,
                    connectionFactory = { connection },
                )
            val request =
                SupabaseHttpRequest(
                    method = SupabaseHttpMethod.POST,
                    pathSegments = listOf("functions", "v1", "register"),
                    jsonBody = buildJsonObject { put("invitation_code", "sensitive-test-value") },
                )
            val execution = async(Dispatchers.Default) { transport.execute(request) }

            try {
                assertTrue(
                    "The transport never reached the blocking response read",
                    connection.responseReadStarted.await(2, TimeUnit.SECONDS),
                )

                execution.cancel()

                assertTrue(
                    "Cancellation did not disconnect the active HTTPS connection",
                    connection.disconnectCalled.await(2, TimeUnit.SECONDS),
                )
                assertTrue(execution.isCancelled)
            } finally {
                connection.releaseResponseRead.countDown()
                execution.cancelAndJoin()
            }
        }

    private class BlockingHttpsURLConnection(
        url: URL,
    ) : HttpsURLConnection(url) {
        val responseReadStarted = CountDownLatch(1)
        val disconnectCalled = CountDownLatch(1)
        val releaseResponseRead = CountDownLatch(1)

        override fun getResponseCode(): Int {
            responseReadStarted.countDown()
            releaseResponseRead.await()
            throw IOException("Response read interrupted by disconnect")
        }

        override fun disconnect() {
            disconnectCalled.countDown()
            releaseResponseRead.countDown()
        }

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getCipherSuite(): String = "TLS_TEST"

        override fun getLocalCertificates(): Array<Certificate>? = null

        override fun getServerCertificates(): Array<Certificate>? = null
    }

    private companion object {
        val TEST_CONFIG =
            SynapsePrivateBackendConfig.requireValid(
                projectUrl = "https://example.supabase.co",
                publishableKey = "sb_publishable_01234567890123456789",
            )
    }
}
