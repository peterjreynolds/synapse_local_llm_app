package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SupabasePrivateChatPollingApiTest {
    @Test
    fun deniedActivityFeedsDoNotDiscardCorePollingState() =
        runBlocking {
            val api =
                pollingApi(
                    mapOf(
                        "typing_state" to 403,
                        "presence_state" to 403,
                    ),
                )

            val state = api.loadPollingState(authenticatedSession(), POLL_TIME)

            assertTrue(state.profiles.isEmpty())
            assertTrue(state.rooms.isEmpty())
            assertSame(PrivateBackendActivityFeed.AccessDenied, state.typing)
            assertSame(PrivateBackendActivityFeed.AccessDenied, state.presence)
        }

    @Test
    fun authenticationFailureStillFailsTheWholePoll() {
        val api = pollingApi(mapOf("typing_state" to 401))

        val rejection =
            assertThrows(SupabasePrivateChatRequestRejectedException::class.java) {
                runBlocking { api.loadPollingState(authenticatedSession(), POLL_TIME) }
            }

        assertTrue(rejection.statusCode == 401)
    }

    private fun pollingApi(tableStatuses: Map<String, Int>): SupabasePrivateChatPollingApi =
        SupabasePrivateChatPollingApi(
            SupabasePrivateChatRequestExecutor(
                transport = TableStatusTransport(tableStatuses),
                retryDelay = {},
            ),
        )

    private fun authenticatedSession(): PrivateChatAuthenticatedSession =
        PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
            accountId = ACCOUNT_ID,
            transportDeviceId = DEVICE_ID,
            signalDeviceId = SignalDeviceId.fromWire(1),
            authenticationUsername = "private_user",
            accessToken = "header.payload.signature-material",
            expiresAt = POLL_TIME.plusSeconds(3_600),
        )

    private companion object {
        val ACCOUNT_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val DEVICE_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val POLL_TIME: Instant = Instant.parse("2026-08-27T07:00:00Z")
    }
}

private class TableStatusTransport(
    private val tableStatuses: Map<String, Int>,
) : SupabaseHttpTransport {
    override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
        val statusCode = tableStatuses[request.pathSegments.last()] ?: 200
        return SupabaseHttpResponse(
            statusCode = statusCode,
            jsonBody =
                if (statusCode in 200..299) {
                    JsonArray(emptyList())
                } else {
                    buildJsonObject { put("message", "Request rejected") }
                },
        )
    }
}
