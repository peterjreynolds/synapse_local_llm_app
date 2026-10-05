package app.synapse.privatechat.data.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class PrivateDirectoryRealtimeEvent { JOINED, CHANGED, HEARTBEAT_ACKNOWLEDGED, IGNORED, REJECTED }

internal object PrivateDirectoryRealtimeProtocol {
    const val TOPIC = "realtime:synapse-private-directory"

    fun join(accessToken: String): String =
        frame(
            "phx_join",
            "1",
            buildJsonObject {
                put("access_token", accessToken)
                put(
                    "config",
                    buildJsonObject {
                        put("private", true)
                        put(
                            "broadcast",
                            buildJsonObject {
                                put("ack", false)
                                put("self", false)
                            },
                        )
                        put("presence", buildJsonObject { put("enabled", false) })
                    },
                )
            },
        )

    fun heartbeat(): String =
        buildJsonObject {
            put("topic", "phoenix")
            put("event", "heartbeat")
            put("ref", "heartbeat")
            put("payload", buildJsonObject {})
        }.toString()

    fun parse(frame: String): PrivateDirectoryRealtimeEvent {
        require(frame.length <= 16_384)
        val message = Json.parseToJsonElement(frame) as? JsonObject ?: return PrivateDirectoryRealtimeEvent.REJECTED
        val topic = message.string("topic")
        val event = message.string("event")
        val payload = message["payload"] as? JsonObject
        if (topic == "phoenix" && event == "phx_reply" && message.string("ref") == "heartbeat") {
            return if (payload?.string("status") ==
                "ok"
            ) {
                PrivateDirectoryRealtimeEvent.HEARTBEAT_ACKNOWLEDGED
            } else {
                PrivateDirectoryRealtimeEvent.REJECTED
            }
        }
        if (topic != TOPIC) return PrivateDirectoryRealtimeEvent.IGNORED
        return when (event) {
            "phx_reply" ->
                if (message.string("ref") == "1" && payload?.string("status") == "ok") {
                    PrivateDirectoryRealtimeEvent.JOINED
                } else {
                    PrivateDirectoryRealtimeEvent.REJECTED
                }
            "broadcast" ->
                if (payload?.string("event") == "directory_changed") {
                    PrivateDirectoryRealtimeEvent.CHANGED
                } else {
                    PrivateDirectoryRealtimeEvent.IGNORED
                }
            "phx_error", "phx_close" -> PrivateDirectoryRealtimeEvent.REJECTED
            else -> PrivateDirectoryRealtimeEvent.IGNORED
        }
    }

    private fun frame(
        event: String,
        ref: String,
        payload: JsonObject,
    ): String =
        buildJsonObject {
            put("topic", TOPIC)
            put("event", event)
            put("ref", ref)
            put("join_ref", "1")
            put("payload", payload)
        }.toString()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
