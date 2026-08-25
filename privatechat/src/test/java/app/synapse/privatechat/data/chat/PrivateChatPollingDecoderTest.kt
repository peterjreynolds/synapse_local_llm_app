package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateChatPollingDecoderTest {
    @Test
    fun createdRoomMetadataAcceptsItsAuthoritativeCreationContext() {
        validateRoomMetadata(createdMetadata(CREATION_MUTATION_ID, OWNER_ID), roomRecord(), envelopeRecord(OWNER_ID))
    }

    @Test
    fun createdRoomMetadataRejectsAnotherCreationMutation() {
        assertThrows(SupabasePrivateChatResponseException::class.java) {
            validateRoomMetadata(createdMetadata(OTHER_MUTATION_ID, OWNER_ID), roomRecord(), envelopeRecord(OWNER_ID))
        }
    }

    @Test
    fun createdRoomMetadataRejectsAnotherOwner() {
        assertThrows(SupabasePrivateChatResponseException::class.java) {
            validateRoomMetadata(createdMetadata(CREATION_MUTATION_ID, OTHER_OWNER_ID), roomRecord(), envelopeRecord(OTHER_OWNER_ID))
        }
    }

    @Test
    fun createdRoomMetadataRejectsAnotherRoom() {
        assertThrows(SupabasePrivateChatResponseException::class.java) {
            validateRoomMetadata(
                createdMetadata(CREATION_MUTATION_ID, OWNER_ID, OTHER_ROOM_ID),
                roomRecord(),
                envelopeRecord(OWNER_ID),
            )
        }
    }

    private fun createdMetadata(
        mutationId: UUID,
        ownerId: UUID,
        roomId: UUID = ROOM_ID,
    ): PrivateChatPlaintextPayload.CreatedRoomMetadata =
        PrivateChatPlaintextPayload.CreatedRoomMetadata(
            accountId = PrivateAccountId(ownerId.toString()),
            roomId = PrivateRoomId(roomId.toString()),
            mutationId = PrivateClientMutationId(mutationId.toString()),
            roomKind = PrivateRoomKind.GROUP,
            retention = PrivateMessageRetention.FIVE_MINUTES,
            title = "Trusted room",
        )

    private fun roomRecord(): PrivateBackendRoomRecord =
        PrivateBackendRoomRecord(
            roomId = ROOM_ID,
            ownerAccountId = OWNER_ID,
            creationClientMutationId = CREATION_MUTATION_ID,
            kind = PrivateRoomKind.GROUP,
            retention = PrivateMessageRetention.FIVE_MINUTES,
            membershipEpoch = 1,
            metadataRevision = 1,
            metadataUpdatedAt = NOW,
            createdAt = NOW,
        )

    private fun envelopeRecord(senderId: UUID): PrivateBackendEnvelopeRecord =
        PrivateBackendEnvelopeRecord(
            parentRecordId = ROOM_ID,
            serverRevision = 1,
            senderAccountId = senderId,
            senderDeviceId = DEVICE_ID,
            envelope =
                PrivateChatEncryptedEnvelope(
                    recipientDeviceId = DEVICE_ID,
                    protocolAdapterVersion = SignalEnvelope.CURRENT_PROTOCOL_VERSION,
                    kind = PrivateChatEnvelopeKind.LOCAL_AEAD,
                    ciphertext = ByteArray(29) { 1 },
                ),
            createdAt = NOW,
        )

    private companion object {
        val ROOM_ID: UUID = UUID.fromString("30000000-0000-4000-8000-000000000003")
        val OTHER_ROOM_ID: UUID = UUID.fromString("31000000-0000-4000-8000-000000000003")
        val OWNER_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val OTHER_OWNER_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000002")
        val DEVICE_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val CREATION_MUTATION_ID: UUID = UUID.fromString("40000000-0000-4000-8000-000000000004")
        val OTHER_MUTATION_ID: UUID = UUID.fromString("50000000-0000-4000-8000-000000000005")
        val NOW: Instant = Instant.parse("2026-08-25T13:00:00Z")
    }
}
