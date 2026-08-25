package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SupabasePrivateContentMutationApiTest {
    @Test
    fun sendMessagePreservesReplyTargetAndExplicitNullAtTheRpcBoundary() =
        runBlocking {
            val transport = RecordingContentMutationTransport()
            val api =
                SupabasePrivateContentMutationApi(
                    SupabasePrivateChatMutationTransport(
                        SupabasePrivateChatRequestExecutor(transport),
                    ),
                )

            api.sendMessage(
                session = authenticatedSession(),
                roomId = ROOM_ID,
                clientMutationId = FIRST_MUTATION_ID,
                replyToMessageId = REPLY_MESSAGE_ID,
                envelopes = listOf(localEnvelope()),
            )
            api.sendMessage(
                session = authenticatedSession(),
                roomId = ROOM_ID,
                clientMutationId = SECOND_MUTATION_ID,
                replyToMessageId = null,
                envelopes = listOf(localEnvelope()),
            )

            val replyBody = requireNotNull(transport.requests[0].jsonBody).jsonObject
            val nonReplyBody = requireNotNull(transport.requests[1].jsonBody).jsonObject
            assertEquals(REPLY_MESSAGE_ID.toString(), replyBody.getValue("p_reply_to_message_id").jsonPrimitive.content)
            assertSame(JsonNull, nonReplyBody.getValue("p_reply_to_message_id"))
        }

    @Test
    fun sendMessageRejectsAReceiptForAnotherRoom(): Unit =
        runBlocking {
            val api = apiReturning(messageReceipt(roomId = OTHER_ROOM_ID, mutationId = FIRST_MUTATION_ID))

            assertThrows(SupabasePrivateChatResponseException::class.java) {
                runBlocking {
                    api.sendMessage(
                        session = authenticatedSession(),
                        roomId = ROOM_ID,
                        clientMutationId = FIRST_MUTATION_ID,
                        replyToMessageId = null,
                        envelopes = listOf(localEnvelope()),
                    )
                }
            }
        }

    @Test
    fun sendReactionRejectsAReceiptForAnotherMutation(): Unit =
        runBlocking {
            val api = apiReturning(reactionReceipt(messageId = REPLY_MESSAGE_ID, mutationId = SECOND_MUTATION_ID))

            assertThrows(SupabasePrivateChatResponseException::class.java) {
                runBlocking {
                    api.addReaction(
                        session = authenticatedSession(),
                        messageId = REPLY_MESSAGE_ID,
                        clientMutationId = FIRST_MUTATION_ID,
                        envelopes = listOf(localEnvelope()),
                    )
                }
            }
        }

    private fun apiReturning(receipt: JsonArray): SupabasePrivateContentMutationApi =
        SupabasePrivateContentMutationApi(
            SupabasePrivateChatMutationTransport(
                SupabasePrivateChatRequestExecutor(StaticContentMutationTransport(receipt)),
            ),
        )

    private fun messageReceipt(
        roomId: UUID,
        mutationId: UUID,
    ): JsonArray =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("message_id", "70000000-0000-4000-8000-000000000007")
                    put("room_id", roomId.toString())
                    put("client_mutation_id", mutationId.toString())
                    put("expires_at", "2026-08-25T14:00:00Z")
                },
            ),
        )

    private fun reactionReceipt(
        messageId: UUID,
        mutationId: UUID,
    ): JsonArray =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("reaction_id", "80000000-0000-4000-8000-000000000008")
                    put("message_id", messageId.toString())
                    put("client_mutation_id", mutationId.toString())
                    put("expires_at", "2026-08-25T14:00:00Z")
                },
            ),
        )

    private fun authenticatedSession(): PrivateChatAuthenticatedSession =
        PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
            accountId = ACCOUNT_ID,
            transportDeviceId = LOCAL_DEVICE_ID,
            signalDeviceId = SignalDeviceId.fromWire(7),
            authenticationUsername = "peter_01",
            accessToken = "header.payload.signature-material",
            expiresAt = Instant.parse("2026-08-25T13:00:00Z"),
        )

    private fun localEnvelope(): PrivateChatEncryptedEnvelope =
        PrivateChatEncryptedEnvelope(
            recipientDeviceId = LOCAL_DEVICE_ID,
            protocolAdapterVersion = SignalEnvelope.CURRENT_PROTOCOL_VERSION,
            kind = PrivateChatEnvelopeKind.LOCAL_AEAD,
            ciphertext = ByteArray(29) { 1 },
        )

    private companion object {
        val ACCOUNT_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val LOCAL_DEVICE_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val ROOM_ID: UUID = UUID.fromString("30000000-0000-4000-8000-000000000003")
        val OTHER_ROOM_ID: UUID = UUID.fromString("31000000-0000-4000-8000-000000000003")
        val FIRST_MUTATION_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000004")
        val SECOND_MUTATION_ID: UUID = UUID.fromString("50000000-0000-4000-8000-000000000005")
        val REPLY_MESSAGE_ID: UUID = UUID.fromString("60000000-0000-4000-8000-000000000006")
    }
}

private class RecordingContentMutationTransport : SupabaseHttpTransport {
    val requests = mutableListOf<SupabaseHttpRequest>()

    override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
        requests += request
        val requestBody = requireNotNull(request.jsonBody).jsonObject
        return SupabaseHttpResponse(
            statusCode = 200,
            jsonBody =
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("message_id", "70000000-0000-4000-8000-000000000007")
                            put("room_id", requestBody.getValue("p_room_id").jsonPrimitive.content)
                            put("client_mutation_id", requestBody.getValue("p_client_message_id").jsonPrimitive.content)
                            put("expires_at", "2026-08-25T14:00:00Z")
                        },
                    ),
                ),
        )
    }
}

private class StaticContentMutationTransport(
    private val receipt: JsonArray,
) : SupabaseHttpTransport {
    override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse =
        SupabaseHttpResponse(statusCode = 200, jsonBody = receipt)
}
