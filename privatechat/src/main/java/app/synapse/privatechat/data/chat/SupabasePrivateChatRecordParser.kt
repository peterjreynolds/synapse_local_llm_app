package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalKyberPreKey
import app.synapse.privatechat.crypto.SignalOneTimePreKey
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.crypto.SignalSignedPreKey
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateSocialTextValidation
import app.synapse.privatechat.domain.chat.validatePrivateProfileDisplayName
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

internal fun SupabaseHttpResponse.parseProfiles(): List<PrivateBackendProfileRecord> =
    requireChatRows("profile polling").map { row ->
        row.requireExactChatFields(
            "user_id",
            "display_name",
            "presence_sharing_enabled",
            "typing_indicators_enabled",
            "read_receipts_enabled",
        )
        val displayName = row.requireChatString("display_name")
        val validatedName = validatePrivateProfileDisplayName(displayName)
        if (validatedName !is PrivateSocialTextValidation.Accepted || validatedName.normalizedText != displayName) {
            malformedRecord("Supabase profile display name is malformed")
        }
        PrivateBackendProfileRecord(
            accountId = row.requireChatUuid("user_id"),
            displayName = displayName,
            presenceSharing = row.requireChatBoolean("presence_sharing_enabled").toPresenceSharing(),
            activitySharing =
                PrivateActivitySharingPreferences(
                    readReceipts = row.requireChatBoolean("read_receipts_enabled").toActivitySharing(),
                    typingIndicators = row.requireChatBoolean("typing_indicators_enabled").toActivitySharing(),
                ),
        )
    }

internal fun SupabaseHttpResponse.parseRooms(): List<PrivateBackendRoomRecord> =
    requireChatRows("room polling").map { row ->
        row.requireExactChatFields(
            "id",
            "owner_user_id",
            "creation_client_mutation_id",
            "room_kind",
            "retention_seconds",
            "membership_epoch",
            "metadata_revision",
            "metadata_updated_at",
            "created_at",
        )
        PrivateBackendRoomRecord(
            roomId = row.requireChatUuid("id"),
            ownerAccountId = row.requireChatUuid("owner_user_id"),
            creationClientMutationId = row.requireNullableChatUuid("creation_client_mutation_id"),
            kind = row.requireEnum("room_kind"),
            retention = row.requireRetention("retention_seconds"),
            membershipEpoch = row.requireChatInt("membership_epoch", 1..Int.MAX_VALUE),
            metadataRevision = row.requireChatInt("metadata_revision", 1..Int.MAX_VALUE),
            metadataUpdatedAt = row.requireNullableChatInstant("metadata_updated_at"),
            createdAt = row.requireChatInstant("created_at"),
        ).also { room ->
            if (room.metadataUpdatedAt == null || room.metadataUpdatedAt.isBefore(room.createdAt)) {
                malformedRecord("Supabase room metadata timestamps are inconsistent")
            }
        }
    }

internal fun SupabaseHttpResponse.parseRoomMembers(): List<PrivateBackendRoomMemberRecord> =
    requireChatRows("room member polling").map { row ->
        row.requireExactChatFields("room_id", "user_id", "member_role", "joined_at")
        PrivateBackendRoomMemberRecord(
            roomId = row.requireChatUuid("room_id"),
            accountId = row.requireChatUuid("user_id"),
            role = row.requireEnum("member_role"),
            joinedAt = row.requireChatInstant("joined_at"),
        )
    }

internal fun SupabaseHttpResponse.parseRoomPreferences(): List<PrivateBackendRoomPreferenceRecord> =
    requireChatRows("room preference polling").map { row -> row.parseRoomPreferenceRecord() }

internal fun SupabaseHttpResponse.parseDevices(): List<PrivateBackendDeviceRecord> =
    requireChatRows("device polling").map { row ->
        row.requireExactChatFields("id", "user_id", "protocol_adapter_version", "signal_device_id")
        val protocolVersion = row.requireChatInt("protocol_adapter_version", 1..1)
        PrivateBackendDeviceRecord(
            address =
                SignalDeviceAddress.fromWire(
                    accountId = row.requireChatUuid("user_id").toString(),
                    transportDeviceId = row.requireChatUuid("id").toString(),
                    protocolDeviceId = row.requireChatInt("signal_device_id", 1..127),
                ),
            protocolAdapterVersion = protocolVersion,
        )
    }

