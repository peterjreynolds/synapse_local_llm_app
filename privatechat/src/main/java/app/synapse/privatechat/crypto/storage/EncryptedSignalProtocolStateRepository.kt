package app.synapse.privatechat.crypto.storage

import app.synapse.privatechat.crypto.RemoteIdentityWriteOutcome
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalPendingOutboundMutationKey
import app.synapse.privatechat.crypto.SignalPreKeyId
import app.synapse.privatechat.crypto.SignalProtocolStateAddress
import app.synapse.privatechat.crypto.SignalProtocolStateCorruptedException
import app.synapse.privatechat.crypto.SignalProtocolStateLimits
import app.synapse.privatechat.crypto.SignalProtocolStateRepository
import app.synapse.privatechat.crypto.StoredLocalSignalIdentity
import app.synapse.privatechat.crypto.StoredSignalPendingOutboundMutation
import app.synapse.privatechat.security.storage.Aes256GcmEncryptedStateCipher
import app.synapse.privatechat.security.storage.EncryptedStateCipher
import app.synapse.privatechat.security.storage.EncryptedStateFile
import java.util.UUID

/**
 * Single-owner durable Signal state store. Plaintext state exists only in process memory and the
 * complete serialized snapshot is authenticated and encrypted before it crosses [EncryptedStateFile].
 */
