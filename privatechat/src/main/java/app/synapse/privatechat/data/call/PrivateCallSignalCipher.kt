package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.crypto.SignalProtocolAdapter
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSession
import app.synapse.privatechat.data.chat.PrivateChatPollingBackend
import app.synapse.privatechat.data.chat.PrivateChatRecipientDevice
import app.synapse.privatechat.domain.call.PrivateCallPeerSafetyNumber
import java.io.IOException

/** Shares the account's one ratchet owner; call plaintext never enters the durable chat outbox. */
internal class PrivateCallSignalCipher(
    private val adapterOwner: SignalProtocolAdapterOwner,
    private val recipientBackend: PrivateChatPollingBackend,
) {
    suspend fun preparePeers(
        session: PrivateChatAuthenticatedSession,
        peers: List<PrivateChatRecipientDevice>,
    ): List<PrivateCallPeerSafetyNumber> {
        val adapter = boundAdapter(session)
        return peers.map { peer ->
            if (!adapter.hasPairwiseSession(peer.address)) {
                val bundle = recipientBackend.claimDevicePreKey(session, peer)
                require(bundle.address == peer.address) { "Call pre-key belongs to another device" }
                adapter.establishPairwiseSession(bundle)
            }
            safetyNumber(adapter, peer.address)
        }
    }

    fun safetyNumber(
        session: PrivateChatAuthenticatedSession,
        address: SignalDeviceAddress,
    ): PrivateCallPeerSafetyNumber = safetyNumber(boundAdapter(session), address)

    fun encrypt(
        session: PrivateChatAuthenticatedSession,
        payload: PrivateCallPayload,
    ): SignalEnvelope {
        require(payload.sender == session.localSignalAddress)
        val plaintext = PrivateCallPayloadCodec.encode(payload)
        try {
            return boundAdapter(session).encryptForDevice(payload.recipient, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    fun decrypt(
        session: PrivateChatAuthenticatedSession,
        envelope: SignalEnvelope,
        validatePayload: (PrivateCallPayload) -> Unit = {},
    ): PrivateCallPayload =
        // Exception owner: call signaling. Payloads are intentionally ephemeral: process death
        // ends the call by lease, never resumes it. Keep validation inside the ratchet transaction
        // so a chat ciphertext misrouted as a call cannot consume a shared chat message key.
        // Remove this exception only if an explicit durable call-resumption contract is introduced.
        boundAdapter(session).decryptFromDeviceWithDurableCommit(envelope) { plaintext ->
            val payload =
                try {
                    PrivateCallPayloadCodec.decode(plaintext)
                } catch (malformed: IOException) {
                    throw IllegalArgumentException("Authenticated call payload is malformed", malformed)
                }
            payload.also(validatePayload)
        }

    private fun boundAdapter(session: PrivateChatAuthenticatedSession): SignalProtocolAdapter {
        require(adapterOwner.storedLocalAddress() == session.localSignalAddress) { "Call Signal device is not authenticated" }
        return adapterOwner.adapterFor(session.localSignalAddress)
    }

    private fun safetyNumber(
        adapter: SignalProtocolAdapter,
        address: SignalDeviceAddress,
    ): PrivateCallPeerSafetyNumber =
        PrivateCallPeerSafetyNumber(address.accountId, address.transportDeviceId, adapter.safetyNumberFor(address).grouped)
}