internal fun SupabaseHttpResponse.parseRecipientDevices(): List<PrivateChatRecipientDevice> =
    requireChatRows("room recipient device listing", maximumRows = 129)
        .map { row ->
            row.requireExactChatFields("device_id", "user_id", "protocol_adapter_version", "signal_device_id")
            PrivateChatRecipientDevice(
                address =
                    SignalDeviceAddress.fromWire(
                        accountId = row.requireChatUuid("user_id").toString(),
                        transportDeviceId = row.requireChatUuid("device_id").toString(),
                        protocolDeviceId = row.requireChatInt("signal_device_id", 1..127),
                    ),
                protocolAdapterVersion = row.requireChatInt("protocol_adapter_version", 1..1),
            )
        }.also { recipients ->
            if (recipients.isEmpty()) malformedRecord("Supabase returned no room recipient devices")
        }

internal fun SupabaseHttpResponse.parseMessages(now: Instant): List<PrivateBackendMessageRecord> =
    requireChatRows("message polling").map { row ->
        row.requireExactChatFields(
            "id",
            "room_id",
            "sender_user_id",
            "sender_device_id",
            "client_message_id",
            "membership_epoch",
            "current_revision",
            "created_at",
            "expires_at",
        )
        PrivateBackendMessageRecord(
            messageId = row.requireChatUuid("id"),
            roomId = row.requireChatUuid("room_id"),
            senderAccountId = row.requireChatUuid("sender_user_id"),
            senderDeviceId = row.requireNullableChatUuid("sender_device_id") ?: malformedRecord("Message sender device is missing"),
            clientMutationId = row.requireChatUuid("client_message_id"),
            membershipEpoch = row.requireChatInt("membership_epoch", 1..Int.MAX_VALUE),
            currentRevision = row.requireChatInt("current_revision", 0..100),
            createdAt = row.requireChatInstant("created_at"),
            expiresAt = row.requireChatInstant("expires_at"),
        ).also { message ->
            if (!message.expiresAt.isAfter(message.createdAt) || !message.expiresAt.isAfter(now)) {
                malformedRecord("Supabase returned an expired message")
            }
        }
    }

internal fun SupabaseHttpResponse.parseMessageRevisions(now: Instant): List<PrivateBackendMessageRevisionRecord> =
    requireChatRows("message revision polling").map { row ->
        row.requireExactChatFields(
            "id",
            "message_id",
            "editor_user_id",
            "editor_device_id",
            "revision_number",
            "membership_epoch",
            "created_at",
            "expires_at",
        )
        PrivateBackendMessageRevisionRecord(
            revisionId = row.requireChatUuid("id"),
            messageId = row.requireChatUuid("message_id"),
            editorAccountId = row.requireNullableChatUuid("editor_user_id") ?: malformedRecord("Message editor is missing"),
            editorDeviceId = row.requireNullableChatUuid("editor_device_id") ?: malformedRecord("Message editor device is missing"),
            revisionNumber = row.requireChatInt("revision_number", 1..100),
            membershipEpoch = row.requireChatInt("membership_epoch", 1..Int.MAX_VALUE),
            editedAt = row.requireChatInstant("created_at"),
            expiresAt = row.requireChatInstant("expires_at"),
        ).also { revision ->
            if (!revision.expiresAt.isAfter(revision.editedAt) || !revision.expiresAt.isAfter(now)) {
                malformedRecord("Supabase returned an expired message revision")
            }
        }
    }

internal fun SupabaseHttpResponse.parseReplies(): List<PrivateBackendReplyRecord> =
    requireChatRows("reply polling").map { row ->
        row.requireExactChatFields("message_id", "replied_to_message_id")
        PrivateBackendReplyRecord(
            messageId = row.requireChatUuid("message_id"),
            repliedToMessageId = row.requireChatUuid("replied_to_message_id"),
        )
    }

