package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.crypto.SignalPendingOutboundMutationKey
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.crypto.StoredSignalPendingOutboundMutation
import app.synapse.privatechat.crypto.local.DeviceLocalContentEnvelopeCipher
import app.synapse.privatechat.crypto.local.DeviceLocalEncryptedPayloadCacheStorage
import app.synapse.privatechat.data.account.PrivateAccountLocalStatePurgeReceipt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateChatLocalStateInvalidatorTest {
    @Test
    fun purgeClearsCacheThenPendingSignalStateThenLocalEnvelopeKeys() =
        runTest {
            val events = mutableListOf<String>()
            val invalidator = createInvalidator(events)

            val receipt = invalidator.purgeForSessionInvalidation()

            assertEquals(PrivateAccountLocalStatePurgeReceipt.PURGED, receipt)
            assertEquals(listOf("payload-cache", "signal-outbox", "local-envelope-keys"), events)
        }

    @Test
    fun pendingSignalFailurePreservesLocalEnvelopeKeys() =
        runTest {
            val events = mutableListOf<String>()
            val invalidator = createInvalidator(events, failPendingSignalClear = true)

            val failure =
                runCatching {
                    invalidator.purgeForSessionInvalidation()
                }.exceptionOrNull()

            check(failure is IllegalStateException)
            assertEquals(listOf("payload-cache", "signal-outbox"), events)
        }

    private fun createInvalidator(
        events: MutableList<String>,
        failPendingSignalClear: Boolean = false,
    ): PrivateChatLocalStateInvalidator {
        val payloadCache =
            PrivateDecryptedPayloadCacheRepository(
                RecordingPayloadCacheStorage(events),
            )
        val envelopeCipher =
            PrivateChatEnvelopeCipher(
                signalCipher = RecordingSignalCipher(events, failPendingSignalClear),
                localCipher = RecordingLocalEnvelopeCipher(events),
            )
        val pollingRepository =
            PrivateChatPollingRepository(
                backend = UnusedPollingBackend,
                envelopeCipher = envelopeCipher,
                payloadCache = payloadCache,
            )
        return PrivateChatLocalStateInvalidator(pollingRepository, envelopeCipher)
    }
}

private object UnusedPollingBackend : PrivateChatPollingBackend {
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
}

private class RecordingPayloadCacheStorage(
    private val events: MutableList<String>,
) : DeviceLocalEncryptedPayloadCacheStorage {
    override fun readDecryptedState(): ByteArray? = null

    override fun replaceEncryptedState(plaintext: ByteArray) = error("Not used")

    override fun replaceAfterPurge(retainedPlaintext: ByteArray?) {
        events += "payload-cache"
    }

    override fun deletePhysically() = error("Not used")
}

private class RecordingLocalEnvelopeCipher(
    private val events: MutableList<String>,
) : DeviceLocalContentEnvelopeCipher {
    override fun encryptLocalEnvelope(plaintext: ByteArray): ByteArray = error("Not used")

    override fun decryptLocalEnvelope(ciphertext: ByteArray): ByteArray = error("Not used")

    override fun markEnvelopeDurablyReferenced(ciphertext: ByteArray) = error("Not used")

    override fun reconcileRetainedEnvelopeKeys(
        authoritativeCiphertexts: Collection<ByteArray>,
        pendingCiphertexts: Collection<ByteArray>,
        observedAt: Instant,
    ) = error("Not used")

    override fun clearForSessionInvalidation() {
        events += "local-envelope-keys"
    }
}

private class RecordingSignalCipher(
    private val events: MutableList<String>,
    private val failPendingSignalClear: Boolean,
) : PrivateChatSignalCipher {
    override fun localAddress(): SignalDeviceAddress = error("Not used")

    override fun establishPairwiseSession(remoteBundle: SignalPublicPreKeyBundle) = error("Not used")

    override fun hasPairwiseSession(recipient: SignalDeviceAddress): Boolean = error("Not used")

    override fun encryptForRecipientDevicesWithPendingOutboundCommit(
        recipients: List<SignalDeviceAddress>,
        plaintext: ByteArray,
        createPendingMutation: (List<SignalEnvelope>) -> StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation = error("Not used")

    override fun loadPendingOutboundMutation(key: SignalPendingOutboundMutationKey): StoredSignalPendingOutboundMutation? =
        error("Not used")

    override fun listPendingOutboundMutations(): List<StoredSignalPendingOutboundMutation> = error("Not used")

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

    override fun clearPendingOutboundMutationsForSessionInvalidation(): Int {
        events += "signal-outbox"
        check(!failPendingSignalClear) { "Simulated pending Signal clear failure" }
        return 1
    }

    override fun decryptFromDevice(envelope: SignalEnvelope): ByteArray = error("Not used")

    override fun <Receipt> decryptFromDeviceWithDurableCommit(
        envelope: SignalEnvelope,
        commitDecryptedPayload: (ByteArray) -> Receipt,
    ): Receipt = error("Not used")
}