class EncryptedSignalProtocolStateRepository internal constructor(
    private val encryptedStateFile: EncryptedStateFile,
    private val stateCipher: EncryptedStateCipher,
) : SignalProtocolStateRepository {
    private val monitor = Any()
    private var transactionDepth = 0
    private var state = loadState()

    override fun <T> writeTransaction(block: () -> T): T =
        synchronized(monitor) {
            val before = state.deepCopy()
            transactionDepth += 1
            try {
                val outcome = block()
                transactionDepth -= 1
                try {
                    if (transactionDepth == 0 && !state.hasSameContentAs(before)) persistState()
                } catch (error: Throwable) {
                    state = before
                    throw error
                }
                outcome
            } catch (error: Throwable) {
                if (transactionDepth > 0) transactionDepth -= 1
                state = before
                throw error
            }
        }

    override fun loadLocalIdentity(): StoredLocalSignalIdentity? = synchronized(monitor) { state.localIdentity?.copyForRead() }

    override fun insertLocalIdentityIfAbsent(identity: StoredLocalSignalIdentity): Boolean =
        mutate {
            if (localIdentity != null) {
                false
            } else {
                localIdentity = identity.copyForRead()
                true
            }
        }

    override fun loadRemoteIdentity(address: SignalProtocolStateAddress): ByteArray? =
        synchronized(monitor) { state.remoteIdentities[address]?.copyOf() }

    override fun storeRemoteIdentityWithoutReplacement(
        address: SignalProtocolStateAddress,
        identityKeyBytes: ByteArray,
    ): RemoteIdentityWriteOutcome {
        requireRecordSize(identityKeyBytes, MAX_REMOTE_IDENTITY_BYTES, "remote identity")
        return mutate {
            val existing = remoteIdentities[address]
            when {
                existing == null -> {
                    remoteIdentities[address] = identityKeyBytes.copyOf()
                    RemoteIdentityWriteOutcome.STORED_NEW
                }
                existing.contentEquals(identityKeyBytes) -> RemoteIdentityWriteOutcome.UNCHANGED
                else -> RemoteIdentityWriteOutcome.REPLACEMENT_REJECTED
            }
        }
    }

    override fun replaceRemoteIdentity(
        address: SignalProtocolStateAddress,
        expectedIdentityKeyBytes: ByteArray,
        replacementIdentityKeyBytes: ByteArray,
    ): Boolean {
        requireRecordSize(expectedIdentityKeyBytes, MAX_REMOTE_IDENTITY_BYTES, "expected remote identity")
        requireRecordSize(replacementIdentityKeyBytes, MAX_REMOTE_IDENTITY_BYTES, "replacement remote identity")
        return mutate {
            val existing = remoteIdentities[address]
            if (existing?.contentEquals(expectedIdentityKeyBytes) != true) {
                false
            } else {
                remoteIdentities[address] = replacementIdentityKeyBytes.copyOf()
                true
            }
        }
    }

    override fun loadSession(address: SignalProtocolStateAddress): ByteArray? = synchronized(monitor) { state.sessions[address]?.copyOf() }

    override fun storeSession(
        address: SignalProtocolStateAddress,
        serializedSession: ByteArray,
    ) {
        storeBounded(state.sessions, address, serializedSession, SignalProtocolStateLimits.MAX_SESSION_RECORD_BYTES, "session")
    }

    override fun listSessionDeviceIds(accountId: UUID): List<SignalDeviceId> =
        synchronized(monitor) {
            state.sessions.keys
                .asSequence()
                .filter { it.accountId == accountId }
                .map(SignalProtocolStateAddress::protocolDeviceId)
                .distinct()
                .sortedBy(SignalDeviceId::raw)
                .toList()
        }

    override fun deleteSession(address: SignalProtocolStateAddress): Boolean = mutate { sessions.remove(address) != null }

    override fun deleteAllSessions(accountId: UUID): Int =
        mutate {
            val matching = sessions.keys.filter { it.accountId == accountId }
            matching.forEach(sessions::remove)
            matching.size
        }

    override fun loadPendingOutboundMutation(key: SignalPendingOutboundMutationKey): StoredSignalPendingOutboundMutation? =
        synchronized(monitor) { state.pendingOutboundMutations[key]?.copyForStorage() }

    override fun listPendingOutboundMutations(
        accountId: UUID,
        transportDeviceId: UUID,
    ): List<StoredSignalPendingOutboundMutation> =
        synchronized(monitor) {
            state.pendingOutboundMutations.values
                .asSequence()
                .filter { mutation ->
                    mutation.key.accountId == accountId && mutation.key.transportDeviceId == transportDeviceId
                }.sortedBy { mutation -> mutation.createdAt }
                .map(StoredSignalPendingOutboundMutation::copyForStorage)
                .toList()
        }

    override fun insertPendingOutboundMutationIfAbsent(mutation: StoredSignalPendingOutboundMutation): Boolean =
        mutate {
            require(pendingOutboundMutations.size < MAX_PENDING_OUTBOUND_MUTATIONS) {
                "Pending outbound mutation limit is reached"
            }
            if (pendingOutboundMutations.containsKey(mutation.key)) {
                false
            } else {
                pendingOutboundMutations[mutation.key] = mutation.copyForStorage()
                true
            }
        }

    override fun deletePendingOutboundMutation(key: SignalPendingOutboundMutationKey): Boolean =
        mutate { pendingOutboundMutations.remove(key) != null }

    override fun loadPreKey(preKeyId: SignalPreKeyId): ByteArray? = synchronized(monitor) { state.preKeys[preKeyId]?.copyOf() }

    override fun storePreKey(
        preKeyId: SignalPreKeyId,
        serializedPreKey: ByteArray,
    ) {
        storeBounded(state.preKeys, preKeyId, serializedPreKey, SignalProtocolStateLimits.MAX_PRE_KEY_RECORD_BYTES, "pre-key")
    }

    override fun containsPreKey(preKeyId: SignalPreKeyId): Boolean = synchronized(monitor) { state.preKeys.containsKey(preKeyId) }

    override fun deletePreKey(preKeyId: SignalPreKeyId): Boolean = mutate { preKeys.remove(preKeyId) != null }

    override fun loadSignedPreKey(preKeyId: SignalPreKeyId): ByteArray? = synchronized(monitor) { state.signedPreKeys[preKeyId]?.copyOf() }

    override fun listSignedPreKeys(): List<ByteArray> =
        synchronized(monitor) {
            state.signedPreKeys
                .toSortedMap(compareBy(SignalPreKeyId::raw))
                .values
                .map(ByteArray::copyOf)
        }

    override fun storeSignedPreKey(
        preKeyId: SignalPreKeyId,
        serializedSignedPreKey: ByteArray,
    ) {
        storeBounded(
            state.signedPreKeys,
            preKeyId,
            serializedSignedPreKey,
            SignalProtocolStateLimits.MAX_SIGNED_PRE_KEY_RECORD_BYTES,
            "signed pre-key",
        )
    }

    override fun containsSignedPreKey(preKeyId: SignalPreKeyId): Boolean =
        synchronized(monitor) { state.signedPreKeys.containsKey(preKeyId) }

    override fun deleteSignedPreKey(preKeyId: SignalPreKeyId): Boolean = mutate { signedPreKeys.remove(preKeyId) != null }

    override fun loadKyberPreKey(preKeyId: SignalPreKeyId): ByteArray? = synchronized(monitor) { state.kyberPreKeys[preKeyId]?.copyOf() }

    override fun listKyberPreKeys(): List<ByteArray> =
        synchronized(monitor) {
            state.kyberPreKeys
                .toSortedMap(compareBy(SignalPreKeyId::raw))
                .values
                .map(ByteArray::copyOf)
        }

    override fun storeKyberPreKey(
        preKeyId: SignalPreKeyId,
        serializedKyberPreKey: ByteArray,
    ) {
        storeBounded(
            state.kyberPreKeys,
            preKeyId,
            serializedKyberPreKey,
            SignalProtocolStateLimits.MAX_KYBER_PRE_KEY_RECORD_BYTES,
            "Kyber pre-key",
        )
    }

    override fun containsKyberPreKey(preKeyId: SignalPreKeyId): Boolean = synchronized(monitor) { state.kyberPreKeys.containsKey(preKeyId) }

    override fun recordKyberPreKeyUse(
        kyberPreKeyId: SignalPreKeyId,
        signedPreKeyId: SignalPreKeyId,
        baseKeyBytes: ByteArray,
    ): Boolean {
        requireRecordSize(baseKeyBytes, MAX_BASE_KEY_BYTES, "Kyber base key")
        return mutate {
            consumedKyberBaseKeys.add(
                ConsumedKyberBaseKey(kyberPreKeyId, signedPreKeyId, ByteArrayKey(baseKeyBytes)),
            )
        }
    }

    private fun <K> storeBounded(
        target: MutableMap<K, ByteArray>,
        key: K,
        bytes: ByteArray,
        maximumBytes: Int,
        recordName: String,
    ) {
        requireRecordSize(bytes, maximumBytes, recordName)
        mutate { target[key] = bytes.copyOf() }
    }

    private fun <T> mutate(block: MutableSignalState.() -> T): T =
        synchronized(monitor) {
            val before = state.deepCopy()
            try {
                val outcome = state.block()
                if (transactionDepth == 0 && !state.hasSameContentAs(before)) persistState()
                outcome
            } catch (error: Throwable) {
                state = before
                throw error
            }
        }

    private fun persistState() {
        val plaintext = SignalStateCodec.encode(state)
        try {
            val ciphertext = stateCipher.encrypt(plaintext)
            try {
                encryptedStateFile.replace(ciphertext)
            } finally {
                ciphertext.fill(0)
            }
        } catch (error: Throwable) {
            throw SignalProtocolStateCorruptedException("Signal state commit failed", error)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun loadState(): MutableSignalState {
        val ciphertext =
            try {
                encryptedStateFile.read(MAX_ENCRYPTED_STATE_BYTES)
            } catch (error: Exception) {
                throw SignalProtocolStateCorruptedException("Signal state could not be read", error)
            } ?: return MutableSignalState()
        if (ciphertext.size > MAX_ENCRYPTED_STATE_BYTES) {
            throw SignalProtocolStateCorruptedException("Encrypted Signal state exceeds the size limit")
        }
        val plaintext =
            try {
                stateCipher.decrypt(ciphertext)
            } catch (error: Exception) {
                throw SignalProtocolStateCorruptedException("Signal state authentication failed", error)
            }
        return try {
            SignalStateCodec.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun StoredLocalSignalIdentity.copyForRead(): StoredLocalSignalIdentity =
        StoredLocalSignalIdentity.fromPersistence(address, serializedIdentityKeyPair, registrationId.raw)

    private companion object {
        const val MAX_REMOTE_IDENTITY_BYTES = 256
        const val MAX_BASE_KEY_BYTES = 256
        const val MAX_PENDING_OUTBOUND_MUTATIONS = 4
        const val MAX_ENCRYPTED_STATE_BYTES =
            SignalStateCodec.MAX_TOTAL_PLAINTEXT_BYTES + Aes256GcmEncryptedStateCipher.MAX_ENVELOPE_OVERHEAD_BYTES
    }
}

internal data class MutableSignalState(
    var localIdentity: StoredLocalSignalIdentity? = null,
    val remoteIdentities: MutableMap<SignalProtocolStateAddress, ByteArray> = mutableMapOf(),
    val sessions: MutableMap<SignalProtocolStateAddress, ByteArray> = mutableMapOf(),
    val preKeys: MutableMap<SignalPreKeyId, ByteArray> = mutableMapOf(),
    val signedPreKeys: MutableMap<SignalPreKeyId, ByteArray> = mutableMapOf(),
    val kyberPreKeys: MutableMap<SignalPreKeyId, ByteArray> = mutableMapOf(),
    val consumedKyberBaseKeys: MutableSet<ConsumedKyberBaseKey> = mutableSetOf(),
    val pendingOutboundMutations: MutableMap<SignalPendingOutboundMutationKey, StoredSignalPendingOutboundMutation> =
        mutableMapOf(),
) {
    fun hasSameContentAs(other: MutableSignalState): Boolean =
        localIdentity.hasSameContentAs(other.localIdentity) &&
            remoteIdentities.hasSameArrayContentAs(other.remoteIdentities) &&
            sessions.hasSameArrayContentAs(other.sessions) &&
            preKeys.hasSameArrayContentAs(other.preKeys) &&
            signedPreKeys.hasSameArrayContentAs(other.signedPreKeys) &&
            kyberPreKeys.hasSameArrayContentAs(other.kyberPreKeys) &&
            consumedKyberBaseKeys == other.consumedKyberBaseKeys &&
            pendingOutboundMutations.hasSamePendingMutationContentAs(other.pendingOutboundMutations)

    fun deepCopy(): MutableSignalState =
        MutableSignalState(
            localIdentity =
                localIdentity?.let {
                    StoredLocalSignalIdentity.fromPersistence(it.address, it.serializedIdentityKeyPair, it.registrationId.raw)
                },
            remoteIdentities = remoteIdentities.copyArrays(),
            sessions = sessions.copyArrays(),
            preKeys = preKeys.copyArrays(),
            signedPreKeys = signedPreKeys.copyArrays(),
            kyberPreKeys = kyberPreKeys.copyArrays(),
            consumedKyberBaseKeys = consumedKyberBaseKeys.toMutableSet(),
            pendingOutboundMutations =
                pendingOutboundMutations.entries.associateTo(mutableMapOf()) { (key, mutation) ->
                    key to mutation.copyForStorage()
                },
        )

    private fun <K> Map<K, ByteArray>.copyArrays(): MutableMap<K, ByteArray> =
        entries.associateTo(mutableMapOf()) { (key, bytes) -> key to bytes.copyOf() }

    private fun StoredLocalSignalIdentity?.hasSameContentAs(other: StoredLocalSignalIdentity?): Boolean =
        when {
            this == null || other == null -> this == other
            else ->
                address == other.address &&
                    registrationId == other.registrationId &&
                    serializedIdentityKeyPair.contentEquals(other.serializedIdentityKeyPair)
        }

    private fun <K> Map<K, ByteArray>.hasSameArrayContentAs(other: Map<K, ByteArray>): Boolean =
        size == other.size && all { (key, bytes) -> other[key]?.contentEquals(bytes) == true }

    private fun Map<SignalPendingOutboundMutationKey, StoredSignalPendingOutboundMutation>.hasSamePendingMutationContentAs(
        other: Map<SignalPendingOutboundMutationKey, StoredSignalPendingOutboundMutation>,
    ): Boolean =
        size == other.size &&
            all { (key, mutation) -> other[key]?.let(mutation::hasSameContentAs) == true }
}

internal data class ConsumedKyberBaseKey(
    val kyberPreKeyId: SignalPreKeyId,
    val signedPreKeyId: SignalPreKeyId,
    val baseKeyBytes: ByteArrayKey,
)

internal class ByteArrayKey(
    bytes: ByteArray,
) {
    private val ownedBytes = bytes.copyOf()

    fun copyBytes(): ByteArray = ownedBytes.copyOf()

    override fun equals(other: Any?): Boolean = other is ByteArrayKey && ownedBytes.contentEquals(other.ownedBytes)

    override fun hashCode(): Int = ownedBytes.contentHashCode()
}

private fun requireRecordSize(
    bytes: ByteArray,
    maximumBytes: Int,
    recordName: String,
) {
    require(bytes.isNotEmpty()) { "$recordName must not be empty" }
    require(bytes.size <= maximumBytes) { "$recordName exceeds the state limit" }
}