internal fun SupabaseHttpResponse.parseReactions(now: Instant): List<PrivateBackendReactionRecord> =
    requireChatRows("reaction polling").map { row ->
        row.requireExactChatFields(
            "id",
            "message_id",
            "sender_user_id",
            "sender_device_id",
            "client_reaction_id",
            "membership_epoch",
            "created_at",
            "expires_at",
        )
        PrivateBackendReactionRecord(
            reactionId = row.requireChatUuid("id"),
            messageId = row.requireChatUuid("message_id"),
            senderAccountId = row.requireChatUuid("sender_user_id"),
            senderDeviceId = row.requireNullableChatUuid("sender_device_id") ?: malformedRecord("Reaction sender device is missing"),
            clientMutationId = row.requireChatUuid("client_reaction_id"),
            membershipEpoch = row.requireChatInt("membership_epoch", 1..Int.MAX_VALUE),
            createdAt = row.requireChatInstant("created_at"),
            expiresAt = row.requireChatInstant("expires_at"),
        ).also { reaction ->
            if (!reaction.expiresAt.isAfter(reaction.createdAt) || !reaction.expiresAt.isAfter(now)) {
                malformedRecord("Supabase returned an expired reaction")
            }
        }
    }

internal fun SupabaseHttpResponse.parseMessageReceipts(now: Instant?): List<PrivateBackendMessageReceiptRecord> =
    requireChatRows("message receipt polling").map { row ->
        row.requireExactChatFields("message_id", "recipient_device_id", "receipt_kind", "created_at", "expires_at")
        PrivateBackendMessageReceiptRecord(
            messageId = row.requireChatUuid("message_id"),
            recipientDeviceId = row.requireChatUuid("recipient_device_id"),
            kind = row.requireEnum("receipt_kind"),
            createdAt = row.requireChatInstant("created_at"),
            expiresAt = row.requireChatInstant("expires_at"),
        ).also { receipt ->
            if (
                !receipt.expiresAt.isAfter(receipt.createdAt) ||
                (now != null && !receipt.expiresAt.isAfter(now))
            ) {
                malformedRecord("Supabase returned an expired message receipt")
            }
        }
    }

internal fun SupabaseHttpResponse.parseTyping(now: Instant?): List<PrivateBackendTypingRecord> =
    requireChatRows("typing polling").map { row ->
        row.requireExactChatFields("room_id", "device_id", "created_at", "expires_at")
        PrivateBackendTypingRecord(
            roomId = row.requireChatUuid("room_id"),
            deviceId = row.requireChatUuid("device_id"),
            createdAt = row.requireChatInstant("created_at"),
            expiresAt = row.requireChatInstant("expires_at"),
        ).also { typing ->
            if (
                !typing.expiresAt.isAfter(typing.createdAt) ||
                (now != null && !typing.expiresAt.isAfter(now))
            ) {
                malformedRecord("Supabase returned expired typing state")
            }
        }
    }

internal fun SupabaseHttpResponse.parsePresence(now: Instant?): List<PrivateBackendPresenceRecord> =
    requireChatRows("presence polling").map { row -> row.parsePresenceRecord(now) }

internal data class SupabaseEncryptedEnvelopeRow(
    val parentRecordId: UUID,
    val recipientDeviceId: UUID,
    val protocolAdapterVersion: Int,
    val kind: PrivateChatEnvelopeKind,
    val ciphertext: ByteArray,
    val createdAt: Instant,
)

internal fun SupabaseHttpResponse.parseEnvelopeRows(
    operation: String,
    parentField: String,
    maximumCiphertextBytes: Int,
): List<SupabaseEncryptedEnvelopeRow> =
    requireChatRows(operation).map { row ->
        row.requireExactChatFields(
            parentField,
            "recipient_device_id",
            "protocol_adapter_version",
            "signal_message_type",
            "ciphertext",
            "created_at",
        )
        SupabaseEncryptedEnvelopeRow(
            parentRecordId = row.requireChatUuid(parentField),
            recipientDeviceId = row.requireChatUuid("recipient_device_id"),
            protocolAdapterVersion = row.requireChatInt("protocol_adapter_version", 1..1),
            kind = row.requireEnvelopeKind("signal_message_type"),
            ciphertext = row.requirePostgresBytea("ciphertext", 1..maximumCiphertextBytes),
            createdAt = row.requireChatInstant("created_at"),
        )
    }

