package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalPendingOutboundMutationKey
import app.synapse.privatechat.crypto.StoredSignalPendingOutboundMutation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

/**
 * Serializes all encrypted mutations through one durable pending request. A later mutation first
 * replays any earlier request so a WHISPER envelope can never overtake its establishing PREKEY.
 */
internal class PrivateEncryptedMutationOutbox(
    private val envelopeCipher: PrivateChatEnvelopeCipher,
    private val backend: PrivateChatBackend,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dispatcher = PrivateEncryptedMutationDispatcher(backend)
    private val executionMutex = Mutex()

    suspend fun execute(
        session: PrivateChatAuthenticatedSession,
        intent: PrivateEncryptedMutationIntent,
        plaintext: ByteArray,
        recipients: List<PrivateChatRecipientDevice>,
    ): PrivateEncryptedMutationBackendReceipt =
        executionMutex.withLock {
            val now = clock.instant()
            val key =
                SignalPendingOutboundMutationKey(
                    accountId = session.localSignalAddress.accountId,
                    transportDeviceId = session.localSignalAddress.transportDeviceId,
                    clientMutationId = intent.clientMutationId,
                )
            val operationDigest = PrivateEncryptedMutationCodec.operationDigest(intent, plaintext)
            try {
                replayEarlierPendingMutations(session, key, now)
                val pending = envelopeCipher.loadPendingOutboundMutation(key)
                val prepared =
                    if (pending != null) {
                        requireMatchingPendingMutation(pending, operationDigest, now)
                        pending
                    } else {
                        preparePendingMutation(session, key, intent, plaintext, recipients, operationDigest, now)
                    }
                val request = PrivateEncryptedMutationCodec.decode(prepared.opaqueRequest)
                if (request.clientMutationId != intent.clientMutationId) {
                    throw PrivateEncryptedMutationOutboxException(
                        "Pending encrypted mutation request does not match its durable key",
                    )
                }
                executeAndConfirm(session, prepared, request)
            } finally {
                operationDigest.fill(0)
            }
        }

    private suspend fun replayEarlierPendingMutations(
        session: PrivateChatAuthenticatedSession,
        requestedKey: SignalPendingOutboundMutationKey,
        now: Instant,
    ) {
        envelopeCipher.listPendingOutboundMutations().forEach { pending ->
            if (pending.key == requestedKey) return@forEach
            if (!pending.expiresAt.isAfter(now)) {
                discardExpired(pending)
                return@forEach
            }
            val request = PrivateEncryptedMutationCodec.decode(pending.opaqueRequest)
            if (request.clientMutationId != pending.key.clientMutationId) {
                throw PrivateEncryptedMutationOutboxException("Pending encrypted mutation key is inconsistent")
            }
            executeAndConfirm(session, pending, request)
        }
    }

    private suspend fun preparePendingMutation(
        session: PrivateChatAuthenticatedSession,
        key: SignalPendingOutboundMutationKey,
        intent: PrivateEncryptedMutationIntent,
        plaintext: ByteArray,
        recipients: List<PrivateChatRecipientDevice>,
        operationDigest: ByteArray,
        now: Instant,
    ): StoredSignalPendingOutboundMutation =
        envelopeCipher.encryptAndCommitPendingOutbound(
            session = session,
            recipients = recipients,
            plaintext = plaintext,
            claimPreKeyBundle = { recipient -> backend.claimDevicePreKey(session, recipient) },
        ) { envelopes, peers ->
            val encodedRequest = PrivateEncryptedMutationCodec.encode(intent.attachEnvelopes(envelopes))
            try {
                StoredSignalPendingOutboundMutation.create(
                    key = key,
                    operationDigest = operationDigest,
                    opaqueRequest = encodedRequest,
                    peerRecipients = peers,
                    createdAt = now,
                    expiresAt = now.plusSeconds(StoredSignalPendingOutboundMutation.MAX_LIFETIME_SECONDS),
                )
            } finally {
                encodedRequest.fill(0)
            }
        }

    private suspend fun executeAndConfirm(
        session: PrivateChatAuthenticatedSession,
        pending: StoredSignalPendingOutboundMutation,
        request: PrivatePendingEncryptedMutation,
    ): PrivateEncryptedMutationBackendReceipt {
        val receipt =
            try {
                dispatcher.execute(session, request)
            } catch (rejection: SupabasePrivateChatRequestRejectedException) {
                val digest = pending.operationDigest
                try {
                    envelopeCipher.discardPendingOutboundMutationAndResetPeerSessions(pending.key, digest)
                } finally {
                    digest.fill(0)
                }
                throw rejection
            }
        val digest = pending.operationDigest
        try {
            envelopeCipher.confirmPendingOutboundMutation(pending.key, digest)
        } finally {
            digest.fill(0)
        }
        return receipt
    }

    private fun requireMatchingPendingMutation(
        pending: StoredSignalPendingOutboundMutation,
        expectedOperationDigest: ByteArray,
        now: Instant,
    ) {
        if (!pending.expiresAt.isAfter(now)) {
            discardExpired(pending)
            throw PrivateChatCommandRejectedException(
                "The previous encrypted request expired safely. Try the action again.",
            )
        }
        if (!java.security.MessageDigest.isEqual(pending.operationDigest, expectedOperationDigest)) {
            throw PrivateChatCommandRejectedException(
                "This action reused an encrypted mutation identifier with different content.",
            )
        }
    }

    private fun discardExpired(pending: StoredSignalPendingOutboundMutation) {
        val digest = pending.operationDigest
        try {
            envelopeCipher.discardPendingOutboundMutationAndResetPeerSessions(pending.key, digest)
        } finally {
            digest.fill(0)
        }
    }
}

internal class PrivateEncryptedMutationOutboxException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
