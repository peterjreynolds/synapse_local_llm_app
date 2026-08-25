package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateMessageText
import app.synapse.privatechat.domain.chat.PrivateMessageTextValidation
import app.synapse.privatechat.domain.chat.PrivateReactionCode
import app.synapse.privatechat.domain.chat.PrivateReactionValidation
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateSocialTextValidation
import app.synapse.privatechat.domain.chat.parsePrivateClientMutationId
import app.synapse.privatechat.domain.chat.parsePrivateMessageId
import app.synapse.privatechat.domain.chat.parsePrivateRoomId
import app.synapse.privatechat.domain.chat.validatePrivateMessageText
import app.synapse.privatechat.domain.chat.validatePrivateReaction
import app.synapse.privatechat.domain.chat.validatePrivateRoomTitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

internal sealed interface PrivateChatPlaintextPayload {
    val accountId: PrivateAccountId
    val mutationId: PrivateClientMutationId

    data class Message(
        override val accountId: PrivateAccountId,
        val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val body: PrivateMessageText,
        val replyToMessageId: PrivateMessageId?,
    ) : PrivateChatPlaintextPayload

    data class Reaction(
        override val accountId: PrivateAccountId,
        val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val messageId: PrivateMessageId,
        val reaction: PrivateReactionCode,
    ) : PrivateChatPlaintextPayload

    data class MessageRevision(
        override val accountId: PrivateAccountId,
        val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val messageId: PrivateMessageId,
        val revision: Int,
        val body: PrivateMessageText,
    ) : PrivateChatPlaintextPayload {
        init {
            require(revision in 2..MAXIMUM_MESSAGE_DOMAIN_REVISION) {
                "Edited message revision is unsupported"
            }
        }
    }

    data class CreatedRoomMetadata(
        override val accountId: PrivateAccountId,
        val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val roomKind: PrivateRoomKind,
        val retention: PrivateMessageRetention,
        val title: String,
    ) : PrivateChatPlaintextPayload

    data class UpdatedRoomMetadata(
        override val accountId: PrivateAccountId,
        val roomId: PrivateRoomId,
        override val mutationId: PrivateClientMutationId,
        val expectedMetadataRevision: Int,
        val title: String,
    ) : PrivateChatPlaintextPayload {
        init {
            require(expectedMetadataRevision in 1 until MAXIMUM_METADATA_REVISION) {
                "Expected room metadata revision is unsupported"
            }
        }
    }
}