internal fun SupabaseHttpResponse.parseRoomMetadataEnvelopeRows(): List<PrivateBackendEnvelopeRecord> =
    requireChatRows("room metadata envelope polling").map { row ->
        row.requireExactChatFields(
            "room_id",
            "metadata_revision",
            "sender_user_id",
            "sender_device_id",
            "recipient_device_id",
            "protocol_adapter_version",
            "signal_message_type",
            "ciphertext",
            "created_at",
        )
        val roomId = row.requireChatUuid("room_id")
        val revision = row.requireChatInt("metadata_revision", 1..Int.MAX_VALUE)
        PrivateBackendEnvelopeRecord(
            parentRecordId = roomId,
            serverRevision = revision,
            senderAccountId = row.requireNullableChatUuid("sender_user_id") ?: malformedRecord("Room metadata sender is missing"),
            senderDeviceId = row.requireNullableChatUuid("sender_device_id") ?: malformedRecord("Room metadata sender device is missing"),
            envelope = row.parseEncryptedEnvelope(maximumCiphertextBytes = 16_384),
            createdAt = row.requireChatInstant("created_at"),
        ).also {
            if (revision < 1) malformedRecord("Room metadata revision is invalid")
        }
    }

internal fun SupabaseHttpResponse.parseClaimedPreKeyBundle(recipient: PrivateChatRecipientDevice): SignalPublicPreKeyBundle {
    val row = requireSingleChatRow("device pre-key claim")
    row.requireExactChatFields(
        "target_device_id",
        "protocol_adapter_version",
        "registration_id",
        "signal_device_id",
        "identity_key",
        "signed_pre_key_id",
        "signed_pre_key_public",
        "signed_pre_key_signature",
        "kyber_pre_key_id",
        "kyber_pre_key_public",
        "kyber_pre_key_signature",
        "one_time_pre_key_id",
        "one_time_pre_key_public",
    )
    if (
        row.requireChatUuid("target_device_id") != recipient.address.transportDeviceId ||
        row.requireChatInt("signal_device_id", 1..127) != recipient.address.protocolDeviceId.raw
    ) {
        malformedRecord("Claimed pre-key bundle does not match its recipient")
    }
    val oneTimeId = row.requireNullableChatInt("one_time_pre_key_id", 0..16_777_215)
    val oneTimePublic = row.requireNullablePostgresBytea("one_time_pre_key_public", 33..33)
    if ((oneTimeId == null) != (oneTimePublic == null)) malformedRecord("Claimed one-time pre-key is incomplete")
    return SignalPublicPreKeyBundle.fromWire(
        protocolVersion = row.requireChatInt("protocol_adapter_version", 1..1),
        address = recipient.address,
        registrationId = row.requireChatInt("registration_id", 1..16_380),
        identityKeyBytes = row.requirePostgresBytea("identity_key", 33..33),
        oneTimePreKey =
            oneTimeId?.let { id ->
                SignalOneTimePreKey.fromWire(id, requireNotNull(oneTimePublic))
            },
        signedPreKey =
            SignalSignedPreKey.fromWire(
                id = row.requireChatInt("signed_pre_key_id", 0..16_777_215),
                publicKeyBytes = row.requirePostgresBytea("signed_pre_key_public", 33..33),
                signatureBytes = row.requirePostgresBytea("signed_pre_key_signature", 64..64),
            ),
        kyberPreKey =
            SignalKyberPreKey.fromWire(
                id = row.requireChatInt("kyber_pre_key_id", 0..16_777_215),
                publicKeyBytes = row.requirePostgresBytea("kyber_pre_key_public", 1_569..1_569),
                signatureBytes = row.requirePostgresBytea("kyber_pre_key_signature", 64..64),
            ),
    )
}

