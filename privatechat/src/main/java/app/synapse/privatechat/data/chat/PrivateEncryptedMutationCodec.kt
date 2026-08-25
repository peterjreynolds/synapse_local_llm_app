package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

internal object PrivateEncryptedMutationCodec {
    fun operationDigest(
        intent: PrivateEncryptedMutationIntent,
        plaintext: ByteArray,
    ): ByteArray {
        require(plaintext.isNotEmpty() && plaintext.size <= MAXIMUM_INTENT_PLAINTEXT_BYTES) {
            "Encrypted mutation plaintext size is invalid"
        }
        val encodedIntent = encodeIntent(intent)
        return try {
            MessageDigest.getInstance("SHA-256").run {
                update(encodedIntent)
                digest(plaintext)
            }
        } finally {
            encodedIntent.fill(0)
        }
    }

    fun encode(request: PrivatePendingEncryptedMutation): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeRequestFields(request)
                data.writeEnvelopes(request.envelopes)
            }
            output.toByteArray().also { encoded ->
                require(encoded.size in 1..MAXIMUM_ENCODED_REQUEST_BYTES) {
                    "Pending encrypted mutation exceeds the size limit"
                }
            }
        }

    fun decode(encoded: ByteArray): PrivatePendingEncryptedMutation {
        if (encoded.isEmpty() || encoded.size > MAXIMUM_ENCODED_REQUEST_BYTES) malformed()
        try {
            val input = ByteArrayInputStream(encoded)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC || data.readInt() != VERSION) malformed()
            val kind = PrivateEncryptedMutationKind.fromWire(data.readInt())
            val clientMutationId = data.readUuid()
            val requestFields = data.readRequestFields(kind, clientMutationId)
            val envelopes = data.readEnvelopes()
            if (input.available() != 0) malformed()
            return requestFields.attachEnvelopes(envelopes)
        } catch (error: PrivateEncryptedMutationCodecException) {
            throw error
        } catch (error: Exception) {
            throw PrivateEncryptedMutationCodecException("Pending encrypted mutation is malformed", error)
        }
    }

    private fun encodeIntent(intent: PrivateEncryptedMutationIntent): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeIntentFields(intent)
            }
            output.toByteArray()
        }

    private fun DataOutputStream.writeRequestFields(request: PrivatePendingEncryptedMutation) {
        val intent =
            when (request) {
                is PrivatePendingEncryptedMutation.SendMessage ->
                    PrivateEncryptedMutationIntent.SendMessage(
                        request.roomId,
                        request.clientMutationId,
                        request.replyToMessageId,
                    )

                is PrivatePendingEncryptedMutation.EditMessage ->
                    PrivateEncryptedMutationIntent.EditMessage(
                        request.messageId,
                        request.clientMutationId,
                        request.expectedServerRevision,
                    )

                is PrivatePendingEncryptedMutation.AddReaction ->
                    PrivateEncryptedMutationIntent.AddReaction(request.messageId, request.clientMutationId)

                is PrivatePendingEncryptedMutation.CreateRoom ->
                    PrivateEncryptedMutationIntent.CreateRoom(
                        request.roomId,
                        request.clientMutationId,
                        request.kind,
                        request.retention,
                    )
            }
        writeIntentFields(intent)
    }

    private fun DataOutputStream.writeIntentFields(intent: PrivateEncryptedMutationIntent) {
        when (intent) {
            is PrivateEncryptedMutationIntent.SendMessage -> {
                writeInt(PrivateEncryptedMutationKind.SEND_MESSAGE.wireCode)
                writeUuid(intent.clientMutationId)
                writeUuid(intent.roomId)
                writeOptionalUuid(intent.replyToMessageId)
            }

            is PrivateEncryptedMutationIntent.EditMessage -> {
                writeInt(PrivateEncryptedMutationKind.EDIT_MESSAGE.wireCode)
                writeUuid(intent.clientMutationId)
                writeUuid(intent.messageId)
                writeInt(intent.expectedServerRevision)
            }

            is PrivateEncryptedMutationIntent.AddReaction -> {
                writeInt(PrivateEncryptedMutationKind.ADD_REACTION.wireCode)
                writeUuid(intent.clientMutationId)
                writeUuid(intent.messageId)
            }

            is PrivateEncryptedMutationIntent.CreateRoom -> {
                writeInt(PrivateEncryptedMutationKind.CREATE_ROOM.wireCode)
                writeUuid(intent.clientMutationId)
                writeUuid(intent.roomId)
                writeUTF(intent.kind.name)
                writeInt(intent.retention.durationSeconds)
            }
        }
    }

    private fun DataInputStream.readRequestFields(
        kind: PrivateEncryptedMutationKind,
        clientMutationId: UUID,
    ): PrivateEncryptedMutationIntent =
        when (kind) {
            PrivateEncryptedMutationKind.SEND_MESSAGE ->
                PrivateEncryptedMutationIntent.SendMessage(readUuid(), clientMutationId, readOptionalUuid())

            PrivateEncryptedMutationKind.EDIT_MESSAGE ->
                PrivateEncryptedMutationIntent.EditMessage(
                    readUuid(),
                    clientMutationId,
                    readInt().also { revision -> if (revision !in 0..100) malformed() },
                )

            PrivateEncryptedMutationKind.ADD_REACTION ->
                PrivateEncryptedMutationIntent.AddReaction(readUuid(), clientMutationId)

            PrivateEncryptedMutationKind.CREATE_ROOM ->
                readCreatedRoomIntent(clientMutationId)
        }

    private fun DataInputStream.readCreatedRoomIntent(clientMutationId: UUID): PrivateEncryptedMutationIntent.CreateRoom {
        val roomId = readUuid()
        val kind = runCatching { PrivateRoomKind.valueOf(readUTF()) }.getOrElse { malformed() }
        val retentionSeconds = readInt()
        val retention =
            PrivateMessageRetention.entries.firstOrNull { candidate ->
                candidate.durationSeconds == retentionSeconds
            } ?: malformed()
        return PrivateEncryptedMutationIntent.CreateRoom(roomId, clientMutationId, kind, retention)
    }

    private fun DataOutputStream.writeEnvelopes(envelopes: List<PrivateChatEncryptedEnvelope>) {
        require(envelopes.size in 1..MAXIMUM_ENVELOPES) { "Pending encrypted mutation fan-out is invalid" }
        require(envelopes.map(PrivateChatEncryptedEnvelope::recipientDeviceId).distinct().size == envelopes.size) {
            "Pending encrypted mutation recipient devices are not unique"
        }
        writeInt(envelopes.size)
        envelopes.forEach { envelope ->
            writeUuid(envelope.recipientDeviceId)
            writeInt(envelope.protocolAdapterVersion)
            writeInt(envelope.kind.ordinal)
            val ciphertext = envelope.ciphertextCopy()
            try {
                writeInt(ciphertext.size)
                write(ciphertext)
            } finally {
                ciphertext.fill(0)
            }
        }
    }

    private fun DataInputStream.readEnvelopes(): List<PrivateChatEncryptedEnvelope> {
        val count = readInt()
        if (count !in 1..MAXIMUM_ENVELOPES) malformed()
        val envelopes =
            List(count) {
                val recipientDeviceId = readUuid()
                val protocolAdapterVersion = readInt()
                val envelopeKind = PrivateChatEnvelopeKind.entries.getOrNull(readInt()) ?: malformed()
                val ciphertextSize = readInt()
                if (ciphertextSize !in 1..MAXIMUM_ENVELOPE_BYTES) malformed()
                val ciphertext = ByteArray(ciphertextSize).also(::readFully)
                try {
                    PrivateChatEncryptedEnvelope(
                        recipientDeviceId = recipientDeviceId,
                        protocolAdapterVersion = protocolAdapterVersion,
                        kind = envelopeKind,
                        ciphertext = ciphertext,
                    )
                } finally {
                    ciphertext.fill(0)
                }
            }
        if (envelopes.map(PrivateChatEncryptedEnvelope::recipientDeviceId).distinct().size != envelopes.size) malformed()
        return envelopes
    }

    private fun DataOutputStream.writeUuid(value: UUID) {
        require(value != NIL_UUID) { "Pending encrypted mutation UUID must be non-zero" }
        writeLong(value.mostSignificantBits)
        writeLong(value.leastSignificantBits)
    }

    private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong()).also { value -> if (value == NIL_UUID) malformed() }

    private fun DataOutputStream.writeOptionalUuid(value: UUID?) {
        writeBoolean(value != null)
        value?.let { uuid -> writeUuid(uuid) }
    }

    private fun DataInputStream.readOptionalUuid(): UUID? = if (readBoolean()) readUuid() else null

    private enum class PrivateEncryptedMutationKind(
        val wireCode: Int,
    ) {
        SEND_MESSAGE(1),
        EDIT_MESSAGE(2),
        ADD_REACTION(3),
        CREATE_ROOM(4),
        ;

        companion object {
            fun fromWire(wireCode: Int): PrivateEncryptedMutationKind =
                entries.firstOrNull { kind -> kind.wireCode == wireCode } ?: malformed()
        }
    }
}

internal class PrivateEncryptedMutationCodecException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun malformed(): Nothing = throw PrivateEncryptedMutationCodecException("Pending encrypted mutation is malformed")

private const val MAGIC = 0x5350454d
private const val VERSION = 2
private const val MAXIMUM_INTENT_PLAINTEXT_BYTES = 64 * 1_024
private const val MAXIMUM_ENVELOPES = 129
private const val MAXIMUM_ENVELOPE_BYTES = 256 * 1_024
private const val MAXIMUM_ENCODED_REQUEST_BYTES = 12 * 1_024 * 1_024
private val NIL_UUID = UUID(0L, 0L)
