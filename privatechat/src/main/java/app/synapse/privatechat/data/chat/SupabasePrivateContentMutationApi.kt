package app.synapse.privatechat.data.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal class SupabasePrivateContentMutationApi(
    private val transport: SupabasePrivateChatMutationTransport,
) {
    suspend fun sendMessage(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        replyToMessageId: UUID?,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendMessageSendReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "send_message",
                    body =
                        buildJsonObject {
                            put("p_room_id", roomId.toString())
                            put("p_client_message_id", clientMutationId.toString())
                            put("p_envelopes", envelopes.toSupabaseEnvelopeRows())
                            if (replyToMessageId == null) {
                                put("p_reply_to_message_id", kotlinx.serialization.json.JsonNull)
                            } else {
                                put("p_reply_to_message_id", replyToMessageId.toString())
                            }
                        },
                ).requireChatMutationSuccess("message send")
        receipt.requireExactChatFields("message_id", "room_id", "client_mutation_id", "expires_at")
        val parsed =
            PrivateBackendMessageSendReceipt(
                messageId = receipt.requireChatUuid("message_id"),
                roomId = receipt.requireChatUuid("room_id"),
                clientMutationId = receipt.requireChatUuid("client_mutation_id"),
                expiresAt = receipt.requireChatInstant("expires_at"),
            )
        if (parsed.roomId != roomId || parsed.clientMutationId != clientMutationId) {
            throw SupabasePrivateChatResponseException("Supabase message send receipt has another request context")
        }
        return parsed
    }

    suspend fun editMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        expectedServerRevision: Int,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendMessageEditReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "edit_message",
                    body =
                        buildJsonObject {
                            put("p_message_id", messageId.toString())
                            put("p_client_mutation_id", clientMutationId.toString())
                            put("p_expected_revision", expectedServerRevision)
                            put("p_envelopes", envelopes.toSupabaseEnvelopeRows())
                        },
                ).requireChatMutationSuccess("message edit")
        receipt.requireExactChatFields(
            "message_id",
            "revision_id",
            "revision_number",
            "edited_at",
            "expires_at",
        )
        val parsed =
            PrivateBackendMessageEditReceipt(
                messageId = receipt.requireChatUuid("message_id"),
                revisionId = receipt.requireChatUuid("revision_id"),
                serverRevision = receipt.requireChatInt("revision_number", 1..100),
                editedAt = receipt.requireChatInstant("edited_at"),
                expiresAt = receipt.requireChatInstant("expires_at"),
            )
        if (
            parsed.messageId != messageId ||
            parsed.serverRevision != expectedServerRevision + 1 ||
            !parsed.expiresAt.isAfter(parsed.editedAt)
        ) {
            throw SupabasePrivateChatResponseException("Supabase message edit receipt is inconsistent")
        }
        return parsed
    }

    suspend fun deleteMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        expectedServerRevision: Int,
    ): PrivateBackendMessageDeleteReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "delete_message_for_everyone",
                    body =
                        buildJsonObject {
                            put("p_message_id", messageId.toString())
                            put("p_client_mutation_id", clientMutationId.toString())
                            put("p_expected_revision", expectedServerRevision)
                        },
                ).requireChatMutationSuccess("message deletion")
        receipt.requireExactChatFields(
            "message_id",
            "deleted_revision",
            "correlation_id",
            "deletion_state",
            "requested_at",
        )
        val deletionState = receipt.requireChatString("deletion_state")
        if (deletionState !in MESSAGE_DELETION_STATES) {
            throw SupabasePrivateChatResponseException("Supabase message deletion state is unsupported")
        }
        return PrivateBackendMessageDeleteReceipt(
            messageId =
                receipt.requireChatUuid("message_id").also { returnedMessageId ->
                    if (returnedMessageId != messageId) {
                        throw SupabasePrivateChatResponseException("Supabase message deletion receipt targets another message")
                    }
                },
            serverRevision =
                receipt.requireChatInt("deleted_revision", 0..100).also { returnedRevision ->
                    if (returnedRevision != expectedServerRevision) {
                        throw SupabasePrivateChatResponseException(
                            "Supabase message deletion receipt has another revision",
                        )
                    }
                },
            correlationId = receipt.requireChatUuid("correlation_id"),
            deletionState = deletionState,
            requestedAt = receipt.requireChatInstant("requested_at"),
        )
    }

    suspend fun addReaction(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendReactionSendReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "send_reaction",
                    body =
                        buildJsonObject {
                            put("p_message_id", messageId.toString())
                            put("p_client_reaction_id", clientMutationId.toString())
                            put("p_envelopes", envelopes.toSupabaseEnvelopeRows())
                        },
                ).requireChatMutationSuccess("reaction send")
        receipt.requireExactChatFields("reaction_id", "message_id", "client_mutation_id", "expires_at")
        val parsed =
            PrivateBackendReactionSendReceipt(
                reactionId = receipt.requireChatUuid("reaction_id"),
                messageId = receipt.requireChatUuid("message_id"),
                clientMutationId = receipt.requireChatUuid("client_mutation_id"),
                expiresAt = receipt.requireChatInstant("expires_at"),
            )
        if (parsed.messageId != messageId || parsed.clientMutationId != clientMutationId) {
            throw SupabasePrivateChatResponseException("Supabase reaction send receipt has another request context")
        }
        return parsed
    }

    suspend fun removeReaction(
        session: PrivateChatAuthenticatedSession,
        reactionId: UUID,
        clientMutationId: UUID,
    ): PrivateBackendReactionRemoveReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "remove_reaction",
                    body =
                        buildJsonObject {
                            put("p_reaction_id", reactionId.toString())
                            put("p_client_mutation_id", clientMutationId.toString())
                        },
                ).requireChatMutationSuccess("reaction removal")
        receipt.requireExactChatFields("reaction_id", "removed_at")
        val returnedReactionId = receipt.requireChatUuid("reaction_id")
        if (returnedReactionId != reactionId) {
            throw SupabasePrivateChatResponseException("Supabase reaction removal receipt targets another reaction")
        }
        return PrivateBackendReactionRemoveReceipt(
            reactionId = returnedReactionId,
            removedAt = receipt.requireChatInstant("removed_at"),
        )
    }
}

private val MESSAGE_DELETION_STATES = setOf("DELETED", "PURGE_PENDING")