internal fun JsonObject.parseRoomPreferenceRecord(): PrivateBackendRoomPreferenceRecord {
    requireExactChatFields("room_id", "archive_state", "pin_state", "mute_state", "muted_until", "updated_at")
    val rawMuteState = requireChatString("mute_state")
    val mutedUntil = requireNullableChatInstant("muted_until")
    if ((rawMuteState == "MUTED_UNTIL") != (mutedUntil != null)) {
        malformedRecord("Supabase room mute state is incomplete")
    }
    return PrivateBackendRoomPreferenceRecord(
        roomId = requireChatUuid("room_id"),
        archiveState = requireEnum("archive_state"),
        pinState = requireEnum("pin_state"),
        muteState =
            when (rawMuteState) {
                "UNMUTED" -> PrivateRoomMuteState.AUDIBLE
                "MUTED_UNTIL", "MUTED_FOREVER" -> PrivateRoomMuteState.MUTED
                else -> malformedRecord("Supabase room mute state is unsupported")
            },
        updatedAt = requireChatInstant("updated_at"),
    )
}

internal fun JsonObject.parsePresenceRecord(now: Instant? = null): PrivateBackendPresenceRecord {
    requireExactChatFields("device_id", "created_at", "expires_at")
    return PrivateBackendPresenceRecord(
        deviceId = requireChatUuid("device_id"),
        createdAt = requireChatInstant("created_at"),
        expiresAt = requireChatInstant("expires_at"),
    ).also { presence ->
        if (
            !presence.expiresAt.isAfter(presence.createdAt) ||
            presence.expiresAt.isAfter(presence.createdAt.plusSeconds(120)) ||
            (now != null && !presence.expiresAt.isAfter(now))
        ) {
            malformedRecord("Supabase presence interval is invalid")
        }
    }
}

internal fun JsonObject.parseEncryptedEnvelope(maximumCiphertextBytes: Int): PrivateChatEncryptedEnvelope =
    PrivateChatEncryptedEnvelope(
        recipientDeviceId = requireChatUuid("recipient_device_id"),
        protocolAdapterVersion = requireChatInt("protocol_adapter_version", 1..1),
        kind = requireEnvelopeKind("signal_message_type"),
        ciphertext = requirePostgresBytea("ciphertext", 1..maximumCiphertextBytes),
    )

internal fun JsonObject.requireNullableChatInt(
    field: String,
    supportedRange: IntRange,
): Int? =
    when (this[field]) {
        kotlinx.serialization.json.JsonNull -> null
        else -> requireChatInt(field, supportedRange)
    }

private inline fun <reified EnumType : Enum<EnumType>> JsonObject.requireEnum(field: String): EnumType =
    try {
        enumValueOf(requireChatString(field))
    } catch (error: IllegalArgumentException) {
        throw SupabasePrivateChatResponseException("Supabase chat field $field is unsupported", error)
    }

private fun JsonObject.requireRetention(field: String): PrivateMessageRetention {
    val seconds = requireChatInt(field, 1..604_800)
    return PrivateMessageRetention.entries.firstOrNull { retention -> retention.durationSeconds == seconds }
        ?: malformedRecord("Supabase room retention is unsupported")
}

private fun JsonObject.requireEnvelopeKind(field: String): PrivateChatEnvelopeKind =
    try {
        PrivateChatEnvelopeKind.fromWire(requireChatString(field))
    } catch (error: PrivateChatEnvelopeException) {
        throw SupabasePrivateChatResponseException("Supabase envelope kind is malformed", error)
    }

private fun Boolean.toActivitySharing(): PrivateActivitySharingState =
    if (this) PrivateActivitySharingState.ENABLED else PrivateActivitySharingState.DISABLED

private fun Boolean.toPresenceSharing(): PrivatePresenceSharingState =
    if (this) PrivatePresenceSharingState.ENABLED else PrivatePresenceSharingState.DISABLED

private fun malformedRecord(message: String): Nothing = throw SupabasePrivateChatResponseException(message)
