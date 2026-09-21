package app.synapse.privatechat.data.call

import app.synapse.privatechat.data.chat.SupabasePrivateChatRequestExecutor
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import app.synapse.privatechat.domain.call.PrivateCallException
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class SupabasePrivateCallBackendTest {
    @Test
    fun retriesOnlyCiphertextWithStableMutationAndAuthenticatedDevice() =
        runBlocking {
            val peers = CallCryptoPeers()
            peers.callerCipher.preparePeers(
                peers.callerSession,
                peers.recipientBackend.listRoomRecipientDevices(peers.callerSession, peers.payload.roomId).filter {
                    it.address ==
                        peers.payload.recipient
                },
            )
            val envelope = peers.callerCipher.encrypt(peers.callerSession, peers.payload)
            val mutationId = UUID.randomUUID()
            val requests = mutableListOf<SupabaseHttpRequest>()
            val backend =
                SupabasePrivateCallBackend(
                    SupabasePrivateChatRequestExecutor(
                        object : SupabaseHttpTransport {
                            override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
                                requests += request
                                return if (requests.size ==
                                    1
                                ) {
                                    SupabaseHttpResponse(503, null)
                                } else {
                                    SupabaseHttpResponse(200, JsonArray(listOf(callJson(peers, mutationId))))
                                }
                            }
                        },
                        retryDelay = {},
                    ),
                )
            val receipt =
                backend.createCall(
                    peers.callerSession,
                    peers.call.callId,
                    peers.payload.roomId,
                    1,
                    PrivateCallMediaKind.VOICE,
                    mutationId,
                    peers.payload.expiresAt,
                    listOf(envelope),
                )
            assertEquals(peers.call, receipt)
            assertEquals(2, requests.size)
            assertEquals(requests[0], requests[1])
            assertEquals(peers.callerSession.accessTokenForRequest(), requests[0].accessToken)
            assertEquals("create_private_call", requests[0].pathSegments.last())
            assertFalse(requests[0].jsonBody.toString().contains("fingerprint"))
            assertFalse(requests[0].toString().contains(peers.callerSession.accessTokenForRequest()))
        }

    @Test
    fun rejectsWrongMutationAndUnexpectedFieldsInReceipts() {
        val peers = CallCryptoPeers()
        val mutation = UUID.randomUUID()
        val wrongMutation = SupabaseHttpResponse(200, JsonArray(listOf(callJson(peers, UUID.randomUUID()))))
        assertThrows(IllegalArgumentException::class.java) { wrongMutation.parseCallReceipt(peers.call.callId, mutation) }
        val unexpected =
            SupabaseHttpResponse(
                200,
                JsonArray(
                    listOf(
                        JsonObject(
                            callJson(peers, mutation) + ("plaintext" to JsonPrimitive("unexpected")),
                        ),
                    ),
                ),
            )
        assertThrows(IllegalStateException::class.java) { unexpected.parseCallReceipt(peers.call.callId, mutation) }
    }

    @Test
    fun forbiddenResponseFailsClosedWithoutRetry() =
        runBlocking {
            val peers = CallCryptoPeers()
            var attempts = 0
            val backend =
                SupabasePrivateCallBackend(
                    SupabasePrivateChatRequestExecutor(
                        object : SupabaseHttpTransport {
                            override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
                                attempts += 1
                                return SupabaseHttpResponse(403, null)
                            }
                        },
                        retryDelay = {},
                    ),
                )
            assertThrows(PrivateCallException::class.java) { runBlocking { backend.poll(peers.callerSession) } }
            assertEquals(1, attempts)
        }

    private fun callJson(
        peers: CallCryptoPeers,
        mutationId: UUID,
    ): JsonObject =
        buildJsonObject {
            put("call_id", peers.call.callId.toString())
            put("room_id", peers.call.roomId.canonical)
            put("membership_epoch", 1)
            put("caller_user_id", peers.call.callerAccountId.toString())
            put("caller_device_id", peers.call.callerDeviceId.toString())
            put("recipient_user_id", peers.call.recipientAccountId.toString())
            put("accepted_device_id", JsonNull)
            put("media_kind", "VOICE")
            put("state", "RINGING")
            put("terminal_reason", JsonNull)
            put("created_at", CALL_TEST_TIME.toString())
            put("ring_expires_at", peers.payload.expiresAt.toString())
            put("lease_expires_at", peers.payload.expiresAt.toString())
            put("client_mutation_id", mutationId.toString())
        }
}
