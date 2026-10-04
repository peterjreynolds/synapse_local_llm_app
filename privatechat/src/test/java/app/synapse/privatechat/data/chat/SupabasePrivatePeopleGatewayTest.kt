package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.local.DeviceLocalEncryptedPayloadCacheStorage
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SupabasePrivatePeopleGatewayTest {
    @Test
    fun directoryPaginatesAndRejectsUnexpectedPrivateFields() =
        runTest {
            var page = 0
            var exposeSecret = false
            val gateway =
                gateway { request ->
                    if (exposeSecret) {
                        return@gateway rows(
                            buildJsonObject {
                                put("user_id", PEER.canonical)
                                put("display_name", "Peer")
                                put("active_for_seconds", 20)
                                put("email", "hidden")
                            },
                        )
                    }
                    page++
                    if (page == 1) {
                        SupabaseHttpResponse(200, JsonArray((2..101).map { person(it) }))
                    } else {
                        assertEquals(person(101)["user_id"], request.jsonBody!!.jsonObject["p_after_user_id"])
                        rows(person(102))
                    }
                }
            val people = gateway.loadPeople(ACTOR)
            check(people is PrivateChatObservation.Available)
            assertEquals(101, people.snapshot.size)
            assertEquals(NOW.plusSeconds(20), people.snapshot.first().activeUntil)
            exposeSecret = true
            assertEquals(PrivateChatObservation.TransportUnavailable, gateway.loadPeople(ACTOR))
        }

    @Test
    fun directRoomRequiresMatchingReceiptAndNeverSendsPlaintextContent() =
        runTest {
            var invalidateCount = 0
            var mismatched = true
            val gateway =
                gateway(invalidate = { invalidateCount++ }) { request ->
                    assertEquals(listOf("rest", "v1", "rpc", "open_direct_conversation"), request.pathSegments)
                    assertEquals(setOf("p_target_user_id"), request.jsonBody!!.jsonObject.keys)
                    assertEquals(
                        PEER.canonical,
                        request.jsonBody!!
                            .jsonObject["p_target_user_id"]!!
                            .jsonPrimitive.content,
                    )
                    rows(
                        buildJsonObject {
                            put("room_id", "84000000-0000-4000-8000-000000000001")
                            put("actor_user_id", ACTOR.canonical)
                            put("target_user_id", if (mismatched) ACTOR.canonical else PEER.canonical)
                        },
                    )
                }
            assertEquals(PrivateChatMutationOutcome.TransportUnavailable, gateway.openDirectConversation(ACTOR, PEER))
            assertEquals(0, invalidateCount)
            mismatched = false
            assertTrue(gateway.openDirectConversation(ACTOR, PEER) is PrivateChatMutationOutcome.Confirmed)
            assertEquals(1, invalidateCount)
        }

    @Test
    fun selfSelectionAndAnotherAccountSessionCannotReachTransport() =
        runTest {
            val gateway = gateway { error("No request should be sent") }
            assertTrue(gateway.openDirectConversation(ACTOR, ACTOR) is PrivateChatMutationOutcome.Rejected)
            assertEquals(PrivateChatObservation.TransportUnavailable, gateway.loadPeople(PEER))
            assertEquals(PrivateChatMutationOutcome.TransportUnavailable, gateway.publishActivity(PEER))
        }

    @Test
    fun heartbeatRequiresCurrentDeviceReceipt() =
        runTest {
            val gateway =
                gateway {
                    rows(
                        buildJsonObject {
                            put("device_id", "83000000-0000-4000-8000-000000000099")
                            put("expires_at", NOW.plusSeconds(60).toString())
                        },
                    )
                }
            assertEquals(PrivateChatMutationOutcome.TransportUnavailable, gateway.publishActivity(ACTOR))
        }

    private fun gateway(
        invalidate: suspend () -> Unit = {},
        respond: suspend (SupabaseHttpRequest) -> SupabaseHttpResponse,
    ): SupabasePrivatePeopleGateway {
        val session =
            PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
                UUID.fromString(ACTOR.canonical),
                UUID.fromString("83000000-0000-4000-8000-000000000001"),
                SignalDeviceId.fromWire(1),
                "person_one",
                "header.payload.signature",
                NOW.plusSeconds(3600),
            )
        val storage =
            object : DeviceLocalEncryptedPayloadCacheStorage {
                override fun readDecryptedState(): ByteArray? = null

                override fun replaceEncryptedState(plaintext: ByteArray) = Unit

                override fun replaceAfterPurge(retainedPlaintext: ByteArray?) = Unit

                override fun deletePhysically() = Unit
            }
        val transport =
            object : SupabaseHttpTransport {
                override suspend fun execute(request: SupabaseHttpRequest) = respond(request)
            }
        return SupabasePrivatePeopleGateway(
            PrivateChatGatewayExecution(PrivateChatSessionResolver({ session }, PrivateDecryptedPayloadCacheRepository(storage), CLOCK)),
            SupabasePrivateChatMutationTransport(SupabasePrivateChatRequestExecutor(transport, {})),
            invalidate,
            CLOCK,
        )
    }

    private fun person(number: Int) =
        buildJsonObject {
            put("user_id", "81000000-0000-4000-8000-" + number.toString().padStart(12, '0'))
            put("display_name", "Person $number")
            put("active_for_seconds", 20)
        }

    private fun rows(vararg rows: JsonObject) = SupabaseHttpResponse(200, JsonArray(rows.toList()))

    private companion object {
        val ACTOR = PrivateAccountId("81000000-0000-4000-8000-000000000001")
        val PEER = PrivateAccountId("81000000-0000-4000-8000-000000000002")
        val NOW = Instant.parse("2026-10-04T12:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
