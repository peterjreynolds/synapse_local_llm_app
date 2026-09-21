package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.crypto.local.DeviceLocalEncryptedPayloadCacheStorage
import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSession
import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSessionProvider
import app.synapse.privatechat.data.chat.PrivateChatSessionResolver
import app.synapse.privatechat.data.chat.PrivateDecryptedPayloadCacheRepository
import app.synapse.privatechat.domain.call.PrivateCallEndReason
import app.synapse.privatechat.domain.call.PrivateCallException
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignalReceipt
import app.synapse.privatechat.domain.call.PrivateCallState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SupabasePrivateCallSignalingGatewayTest {
    @Test
    fun retriesTheExactCiphertextAfterAmbiguousCreateWithoutTouchingChatOutbox() =
        runBlocking {
            val peers = CallCryptoPeers()
            val backend = CallMutationBackend(peers.call)
            val gateway = gateway(peers, backend)
            val preparation = gateway.prepareCall(peers.callerSession.accountId, peers.call.roomId)
            assertEquals(1, preparation.peerSafetyNumbers.size)
            val start =
                suspend {
                    gateway.createCall(
                        peers.callerSession.accountId,
                        peers.call.callId,
                        peers.call.roomId,
                        preparation.membershipEpoch,
                        PrivateCallMediaKind.VOICE,
                        PrivateCallMediaSignal.Offer(CALL_TEST_SDP),
                    )
                }
            backend.failFirstCreate = true
            assertThrows(PrivateCallException::class.java) { runBlocking { start() } }
            assertEquals(peers.call.callId, start().callId)
            assertEquals(backend.sentMutations[0], backend.sentMutations[1])
            assertArrayEquals(backend.sentCiphertexts[0], backend.sentCiphertexts[1])
            assertTrue(
                peers.callerOwner
                    .requireAdapterForStoredIdentity()
                    .listPendingOutboundMutations()
                    .isEmpty(),
            )
        }

    @Test
    fun cannotStartBeforeIdentityPreparationOrAfterEphemeralStateIsCleared(): Unit =
        runBlocking {
            val peers = CallCryptoPeers()
            val gateway = gateway(peers, CallMutationBackend(peers.call))
            val start =
                suspend {
                    gateway.createCall(
                        peers.callerSession.accountId,
                        peers.call.callId,
                        peers.call.roomId,
                        1,
                        PrivateCallMediaKind.VOICE,
                        PrivateCallMediaSignal.Offer(CALL_TEST_SDP),
                    )
                }
            assertThrows(PrivateCallException::class.java) { runBlocking { start() } }
            gateway.prepareCall(peers.callerSession.accountId, peers.call.roomId)
            gateway.clearEphemeralState()
            assertThrows(PrivateCallException::class.java) { runBlocking { start() } }
        }

    @Test
    fun expiredAuthenticationNeverReachesCallBackend() =
        runBlocking {
            val peers = CallCryptoPeers()
            val backend = CallMutationBackend(peers.call)
            val gateway = gateway(peers, backend, Clock.fixed(CALL_TEST_TIME.plusSeconds(7_200), ZoneOffset.UTC))
            assertThrows(PrivateCallException::class.java) {
                runBlocking { gateway.prepareCall(peers.callerSession.accountId, peers.call.roomId) }
            }
            assertEquals(0, backend.roomReads)
        }

    @Test
    fun applicationRetryReusesTheTerminationMutationAfterAnAmbiguousFailure() =
        runBlocking {
            val peers = CallCryptoPeers()
            val backend = CallMutationBackend(peers.call)
            val gateway = gateway(peers, backend)
            gateway.poll(peers.callerSession.accountId)
            backend.failFirstEnd = true
            assertThrows(PrivateCallException::class.java) {
                runBlocking { gateway.endCall(peers.callerSession.accountId, peers.call.callId, PrivateCallEndReason.CANCELLED) }
            }
            val ended = gateway.endCall(peers.callerSession.accountId, peers.call.callId, PrivateCallEndReason.CANCELLED)
            assertEquals(PrivateCallState.ENDED, ended.state)
            assertEquals(2, backend.endMutations.size)
            assertEquals(backend.endMutations[0], backend.endMutations[1])
        }

    @Test
    fun pollingAndHeartbeatCannotChangeAnAuthenticatedCallsImmutableContext(): Unit =
        runBlocking {
            val peers = CallCryptoPeers()
            val backend = CallMutationBackend(peers.call)
            val gateway = gateway(peers, backend)
            gateway.poll(peers.callerSession.accountId)
            backend.receipt = peers.call.copy(membershipEpoch = 2)
            assertThrows(PrivateCallException::class.java) {
                runBlocking { gateway.poll(peers.callerSession.accountId) }
            }
            assertThrows(PrivateCallException::class.java) {
                runBlocking { gateway.heartbeat(peers.callerSession.accountId, peers.call.callId) }
            }
        }

    private fun gateway(
        peers: CallCryptoPeers,
        backend: CallMutationBackend,
        clock: Clock = Clock.fixed(CALL_TEST_TIME, ZoneOffset.UTC),
    ): SupabasePrivateCallSignalingGateway =
        SupabasePrivateCallSignalingGateway(
            PrivateChatSessionResolver(
                PrivateChatAuthenticatedSessionProvider { peers.callerSession },
                PrivateDecryptedPayloadCacheRepository(UnusedCallCacheStorage),
                clock,
            ),
            peers.recipientBackend,
            backend,
            peers.callerOwner,
            clock,
        )
}

