package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.InMemorySignalProtocolStateRepository
import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.crypto.SignalPendingOutboundMutationKey
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.crypto.StoredSignalPendingOutboundMutation
import app.synapse.privatechat.crypto.local.DeviceLocalContentEnvelopeCipher
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PrivateEncryptedMutationOutboxTest {
    @Test
    fun createdRoomPendingRequestRetainsItsChosenRoomIdentity() {
        val roomId = UUID.fromString("31000000-0000-4000-8000-000000000003")
        val mutationId = UUID.fromString("41000000-0000-4000-8000-000000000004")
        val request =
            PrivatePendingEncryptedMutation.CreateRoom(
                roomId = roomId,
                clientMutationId = mutationId,
                kind = PrivateRoomKind.GROUP,
                retention = PrivateMessageRetention.FIVE_MINUTES,
                envelopes =
                    listOf(
                        PrivateChatEncryptedEnvelope(
                            recipientDeviceId = DEVICE_ID,
                            protocolAdapterVersion = SignalEnvelope.CURRENT_PROTOCOL_VERSION,
                            kind = PrivateChatEnvelopeKind.LOCAL_AEAD,
                            ciphertext = ByteArray(29) { 1 },
                        ),
                    ),
            )

        val decoded = PrivateEncryptedMutationCodec.decode(PrivateEncryptedMutationCodec.encode(request))

        check(decoded is PrivatePendingEncryptedMutation.CreateRoom)
        assertEquals(roomId, decoded.roomId)
        assertEquals(mutationId, decoded.clientMutationId)
        assertEquals(PrivateRoomKind.GROUP, decoded.kind)
        assertEquals(PrivateMessageRetention.FIVE_MINUTES, decoded.retention)
    }

    @Test
    fun pristineInstallPendingClearIsAlreadyEmptyWithoutCreatingAnIdentity() {
        val signalCipher =
            LibSignalPrivateChatCipher(
                SignalProtocolAdapterOwner(InMemorySignalProtocolStateRepository()),
            )

        assertEquals(0, signalCipher.clearPendingOutboundMutationsForSessionInvalidation())
    }

    @Test
    fun newMutationStopsAfterRecoveringExactAmbiguousRequest() =
        runTest {
            val firstDispatchEntered = CompletableDeferred<Unit>()
            val releaseFirstDispatch = CompletableDeferred<Unit>()
            val backend =
                RecordingSendBackend { dispatchIndex ->
                    if (dispatchIndex == 1) {
                        firstDispatchEntered.complete(Unit)
                        releaseFirstDispatch.await()
                        throw IOException("ambiguous transport failure")
                    }
                }
            val signalCipher = RecordingPendingSignalCipher()
            val outbox = createOutbox(signalCipher, backend)

            supervisorScope {
                val first = async { outbox.execute(SESSION, FIRST_INTENT, FIRST_PLAINTEXT, LOCAL_RECIPIENTS) }
                firstDispatchEntered.await()
                val second = async { outbox.execute(SESSION, SECOND_INTENT, SECOND_PLAINTEXT, LOCAL_RECIPIENTS) }
                yield()

                assertEquals(1, signalCipher.preparationCount)
                assertEquals(listOf(FIRST_INTENT.clientMutationId), backend.dispatchedMutationIds)

                releaseFirstDispatch.complete(Unit)
                val firstFailure = runCatching { first.await() }.exceptionOrNull()
                check(firstFailure is IOException)
                val secondFailure = runCatching { second.await() }.exceptionOrNull()
                check(secondFailure is PrivateChatCommandRejectedException)
            }

            assertEquals(1, signalCipher.preparationCount)
            assertEquals(
                listOf(
                    FIRST_INTENT.clientMutationId,
                    FIRST_INTENT.clientMutationId,
                ),
                backend.dispatchedMutationIds,
            )
            assertArrayEquals(backend.dispatchedCiphertexts[0], backend.dispatchedCiphertexts[1])
            assertEquals(0, signalCipher.listPendingOutboundMutations().size)
            assertEquals(
                setOf(FIRST_INTENT.clientMutationId),
                outbox.retainedRecoveredMutationIds(SESSION),
            )
            backend.destroyRecordedCiphertexts()
        }

    @Test
    fun restartRetryReusesExactCiphertextWithoutAdvancingPreparationAgain() =
        runTest {
            var failNextDispatch = true
            val backend =
                RecordingSendBackend {
                    if (failNextDispatch) {
                        failNextDispatch = false
                        throw IOException("ambiguous transport failure")
                    }
                }
            val signalCipher = RecordingPendingSignalCipher()
            val firstProcess = createOutbox(signalCipher, backend)

            val firstFailure =
                runCatching {
                    firstProcess.execute(SESSION, FIRST_INTENT, FIRST_PLAINTEXT, LOCAL_RECIPIENTS)
                }.exceptionOrNull()
            check(firstFailure is IOException)
            val restarted = createOutbox(signalCipher, backend)
            restarted.execute(SESSION, FIRST_INTENT, FIRST_PLAINTEXT, LOCAL_RECIPIENTS)

            assertEquals(1, signalCipher.preparationCount)
            assertEquals(2, backend.dispatchedCiphertexts.size)
            assertArrayEquals(backend.dispatchedCiphertexts[0], backend.dispatchedCiphertexts[1])
            assertEquals(0, signalCipher.listPendingOutboundMutations().size)
            backend.destroyRecordedCiphertexts()
        }

    @Test
    fun pollingRecoveryResumesExactPendingRequestBeforePublishingFreshState() =
        runTest {
            var failNextDispatch = true
            val backend =
                RecordingSendBackend {
                    if (failNextDispatch) {
                        failNextDispatch = false
                        throw IOException("ambiguous transport failure")
                    }
                }
            val signalCipher = RecordingPendingSignalCipher()
            val outbox = createOutbox(signalCipher, backend)
            runCatching { outbox.execute(SESSION, FIRST_INTENT, FIRST_PLAINTEXT, LOCAL_RECIPIENTS) }

            val recoveredMutationIds = outbox.recoverPendingMutations(SESSION)

            assertEquals(setOf(FIRST_INTENT.clientMutationId), recoveredMutationIds)
            assertEquals(
                setOf(FIRST_INTENT.clientMutationId),
                outbox.retainedRecoveredMutationIds(SESSION),
            )
            outbox.clearRecoveredMutationIds()
            assertEquals(emptySet<UUID>(), outbox.retainedRecoveredMutationIds(SESSION))
            assertEquals(1, signalCipher.preparationCount)
            assertArrayEquals(backend.dispatchedCiphertexts[0], backend.dispatchedCiphertexts[1])
            assertEquals(0, signalCipher.listPendingOutboundMutations().size)
            backend.destroyRecordedCiphertexts()
        }

    @Test
    fun retryWithSameMutationIdAndDifferentPlaintextFailsClosed() =
        runTest {
            val backend = RecordingSendBackend { throw IOException("ambiguous transport failure") }
            val signalCipher = RecordingPendingSignalCipher()
            val outbox = createOutbox(signalCipher, backend)
            runCatching { outbox.execute(SESSION, FIRST_INTENT, FIRST_PLAINTEXT, LOCAL_RECIPIENTS) }

            val mismatchFailure =
                runCatching {
                    outbox.execute(
                        SESSION,
                        FIRST_INTENT,
                        "changed plaintext".encodeToByteArray(),
                        LOCAL_RECIPIENTS,
                    )
                }.exceptionOrNull()
            check(mismatchFailure is PrivateChatCommandRejectedException)

            assertEquals(1, signalCipher.preparationCount)
            assertEquals(1, backend.dispatchedMutationIds.size)
            assertEquals(1, signalCipher.listPendingOutboundMutations().size)
            backend.destroyRecordedCiphertexts()
        }

    private fun createOutbox(
        signalCipher: RecordingPendingSignalCipher,
        backend: PrivateChatBackend,
    ): PrivateEncryptedMutationOutbox =
        PrivateEncryptedMutationOutbox(
            envelopeCipher =
                PrivateChatEnvelopeCipher(
                    signalCipher = signalCipher,
                    localCipher = OutboxRecordingLocalEnvelopeCipher(),
                ),
            backend = backend,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )
}

