package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.crypto.SignalPendingOutboundMutationKey
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.crypto.StoredSignalPendingOutboundMutation
import app.synapse.privatechat.crypto.local.DeviceLocalContentEnvelopeCipher
import java.time.Instant
import java.util.UUID

internal enum class PrivateChatEnvelopeKind(
    val wireName: String,
) {
    LOCAL_AEAD("LOCAL_AEAD"),
    PREKEY("PREKEY"),
    WHISPER("WHISPER"),
    ;

    companion object {
        fun fromWire(wireName: String): PrivateChatEnvelopeKind =
            entries.firstOrNull { kind -> kind.wireName == wireName }
                ?: throw PrivateChatEnvelopeException("Encrypted envelope kind is unsupported")
    }
}

internal data class PrivateChatRecipientDevice(
    val address: SignalDeviceAddress,
    val protocolAdapterVersion: Int,
) {
    init {
        require(protocolAdapterVersion == SignalEnvelope.CURRENT_PROTOCOL_VERSION) {
            "Recipient device protocol version is unsupported"
        }
    }
}

internal class PrivateChatEncryptedEnvelope(
    val recipientDeviceId: UUID,
    val protocolAdapterVersion: Int,
    val kind: PrivateChatEnvelopeKind,
    ciphertext: ByteArray,
) {
    private val immutableCiphertext = ciphertext.copyOf()

    init {
        require(ciphertext.isNotEmpty() && ciphertext.size <= MAXIMUM_ENVELOPE_CIPHERTEXT_BYTES) {
            "Encrypted envelope ciphertext size is invalid"
        }
        if (kind == PrivateChatEnvelopeKind.LOCAL_AEAD) {
            require(ciphertext.size >= MINIMUM_LOCAL_AEAD_CIPHERTEXT_BYTES) {
                "Device-local envelope ciphertext size is invalid"
            }
        }
        require(protocolAdapterVersion == SignalEnvelope.CURRENT_PROTOCOL_VERSION) {
            "Encrypted envelope protocol version is unsupported"
        }
    }

    fun ciphertextCopy(): ByteArray = immutableCiphertext.copyOf()

    override fun toString(): String =
        "PrivateChatEncryptedEnvelope(" +
            "recipientDeviceId=$recipientDeviceId, " +
            "protocolAdapterVersion=$protocolAdapterVersion, " +
            "kind=$kind, ciphertext=[REDACTED])"
}

internal interface PrivateChatSignalCipher {
    fun localAddress(): SignalDeviceAddress

    fun establishPairwiseSession(remoteBundle: SignalPublicPreKeyBundle)

    fun hasPairwiseSession(recipient: SignalDeviceAddress): Boolean

