package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.crypto.SignalPendingOutboundMutationKey
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.crypto.StoredSignalPendingOutboundMutation
import app.synapse.privatechat.crypto.local.DeviceLocalContentEnvelopeCipher
import app.synapse.privatechat.crypto.local.DeviceLocalContentEnvelopeUnavailableException
import app.synapse.privatechat.crypto.local.DeviceLocalEncryptedPayloadCacheStorage
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMetadataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateChatPollingDecoderTest {
    @Test
    fun missingDeviceLocalRoomTitleKeyKeepsTheRoomVisible() {
        val localAddress = SignalDeviceAddress(OWNER_ID, DEVICE_ID, SignalDeviceId.fromWire(7))
        val session =
            PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
                accountId = OWNER_ID,
                transportDeviceId = DEVICE_ID,
                signalDeviceId = localAddress.protocolDeviceId,
                authenticationUsername = "peter_01",
                accessToken = "header.payload.signature-material",
                expiresAt = NOW.plusSeconds(3_600),
            )
        val decoder =
            PrivateChatPollingDecoder(
                envelopeCipher =
                    PrivateChatEnvelopeCipher(
                        signalCipher = LocalAddressOnlySignalCipher(localAddress),
                        localCipher = MissingLocalEnvelopeKeyCipher,
                    ),
                payloadCache = PrivateDecryptedPayloadCacheRepository(EmptyPayloadCacheStorage),
            )

        val resolved = decoder.decode(session, pollingState(localAddress), NOW)

        val room = resolved.rooms.getValue(ROOM_ID)
        assertEquals("Encrypted conversation", room.title)
        assertEquals(PrivateRoomMetadataState.UNAVAILABLE_ON_DEVICE, room.metadataState)
    }

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

    private fun pollingState(localAddress: SignalDeviceAddress): PrivateBackendPollingState =
        PrivateBackendPollingState(
            profiles =
                listOf(
                    PrivateBackendProfileRecord(
                        accountId = OWNER_ID,
                        displayName = "Peter",
                        presenceSharing = PrivatePresenceSharingState.DISABLED,
                        activitySharing = PrivateActivitySharingPreferences(),
                    ),
                ),
            rooms = listOf(roomRecord()),
            roomMembers =
                listOf(
                    PrivateBackendRoomMemberRecord(
                        roomId = ROOM_ID,
                        accountId = OWNER_ID,
                        role = PrivateRoomMemberRole.OWNER,
                        joinedAt = NOW,
                    ),
                ),
            roomPreferences = emptyList(),
            devices = listOf(PrivateBackendDeviceRecord(localAddress, SignalEnvelope.CURRENT_PROTOCOL_VERSION)),
            messages = emptyList(),
            messageEnvelopes = emptyList(),
            messageRevisions = emptyList(),
            messageRevisionEnvelopes = emptyList(),
            replies = emptyList(),
            reactions = emptyList(),
            reactionEnvelopes = emptyList(),
            roomMetadataEnvelopes = listOf(envelopeRecord(OWNER_ID)),
            messageReceipts = emptyList(),
            typing = PrivateBackendActivityFeed.Available(emptyList()),
            presence = PrivateBackendActivityFeed.Available(emptyList()),
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

private class LocalAddressOnlySignalCipher(
    private val address: SignalDeviceAddress,
) : PrivateChatSignalCipher {
    override fun localAddress(): SignalDeviceAddress = address

    override fun establishPairwiseSession(remoteBundle: SignalPublicPreKeyBundle) = error("Not used")

    override fun hasPairwiseSession(recipient: SignalDeviceAddress): Boolean = error("Not used")

    override fun encryptForRecipientDevicesWithPendingOutboundCommit(
        recipients: List<SignalDeviceAddress>,
        plaintext: ByteArray,
        createPendingMutation: (List<SignalEnvelope>) -> StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation = error("Not used")

    override fun loadPendingOutboundMutation(key: SignalPendingOutboundMutationKey): StoredSignalPendingOutboundMutation? =
        error("Not used")

    override fun listPendingOutboundMutations(): List<StoredSignalPendingOutboundMutation> = emptyList()

    override fun commitPendingOutboundWithoutPeerRatchet(
        mutation: StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation = error("Not used")

    override fun confirmPendingOutboundMutation(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    ) = error("Not used")

    override fun discardPendingOutboundMutationAndResetPeerSessions(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    ) = error("Not used")

    override fun clearPendingOutboundMutationsForSessionInvalidation(): Int = error("Not used")

    override fun decryptFromDevice(envelope: SignalEnvelope): ByteArray = error("Not used")

    override fun <Receipt> decryptFromDeviceWithDurableCommit(
        envelope: SignalEnvelope,
        commitDecryptedPayload: (ByteArray) -> Receipt,
    ): Receipt = error("Not used")
}

private object MissingLocalEnvelopeKeyCipher : DeviceLocalContentEnvelopeCipher {
    override fun encryptLocalEnvelope(plaintext: ByteArray): ByteArray = error("Not used")

    override fun decryptLocalEnvelope(ciphertext: ByteArray): ByteArray =
        throw DeviceLocalContentEnvelopeUnavailableException("Device-local content envelope key was erased")

    override fun markEnvelopeDurablyReferenced(ciphertext: ByteArray) = error("Not used")

    override fun reconcileRetainedEnvelopeKeys(
        authoritativeCiphertexts: Collection<ByteArray>,
        pendingCiphertexts: Collection<ByteArray>,
        observedAt: Instant,
    ) = Unit

    override fun clearForSessionInvalidation() = error("Not used")
}

private object EmptyPayloadCacheStorage : DeviceLocalEncryptedPayloadCacheStorage {
    override fun readDecryptedState(): ByteArray? = null

    override fun replaceEncryptedState(plaintext: ByteArray) = error("Not used")

    override fun replaceAfterPurge(retainedPlaintext: ByteArray?) = Unit

    override fun deletePhysically() = error("Not used")
}