internal object PrivateChatPayloadCodec {
    fun encodeMessage(payload: PrivateChatPlaintextPayload.Message): ByteArray =
        buildJsonObject {
            put("schema_version", PAYLOAD_SCHEMA_VERSION)
            put("payload_kind", MESSAGE_KIND)
            put("sender_account_id", payload.accountId.canonical)
            put("room_id", payload.roomId.canonical)
            put("client_mutation_id", payload.mutationId.canonical)
            put("body", payload.body.plaintext)
            if (payload.replyToMessageId == null) {
                put("reply_to_message_id", JsonNull)
            } else {
                put("reply_to_message_id", payload.replyToMessageId.canonical)
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)

    fun encodeReaction(payload: PrivateChatPlaintextPayload.Reaction): ByteArray =
        buildJsonObject {
            put("schema_version", PAYLOAD_SCHEMA_VERSION)
            put("payload_kind", REACTION_KIND)
            put("sender_account_id", payload.accountId.canonical)
            put("room_id", payload.roomId.canonical)
            put("client_mutation_id", payload.mutationId.canonical)
            put("message_id", payload.messageId.canonical)
            put("reaction", payload.reaction.canonical)
        }.toString().toByteArray(StandardCharsets.UTF_8)

    fun encodeMessageRevision(payload: PrivateChatPlaintextPayload.MessageRevision): ByteArray =
        buildJsonObject {
            put("schema_version", PAYLOAD_SCHEMA_VERSION)
            put("payload_kind", MESSAGE_REVISION_KIND)
            put("sender_account_id", payload.accountId.canonical)
            put("room_id", payload.roomId.canonical)
            put("client_mutation_id", payload.mutationId.canonical)
            put("message_id", payload.messageId.canonical)
            put("revision", payload.revision)
            put("body", payload.body.plaintext)
        }.toString().toByteArray(StandardCharsets.UTF_8)

    fun encodeCreatedRoomMetadata(payload: PrivateChatPlaintextPayload.CreatedRoomMetadata): ByteArray =
        buildJsonObject {
            put("schema_version", PAYLOAD_SCHEMA_VERSION)
            put("payload_kind", CREATED_ROOM_METADATA_KIND)
            put("sender_account_id", payload.accountId.canonical)
            put("room_id", payload.roomId.canonical)
            put("client_mutation_id", payload.mutationId.canonical)
            put("room_kind", payload.roomKind.name)
            put("retention_seconds", payload.retention.durationSeconds)
            put("title", payload.title)
        }.toString().toByteArray(StandardCharsets.UTF_8)

    fun encodeUpdatedRoomMetadata(payload: PrivateChatPlaintextPayload.UpdatedRoomMetadata): ByteArray =
        buildJsonObject {
            put("schema_version", PAYLOAD_SCHEMA_VERSION)
            put("payload_kind", UPDATED_ROOM_METADATA_KIND)
            put("sender_account_id", payload.accountId.canonical)
            put("room_id", payload.roomId.canonical)
            put("client_mutation_id", payload.mutationId.canonical)
            put("expected_metadata_revision", payload.expectedMetadataRevision)
            put("title", payload.title)
        }.toString().toByteArray(StandardCharsets.UTF_8)

    fun decode(plaintext: ByteArray): PrivateChatPlaintextPayload {
        if (plaintext.isEmpty() || plaintext.size > MAXIMUM_CHAT_PAYLOAD_BYTES) {
            malformedPayload("Encrypted chat payload size is invalid")
        }
        val encodedJson = decodeStrictUtf8(plaintext)
        val payloadObject =
            try {
                STRICT_JSON.parseToJsonElement(encodedJson) as? JsonObject
            } catch (error: IllegalArgumentException) {
                throw PrivateChatPayloadException("Encrypted chat payload is malformed", error)
            } ?: malformedPayload("Encrypted chat payload is malformed")
        val schemaVersion = payloadObject["schema_version"]?.jsonPrimitive?.intOrNull
        if (schemaVersion != PAYLOAD_SCHEMA_VERSION) malformedPayload("Encrypted chat payload version is unsupported")
        return when (payloadObject.requireString("payload_kind")) {
            MESSAGE_KIND -> payloadObject.decodeMessage()
            REACTION_KIND -> payloadObject.decodeReaction()
            MESSAGE_REVISION_KIND -> payloadObject.decodeMessageRevision()
            CREATED_ROOM_METADATA_KIND -> payloadObject.decodeCreatedRoomMetadata()
            UPDATED_ROOM_METADATA_KIND -> payloadObject.decodeUpdatedRoomMetadata()
            else -> malformedPayload("Encrypted chat payload kind is unsupported")
        }
    }

    private fun JsonObject.decodeMessage(): PrivateChatPlaintextPayload.Message {
        requireExactKeys(MESSAGE_KEYS)
        val rawBody = requireString("body")
        val body =
            when (val validation = validatePrivateMessageText(rawBody)) {
                is PrivateMessageTextValidation.Accepted ->
                    validation.message.takeIf { message -> message.plaintext == rawBody }
                        ?: malformedPayload("Encrypted message body is not canonical")

                is PrivateMessageTextValidation.Rejected -> malformedPayload("Encrypted message body is invalid")
            }
        val replyToMessageId =
            when (val reply = getValue("reply_to_message_id")) {
                JsonNull -> null
                is JsonPrimitive ->
                    reply.takeIf(JsonPrimitive::isString)?.content?.let(::requireMessageId)
                        ?: malformedPayload("Encrypted reply target is invalid")

                else -> malformedPayload("Encrypted reply target is invalid")
            }
        return PrivateChatPlaintextPayload.Message(
            accountId = requireAccountId("sender_account_id"),
            roomId = requireRoomId("room_id"),
            mutationId = requireMutationId("client_mutation_id"),
            body = body,
            replyToMessageId = replyToMessageId,
        )
    }

    private fun JsonObject.decodeReaction(): PrivateChatPlaintextPayload.Reaction {
        requireExactKeys(REACTION_KEYS)
        val rawReaction = requireString("reaction")
        val reaction =
            when (val validation = validatePrivateReaction(rawReaction)) {
                is PrivateReactionValidation.Accepted ->
                    validation.reaction.takeIf { code -> code.canonical == rawReaction }
                        ?: malformedPayload("Encrypted reaction is not canonical")

                is PrivateReactionValidation.Rejected -> malformedPayload("Encrypted reaction is invalid")
            }
        return PrivateChatPlaintextPayload.Reaction(
            accountId = requireAccountId("sender_account_id"),
            roomId = requireRoomId("room_id"),
            mutationId = requireMutationId("client_mutation_id"),
            messageId = requireMessageId(requireString("message_id")),
            reaction = reaction,
        )
    }

    private fun JsonObject.decodeMessageRevision(): PrivateChatPlaintextPayload.MessageRevision {
        requireExactKeys(MESSAGE_REVISION_KEYS)
        return PrivateChatPlaintextPayload.MessageRevision(
            accountId = requireAccountId("sender_account_id"),
            roomId = requireRoomId("room_id"),
            mutationId = requireMutationId("client_mutation_id"),
            messageId = requireMessageId(requireString("message_id")),
            revision = requireBoundedInt("revision", 2..MAXIMUM_MESSAGE_DOMAIN_REVISION),
            body = requireCanonicalMessageBody("body"),
        )
    }

    private fun JsonObject.decodeCreatedRoomMetadata(): PrivateChatPlaintextPayload.CreatedRoomMetadata {
        requireExactKeys(CREATED_ROOM_METADATA_KEYS)
        return PrivateChatPlaintextPayload.CreatedRoomMetadata(
            accountId = requireAccountId("sender_account_id"),
            roomId = requireRoomId("room_id"),
            mutationId = requireMutationId("client_mutation_id"),
            roomKind = requireRoomKind("room_kind"),
            retention = requireRetention("retention_seconds"),
            title = requireCanonicalRoomTitle("title"),
        )
    }

    private fun JsonObject.decodeUpdatedRoomMetadata(): PrivateChatPlaintextPayload.UpdatedRoomMetadata {
        requireExactKeys(UPDATED_ROOM_METADATA_KEYS)
        return PrivateChatPlaintextPayload.UpdatedRoomMetadata(
            accountId = requireAccountId("sender_account_id"),
            roomId = requireRoomId("room_id"),
            mutationId = requireMutationId("client_mutation_id"),
            expectedMetadataRevision =
                requireBoundedInt(
                    "expected_metadata_revision",
                    1 until MAXIMUM_METADATA_REVISION,
                ),
            title = requireCanonicalRoomTitle("title"),
        )
    }
}

internal class PrivateChatPayloadException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private fun JsonObject.requireExactKeys(expectedKeys: Set<String>) {
    if (keys != expectedKeys) malformedPayload("Encrypted chat payload fields are invalid")
}

private fun JsonObject.requireString(field: String): String {
    val primitive = this[field] as? JsonPrimitive
    if (primitive == null || !primitive.isString) malformedPayload("Encrypted chat payload field is invalid")
    return primitive.content
}

private fun JsonObject.requireBoundedInt(
    field: String,
    supportedRange: IntRange,
): Int {
    val integer = this[field]?.jsonPrimitive?.intOrNull
    if (integer == null || integer !in supportedRange) malformedPayload("Encrypted chat integer is invalid")
    return integer
}

private fun JsonObject.requireCanonicalMessageBody(field: String): PrivateMessageText {
    val rawBody = requireString(field)
    return when (val validation = validatePrivateMessageText(rawBody)) {
        is PrivateMessageTextValidation.Accepted ->
            validation.message.takeIf { message -> message.plaintext == rawBody }
                ?: malformedPayload("Encrypted message body is not canonical")

        is PrivateMessageTextValidation.Rejected -> malformedPayload("Encrypted message body is invalid")
    }
}

private fun JsonObject.requireCanonicalRoomTitle(field: String): String {
    val rawTitle = requireString(field)
    return when (val validation = validatePrivateRoomTitle(rawTitle)) {
        is PrivateSocialTextValidation.Accepted ->
            validation.normalizedText.takeIf { title -> title == rawTitle }
                ?: malformedPayload("Encrypted room title is not canonical")

        is PrivateSocialTextValidation.Rejected -> malformedPayload("Encrypted room title is invalid")
    }
}

private fun JsonObject.requireRoomKind(field: String): PrivateRoomKind =
    try {
        PrivateRoomKind.valueOf(requireString(field))
    } catch (error: IllegalArgumentException) {
        throw PrivateChatPayloadException("Encrypted room kind is invalid", error)
    }

private fun JsonObject.requireRetention(field: String): PrivateMessageRetention {
    val seconds = requireBoundedInt(field, 1..604_800)
    return PrivateMessageRetention.entries.firstOrNull { retention -> retention.durationSeconds == seconds }
        ?: malformedPayload("Encrypted room retention is unsupported")
}

private fun JsonObject.requireAccountId(field: String): PrivateAccountId {
    val canonical = requireCanonicalUuid(requireString(field), "account ID")
    return PrivateAccountId(canonical)
}

private fun JsonObject.requireRoomId(field: String): PrivateRoomId =
    parsePrivateRoomId(requireCanonicalUuid(requireString(field), "room ID"))
        ?: malformedPayload("Encrypted room ID is invalid")

private fun JsonObject.requireMutationId(field: String): PrivateClientMutationId =
    parsePrivateClientMutationId(requireCanonicalUuid(requireString(field), "client mutation ID"))
        ?: malformedPayload("Encrypted client mutation ID is invalid")

private fun requireMessageId(rawMessageId: String): PrivateMessageId =
    parsePrivateMessageId(requireCanonicalUuid(rawMessageId, "message ID"))
        ?: malformedPayload("Encrypted message ID is invalid")

private fun requireCanonicalUuid(
    rawUuid: String,
    fieldName: String,
): String {
    if (rawUuid != rawUuid.lowercase(Locale.ROOT)) malformedPayload("Encrypted $fieldName is invalid")
    val parsed =
        try {
            UUID.fromString(rawUuid)
        } catch (error: IllegalArgumentException) {
            throw PrivateChatPayloadException("Encrypted $fieldName is invalid", error)
        }
    if (parsed.toString() != rawUuid) malformedPayload("Encrypted $fieldName is invalid")
    return rawUuid
}

private fun decodeStrictUtf8(bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw PrivateChatPayloadException("Encrypted chat payload is not valid UTF-8", error)
    }

private fun malformedPayload(message: String): Nothing = throw PrivateChatPayloadException(message)

private const val PAYLOAD_SCHEMA_VERSION = 1
private const val MESSAGE_KIND = "MESSAGE"
private const val REACTION_KIND = "REACTION"
private const val MESSAGE_REVISION_KIND = "MESSAGE_REVISION"
private const val CREATED_ROOM_METADATA_KIND = "CREATED_ROOM_METADATA"
private const val UPDATED_ROOM_METADATA_KIND = "UPDATED_ROOM_METADATA"
private const val MAXIMUM_CHAT_PAYLOAD_BYTES = 64 * 1_024
private const val MAXIMUM_MESSAGE_DOMAIN_REVISION = 101
private const val MAXIMUM_METADATA_REVISION = 2_147_483_647
private val STRICT_JSON = Json { ignoreUnknownKeys = false }
private val MESSAGE_KEYS =
    setOf(
        "schema_version",
        "payload_kind",
        "sender_account_id",
        "room_id",
        "client_mutation_id",
        "body",
        "reply_to_message_id",
    )
private val REACTION_KEYS =
    setOf(
        "schema_version",
        "payload_kind",
        "sender_account_id",
        "room_id",
        "client_mutation_id",
        "message_id",
        "reaction",
    )
private val MESSAGE_REVISION_KEYS =
    setOf(
        "schema_version",
        "payload_kind",
        "sender_account_id",
        "room_id",
        "client_mutation_id",
        "message_id",
        "revision",
        "body",
    )
private val CREATED_ROOM_METADATA_KEYS =
    setOf(
        "schema_version",
        "payload_kind",
        "sender_account_id",
        "room_id",
        "client_mutation_id",
        "room_kind",
        "retention_seconds",
        "title",
    )
private val UPDATED_ROOM_METADATA_KEYS =
    setOf(
        "schema_version",
        "payload_kind",
        "sender_account_id",
        "room_id",
        "client_mutation_id",
        "expected_metadata_revision",
        "title",
    )
