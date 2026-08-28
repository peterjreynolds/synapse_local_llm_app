package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalPendingOutboundMutationKey
import app.synapse.privatechat.crypto.StoredSignalPendingOutboundMutation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Serializes encrypted mutations through durable pending requests. An earlier ambiguous request is
 * recovered before a later request, and the later request is then rejected so callers must refresh.
 */
internal class PrivateEncryptedMutationOutbox(
    private val envelopeCipher: PrivateChatEnvelopeCipher,
    private val backend: PrivateChatBackend,
    private val clock: Clock = Clock.systemUTC(),
) : PrivatePendingOutboundMutationRecovery {
    private val dispatcher = PrivateEncryptedMutationDispatcher(backend)
    private val executionMutex = Mutex()
    private val recoveredMutationLedger = PrivateRecoveredMutationLedger()

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
                val recoveredEarlierMutations = recoverPendingMutations(session, key, now)
                if (recoveredEarlierMutations.isNotEmpty()) {
                    recoveredMutationLedger.recordAndSnapshot(
                        session.localSignalAddress,
                        recoveredEarlierMutations,
                    )
                    throw PrivateChatCommandRejectedException(
                        "An earlier encrypted request was recovered. Refresh before trying this action again.",
                    )
                }
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

    override suspend fun recoverPendingMutations(session: PrivateChatAuthenticatedSession): Set<UUID> =
        executionMutex.withLock {
            val recoveredMutationIds =
                recoverPendingMutations(
                    session = session,
                    skippedKey = null,
                    now = clock.instant(),
                )
            recoveredMutationLedger.recordAndSnapshot(
                session.localSignalAddress,
                recoveredMutationIds,
            )
            recoveredMutationIds
        }

    override suspend fun retainedRecoveredMutationIds(session: PrivateChatAuthenticatedSession): Set<UUID> =
        executionMutex.withLock {
            recoveredMutationLedger.snapshot(session.localSignalAddress)
        }

    override suspend fun clearRecoveredMutationIds() {
        executionMutex.withLock { recoveredMutationLedger.clear() }
    }

    private suspend fun recoverPendingMutations(
        session: PrivateChatAuthenticatedSession,
        skippedKey: SignalPendingOutboundMutationKey?,
        now: Instant,
    ): Set<UUID> =
        buildSet {
            envelopeCipher.listPendingOutboundMutations().forEach { pending ->
                if (pending.key == skippedKey) return@forEach
                if (
                    pending.key.accountId != session.localSignalAddress.accountId ||
                    pending.key.transportDeviceId != session.localSignalAddress.transportDeviceId
                ) {
                    throw PrivateEncryptedMutationOutboxException(
                        "Pending encrypted mutation belongs to another authenticated device",
                    )
                }
                if (!pending.expiresAt.isAfter(now)) {
                    discardExpired(pending)
                    return@forEach
                }
                val request = PrivateEncryptedMutationCodec.decode(pending.opaqueRequest)
                if (request.clientMutationId != pending.key.clientMutationId) {
                    throw PrivateEncryptedMutationOutboxException("Pending encrypted mutation key is inconsistent")
                }
                executeAndConfirm(session, pending, request)
                add(pending.key.clientMutationId)
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

internal fun interface PrivatePendingOutboundMutationRecovery {
    suspend fun recoverPendingMutations(session: PrivateChatAuthenticatedSession): Set<UUID>

    suspend fun retainedRecoveredMutationIds(session: PrivateChatAuthenticatedSession): Set<UUID> = emptySet()

    suspend fun clearRecoveredMutationIds() = Unit
}

internal class PrivateEncryptedMutationOutboxException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