private class CallMutationBackend(
    var receipt: PrivateCallSession,
) : PrivateCallBackend {
    var failFirstCreate = false
    var failFirstEnd = false
    var roomReads = 0
    val sentCiphertexts = mutableListOf<ByteArray>()
    val sentMutations = mutableListOf<UUID>()
    val endMutations = mutableListOf<UUID>()

    override suspend fun loadDirectRoomEpoch(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ): Int {
        roomReads += 1
        return 1
    }

    override suspend fun createCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        roomId: UUID,
        membershipEpoch: Int,
        mediaKind: PrivateCallMediaKind,
        mutationId: UUID,
        expiresAt: Instant,
        envelopes: List<SignalEnvelope>,
    ): PrivateCallSession {
        sentMutations += mutationId
        sentCiphertexts += envelopes.single().serializedCiphertext
        if (failFirstCreate && sentMutations.size == 1) throw IOException("Lost receipt")
        return receipt
    }

    override suspend fun poll(session: PrivateChatAuthenticatedSession) = PrivateCallBackendPoll(listOf(receipt), emptyList())

    override suspend fun acceptCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
    ) = error("Not used")

    override suspend fun sendSignal(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
        sequence: Int,
        expiresAt: Instant,
        envelopes: List<SignalEnvelope>,
    ): PrivateCallSignalReceipt = PrivateCallSignalReceipt(callId, mutationId, sequence, CALL_TEST_TIME, expiresAt)

    override suspend fun heartbeat(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
    ) = receipt

    override suspend fun endCall(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        mutationId: UUID,
        reason: PrivateCallEndReason,
    ): PrivateCallSession {
        endMutations += mutationId
        if (failFirstEnd && endMutations.size == 1) throw IOException("Lost termination receipt")
        return receipt.copy(state = PrivateCallState.ENDED, terminalReason = reason.name)
    }
}

private object UnusedCallCacheStorage : DeviceLocalEncryptedPayloadCacheStorage {
    override fun readDecryptedState(): ByteArray? = null

    override fun replaceEncryptedState(plaintext: ByteArray) = error("Calls must not persist plaintext")

    override fun replaceAfterPurge(retainedPlaintext: ByteArray?) = Unit

    override fun deletePhysically() = Unit
}
