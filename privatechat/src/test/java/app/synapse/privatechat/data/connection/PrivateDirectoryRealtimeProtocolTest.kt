package app.synapse.privatechat.data.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateDirectoryRealtimeProtocolTest {
    @Test
    fun joinsPrivateDirectoryWithoutPublishingPresenceOrAccountIdentifiers() {
        val join = Json.parseToJsonElement(PrivateDirectoryRealtimeProtocol.join("test-token")).jsonObject
        assertEquals("phx_join", join["event"]?.jsonPrimitive?.content)
        val payload = join.getValue("payload").jsonObject
        assertEquals("test-token", payload["access_token"]?.jsonPrimitive?.content)
        assertEquals(
            "true",
            payload
                .getValue("config")
                .jsonObject["private"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(setOf("config", "access_token"), payload.keys)
    }

    @Test
    fun acceptsOnlyExpectedChannelEventsAndConfirmedHeartbeats() {
        assertEquals(PrivateDirectoryRealtimeEvent.JOINED, parse("phx_reply", """{"status":"ok"}"""))
        assertEquals(PrivateDirectoryRealtimeEvent.REJECTED, parse("phx_reply", """{"status":"error"}"""))
        assertEquals(PrivateDirectoryRealtimeEvent.CHANGED, parse("broadcast", """{"event":"directory_changed","payload":{}}"""))
        assertEquals(PrivateDirectoryRealtimeEvent.IGNORED, parse("broadcast", """{"event":"other"}"""))
        assertEquals(
            PrivateDirectoryRealtimeEvent.IGNORED,
            PrivateDirectoryRealtimeProtocol.parse("""{"topic":"other","event":"broadcast"}"""),
        )
        assertEquals(
            PrivateDirectoryRealtimeEvent.HEARTBEAT_ACKNOWLEDGED,
            PrivateDirectoryRealtimeProtocol.parse(
                """{"topic":"phoenix","event":"phx_reply","ref":"heartbeat","payload":{"status":"ok"}}""",
            ),
        )
        assertTrue(PrivateDirectoryRealtimeProtocol.heartbeat().contains("heartbeat"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedFrames() {
        PrivateDirectoryRealtimeProtocol.parse(" ".repeat(16_385))
    }

    private fun parse(
        event: String,
        payload: String,
    ) = PrivateDirectoryRealtimeProtocol.parse(
        """{"topic":"realtime:synapse-private-directory","event":"$event","ref":"1","payload":$payload}""",
    )
}
