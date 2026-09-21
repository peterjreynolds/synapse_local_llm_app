package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.crypto.SignalProtocolException
import app.synapse.privatechat.crypto.SignalProtocolFailureKind
import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSession
import app.synapse.privatechat.data.chat.PrivateChatPollingBackend
import app.synapse.privatechat.data.chat.PrivateChatRecipientDevice
import app.synapse.privatechat.data.chat.PrivateChatSessionResolver
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.call.PrivateCallEndReason
import app.synapse.privatechat.domain.call.PrivateCallException
import app.synapse.privatechat.domain.call.PrivateCallFailure
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallPeerSafetyNumber
import app.synapse.privatechat.domain.call.PrivateCallPoll
import app.synapse.privatechat.domain.call.PrivateCallPreparation
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignal
import app.synapse.privatechat.domain.call.PrivateCallSignalReceipt
import app.synapse.privatechat.domain.call.PrivateCallSignalingGateway
import app.synapse.privatechat.domain.call.PrivateCallState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Ephemeral signaling: exact ciphertext survives retries, but process death cannot resume a call. */
internal class SupabasePrivateCallSignalingGateway(
    private val sessionResolver: PrivateChatSessionResolver,
    private val chatPollingBackend: PrivateChatPollingBackend,
    private val callBackend: PrivateCallBackend,
    adapterOwner: SignalProtocolAdapterOwner,
    private val clock: Clock = Clock.systemUTC(),
) : PrivateCallSignalingGateway {
    private val mutex = Mutex()
    private val cipher = PrivateCallSignalCipher(adapterOwner, chatPollingBackend)
    private val incoming = PrivateCallIncomingSignals(cipher)
    private val preparedRooms = mutableMapOf<PrivateRoomId, PreparedCallRoom>()
    private val calls = mutableMapOf<UUID, PrivateCallSession>()
    private val outgoing = mutableMapOf<UUID, PendingCallSignal>()
    private val nextSequences = mutableMapOf<UUID, Int>()
    private val acceptanceIds = mutableMapOf<UUID, UUID>()
    private val terminationIds = mutableMapOf<Pair<UUID, PrivateCallEndReason>, UUID>()
    private val callPeerSafetyNumbers = mutableMapOf<UUID, List<PrivateCallPeerSafetyNumber>>()
    private var boundDeviceId: UUID? = null

    override suspend fun prepareCall(
        accountId: PrivateAccountId,
        roomId: PrivateRoomId,
    ): PrivateCallPreparation =
        authenticated(accountId) { session ->
            val epoch = callBackend.loadDirectRoomEpoch(session, UUID.fromString(roomId.canonical))
            val peers = roomPeers(session, roomId)
            val safety = cipher.preparePeers(session, peers)
            val preparation = PrivateCallPreparation(roomId, epoch, safety.toList())
            preparedRooms.clear()
            preparedRooms[roomId] = PreparedCallRoom(preparation, peers, clock.instant().plusSeconds(120))
            preparation
        }

    override suspend fun createCall(
        accountId: PrivateAccountId,
        callId: UUID,
        roomId: PrivateRoomId,
        membershipEpoch: Int,
        mediaKind: PrivateCallMediaKind,
        offer: PrivateCallMediaSignal.Offer,
    ): PrivateCallSession =
        authenticated(accountId) { session ->
            require(callId != UUID(0, 0))
            calls[callId]?.let { existing ->
                require(existing.roomId == roomId && existing.membershipEpoch == membershipEpoch && existing.mediaKind == mediaKind)
                require(existing.callerDeviceId == session.localSignalAddress.transportDeviceId)
                return@authenticated existing
            }
            check(calls.values.none { it.state != PrivateCallState.ENDED }) { "Another call is active" }
            check(outgoing.keys.all { it == callId }) { "An earlier call creation has not completed" }
            val prepared = preparedRooms[roomId] ?: throw PrivateCallException(PrivateCallFailure.IDENTITY_CHANGED)
            require(prepared.preparation.membershipEpoch == membershipEpoch && prepared.expiresAt.isAfter(clock.instant()))
            val peers = roomPeers(session, roomId)
            requirePreparedPeers(session, prepared, peers)
            val pending =
                outgoing[callId] ?: encryptPending(
                    session,
                    callId,
                    roomId,
                    membershipEpoch,
                    mediaKind,
                    0,
                    PrivateCallSignal.Media(offer),
                    peers,
                ).also { outgoing[callId] = it }
            require(pending.signal == PrivateCallSignal.Media(offer))
            require(pending.roomId == roomId && pending.membershipEpoch == membershipEpoch && pending.mediaKind == mediaKind)
            requireNotExpired(pending.expiresAt)
            val receipt =
                callBackend.createCall(
                    session,
                    callId,
                    UUID.fromString(roomId.canonical),
                    membershipEpoch,
                    mediaKind,
                    pending.mutationId,
                    pending.expiresAt,
                    pending.envelopes,
                )
            require(receipt.callId == callId && receipt.roomId == roomId && receipt.membershipEpoch == membershipEpoch)
            require(receipt.callerDeviceId == session.localSignalAddress.transportDeviceId)
            require(receipt.callerAccountId == session.localSignalAddress.accountId)
            require(receipt.recipientAccountId == peers.first().address.accountId && receipt.mediaKind == mediaKind)
            require(receipt.ringExpiresAt == pending.expiresAt)
            calls[callId] = receipt
            callPeerSafetyNumbers[callId] = prepared.preparation.peerSafetyNumbers
            nextSequences[callId] = 1
            outgoing.remove(callId)
            receipt
        }

    override suspend fun poll(accountId: PrivateAccountId): PrivateCallPoll =
        authenticated(accountId) { session ->
            val polling = callBackend.poll(session)
            polling.calls.forEach { verifySessionOwnership(session, it) }
            val received = incoming.decode(session, polling, clock.instant())
            val presentIds = polling.calls.mapTo(HashSet(), PrivateCallSession::callId)
            calls.keys.retainAll(presentIds)
            polling.calls.forEach { call -> calls[call.callId] = call }
            outgoing.entries.removeAll { (id, pending) ->
                !pending.expiresAt.isAfter(clock.instant()) || calls[id]?.state == PrivateCallState.ENDED
            }
            nextSequences.keys.retainAll(presentIds)
            acceptanceIds.keys.retainAll(presentIds)
            terminationIds.keys.removeAll { it.first !in presentIds }
            callPeerSafetyNumbers.keys.retainAll(presentIds)
            PrivateCallPoll(polling.calls, received)
        }

    override suspend fun acceptCall(
        accountId: PrivateAccountId,
        callId: UUID,
    ): PrivateCallSession =
        authenticated(accountId) { session ->
            val previous = requireKnownCall(callId)
            require(previous.recipientAccountId == session.localSignalAddress.accountId)
            requireNotExpired(previous.ringExpiresAt)
            val mutationId = acceptanceIds.getOrPut(callId, UUID::randomUUID)
            callBackend.acceptCall(session, callId, mutationId).also { accepted ->
                verifySessionOwnership(session, accepted)
                require(accepted.acceptedDeviceId == session.localSignalAddress.transportDeviceId)
                require(accepted.state == PrivateCallState.ACTIVE)
                calls[callId] = accepted
                nextSequences.putIfAbsent(callId, 0)
            }
        }

    override suspend fun sendSignal(
        accountId: PrivateAccountId,
        callId: UUID,
        signal: PrivateCallMediaSignal,
    ): PrivateCallSignalReceipt =
        authenticated(accountId) { session ->
            require(signal !is PrivateCallMediaSignal.Offer)
            val call = requireKnownCall(callId)
            require(call.state != PrivateCallState.ENDED)
            requireNotExpired(call.leaseExpiresAt)
            val pending =
                outgoing[callId] ?: run {
                    val peers = participatingPeers(session, call)
                    val sequence = nextSequences[callId] ?: throw PrivateCallException(PrivateCallFailure.INVALID_STATE)
                    require(sequence <= MAXIMUM_CALL_SEQUENCE)
                    require((sequence == 0) == (signal is PrivateCallMediaSignal.Answer)) { "The answer must precede ICE signals" }
                    cipher.preparePeers(session, peers)
                    requireSameCallPeerIdentity(session, callId, peers)
                    encryptPending(
                        session,
                        callId,
                        call.roomId,
                        call.membershipEpoch,
                        call.mediaKind,
                        sequence,
                        PrivateCallSignal.Media(signal),
                        peers,
                    ).also { outgoing[callId] = it }
                }
            require(pending.signal == PrivateCallSignal.Media(signal)) { "An earlier call signal must complete first" }
            requireNotExpired(pending.expiresAt)
            callBackend
                .sendSignal(session, callId, pending.mutationId, pending.sequence, pending.expiresAt, pending.envelopes)
                .also {
                    nextSequences[callId] = pending.sequence + 1
                    outgoing.remove(callId)
                }
        }

    override suspend fun heartbeat(
        accountId: PrivateAccountId,
        callId: UUID,
    ): PrivateCallSession =
        authenticated(accountId) { session ->
            callBackend.heartbeat(session, callId).also {
                verifySessionOwnership(session, it)
                calls[callId] = it
            }
        }

    override suspend fun endCall(
        accountId: PrivateAccountId,
        callId: UUID,
        reason: PrivateCallEndReason,
    ): PrivateCallSession =
        authenticated(accountId) { session ->
            // Termination is an authorized control RPC, independent of a failed encrypted-media send.
            outgoing.remove(callId)
            nextSequences.remove(callId)
            val mutationId = terminationIds.getOrPut(callId to reason, UUID::randomUUID)
            callBackend.endCall(session, callId, mutationId, reason).also {
                verifySessionOwnership(session, it)
                require(it.state == PrivateCallState.ENDED)
                calls[callId] = it
                incoming.forget(callId)
            }
        }

    override suspend fun safetyNumbers(
        accountId: PrivateAccountId,
        callId: UUID,
    ): List<PrivateCallPeerSafetyNumber> =
        authenticated(accountId) { session ->
            val call = requireKnownCall(callId)
            val peers = participatingPeers(session, call)
            requireSameCallPeerIdentity(session, callId, peers)
            peers.map { cipher.safetyNumber(session, it.address) }.also { callPeerSafetyNumbers[callId] = it }
        }

    override suspend fun clearEphemeralState() = mutex.withLock { clearMemory() }

    private suspend fun roomPeers(
        session: PrivateChatAuthenticatedSession,
        roomId: PrivateRoomId,
    ): List<PrivateChatRecipientDevice> {
        val devices = chatPollingBackend.listRoomRecipientDevices(session, UUID.fromString(roomId.canonical))
        require(devices.count { it.address == session.localSignalAddress } == 1)
        require(devices.map { it.address.transportDeviceId }.distinct().size == devices.size)
        val peers = devices.filter { it.address.accountId != session.localSignalAddress.accountId }
        require(peers.size in 1..8 && peers.map { it.address.accountId }.distinct().size == 1)
        return peers.sortedBy { it.address.transportDeviceId.toString() }
    }

    private suspend fun participatingPeers(
        session: PrivateChatAuthenticatedSession,
        call: PrivateCallSession,
    ): List<PrivateChatRecipientDevice> {
        val peers = roomPeers(session, call.roomId)
        val peerDeviceId =
            if (call.callerAccountId == session.localSignalAddress.accountId) {
                call.acceptedDeviceId
            } else {
                call.callerDeviceId
            }
        return if (peerDeviceId == null) {
            peers
        } else {
            peers.filter { it.address.transportDeviceId == peerDeviceId }.also {
                require(it.size == 1) { "Call peer device is no longer authorized" }
            }
        }
    }

    private fun requirePreparedPeers(
        session: PrivateChatAuthenticatedSession,
        prepared: PreparedCallRoom,
        peers: List<PrivateChatRecipientDevice>,
    ) {
        if (prepared.peers != peers || peers.map { cipher.safetyNumber(session, it.address) } != prepared.preparation.peerSafetyNumbers) {
            throw PrivateCallException(PrivateCallFailure.IDENTITY_CHANGED)
        }
    }

    private fun requireSameCallPeerIdentity(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        peers: List<PrivateChatRecipientDevice>,
    ) {
        val expected = callPeerSafetyNumbers[callId] ?: return
        if (peers.any { cipher.safetyNumber(session, it.address) !in expected }) {
            throw PrivateCallException(PrivateCallFailure.IDENTITY_CHANGED)
        }
    }

    private fun encryptPending(
        session: PrivateChatAuthenticatedSession,
        callId: UUID,
        roomId: PrivateRoomId,
        membershipEpoch: Int,
        mediaKind: PrivateCallMediaKind,
        sequence: Int,
        signal: PrivateCallSignal,
        peers: List<PrivateChatRecipientDevice>,
    ): PendingCallSignal {
        val expiry = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(60)
        val envelopes =
            peers.map { peer ->
                cipher.encrypt(
                    session,
                    PrivateCallPayload(
                        callId,
                        UUID.fromString(roomId.canonical),
                        membershipEpoch,
                        session.localSignalAddress,
                        peer.address,
                        sequence,
                        expiry,
                        mediaKind,
                        signal,
                    ),
                )
            }
        return PendingCallSignal(UUID.randomUUID(), roomId, membershipEpoch, mediaKind, sequence, expiry, signal, envelopes)
    }

    private fun verifySessionOwnership(
        session: PrivateChatAuthenticatedSession,
        call: PrivateCallSession,
    ) {
        val local = session.localSignalAddress
        require(call.callerAccountId == local.accountId || call.recipientAccountId == local.accountId)
        require(call.callerAccountId != local.accountId || call.callerDeviceId == local.transportDeviceId)
        require(
            call.state != PrivateCallState.ACTIVE ||
                call.callerDeviceId == local.transportDeviceId ||
                call.acceptedDeviceId == local.transportDeviceId,
        )
        calls[call.callId]?.let { previous ->
            require(previous.roomId == call.roomId && previous.membershipEpoch == call.membershipEpoch)
            require(previous.callerAccountId == call.callerAccountId && previous.callerDeviceId == call.callerDeviceId)
            require(previous.recipientAccountId == call.recipientAccountId && previous.mediaKind == call.mediaKind)
            require(previous.createdAt == call.createdAt && previous.ringExpiresAt == call.ringExpiresAt)
            require(previous.acceptedDeviceId == null || previous.acceptedDeviceId == call.acceptedDeviceId)
            require(previous.state != PrivateCallState.ENDED || call.state == PrivateCallState.ENDED)
            require(previous.state != PrivateCallState.ACTIVE || call.state != PrivateCallState.RINGING)
        }
    }

    private fun requireKnownCall(callId: UUID): PrivateCallSession =
        calls[callId] ?: throw PrivateCallException(PrivateCallFailure.INVALID_STATE)

    private fun requireNotExpired(expiresAt: Instant) {
        if (!expiresAt.isAfter(clock.instant())) throw PrivateCallException(PrivateCallFailure.EXPIRED)
    }

    private suspend fun <Receipt> authenticated(
        accountId: PrivateAccountId,
        operation: suspend (PrivateChatAuthenticatedSession) -> Receipt,
    ): Receipt =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val session =
                    sessionResolver.resolve(accountId) ?: run {
                        clearMemory()
                        throw PrivateCallException(PrivateCallFailure.AUTHENTICATION_REQUIRED)
                    }
                if (boundDeviceId != null && boundDeviceId != session.localSignalAddress.transportDeviceId) clearMemory()
                boundDeviceId = session.localSignalAddress.transportDeviceId
                try {
                    operation(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: PrivateCallException) {
                    throw failure
                } catch (failure: SignalProtocolException) {
                    throw PrivateCallException(
                        if (failure.kind ==
                            SignalProtocolFailureKind.IDENTITY_REPLACEMENT_BLOCKED
                        ) {
                            PrivateCallFailure.IDENTITY_CHANGED
                        } else {
                            PrivateCallFailure.INVALID_SIGNAL
                        },
                    )
                } catch (_: IOException) {
                    throw PrivateCallException(PrivateCallFailure.UNAVAILABLE)
                } catch (_: IllegalArgumentException) {
                    throw PrivateCallException(PrivateCallFailure.INVALID_SIGNAL)
                } catch (_: IllegalStateException) {
                    throw PrivateCallException(PrivateCallFailure.INVALID_STATE)
                }
            }
        }

    private fun clearMemory() {
        preparedRooms.clear()
        calls.clear()
        outgoing.clear()
        nextSequences.clear()
        acceptanceIds.clear()
        terminationIds.clear()
        callPeerSafetyNumbers.clear()
        incoming.clear()
        boundDeviceId = null
    }
}

private data class PreparedCallRoom(
    val preparation: PrivateCallPreparation,
    val peers: List<PrivateChatRecipientDevice>,
    val expiresAt: Instant,
)

private class PendingCallSignal(
    val mutationId: UUID,
    val roomId: PrivateRoomId,
    val membershipEpoch: Int,
    val mediaKind: PrivateCallMediaKind,
    val sequence: Int,
    val expiresAt: Instant,
    val signal: PrivateCallSignal,
    val envelopes: List<SignalEnvelope>,
)
