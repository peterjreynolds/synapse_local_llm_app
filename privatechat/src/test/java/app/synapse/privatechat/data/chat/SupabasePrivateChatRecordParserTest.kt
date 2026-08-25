package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class SupabasePrivateChatRecordParserTest {
    @Test
    fun roomParserRetainsTheCreationMutationBinding() {
        val parsedRoom = roomResponse(includeCreationMutationId = true).parseRooms().single()

        assertEquals(CREATION_MUTATION_ID, parsedRoom.creationClientMutationId)
    }

    @Test
    fun roomParserRetainsAnExplicitLegacyNullBinding() {
        val parsedRoom = roomResponse(includeCreationMutationId = false).parseRooms().single()

        assertNull(parsedRoom.creationClientMutationId)
    }

    private fun roomResponse(includeCreationMutationId: Boolean): SupabaseHttpResponse =
        SupabaseHttpResponse(
            statusCode = 200,
            jsonBody =
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", ROOM_ID.toString())
                            put("owner_user_id", OWNER_ID.toString())
                            if (includeCreationMutationId) {
                                put("creation_client_mutation_id", CREATION_MUTATION_ID.toString())
                            } else {
                                put("creation_client_mutation_id", JsonNull)
                            }
                            put("room_kind", "GROUP")
                            put("retention_seconds", 300)
                            put("membership_epoch", 1)
                            put("metadata_revision", 1)
                            put("metadata_updated_at", "2026-08-25T13:00:00Z")
                            put("created_at", "2026-08-25T13:00:00Z")
                        },
                    ),
                ),
        )

    private companion object {
        val ROOM_ID: UUID = UUID.fromString("30000000-0000-4000-8000-000000000003")
        val OWNER_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val CREATION_MUTATION_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000004")
    }
}
