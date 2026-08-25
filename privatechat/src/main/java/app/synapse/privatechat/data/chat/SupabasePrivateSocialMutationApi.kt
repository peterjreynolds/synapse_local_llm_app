package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.supabase.SupabaseHttpMethod
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal class SupabasePrivateSocialMutationApi(
    private val transport: SupabasePrivateChatMutationTransport,
) {
    suspend fun updateProfile(
        session: PrivateChatAuthenticatedSession,
        displayName: String,
    ): PrivateBackendProfileRecord =
        updateOwnProfile(
            session,
            buildJsonObject { put("display_name", displayName) },
            "profile update",
        ).also { profile ->
            if (profile.displayName != displayName) {
                throw SupabasePrivateChatResponseException("Supabase profile update receipt is inconsistent")
            }
        }

    suspend fun updateActivitySharing(
        session: PrivateChatAuthenticatedSession,
        preferences: PrivateActivitySharingPreferences,
    ): PrivateBackendProfileRecord =
        updateOwnProfile(
            session,
            buildJsonObject {
                put(
                    "typing_indicators_enabled",
                    preferences.typingIndicators == PrivateActivitySharingState.ENABLED,
                )
                put(
                    "read_receipts_enabled",
                    preferences.readReceipts == PrivateActivitySharingState.ENABLED,
                )
            },
            "activity sharing update",
        ).also { profile ->
            if (profile.activitySharing != preferences) {
                throw SupabasePrivateChatResponseException("Supabase activity sharing receipt is inconsistent")
            }
        }

    suspend fun updatePresenceSharing(
        session: PrivateChatAuthenticatedSession,
        sharingState: PrivatePresenceSharingState,
    ): PrivateBackendProfileRecord =
        updateOwnProfile(
            session,
            buildJsonObject {
                put("presence_sharing_enabled", sharingState == PrivatePresenceSharingState.ENABLED)
            },
            "presence sharing update",
        ).also { profile ->
            if (profile.presenceSharing != sharingState) {
                throw SupabasePrivateChatResponseException("Supabase presence sharing receipt is inconsistent")
            }
        }

    suspend fun createRoom(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        kind: PrivateRoomKind,
        retention: PrivateMessageRetention,
        clientMutationId: UUID,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendRoomCreationReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "create_room_with_metadata",
                    body =
                        buildJsonObject {
                            put("p_room_id", roomId.toString())
                            put("p_room_kind", kind.name)
                            put("p_client_mutation_id", clientMutationId.toString())
                            put("p_envelopes", envelopes.toSupabaseEnvelopeRows())
                            put("p_retention_seconds", retention.durationSeconds)
                        },
                ).requireChatMutationSuccess("room creation")
        receipt.requireExactChatFields(
            "room_id",
            "client_mutation_id",
            "room_kind",
            "retention_seconds",
            "membership_epoch",
            "metadata_revision",
            "created_at",
            "metadata_updated_at",
        )
        val returnedKind = receipt.requireChatString("room_kind")
        val returnedRetentionSeconds = receipt.requireChatInt("retention_seconds", 1..Int.MAX_VALUE)
        val parsed =
            PrivateBackendRoomCreationReceipt(
                roomId = receipt.requireChatUuid("room_id"),
                clientMutationId = receipt.requireChatUuid("client_mutation_id"),
                kind = kind,
                retention = retention,
                membershipEpoch = receipt.requireChatInt("membership_epoch", 1..Int.MAX_VALUE),
                metadataRevision = receipt.requireChatInt("metadata_revision", 1..Int.MAX_VALUE),
                createdAt = receipt.requireChatInstant("created_at"),
                metadataUpdatedAt = receipt.requireChatInstant("metadata_updated_at"),
            )
        if (
            parsed.roomId != roomId ||
            parsed.clientMutationId != clientMutationId ||
            returnedKind != kind.name ||
            returnedRetentionSeconds != retention.durationSeconds ||
            parsed.metadataRevision != 1 ||
            parsed.metadataUpdatedAt.isBefore(parsed.createdAt)
        ) {
            throw SupabasePrivateChatResponseException("Supabase room creation receipt is inconsistent")
        }
        return parsed
    }

    suspend fun updateGroupMemberRole(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        memberAccountId: UUID,
        clientMutationId: UUID,
        role: PrivateRoomMemberRole,
    ): PrivateBackendMemberRoleReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "update_room_member_role",
                    body =
                        buildJsonObject {
                            put("p_room_id", roomId.toString())
                            put("p_client_mutation_id", clientMutationId.toString())
                            put("p_member_user_id", memberAccountId.toString())
                            put("p_member_role", role.name)
                        },
                ).requireChatMutationSuccess("group member role update")
        receipt.requireExactChatFields("room_id", "member_user_id", "member_role", "new_membership_epoch")
        val parsed =
            PrivateBackendMemberRoleReceipt(
                roomId = receipt.requireChatUuid("room_id"),
                memberAccountId = receipt.requireChatUuid("member_user_id"),
                role = receipt.requireMemberRole("member_role"),
                membershipEpoch = receipt.requireChatInt("new_membership_epoch", 1..Int.MAX_VALUE),
            )
        if (parsed.roomId != roomId || parsed.memberAccountId != memberAccountId || parsed.role != role) {
            throw SupabasePrivateChatResponseException("Supabase group member role receipt is inconsistent")
        }
        return parsed
    }

    suspend fun removeGroupMember(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        memberAccountId: UUID,
        clientMutationId: UUID,
    ): PrivateBackendMemberRemovalReceipt {
        val receipt =
            transport
                .rpc(
                    session = session,
                    functionName = "remove_room_member",
                    body =
                        buildJsonObject {
                            put("p_room_id", roomId.toString())
                            put("p_client_mutation_id", clientMutationId.toString())
                            put("p_removed_user_id", memberAccountId.toString())
                        },
                ).requireChatMutationSuccess("group member removal")
        receipt.requireExactChatFields("room_id", "removed_user_id", "new_membership_epoch")
        val parsed =
            PrivateBackendMemberRemovalReceipt(
                roomId = receipt.requireChatUuid("room_id"),
                memberAccountId = receipt.requireChatUuid("removed_user_id"),
                membershipEpoch = receipt.requireChatInt("new_membership_epoch", 1..Int.MAX_VALUE),
            )
        if (parsed.roomId != roomId || parsed.memberAccountId != memberAccountId) {
            throw SupabasePrivateChatResponseException("Supabase group member removal receipt is inconsistent")
        }
        return parsed
    }

    suspend fun issueInvite(
        session: PrivateChatAuthenticatedSession,
        clientMutationId: UUID,
        kind: PrivateBackendInviteKind,
        roomId: UUID?,
    ): PrivateBackendInviteReceipt {
        val response =
            transport
                .edgeFunction(
                    session = session,
                    functionName = "issue-invite",
                    body =
                        buildJsonObject {
                            put("kind", kind.name)
                            put("client_mutation_id", clientMutationId.toString())
                            roomId?.let { requestedRoomId -> put("room_id", requestedRoomId.toString()) }
                        },
                ).requireAcceptedChatMutation("invite issuance")
        val responseBody =
            response.jsonBody as? JsonObject
                ?: throw SupabasePrivateChatResponseException("Supabase invite issuance response is malformed")
        responseBody.requireExactChatFields("invite")
        val invite =
            responseBody["invite"] as? JsonObject
                ?: throw SupabasePrivateChatResponseException("Supabase invite issuance receipt is malformed")
        invite.requireExactChatFields("id", "kind", "room_id", "code", "expires_at")
        val returnedKind = invite.requireInviteKind("kind")
        val returnedRoomId = invite.requireNullableChatUuid("room_id")
        val code = invite.requireChatString("code")
        if (
            returnedKind != kind ||
            returnedRoomId != roomId ||
            !INVITATION_CODE_PATTERN.matches(code)
        ) {
            throw SupabasePrivateChatResponseException("Supabase invite issuance receipt is inconsistent")
        }
        return PrivateBackendInviteReceipt(
            invitationId = invite.requireChatUuid("id"),
            kind = returnedKind,
            roomId = returnedRoomId,
            code = code,
            expiresAt = invite.requireChatInstant("expires_at"),
        )
    }

    suspend fun redeemRoomInvite(
        session: PrivateChatAuthenticatedSession,
        inviteCode: String,
        redemptionId: UUID,
    ): PrivateBackendRoomInvitationRedemptionReceipt {
        if (!ROOM_INVITATION_CODE_PATTERN.matches(inviteCode)) {
            throw IllegalArgumentException("Room invitation code is invalid")
        }
        val response =
            transport
                .edgeFunction(
                    session = session,
                    functionName = "redeem-room-invite",
                    body =
                        buildJsonObject {
                            put("invite_code", inviteCode)
                            put("redemption_id", redemptionId.toString())
                        },
                ).requireAcceptedChatMutation("room invitation redemption")
        val body =
            response.jsonBody as? JsonObject
                ?: throw SupabasePrivateChatResponseException("Supabase room invitation redemption response is malformed")
        body.requireExactChatFields("membership")
        val membership =
            body["membership"] as? JsonObject
                ?: throw SupabasePrivateChatResponseException("Supabase room invitation redemption receipt is malformed")
        membership.requireExactChatFields("room_id", "user_id", "membership_epoch", "completed_at")
        val receipt =
            PrivateBackendRoomInvitationRedemptionReceipt(
                roomId = membership.requireChatUuid("room_id"),
                accountId = membership.requireChatUuid("user_id"),
                membershipEpoch = membership.requireChatInt("membership_epoch", 1..Int.MAX_VALUE),
                completedAt = membership.requireChatInstant("completed_at"),
            )
        if (receipt.accountId.toString() != session.accountId.canonical) {
            throw SupabasePrivateChatResponseException("Supabase room invitation redemption targets another account")
        }
        return receipt
    }

    suspend fun publishPresence(session: PrivateChatAuthenticatedSession): PrivateBackendPresenceRecord {
        val response =
            transport
                .tableMutation(
                    session = session,
                    method = SupabaseHttpMethod.POST,
                    tableName = "presence_state",
                    body =
                        buildJsonObject {
                            put("device_id", session.localSignalAddress.transportDeviceId.toString())
                            put("expires_at", MAXIMUM_PRESENCE_TIMESTAMP)
                        },
                    queryParameters =
                        mapOf(
                            "on_conflict" to "device_id",
                            "select" to PRESENCE_COLUMNS,
                        ),
                    preferHeader = "resolution=merge-duplicates,return=representation",
                ).requireAcceptedChatMutation("presence publication")
        val persisted =
            response.parsePresence(now = null).singleOrNull()
                ?: throw SupabasePrivateChatResponseException("Supabase presence publication receipt is malformed")
        if (persisted.deviceId != session.localSignalAddress.transportDeviceId) {
            throw SupabasePrivateChatResponseException("Supabase presence publication receipt targets another device")
        }
        return persisted
    }

    private suspend fun updateOwnProfile(
        session: PrivateChatAuthenticatedSession,
        body: JsonObject,
        operation: String,
    ): PrivateBackendProfileRecord {
        val response =
            transport
                .tableMutation(
                    session = session,
                    method = SupabaseHttpMethod.PATCH,
                    tableName = "profiles",
                    body = body,
                    queryParameters =
                        mapOf(
                            "user_id" to "eq.${session.accountId.canonical}",
                            "select" to PROFILE_COLUMNS,
                        ),
                    preferHeader = "return=representation",
                ).requireAcceptedChatMutation(operation)
        val profile =
            response.parseProfiles().singleOrNull()
                ?: throw SupabasePrivateChatResponseException("Supabase $operation receipt is malformed")
        if (profile.accountId.toString() != session.accountId.canonical) {
            throw SupabasePrivateChatResponseException("Supabase $operation receipt targets another account")
        }
        return profile
    }
}

private fun JsonObject.requireMemberRole(field: String): PrivateRoomMemberRole =
    try {
        PrivateRoomMemberRole.valueOf(requireChatString(field))
    } catch (error: IllegalArgumentException) {
        throw SupabasePrivateChatResponseException("Supabase member role receipt is unsupported", error)
    }

private fun JsonObject.requireInviteKind(field: String): PrivateBackendInviteKind =
    try {
        PrivateBackendInviteKind.valueOf(requireChatString(field))
    } catch (error: IllegalArgumentException) {
        throw SupabasePrivateChatResponseException("Supabase invite kind receipt is unsupported", error)
    }

private const val PROFILE_COLUMNS =
    "user_id,display_name,presence_sharing_enabled,typing_indicators_enabled,read_receipts_enabled"
private const val PRESENCE_COLUMNS = "device_id,created_at,expires_at"
private const val MAXIMUM_PRESENCE_TIMESTAMP = "9999-12-31T23:59:59Z"
private val INVITATION_CODE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
private val ROOM_INVITATION_CODE_PATTERN = Regex("^[A-Za-z0-9_-]{32,128}$")