private class RecordingPendingSignalCipher : PrivateChatSignalCipher {
    private var pending: StoredSignalPendingOutboundMutation? = null
    var preparationCount: Int = 0
        private set

    override fun localAddress(): SignalDeviceAddress = SESSION.localSignalAddress

    override fun establishPairwiseSession(remoteBundle: SignalPublicPreKeyBundle) = error("Not used")

    override fun hasPairwiseSession(recipient: SignalDeviceAddress): Boolean = error("Not used")

    override fun encryptForRecipientDevicesWithPendingOutboundCommit(
        recipients: List<SignalDeviceAddress>,
        plaintext: ByteArray,
        createPendingMutation: (List<SignalEnvelope>) -> StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation = error("Not used")

    override fun loadPendingOutboundMutation(key: SignalPendingOutboundMutationKey): StoredSignalPendingOutboundMutation? =
        pending?.takeIf { mutation -> mutation.key == key }

    override fun listPendingOutboundMutations(): List<StoredSignalPendingOutboundMutation> = listOfNotNull(pending)

    override fun commitPendingOutboundWithoutPeerRatchet(
        mutation: StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation {
        check(pending == null)
        preparationCount += 1
        pending = mutation
        return mutation
    }

    override fun confirmPendingOutboundMutation(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    ) {
        val current = checkNotNull(pending)
        check(current.key == key && current.operationDigest.contentEquals(expectedOperationDigest))
        pending = null
    }

    override fun discardPendingOutboundMutationAndResetPeerSessions(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    ) {
        confirmPendingOutboundMutation(key, expectedOperationDigest)
    }

    override fun clearPendingOutboundMutationsForSessionInvalidation(): Int {
        val count = if (pending == null) 0 else 1
        pending = null
        return count
    }

    override fun decryptFromDevice(envelope: SignalEnvelope): ByteArray = error("Not used")

    override fun <Receipt> decryptFromDeviceWithDurableCommit(
        envelope: SignalEnvelope,
        commitDecryptedPayload: (ByteArray) -> Receipt,
    ): Receipt = error("Not used")
}

private class OutboxRecordingLocalEnvelopeCipher : DeviceLocalContentEnvelopeCipher {
    private var encryptionCount = 0

    override fun encryptLocalEnvelope(plaintext: ByteArray): ByteArray {
        encryptionCount += 1
        return ByteArray(64) { encryptionCount.toByte() }
    }

    override fun decryptLocalEnvelope(ciphertext: ByteArray): ByteArray = error("Not used")

    override fun markEnvelopeDurablyReferenced(ciphertext: ByteArray) = Unit

    override fun reconcileRetainedEnvelopeKeys(
        authoritativeCiphertexts: Collection<ByteArray>,
        pendingCiphertexts: Collection<ByteArray>,
        observedAt: Instant,
    ) = error("Not used")

    override fun clearForSessionInvalidation() = Unit
}

private class RecordingSendBackend(
    private val beforeReceipt: suspend (dispatchIndex: Int) -> Unit,
) : PrivateChatBackend {
    val dispatchedMutationIds = mutableListOf<UUID>()
    val dispatchedCiphertexts = mutableListOf<ByteArray>()

    override suspend fun sendMessage(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        replyToMessageId: UUID?,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendMessageSendReceipt {
        dispatchedMutationIds += clientMutationId
        dispatchedCiphertexts += envelopes.single().ciphertextCopy()
        beforeReceipt(dispatchedMutationIds.size)
        return PrivateBackendMessageSendReceipt(
            messageId = UUID(0x7000L, dispatchedMutationIds.size.toLong()),
            roomId = roomId,
            clientMutationId = clientMutationId,
            expiresAt = NOW.plusSeconds(PrivateMessageRetention.ONE_DAY.durationSeconds.toLong()),
        )
    }

    fun destroyRecordedCiphertexts() = dispatchedCiphertexts.forEach { ciphertext -> ciphertext.fill(0) }

    override suspend fun loadPollingState(
        session: PrivateChatAuthenticatedSession,
        now: Instant,
    ): PrivateBackendPollingState = error("Not used")

    override suspend fun listRoomRecipientDevices(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ): List<PrivateChatRecipientDevice> = error("Not used")

    override suspend fun listCurrentAccountRecipientDevices(session: PrivateChatAuthenticatedSession): List<PrivateChatRecipientDevice> =
        error("Not used")

    override suspend fun claimDevicePreKey(
        session: PrivateChatAuthenticatedSession,
        recipient: PrivateChatRecipientDevice,
    ): SignalPublicPreKeyBundle = error("Not used")

    override suspend fun editMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        expectedServerRevision: Int,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendMessageEditReceipt = error("Not used")

    override suspend fun deleteMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        expectedServerRevision: Int,
    ): PrivateBackendMessageDeleteReceipt = error("Not used")

    override suspend fun addReaction(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        clientMutationId: UUID,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendReactionSendReceipt = error("Not used")

    override suspend fun removeReaction(
        session: PrivateChatAuthenticatedSession,
        reactionId: UUID,
        clientMutationId: UUID,
    ): PrivateBackendReactionRemoveReceipt = error("Not used")

    override suspend fun updateRoomRetention(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        retention: PrivateMessageRetention,
    ): PrivateBackendRoomRetentionReceipt = error("Not used")

    override suspend fun updateRoomPreferences(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        clientMutationId: UUID,
        archiveState: PrivateRoomArchiveState,
        pinState: PrivateRoomPinState,
        muteState: PrivateRoomMuteState,
    ): PrivateBackendRoomPreferenceReceipt = error("Not used")

    override suspend fun updateActivitySharing(
        session: PrivateChatAuthenticatedSession,
        preferences: PrivateActivitySharingPreferences,
    ): PrivateBackendProfileRecord = error("Not used")

    override suspend fun acknowledgeRoomRead(
        session: PrivateChatAuthenticatedSession,
        messageIds: List<UUID>,
    ) = error("Not used")

    override suspend fun publishTyping(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        active: Boolean,
    ): PrivateBackendTypingRecord? = error("Not used")

    override suspend fun issueInvite(
        session: PrivateChatAuthenticatedSession,
        clientMutationId: UUID,
        kind: PrivateBackendInviteKind,
        roomId: UUID?,
    ): PrivateBackendInviteReceipt = error("Not used")

    override suspend fun redeemRoomInvite(
        session: PrivateChatAuthenticatedSession,
        inviteCode: String,
        redemptionId: UUID,
    ): PrivateBackendRoomInvitationRedemptionReceipt = error("Not used")

    override suspend fun updateProfile(
        session: PrivateChatAuthenticatedSession,
        displayName: String,
    ): PrivateBackendProfileRecord = error("Not used")

    override suspend fun createRoom(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        kind: PrivateRoomKind,
        retention: PrivateMessageRetention,
        clientMutationId: UUID,
        envelopes: List<PrivateChatEncryptedEnvelope>,
    ): PrivateBackendRoomCreationReceipt = error("Not used")

    override suspend fun updateGroupMemberRole(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        memberAccountId: UUID,
        clientMutationId: UUID,
        role: PrivateRoomMemberRole,
    ): PrivateBackendMemberRoleReceipt = error("Not used")

    override suspend fun removeGroupMember(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        memberAccountId: UUID,
        clientMutationId: UUID,
    ): PrivateBackendMemberRemovalReceipt = error("Not used")

    override suspend fun updatePresenceSharing(
        session: PrivateChatAuthenticatedSession,
        sharingState: PrivatePresenceSharingState,
    ): PrivateBackendProfileRecord = error("Not used")

    override suspend fun publishPresence(session: PrivateChatAuthenticatedSession): PrivateBackendPresenceRecord = error("Not used")
}

private val NOW = Instant.parse("2026-08-25T12:00:00Z")
private val ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001")
private val DEVICE_ID = UUID.fromString("20000000-0000-4000-8000-000000000002")
private val SESSION =
    PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
        accountId = ACCOUNT_ID,
        transportDeviceId = DEVICE_ID,
        signalDeviceId = SignalDeviceId.fromWire(7),
        authenticationUsername = "peter_01",
        accessToken = "header.payload.signature-material",
        expiresAt = NOW.plusSeconds(3_600),
    )
private val LOCAL_RECIPIENTS =
    listOf(
        PrivateChatRecipientDevice(
            address = SESSION.localSignalAddress,
            protocolAdapterVersion = SignalEnvelope.CURRENT_PROTOCOL_VERSION,
        ),
    )
private val FIRST_INTENT =
    PrivateEncryptedMutationIntent.SendMessage(
        roomId = UUID.fromString("30000000-0000-4000-8000-000000000003"),
        clientMutationId = UUID.fromString("40000000-0000-4000-8000-000000000004"),
        replyToMessageId = null,
    )
private val SECOND_INTENT =
    PrivateEncryptedMutationIntent.SendMessage(
        roomId = FIRST_INTENT.roomId,
        clientMutationId = UUID.fromString("50000000-0000-4000-8000-000000000005"),
        replyToMessageId = null,
    )
private val FIRST_PLAINTEXT = "first encrypted message".encodeToByteArray()
private val SECOND_PLAINTEXT = "second encrypted message".encodeToByteArray()
