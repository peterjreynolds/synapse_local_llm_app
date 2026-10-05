package app.synapse.privatechat.data.call

import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSession
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallSignal
import app.synapse.privatechat.domain.call.PrivateCallState
import app.synapse.privatechat.domain.call.PrivateReceivedCallSignal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** A process-local replay ledger; losing it ends a call instead of persisting SDP or ICE history. */
internal class PrivateCallIncomingSignals(
    private val cipher: PrivateCallSignalCipher,
) {
    private val seen = mutableMapOf<InboundCallSequence, SeenCallSignal>()
    private val expectedSequences = mutableMapOf<Pair<UUID, UUID>, Int>()
    private val peerSafetyNumbers = mutableMapOf<Pair<UUID, UUID>, String>()

    fun decode(
        session: PrivateChatAuthenticatedSession,
        polling: PrivateCallBackendPoll,
        now: Instant,
    ): List<PrivateReceivedCallSignal> {
        val calls = polling.calls.associateBy(PrivateCallSession::callId)
        seen.entries.removeAll { (key, cached) -> key.callId !in calls || !cached.record.expiresAt.isAfter(now) }
        expectedSequences.keys.removeAll { it.first !in calls }
        peerSafetyNumbers.keys.removeAll { it.first !in calls }
        return polling.signals
            .sortedWith(compareBy({ it.callId }, { it.envelope.sender.transportDeviceId }, { it.sequence }))
            .mapNotNull { record ->
                val call = calls[record.callId] ?: error("Call signal has no authorized call")
                requireRecordContext(session, call, record)
                if (!record.expiresAt.isAfter(now) || call.state == PrivateCallState.ENDED) return@mapNotNull null
                require(!record.expiresAt.isAfter(now.plusSeconds(65)))
                val key = InboundCallSequence(record.callId, record.envelope.sender.transportDeviceId, record.sequence)
                val fingerprint = fingerprint(record)
                seen[key]?.let { previous ->
                    require(previous.matches(record, fingerprint)) { "Call ciphertext or routing changed for an existing sequence" }
                    return@mapNotNull null
                }
                val senderKey = key.callId to key.senderDeviceId
                require(record.sequence == (expectedSequences[senderKey] ?: 0)) { "Call signaling sequence is discontinuous" }
                peerSafetyNumbers[senderKey]?.let { expectedSafetyNumber ->
                    require(cipher.safetyNumber(session, record.envelope.sender).groupedDigits == expectedSafetyNumber) {
                        "Call identity changed during the session"
                    }
                }
                require(seen.size < 4_096) { "Call signaling replay ledger is full" }
                val payload = cipher.decrypt(session, record.envelope) { decoded -> requirePayloadContext(call, record, decoded) }
                seen[key] = SeenCallSignal(record, fingerprint)
                expectedSequences[senderKey] = record.sequence + 1
                peerSafetyNumbers[senderKey] = cipher.safetyNumber(session, record.envelope.sender).groupedDigits
                PrivateReceivedCallSignal(record.callId, key.senderDeviceId, record.sequence, record.expiresAt, payload.signal)
            }
    }

    fun forget(callId: UUID) {
        seen.keys.removeAll { it.callId == callId }
        expectedSequences.keys.removeAll { it.first == callId }
        peerSafetyNumbers.keys.removeAll { it.first == callId }
    }

    fun clear() {
        seen.clear()
        expectedSequences.clear()
        peerSafetyNumbers.clear()
    }
}

private fun requireRecordContext(
    session: PrivateChatAuthenticatedSession,
    call: PrivateCallSession,
    record: PrivateCallEncryptedSignalRecord,
) {
    require(record.roomId.toString() == call.roomId.canonical && record.membershipEpoch == call.membershipEpoch)
    require(record.envelope.recipient == session.localSignalAddress)
    val sender = record.envelope.sender
    if (sender.accountId == call.callerAccountId) {
        require(sender.transportDeviceId == call.callerDeviceId)
        require(session.localSignalAddress.accountId == call.recipientAccountId)
    } else {
        require(sender.accountId == call.recipientAccountId && sender.transportDeviceId == call.acceptedDeviceId)
        require(session.localSignalAddress.transportDeviceId == call.callerDeviceId)
    }
}

private fun requirePayloadContext(
    call: PrivateCallSession,
    record: PrivateCallEncryptedSignalRecord,
    payload: PrivateCallPayload,
) {
    require(payload.callId == record.callId && payload.roomId == record.roomId && payload.membershipEpoch == record.membershipEpoch)
    require(payload.sender == record.envelope.sender && payload.recipient == record.envelope.recipient)
    require(payload.sequence == record.sequence && payload.expiresAt == record.expiresAt && payload.mediaKind == call.mediaKind)
    val signal = payload.signal
    if (payload.sequence == 0) {
        require(signal is PrivateCallSignal.Media)
        if (payload.sender.transportDeviceId == call.callerDeviceId) {
            require(signal.mediaSignal is PrivateCallMediaSignal.Offer)
        } else {
            require(signal.mediaSignal is PrivateCallMediaSignal.Answer)
        }
    } else {
        require(
            signal is PrivateCallSignal.End ||
                (signal is PrivateCallSignal.Media && signal.mediaSignal is PrivateCallMediaSignal.IceCandidate),
        )
    }
}

private fun fingerprint(record: PrivateCallEncryptedSignalRecord): ByteArray {
    val ciphertext = record.envelope.serializedCiphertext
    try {
        return MessageDigest.getInstance("SHA-256").digest(ciphertext)
    } finally {
        ciphertext.fill(0)
    }
}

private data class InboundCallSequence(
    val callId: UUID,
    val senderDeviceId: UUID,
    val sequence: Int,
)

private class SeenCallSignal(
    val record: PrivateCallEncryptedSignalRecord,
    private val fingerprint: ByteArray,
) {
    fun matches(
        candidate: PrivateCallEncryptedSignalRecord,
        candidateFingerprint: ByteArray,
    ): Boolean =
        MessageDigest.isEqual(fingerprint, candidateFingerprint) &&
            record.callId == candidate.callId &&
            record.roomId == candidate.roomId &&
            record.membershipEpoch == candidate.membershipEpoch &&
            record.clientMutationId == candidate.clientMutationId &&
            record.sequence == candidate.sequence &&
            record.expiresAt == candidate.expiresAt &&
            record.envelope.sender == candidate.envelope.sender &&
            record.envelope.recipient == candidate.envelope.recipient &&
            record.envelope.ciphertextType == candidate.envelope.ciphertextType
}
