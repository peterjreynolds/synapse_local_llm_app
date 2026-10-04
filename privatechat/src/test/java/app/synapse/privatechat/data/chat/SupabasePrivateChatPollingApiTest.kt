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
        val api = pollingApi(mapOf("messages" to 401))

        val rejection =
            assertThrows(SupabasePrivateChatRequestRejectedException::class.java) {
                runBlocking { api.loadPollingState(authenticatedSession(), POLL_TIME) }
            }

        assertTrue(rejection.statusCode == 401)
    }

    @Test
    fun allActivityHttpFailuresRemainSeparateFromMessageHealth() =
        runBlocking {
            for (status in listOf(401, 429, 500, 503)) {
                val api = pollingApi(mapOf("presence_state" to status, "typing_state" to status))
                val state = api.loadPollingState(authenticatedSession(), POLL_TIME)
                assertSame(PrivateBackendActivityFeed.Unavailable, state.presence)
                assertSame(PrivateBackendActivityFeed.Unavailable, state.typing)
            }
        }

    @Test
    fun transientMessageFailureRecoversOnTheNextValidPoll() =
        runBlocking {
            val statuses = mutableMapOf("messages" to 503)
            val api = pollingApi(statuses)
            assertThrows(SupabasePrivateChatRequestRejectedException::class.java) {
                runBlocking { api.loadPollingState(authenticatedSession(), POLL_TIME) }
            }
            statuses.clear()
            val recovered = api.loadPollingState(authenticatedSession(), POLL_TIME)
            assertTrue(recovered.messages.isEmpty())
            assertTrue(recovered.presence is PrivateBackendActivityFeed.Available)
        }

    @Test
    fun profilePollingNeverRequestsEncryptedHistoryOrPendingSends() =
        runBlocking {
            val requestedTables = mutableSetOf<String>()
            val api =
                SupabasePrivateChatPollingApi(
                    SupabasePrivateChatRequestExecutor(
                        object : SupabaseHttpTransport {
                            override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
                                val table = request.pathSegments.last()
                                check(table in setOf("profiles", "devices", "presence_state"))
                                requestedTables += table
                                return SupabaseHttpResponse(200, JsonArray(emptyList()))
                            }
                        },
                    ),
                )
            api.loadSocialState(authenticatedSession(), POLL_TIME)
            org.junit.Assert.assertEquals(setOf("profiles", "devices", "presence_state"), requestedTables)
        }

    @Test
    fun historyNotEncryptedForThisDeviceDoesNotBlockNewMessages() =
        runBlocking {
            val olderMessage = "40000000-0000-4000-8000-000000000004"
            val newMessage = "40000000-0000-4000-8000-000000000005"

            fun message(id: String) =
                buildJsonObject {
                    put("id", id)
                    put("room_id", "30000000-0000-4000-8000-000000000003")
                    put("sender_user_id", ACCOUNT_ID.toString())
                    put("sender_device_id", DEVICE_ID.toString())
                    put("client_message_id", id)
                    put("membership_epoch", 1)
                    put("current_revision", 0)
                    put("created_at", POLL_TIME.toString())
                    put("expires_at", POLL_TIME.plusSeconds(300).toString())
                }
            val api =
                SupabasePrivateChatPollingApi(
                    SupabasePrivateChatRequestExecutor(
                        object : SupabaseHttpTransport {
                            override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse =
                                SupabaseHttpResponse(
                                    200,
                                    JsonArray(
                                        when (request.pathSegments.last()) {
                                            "messages" -> listOf(message(olderMessage), message(newMessage))
                                            "message_envelopes" ->
                                                listOf(
                                                    buildJsonObject {
                                                        put("message_id", newMessage)
                                                        put("recipient_device_id", DEVICE_ID.toString())
                                                        put("protocol_adapter_version", 1)
                                                        put("signal_message_type", "LOCAL_AEAD")
                                                        put("ciphertext", "\\x" + "01".repeat(29))
                                                        put("created_at", POLL_TIME.toString())
                                                    },
                                                )
                                            else -> emptyList()
                                        },
                                    ),
                                )
                        },
                    ),
                )
            val state = api.loadPollingState(authenticatedSession(), POLL_TIME)
            org.junit.Assert.assertEquals(listOf(UUID.fromString(newMessage)), state.messages.map { it.messageId })
            org.junit.Assert.assertEquals(1, state.messageEnvelopes.size)
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
