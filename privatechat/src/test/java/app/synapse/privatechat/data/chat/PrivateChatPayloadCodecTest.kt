package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateMessageTextValidation
import app.synapse.privatechat.domain.chat.PrivateReactionValidation
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.validatePrivateMessageText
import app.synapse.privatechat.domain.chat.validatePrivateReaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateChatPayloadCodecTest {
    @Test
    fun messageRoundTripBindsSenderRoomMutationAndReplyContext() {
        val payload =
            PrivateChatPlaintextPayload.Message(
                accountId = PrivateAccountId(ACCOUNT_ID),
                roomId = PrivateRoomId(ROOM_ID),
                mutationId = PrivateClientMutationId(MUTATION_ID),
                body = acceptedMessage("Encrypted hello"),
                replyToMessageId = PrivateMessageId(MESSAGE_ID),
            )

        val decoded = PrivateChatPayloadCodec.decode(PrivateChatPayloadCodec.encodeMessage(payload))

        assertEquals(payload, decoded)
    }

    @Test
    fun reactionRoundTripBindsTheEncryptedAdditionToItsMessage() {
        val payload =
            PrivateChatPlaintextPayload.Reaction(
                accountId = PrivateAccountId(ACCOUNT_ID),
                roomId = PrivateRoomId(ROOM_ID),
                mutationId = PrivateClientMutationId(MUTATION_ID),
                messageId = PrivateMessageId(MESSAGE_ID),
                reaction = acceptedReaction("👍"),
            )

        val decoded = PrivateChatPayloadCodec.decode(PrivateChatPayloadCodec.encodeReaction(payload))

        assertEquals(payload, decoded)
    }

    @Test
    fun createdRoomMetadataRoundTripBindsTheAuthoritativeRoomContext() {
        val payload =
            PrivateChatPlaintextPayload.CreatedRoomMetadata(
                accountId = PrivateAccountId(ACCOUNT_ID),
                roomId = PrivateRoomId(ROOM_ID),
                mutationId = PrivateClientMutationId(MUTATION_ID),
                roomKind = PrivateRoomKind.GROUP,
                retention = PrivateMessageRetention.FIVE_MINUTES,
                title = "Trusted room",
            )

        val decoded = PrivateChatPayloadCodec.decode(PrivateChatPayloadCodec.encodeCreatedRoomMetadata(payload))

        assertEquals(payload, decoded)
    }

    @Test
    fun rejectsUnknownFieldsAndMalformedUtf8WithoutNarrowingThem() {
        val unknownFieldPayload =
            """{"schema_version":1,"payload_kind":"MESSAGE","sender_account_id":"$ACCOUNT_ID","room_id":"$ROOM_ID","client_mutation_id":"$MUTATION_ID","body":"hello","reply_to_message_id":null,"plaintext_fallback":"no"}"""

        assertThrows(PrivateChatPayloadException::class.java) {
            PrivateChatPayloadCodec.decode(unknownFieldPayload.encodeToByteArray())
        }
        assertThrows(PrivateChatPayloadException::class.java) {
            PrivateChatPayloadCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
    }

    @Test
    fun rejectsNonCanonicalMessageContentAndIdentifiers() {
        val paddedBodyPayload =
            """{"schema_version":1,"payload_kind":"MESSAGE","sender_account_id":"$ACCOUNT_ID","room_id":"$ROOM_ID","client_mutation_id":"$MUTATION_ID","body":" padded ","reply_to_message_id":null}"""
        val uppercaseAccountPayload =
            """{"schema_version":1,"payload_kind":"MESSAGE","sender_account_id":"${ALPHABETIC_ACCOUNT_ID.uppercase()}","room_id":"$ROOM_ID","client_mutation_id":"$MUTATION_ID","body":"hello","reply_to_message_id":null}"""

        assertThrows(PrivateChatPayloadException::class.java) {
            PrivateChatPayloadCodec.decode(paddedBodyPayload.encodeToByteArray())
        }
        assertThrows(PrivateChatPayloadException::class.java) {
            PrivateChatPayloadCodec.decode(uppercaseAccountPayload.encodeToByteArray())
        }
    }

    private fun acceptedMessage(input: String) = (validatePrivateMessageText(input) as PrivateMessageTextValidation.Accepted).message

    private fun acceptedReaction(input: String) = (validatePrivateReaction(input) as PrivateReactionValidation.Accepted).reaction

    private companion object {
        const val ACCOUNT_ID = "10000000-0000-4000-8000-000000000001"
        const val ALPHABETIC_ACCOUNT_ID = "a0000000-0000-4000-8000-000000000001"
        const val ROOM_ID = "20000000-0000-4000-8000-000000000002"
        const val MUTATION_ID = "30000000-0000-4000-8000-000000000003"
        const val MESSAGE_ID = "40000000-0000-4000-8000-000000000004"
    }
}
