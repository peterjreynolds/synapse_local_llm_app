package app.synapse.privatechat.data.diagnostics

import app.synapse.privatechat.data.supabase.SupabaseHttpMethod
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateConnectionDiagnosticsTest {
    @Test
    fun reportsExcludeSensitiveRequestResponseAndExceptionText() =
        runBlocking {
            val diagnostics = PrivateConnectionDiagnostics()
            val secret = "private-sensitive-canary"
            val transport =
                DiagnosticSupabaseHttpTransport(
                    object : SupabaseHttpTransport {
                        override suspend fun execute(request: SupabaseHttpRequest) =
                            SupabaseHttpResponse(403, buildJsonObject { put("message", secret) })
                    },
                    diagnostics,
                )
            transport.execute(
                SupabaseHttpRequest(
                    method = SupabaseHttpMethod.POST,
                    pathSegments = listOf(secret),
                    accessToken = secret,
                    jsonBody = buildJsonObject { put("password", secret) },
                ),
            )
            diagnostics.record(PrivateDiagnosticOperation.OBSERVE, failure = IllegalStateException(secret))
            val report = diagnostics.exportReport("0.1.2050", 2050, 25)
            assertFalse(report.contains(secret))
            assertTrue(report.contains("http=403"))
            assertTrue(report.contains("IllegalStateException"))
            assertTrue(report.contains("code=2050"))
        }

    @Test
    fun reportsAreBoundedDuringRepeatedFailure() {
        val diagnostics = PrivateConnectionDiagnostics()
        diagnostics.record(PrivateDiagnosticOperation.HTTP, statusCode = 418)
        repeat(250) { diagnostics.record(PrivateDiagnosticOperation.HTTP, statusCode = 503) }
        val report = diagnostics.exportReport("test", 1, 25)
        assertFalse(report.contains("http=418"))
        org.junit.Assert.assertEquals(200, report.lineSequence().count { it.contains("operation=HTTP") })
    }
}