    fun encryptForRecipientDevicesWithPendingOutboundCommit(
        recipients: List<SignalDeviceAddress>,
        plaintext: ByteArray,
        createPendingMutation: (List<SignalEnvelope>) -> StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation

    fun loadPendingOutboundMutation(key: SignalPendingOutboundMutationKey): StoredSignalPendingOutboundMutation?

    fun listPendingOutboundMutations(): List<StoredSignalPendingOutboundMutation>

    fun commitPendingOutboundWithoutPeerRatchet(mutation: StoredSignalPendingOutboundMutation): StoredSignalPendingOutboundMutation

    fun confirmPendingOutboundMutation(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    )

    fun discardPendingOutboundMutationAndResetPeerSessions(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    )

    fun clearPendingOutboundMutationsForSessionInvalidation(): Int

    fun decryptFromDevice(envelope: SignalEnvelope): ByteArray

    fun <Receipt> decryptFromDeviceWithDurableCommit(
        envelope: SignalEnvelope,
        commitDecryptedPayload: (ByteArray) -> Receipt,
    ): Receipt
}

internal class LibSignalPrivateChatCipher(
    private val adapterOwner: SignalProtocolAdapterOwner,
) : PrivateChatSignalCipher {
    override fun localAddress(): SignalDeviceAddress {
        adapterOwner.requireAdapterForStoredIdentity()
        return adapterOwner.storedLocalAddress()
            ?: throw PrivateChatEnvelopeException("Local Signal identity is unavailable")
    }

    override fun establishPairwiseSession(remoteBundle: SignalPublicPreKeyBundle) {
        adapterOwner.requireAdapterForStoredIdentity().establishPairwiseSession(remoteBundle)
    }

    override fun hasPairwiseSession(recipient: SignalDeviceAddress): Boolean =
        adapterOwner.requireAdapterForStoredIdentity().hasPairwiseSession(recipient)

    override fun encryptForRecipientDevicesWithPendingOutboundCommit(
        recipients: List<SignalDeviceAddress>,
        plaintext: ByteArray,
        createPendingMutation: (List<SignalEnvelope>) -> StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation =
        adapterOwner
            .requireAdapterForStoredIdentity()
            .encryptForRecipientDevicesWithPendingOutboundCommit(recipients, plaintext) { fanOut ->
                createPendingMutation(fanOut.envelopes)
            }

    override fun loadPendingOutboundMutation(key: SignalPendingOutboundMutationKey): StoredSignalPendingOutboundMutation? =
        adapterOwner.requireAdapterForStoredIdentity().loadPendingOutboundMutation(key)

    override fun listPendingOutboundMutations(): List<StoredSignalPendingOutboundMutation> =
        adapterOwner.requireAdapterForStoredIdentity().listPendingOutboundMutations()

    override fun commitPendingOutboundWithoutPeerRatchet(
        mutation: StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation =
        adapterOwner.requireAdapterForStoredIdentity().commitPendingOutboundWithoutPeerRatchet(mutation)

    override fun confirmPendingOutboundMutation(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    ) = adapterOwner.requireAdapterForStoredIdentity().confirmPendingOutboundMutation(key, expectedOperationDigest)

    override fun discardPendingOutboundMutationAndResetPeerSessions(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    ) = adapterOwner
        .requireAdapterForStoredIdentity()
        .discardPendingOutboundMutationAndResetPeerSessions(key, expectedOperationDigest)

    override fun clearPendingOutboundMutationsForSessionInvalidation(): Int =
        if (adapterOwner.storedLocalAddress() == null) {
            0
        } else {
            adapterOwner.requireAdapterForStoredIdentity().clearPendingOutboundMutationsForSessionInvalidation()
        }

    override fun decryptFromDevice(envelope: SignalEnvelope): ByteArray =
        adapterOwner.requireAdapterForStoredIdentity().decryptFromDevice(envelope)

    override fun <Receipt> decryptFromDeviceWithDurableCommit(
        envelope: SignalEnvelope,
        commitDecryptedPayload: (ByteArray) -> Receipt,
    ): Receipt =
        adapterOwner
            .requireAdapterForStoredIdentity()
            .decryptFromDeviceWithDurableCommit(envelope, commitDecryptedPayload)
}

internal class PrivateChatEnvelopeCipher(
    private val signalCipher: PrivateChatSignalCipher,
    private val localCipher: DeviceLocalContentEnvelopeCipher,
) {
    suspend fun encryptAndCommitPendingOutbound(
        session: PrivateChatAuthenticatedSession,
        recipients: List<PrivateChatRecipientDevice>,
        plaintext: ByteArray,
        claimPreKeyBundle: suspend (PrivateChatRecipientDevice) -> SignalPublicPreKeyBundle,
        createPendingMutation: (List<PrivateChatEncryptedEnvelope>, List<SignalDeviceAddress>) ->
        StoredSignalPendingOutboundMutation,
    ): StoredSignalPendingOutboundMutation {
        requireValidRecipientSet(session, recipients)
        requireSignalCipherBoundToSession(session)
        val peers = recipients.filter { recipient -> recipient.address != session.localSignalAddress }
        peers.forEach { peer ->
            if (!signalCipher.hasPairwiseSession(peer.address)) {
                val claimedBundle = claimPreKeyBundle(peer)
                if (claimedBundle.address != peer.address) {
                    throw PrivateChatEnvelopeException("Claimed pre-key bundle belongs to a different device")
                }
                signalCipher.establishPairwiseSession(claimedBundle)
            }
        }
        val localRecipient = recipients.single { recipient -> recipient.address == session.localSignalAddress }
        val localEnvelope =
            PrivateChatEncryptedEnvelope(
                recipientDeviceId = localRecipient.address.transportDeviceId,
                protocolAdapterVersion = localRecipient.protocolAdapterVersion,
                kind = PrivateChatEnvelopeKind.LOCAL_AEAD,
                ciphertext = localCipher.encryptLocalEnvelope(plaintext),
            )
        val committed =
            if (peers.isEmpty()) {
                signalCipher.commitPendingOutboundWithoutPeerRatchet(
                    createPendingMutation(listOf(localEnvelope), emptyList()),
                )
            } else {
                signalCipher.encryptForRecipientDevicesWithPendingOutboundCommit(
                    recipients = peers.map(PrivateChatRecipientDevice::address),
                    plaintext = plaintext,
                ) { peerSignalEnvelopes ->
                    val peerEnvelopes = peerSignalEnvelopes.map(::toPrivateChatEnvelope)
                    val envelopesByRecipient =
                        (peerEnvelopes + localEnvelope).associateBy(PrivateChatEncryptedEnvelope::recipientDeviceId)
                    val orderedEnvelopes =
                        recipients.map { recipient ->
                            envelopesByRecipient[recipient.address.transportDeviceId]
                                ?: throw PrivateChatEnvelopeException("Encrypted fan-out omitted a recipient device")
                        }
                    createPendingMutation(orderedEnvelopes, peers.map(PrivateChatRecipientDevice::address))
                }
            }
        val localCiphertext = localEnvelope.ciphertextCopy()
        try {
            localCipher.markEnvelopeDurablyReferenced(localCiphertext)
        } finally {
            localCiphertext.fill(0)
        }
        return committed
    }

    fun loadPendingOutboundMutation(key: SignalPendingOutboundMutationKey): StoredSignalPendingOutboundMutation? =
        signalCipher.loadPendingOutboundMutation(key)

    fun listPendingOutboundMutations(): List<StoredSignalPendingOutboundMutation> = signalCipher.listPendingOutboundMutations()

    fun confirmPendingOutboundMutation(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    ) = signalCipher.confirmPendingOutboundMutation(key, expectedOperationDigest)

    fun discardPendingOutboundMutationAndResetPeerSessions(
        key: SignalPendingOutboundMutationKey,
        expectedOperationDigest: ByteArray,
    ) = signalCipher.discardPendingOutboundMutationAndResetPeerSessions(key, expectedOperationDigest)

    fun reconcileLocalEnvelopeKeys(
        authoritativeEnvelopes: Collection<PrivateChatEncryptedEnvelope>,
        pendingEnvelopes: Collection<PrivateChatEncryptedEnvelope>,
        observedAt: Instant,
    ) {
        val authoritativeCiphertexts =
            authoritativeEnvelopes
                .filter { envelope -> envelope.kind == PrivateChatEnvelopeKind.LOCAL_AEAD }
                .map(PrivateChatEncryptedEnvelope::ciphertextCopy)
        val pendingCiphertexts =
            pendingEnvelopes
                .filter { envelope -> envelope.kind == PrivateChatEnvelopeKind.LOCAL_AEAD }
                .map(PrivateChatEncryptedEnvelope::ciphertextCopy)
        try {
            localCipher.reconcileRetainedEnvelopeKeys(
                authoritativeCiphertexts = authoritativeCiphertexts,
                pendingCiphertexts = pendingCiphertexts,
                observedAt = observedAt,
            )
        } finally {
            authoritativeCiphertexts.forEach { ciphertext -> ciphertext.fill(0) }
            pendingCiphertexts.forEach { ciphertext -> ciphertext.fill(0) }
        }
    }

    fun clearLocalEnvelopeKeysForSessionInvalidation() = localCipher.clearForSessionInvalidation()

    fun clearPendingOutboundForSessionInvalidation(): Int = signalCipher.clearPendingOutboundMutationsForSessionInvalidation()

    fun decryptForCurrentDevice(
        session: PrivateChatAuthenticatedSession,
        senderAddress: SignalDeviceAddress,
        envelope: PrivateChatEncryptedEnvelope,
    ): ByteArray =
        decryptForCurrentDeviceWithDurableCommit(session, senderAddress, envelope) { plaintext ->
            plaintext.copyOf()
        }

    fun <Receipt> decryptForCurrentDeviceWithDurableCommit(
        session: PrivateChatAuthenticatedSession,
        senderAddress: SignalDeviceAddress,
        envelope: PrivateChatEncryptedEnvelope,
        commitDecryptedPayload: (ByteArray) -> Receipt,
    ): Receipt {
        requireSignalCipherBoundToSession(session)
        if (envelope.recipientDeviceId != session.localSignalAddress.transportDeviceId) {
            throw PrivateChatEnvelopeException("Encrypted envelope is addressed to a different device")
        }
        return when (envelope.kind) {
            PrivateChatEnvelopeKind.LOCAL_AEAD -> {
                if (senderAddress != session.localSignalAddress) {
                    throw PrivateChatEnvelopeException("Device-local envelope sender does not match this device")
                }
                val ciphertext = envelope.ciphertextCopy()
                val plaintext =
                    try {
                        localCipher.decryptLocalEnvelope(ciphertext)
                    } finally {
                        ciphertext.fill(0)
                    }
                try {
                    commitDecryptedPayload(plaintext)
                } finally {
                    plaintext.fill(0)
                }
            }

            PrivateChatEnvelopeKind.PREKEY,
            PrivateChatEnvelopeKind.WHISPER,
            -> {
                if (senderAddress == session.localSignalAddress) {
                    throw PrivateChatEnvelopeException("The local sender requires a device-local envelope")
                }
                val ciphertext = envelope.ciphertextCopy()
                try {
                    signalCipher.decryptFromDeviceWithDurableCommit(
                        envelope =
                            SignalEnvelope.fromWire(
                                protocolVersion = envelope.protocolAdapterVersion,
                                sender = senderAddress,
                                recipient = session.localSignalAddress,
                                ciphertextTypeCode = envelope.kind.requireSignalWireCode(),
                                serializedCiphertext = ciphertext,
                            ),
                        commitDecryptedPayload = commitDecryptedPayload,
                    )
                } finally {
                    ciphertext.fill(0)
                }
            }
        }
    }

    private fun toPrivateChatEnvelope(signalEnvelope: SignalEnvelope): PrivateChatEncryptedEnvelope =
        PrivateChatEncryptedEnvelope(
            recipientDeviceId = signalEnvelope.recipient.transportDeviceId,
            protocolAdapterVersion = signalEnvelope.protocolVersion,
            kind = PrivateChatEnvelopeKind.fromWire(signalEnvelope.ciphertextType.wireName),
            ciphertext = signalEnvelope.serializedCiphertext,
        )

    private fun requireSignalCipherBoundToSession(session: PrivateChatAuthenticatedSession) {
        if (signalCipher.localAddress() != session.localSignalAddress) {
            throw PrivateChatEnvelopeException("Signal state is bound to a different authenticated device")
        }
    }
}

internal class PrivateChatEnvelopeException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun requireValidRecipientSet(
    session: PrivateChatAuthenticatedSession,
    recipients: List<PrivateChatRecipientDevice>,
) {
    if (recipients.size !in 1..MAXIMUM_ROOM_DEVICE_COUNT) {
        throw PrivateChatEnvelopeException("Room recipient device count is unsupported")
    }
    if (recipients.map { recipient -> recipient.address.transportDeviceId }.distinct().size != recipients.size) {
        throw PrivateChatEnvelopeException("Room recipient devices are not unique")
    }
    if (recipients.map(PrivateChatRecipientDevice::address).distinct().size != recipients.size) {
        throw PrivateChatEnvelopeException("Room Signal addresses are not unique")
    }
    if (recipients.count { recipient -> recipient.address == session.localSignalAddress } != 1) {
        throw PrivateChatEnvelopeException("Room recipients must contain this authenticated device exactly once")
    }
    if (recipients.count { recipient -> recipient.address != session.localSignalAddress } > MAXIMUM_PEER_DEVICE_COUNT) {
        throw PrivateChatEnvelopeException("Room peer device count is unsupported")
    }
}

private fun PrivateChatEnvelopeKind.requireSignalWireCode(): Int =
    when (this) {
        PrivateChatEnvelopeKind.PREKEY -> 3
        PrivateChatEnvelopeKind.WHISPER -> 2
        PrivateChatEnvelopeKind.LOCAL_AEAD -> throw PrivateChatEnvelopeException("Local envelopes are not Signal ciphertext")
    }

private const val MAXIMUM_PEER_DEVICE_COUNT = 128
private const val MAXIMUM_ROOM_DEVICE_COUNT = MAXIMUM_PEER_DEVICE_COUNT + 1
private const val MAXIMUM_ENVELOPE_CIPHERTEXT_BYTES = 256 * 1_024
private const val MINIMUM_LOCAL_AEAD_CIPHERTEXT_BYTES = 29
