package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.local.DeviceLocalEncryptedPayloadCacheStorage
import java.time.Instant
import java.util.UUID

internal class PrivateDecryptedPayloadCacheRepository(
    private val storage: DeviceLocalEncryptedPayloadCacheStorage,
) {
    private val monitor = Any()
    private var loaded = false
    private var cachedState: PrivateDecryptedPayloadCacheState? = null

    fun loadPlaintext(
        session: PrivateChatAuthenticatedSession,
        descriptor: PrivateAuthoritativeEncryptedPayload,
        now: Instant,
    ): ByteArray? =
        synchronized(monitor) {
            val state = requireSessionState(session, now) ?: return@synchronized null
            val cachedEntry = state.entries[descriptor.key] ?: return@synchronized null
            if (!cachedEntry.descriptor.fingerprint.matches(descriptor.fingerprint)) {
                throw PrivateDecryptedPayloadCacheUnavailableException(
                    "Authoritative ciphertext changed without a cache revision change",
                )
            }
            if (!cachedEntry.descriptor.matchesAuthoritativePayload(descriptor)) {
                throw PrivateDecryptedPayloadCacheUnavailableException(
                    "Authoritative encrypted payload metadata changed unexpectedly",
                )
            }
            cachedEntry.plaintextCopy()
        }

    /** Must be called from the Signal adapter's durable decrypt callback for Signal envelopes. */
    fun persistPlaintext(
        session: PrivateChatAuthenticatedSession,
        descriptor: PrivateAuthoritativeEncryptedPayload,
        plaintext: ByteArray,
        now: Instant,
    ) {
        synchronized(monitor) {
            if (!session.isUsableAt(now)) {
                clearForSessionInvalidation()
                throw PrivateDecryptedPayloadCacheUnavailableException(
                    "An unusable authenticated session cannot write the decrypted cache",
                )
            }
            if (!descriptor.expiresAt.isAfter(now)) {
                throw PrivateDecryptedPayloadCacheUnavailableException("Expired payload cannot enter the decrypted cache")
            }
            val state = requireSessionState(session, now) ?: emptyState(session)
            val existing = state.entries[descriptor.key]
            if (existing != null && !existing.descriptor.fingerprint.matches(descriptor.fingerprint)) {
                throw PrivateDecryptedPayloadCacheUnavailableException(
                    "Authoritative ciphertext changed without a cache revision change",
                )
            }
            val replacementEntries = state.entries.mapValuesTo(LinkedHashMap()) { (_, entry) -> entry.copyForCache() }
            replacementEntries[descriptor.key] = PrivateCachedPayloadEntry(descriptor, plaintext)
            val replacement =
                PrivateDecryptedPayloadCacheState(
                    accountId = state.accountId,
                    transportDeviceId = state.transportDeviceId,
                    entries = replacementEntries,
                )
            try {
                persistState(replacement)
            } finally {
                replacement.destroy()
            }
        }
    }

    fun reconcileAuthoritativePayloads(
        session: PrivateChatAuthenticatedSession,
        authoritativePayloads: Collection<PrivateAuthoritativeEncryptedPayload>,
        now: Instant,
    ) {
        synchronized(monitor) {
            val state = requireSessionState(session, now) ?: return@synchronized
            val authoritativeByKey = authoritativePayloads.associateBy(PrivateAuthoritativeEncryptedPayload::key)
            if (authoritativeByKey.size != authoritativePayloads.size) {
                throw PrivateDecryptedPayloadCacheUnavailableException("Authoritative payload keys are not unique")
            }
            val retained =
                state.entries.filter { (key, cachedEntry) ->
                    val authoritative = authoritativeByKey[key]
                    authoritative != null &&
                        authoritative.expiresAt.isAfter(now) &&
                        cachedEntry.descriptor.matchesAuthoritativePayload(authoritative)
                }
            if (retained.size != state.entries.size) persistEntriesAfterPurge(state, retained)
        }
    }

    fun purgeMessage(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        now: Instant,
    ) {
        synchronized(monitor) {
            val state = requireSessionState(session, now) ?: return@synchronized
            val retained = state.entries.filterValues { entry -> entry.descriptor.parentMessageId != messageId }
            if (retained.size != state.entries.size) persistEntriesAfterPurge(state, retained)
        }
    }

    fun purgeMessageContent(
        session: PrivateChatAuthenticatedSession,
        messageId: UUID,
        now: Instant,
    ) {
        synchronized(monitor) {
            val state = requireSessionState(session, now) ?: return@synchronized
            val retained =
                state.entries.filterValues { entry ->
                    entry.descriptor.parentMessageId != messageId ||
                        entry.descriptor.key.kind == PrivateCachedPayloadKind.REACTION
                }
            if (retained.size != state.entries.size) persistEntriesAfterPurge(state, retained)
        }
    }

    fun purgeReaction(
        session: PrivateChatAuthenticatedSession,
        reactionId: UUID,
        now: Instant,
    ) {
        synchronized(monitor) {
            val state = requireSessionState(session, now) ?: return@synchronized
            val retained =
                state.entries.filterKeys { key ->
                    key.kind != PrivateCachedPayloadKind.REACTION || key.recordId != reactionId
                }
            if (retained.size != state.entries.size) persistEntriesAfterPurge(state, retained)
        }
    }

    fun purgeRoom(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
        now: Instant,
    ) {
        synchronized(monitor) {
            val state = requireSessionState(session, now) ?: return@synchronized
            val retained = state.entries.filterValues { entry -> entry.descriptor.roomId != roomId }
            if (retained.size != state.entries.size) persistEntriesAfterPurge(state, retained)
        }
    }

    fun clearForSessionInvalidation() {
        synchronized(monitor) {
            replaceStateAfterPurge(replacement = null)
        }
    }

    private fun requireSessionState(
        session: PrivateChatAuthenticatedSession,
        now: Instant,
    ): PrivateDecryptedPayloadCacheState? {
        if (!session.isUsableAt(now)) {
            clearForSessionInvalidation()
            return null
        }
        loadStateIfNeeded()
        val state = cachedState ?: return null
        if (
            state.accountId.toString() != session.accountId.canonical ||
            state.transportDeviceId != session.localSignalAddress.transportDeviceId
        ) {
            replaceStateAfterPurge(replacement = null)
            return null
        }
        val retained = state.entries.filterValues { entry -> entry.descriptor.expiresAt.isAfter(now) }
        if (retained.size == state.entries.size) return state
        persistEntriesAfterPurge(state, retained)
        return cachedState
    }

    private fun loadStateIfNeeded() {
        if (loaded) return
        val plaintext = storage.readDecryptedState()
        cachedState =
            plaintext?.let { encoded ->
                try {
                    PrivateDecryptedPayloadCacheCodec.decode(encoded)
                } catch (error: Exception) {
                    cachedState = null
                    loaded = true
                    try {
                        storage.replaceAfterPurge(retainedPlaintext = null)
                    } catch (deleteFailure: Exception) {
                        error.addSuppressed(deleteFailure)
                    }
                    throw PrivateDecryptedPayloadCacheUnavailableException(
                        "Decrypted payload cache was malformed and purged",
                        error,
                    )
                } finally {
                    encoded.fill(0)
                }
            }
        loaded = true
    }

    private fun persistEntriesAfterPurge(
        previousState: PrivateDecryptedPayloadCacheState,
        entries: Map<PrivateCachedPayloadKey, PrivateCachedPayloadEntry>,
    ) {
        replaceStateAfterPurge(
            replacement =
                entries.takeIf(Map<PrivateCachedPayloadKey, PrivateCachedPayloadEntry>::isNotEmpty)?.let {
                    PrivateDecryptedPayloadCacheState(
                        accountId = previousState.accountId,
                        transportDeviceId = previousState.transportDeviceId,
                        entries = entries,
                    )
                },
        )
    }

    private fun persistState(replacement: PrivateDecryptedPayloadCacheState) {
        replaceState(replacement, storage::replaceEncryptedState)
    }

    private fun replaceState(
        replacement: PrivateDecryptedPayloadCacheState,
        replaceStorageState: (ByteArray) -> Unit,
    ) {
        val encoded = PrivateDecryptedPayloadCacheCodec.encode(replacement)
        try {
            replaceStorageState(encoded)
        } finally {
            encoded.fill(0)
        }
        val cachedReplacement = replacement.copyForCache()
        cachedState?.destroy()
        cachedState = cachedReplacement
        loaded = true
    }

    private fun replaceStateAfterPurge(replacement: PrivateDecryptedPayloadCacheState?) {
        val encoded = replacement?.let(PrivateDecryptedPayloadCacheCodec::encode)
        val cachedReplacement = replacement?.copyForCache()
        cachedState?.destroy()
        cachedState = null
        loaded = true
        try {
            storage.replaceAfterPurge(encoded)
        } catch (purgeFailure: Exception) {
            cachedReplacement?.destroy()
            try {
                storage.replaceAfterPurge(retainedPlaintext = null)
            } catch (clearFailure: Exception) {
                purgeFailure.addSuppressed(clearFailure)
            }
            throw purgeFailure
        } finally {
            encoded?.fill(0)
        }
        cachedState = cachedReplacement
    }

    private fun emptyState(session: PrivateChatAuthenticatedSession): PrivateDecryptedPayloadCacheState =
        PrivateDecryptedPayloadCacheState(
            accountId = session.localSignalAddress.accountId,
            transportDeviceId = session.localSignalAddress.transportDeviceId,
            entries = emptyMap(),
        )
}
