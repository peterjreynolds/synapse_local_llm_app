package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SupabasePrivateSocialMutationApiTest {
    @Test
    fun createRoomBindsTheChosenRoomAndMutationAtTheRpcBoundary() =
        runBlocking {
            val transport = RecordingRoomCreationTransport()
            val api = apiUsing(transport)

            val receipt =
                api.createRoom(
                    session = authenticatedSession(),
                    roomId = ROOM_ID,
                    kind = PrivateRoomKind.GROUP,
                    retention = PrivateMessageRetention.FIVE_MINUTES,
                    clientMutationId = MUTATION_ID,
                    envelopes = listOf(localEnvelope()),
                )

            val requestBody = requireNotNull(transport.request).jsonBody!!.jsonObject
            assertEquals(ROOM_ID.toString(), requestBody.getValue("p_room_id").jsonPrimitive.content)
            assertEquals(MUTATION_ID.toString(), requestBody.getValue("p_client_mutation_id").jsonPrimitive.content)
            assertEquals(ROOM_ID, receipt.roomId)
            assertEquals(MUTATION_ID, receipt.clientMutationId)
        }

    @Test
    fun createRoomRejectsAReceiptForAnotherRoom(): Unit =
        runBlocking {
            val api = apiReturning(roomReceipt(roomId = OTHER_ROOM_ID, mutationId = MUTATION_ID))

            assertThrows(SupabasePrivateChatResponseException::class.java) {
                runBlocking { createRoom(api) }
            }
        }

    @Test
    fun createRoomRejectsAReceiptForAnotherMutation(): Unit =
        runBlocking {
            val api = apiReturning(roomReceipt(roomId = ROOM_ID, mutationId = OTHER_MUTATION_ID))

            assertThrows(SupabasePrivateChatResponseException::class.java) {
                runBlocking { createRoom(api) }
            }
        }

    private suspend fun createRoom(api: SupabasePrivateSocialMutationApi): PrivateBackendRoomCreationReceipt =
        api.createRoom(
            session = authenticatedSession(),
            roomId = ROOM_ID,
            kind = PrivateRoomKind.GROUP,
            retention = PrivateMessageRetention.FIVE_MINUTES,
            clientMutationId = MUTATION_ID,
            envelopes = listOf(localEnvelope()),
        )

    private fun apiUsing(transport: SupabaseHttpTransport): SupabasePrivateSocialMutationApi =
        SupabasePrivateSocialMutationApi(
            SupabasePrivateChatMutationTransport(
                SupabasePrivateChatRequestExecutor(transport),
            ),
        )

    private fun apiReturning(receipt: JsonArray): SupabasePrivateSocialMutationApi = apiUsing(StaticRoomCreationTransport(receipt))

    private fun roomReceipt(
        roomId: UUID,
        mutationId: UUID,
    ): JsonArray =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("room_id", roomId.toString())
                    put("client_mutation_id", mutationId.toString())
                    put("room_kind", PrivateRoomKind.GROUP.name)
                    put("retention_seconds", PrivateMessageRetention.FIVE_MINUTES.durationSeconds)
                    put("membership_epoch", 1)
                    put("metadata_revision", 1)
                    put("created_at", CREATION_TIME.toString())
                    put("metadata_updated_at", CREATION_TIME.toString())
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
            expiresAt = Instant.parse("2026-08-25T14:00:00Z"),
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
        val MUTATION_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000004")
        val OTHER_MUTATION_ID: UUID = UUID.fromString("41000000-0000-4000-8000-000000000004")
        val CREATION_TIME: Instant = Instant.parse("2026-08-25T13:00:00Z")
    }
}

private class RecordingRoomCreationTransport : SupabaseHttpTransport {
    var request: SupabaseHttpRequest? = null

    override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
        this.request = request
        val requestBody = requireNotNull(request.jsonBody).jsonObject
        return SupabaseHttpResponse(
            statusCode = 200,
            jsonBody =
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("room_id", requestBody.getValue("p_room_id").jsonPrimitive.content)
                            put(
                                "client_mutation_id",
                                requestBody.getValue("p_client_mutation_id").jsonPrimitive.content,
                            )
                            put("room_kind", requestBody.getValue("p_room_kind").jsonPrimitive.content)
                            put(
                                "retention_seconds",
                                requestBody
                                    .getValue("p_retention_seconds")
                                    .jsonPrimitive
                                    .content
                                    .toInt(),
                            )
                            put("membership_epoch", 1)
                            put("metadata_revision", 1)
                            put("created_at", "2026-08-25T13:00:00Z")
                            put("metadata_updated_at", "2026-08-25T13:00:00Z")
                        },
                    ),
                ),
        )
    }
}

private class StaticRoomCreationTransport(
    private val receipt: JsonArray,
) : SupabaseHttpTransport {
    override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse =
        SupabaseHttpResponse(statusCode = 200, jsonBody = receipt)
}
