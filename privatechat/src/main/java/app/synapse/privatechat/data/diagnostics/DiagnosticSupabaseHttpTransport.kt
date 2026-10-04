package app.synapse.privatechat.data.diagnostics

import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import kotlinx.coroutines.CancellationException

/** Records status and code locations only, never the request or response. */
internal class DiagnosticSupabaseHttpTransport(
    private val transport: SupabaseHttpTransport,
    private val diagnostics: PrivateConnectionDiagnostics,
) : SupabaseHttpTransport {
    override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse =
        try {
            transport.execute(request).also { response ->
                diagnostics.record(PrivateDiagnosticOperation.HTTP, statusCode = response.statusCode)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            diagnostics.record(PrivateDiagnosticOperation.HTTP, failure = failure)
            throw failure
        }
}
