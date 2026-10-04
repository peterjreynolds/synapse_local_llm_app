package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateDirectConversationReceipt
import app.synapse.privatechat.domain.chat.PrivateDirectoryPerson
import app.synapse.privatechat.domain.chat.PrivatePeopleGateway
import app.synapse.privatechat.domain.chat.PrivateRoomId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal class SupabasePrivatePeopleGateway(
    private val execution: PrivateChatGatewayExecution,
    private val transport: SupabasePrivateChatMutationTransport,
    private val invalidateRoomFeed: suspend () -> Unit,
    private val clock: Clock,
) : PrivatePeopleGateway {
    override suspend fun loadPeople(accountId: PrivateAccountId): PrivateChatObservation<List<PrivateDirectoryPerson>> =
        execution.observe(accountId) { session ->
            val people = LinkedHashMap<UUID, PrivateDirectoryPerson>()
            var cursor: UUID? = null
            do {
                // Anchor expiry before the request so network latency cannot extend activity.
                val requestedAt = clock.instant()
                val rows =
                    transport
                        .rpc(
                            session,
                            "list_directory_people",
                            buildJsonObject { cursor?.let { put("p_after_user_id", it.toString()) } },
                        ).requireAcceptedChatMutation("people directory")
                        .requireChatRows("people directory", 100)
                rows.forEach { row ->
                    row.requireExactChatFields("user_id", "display_name", "active_for_seconds")
                    val id = row.requireChatUuid("user_id")
                    if (id.toString() == accountId.canonical ||
                        people.containsKey(id) ||
                        (cursor != null && id.toString() <= cursor.toString())
                    ) {
                        throw SupabasePrivateChatResponseException("Directory account set is inconsistent")
                    }
                    people[id] =
                        PrivateDirectoryPerson(
                            PrivateAccountId(id.toString()),
                            row.requireChatString("display_name"),
                            requestedAt.plusSeconds(row.requireChatInt("active_for_seconds", 0..60).toLong()),
                        )
                }
                cursor = rows.lastOrNull()?.requireChatUuid("user_id")
            } while (rows.size == 100)
            people.values.toList()
        }

    override suspend fun publishActivity(accountId: PrivateAccountId): PrivateChatMutationOutcome<Instant> =
        execution.mutate(accountId) { session ->
            val receipt =
                transport
                    .rpc(session, "publish_directory_activity", buildJsonObject {})
                    .requireChatMutationSuccess("directory activity")
            receipt.requireExactChatFields("device_id", "expires_at")
            if (receipt.requireChatUuid("device_id") != session.localSignalAddress.transportDeviceId) {
                throw SupabasePrivateChatResponseException("Directory activity receipt targets another device")
            }
            receipt.requireChatInstant("expires_at")
        }

    override suspend fun openDirectConversation(
        accountId: PrivateAccountId,
        targetAccountId: PrivateAccountId,
    ): PrivateChatMutationOutcome<PrivateDirectConversationReceipt> =
        execution.mutate(accountId) { session ->
            if (accountId == targetAccountId) throw PrivateChatCommandRejectedException("Choose another person.")
            val targetId = targetAccountId.canonical.requireUuid()
            val receipt =
                transport
                    .rpc(
                        session,
                        "open_direct_conversation",
                        buildJsonObject { put("p_target_user_id", targetId.toString()) },
                    ).requireChatMutationSuccess("direct conversation")
            receipt.requireExactChatFields("room_id", "actor_user_id", "target_user_id")
            if (
                receipt.requireChatUuid("actor_user_id").toString() != accountId.canonical ||
                receipt.requireChatUuid("target_user_id") != targetId
            ) {
                throw SupabasePrivateChatResponseException("Direct conversation receipt targets another account")
            }
            invalidateRoomFeed()
            PrivateDirectConversationReceipt(accountId, targetAccountId, PrivateRoomId(receipt.requireChatUuid("room_id").toString()))
        }
}
