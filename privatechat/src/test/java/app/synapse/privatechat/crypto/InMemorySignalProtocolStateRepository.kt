package app.synapse.privatechat.crypto

import java.util.UUID

/** Deterministic state repository for JVM tests only. */
internal class InMemorySignalProtocolStateRepository : SignalProtocolStateRepository {
    private val monitor = Any()
    private var localIdentity: StoredLocalSignalIdentity? = null
    private var remoteIdentities = mutableMapOf<SignalProtocolStateAddress, ByteArray>()
    private var sessions = mutableMapOf<SignalProtocolStateAddress, ByteArray>()
    private var preKeys = mutableMapOf<SignalPreKeyId, ByteArray>()
    private var signedPreKeys = mutableMapOf<SignalPreKeyId, ByteArray>()
    private var kyberPreKeys = mutableMapOf<SignalPreKeyId, ByteArray>()
    private var consumedKyberBaseKeys = mutableSetOf<ConsumedKyberBaseKey>()
    private var identityToStoreBeforeNextRemoteIdentityWrite: PendingRemoteIdentityWrite? = null

    override fun <T> writeTransaction(block: () -> T): T =
        synchronized(monitor) {
            val snapshot = snapshot()
            try {
                block()
            } catch (error: Throwable) {
                restore(snapshot)
                throw error
            }
        }

    override fun loadLocalIdentity(): StoredLocalSignalIdentity? =
        synchronized(monitor) {
            localIdentity?.copyForRead()
        }

    override fun insertLocalIdentityIfAbsent(identity: StoredLocalSignalIdentity): Boolean =
        synchronized(monitor) {
            if (localIdentity != null) {
                false
            } else {
                localIdentity = identity.copyForRead()
                true
            }
        }

    override fun loadRemoteIdentity(address: SignalProtocolStateAddress): ByteArray? =
        synchronized(monitor) {
            remoteIdentities[address]?.copyOf()
        }

    override fun storeRemoteIdentityWithoutReplacement(
        address: SignalProtocolStateAddress,
        identityKeyBytes: ByteArray,
    ): RemoteIdentityWriteOutcome =
        synchronized(monitor) {
            identityToStoreBeforeNextRemoteIdentityWrite?.let { pendingWrite ->
                check(pendingWrite.address == address) {
                    "Forced remote identity race targeted an unexpected address"
                }
                remoteIdentities[address] = pendingWrite.identityKeyBytes.copyOf()
                identityToStoreBeforeNextRemoteIdentityWrite = null
            }
            val existingIdentity = remoteIdentities[address]
            when {
                existingIdentity == null -> {
                    remoteIdentities[address] = identityKeyBytes.copyOf()
                    RemoteIdentityWriteOutcome.STORED_NEW
                }

                existingIdentity.contentEquals(identityKeyBytes) -> RemoteIdentityWriteOutcome.UNCHANGED
                else -> RemoteIdentityWriteOutcome.REPLACEMENT_REJECTED
            }
        }

    fun simulateConcurrentIdentityWriteBeforeNextStore(
        address: SignalProtocolStateAddress,
        identityKeyBytes: ByteArray,
    ) {
        synchronized(monitor) {
            check(identityToStoreBeforeNextRemoteIdentityWrite == null) {
                "A forced remote identity race is already pending"
            }
            identityToStoreBeforeNextRemoteIdentityWrite =
                PendingRemoteIdentityWrite(
                    address = address,
                    identityKeyBytes = identityKeyBytes.copyOf(),
                )
        }
    }

    override fun replaceRemoteIdentity(
        address: SignalProtocolStateAddress,
        expectedIdentityKeyBytes: ByteArray,
        replacementIdentityKeyBytes: ByteArray,
    ): Boolean =
        synchronized(monitor) {
            val existingIdentity = remoteIdentities[address]
            if (existingIdentity?.contentEquals(expectedIdentityKeyBytes) != true) {
                false
            } else {
                remoteIdentities[address] = replacementIdentityKeyBytes.copyOf()
                true
            }
        }

    override fun loadSession(address: SignalProtocolStateAddress): ByteArray? =
        synchronized(monitor) {
            sessions[address]?.copyOf()
        }

    override fun storeSession(
        address: SignalProtocolStateAddress,
        serializedSession: ByteArray,
    ) {
        synchronized(monitor) {
            sessions[address] = serializedSession.copyOf()
        }
    }

    override fun listSessionDeviceIds(accountId: UUID): List<SignalDeviceId> =
        synchronized(monitor) {
            sessions.keys
                .asSequence()
                .filter { it.accountId == accountId }
                .map(SignalProtocolStateAddress::protocolDeviceId)
                .distinct()
                .sortedBy(SignalDeviceId::raw)
                .toList()
        }

    override fun deleteSession(address: SignalProtocolStateAddress): Boolean =
        synchronized(monitor) {
            sessions.remove(address) != null
        }

    override fun deleteAllSessions(accountId: UUID): Int =
        synchronized(monitor) {
            val matchingAddresses = sessions.keys.filter { it.accountId == accountId }
            matchingAddresses.forEach(sessions::remove)
            matchingAddresses.size
        }

    override fun loadPreKey(preKeyId: SignalPreKeyId): ByteArray? =
        synchronized(monitor) {
            preKeys[preKeyId]?.copyOf()
        }

    override fun storePreKey(
        preKeyId: SignalPreKeyId,
        serializedPreKey: ByteArray,
    ) {
        synchronized(monitor) {
            preKeys[preKeyId] = serializedPreKey.copyOf()
        }
    }

    override fun containsPreKey(preKeyId: SignalPreKeyId): Boolean =
        synchronized(monitor) {
            preKeys.containsKey(preKeyId)
        }

    override fun deletePreKey(preKeyId: SignalPreKeyId): Boolean =
        synchronized(monitor) {
            preKeys.remove(preKeyId) != null
        }

    override fun loadSignedPreKey(preKeyId: SignalPreKeyId): ByteArray? =
        synchronized(monitor) {
            signedPreKeys[preKeyId]?.copyOf()
        }

    override fun listSignedPreKeys(): List<ByteArray> =
        synchronized(monitor) {
            signedPreKeys.toSortedMap(compareBy(SignalPreKeyId::raw)).values.map(ByteArray::copyOf)
        }

    override fun storeSignedPreKey(
        preKeyId: SignalPreKeyId,
        serializedSignedPreKey: ByteArray,
    ) {
        synchronized(monitor) {
            signedPreKeys[preKeyId] = serializedSignedPreKey.copyOf()
        }
    }

    override fun containsSignedPreKey(preKeyId: SignalPreKeyId): Boolean =
        synchronized(monitor) {
            signedPreKeys.containsKey(preKeyId)
        }

    override fun deleteSignedPreKey(preKeyId: SignalPreKeyId): Boolean =
        synchronized(monitor) {
            signedPreKeys.remove(preKeyId) != null
        }

    override fun loadKyberPreKey(preKeyId: SignalPreKeyId): ByteArray? =
        synchronized(monitor) {
            kyberPreKeys[preKeyId]?.copyOf()
        }

    override fun listKyberPreKeys(): List<ByteArray> =
        synchronized(monitor) {
            kyberPreKeys.toSortedMap(compareBy(SignalPreKeyId::raw)).values.map(ByteArray::copyOf)
        }

    override fun storeKyberPreKey(
        preKeyId: SignalPreKeyId,
        serializedKyberPreKey: ByteArray,
    ) {
        synchronized(monitor) {
            kyberPreKeys[preKeyId] = serializedKyberPreKey.copyOf()
        }
    }

    override fun containsKyberPreKey(preKeyId: SignalPreKeyId): Boolean =
        synchronized(monitor) {
            kyberPreKeys.containsKey(preKeyId)
        }

    override fun recordKyberPreKeyUse(
        kyberPreKeyId: SignalPreKeyId,
        signedPreKeyId: SignalPreKeyId,
        baseKeyBytes: ByteArray,
    ): Boolean =
        synchronized(monitor) {
            consumedKyberBaseKeys.add(
                ConsumedKyberBaseKey(
                    kyberPreKeyId = kyberPreKeyId,
                    signedPreKeyId = signedPreKeyId,
                    baseKeyBytes = ByteArrayKey(baseKeyBytes),
                ),
            )
        }

    private fun StoredLocalSignalIdentity.copyForRead(): StoredLocalSignalIdentity =
        StoredLocalSignalIdentity.fromPersistence(
            address = address,
            serializedIdentityKeyPair = serializedIdentityKeyPair,
            registrationId = registrationId.raw,
        )

    private fun snapshot(): RepositorySnapshot =
        RepositorySnapshot(
            localIdentity = localIdentity?.copyForRead(),
            remoteIdentities = remoteIdentities.copyByteArrays(),
            sessions = sessions.copyByteArrays(),
            preKeys = preKeys.copyByteArrays(),
            signedPreKeys = signedPreKeys.copyByteArrays(),
            kyberPreKeys = kyberPreKeys.copyByteArrays(),
            consumedKyberBaseKeys = consumedKyberBaseKeys.toMutableSet(),
        )

    private fun restore(snapshot: RepositorySnapshot) {
        localIdentity = snapshot.localIdentity
        remoteIdentities = snapshot.remoteIdentities
        sessions = snapshot.sessions
        preKeys = snapshot.preKeys
        signedPreKeys = snapshot.signedPreKeys
        kyberPreKeys = snapshot.kyberPreKeys
        consumedKyberBaseKeys = snapshot.consumedKyberBaseKeys
    }

    private fun <K> Map<K, ByteArray>.copyByteArrays(): MutableMap<K, ByteArray> =
        entries.associateTo(mutableMapOf()) { (key, bytes) -> key to bytes.copyOf() }

    private data class RepositorySnapshot(
        val localIdentity: StoredLocalSignalIdentity?,
        val remoteIdentities: MutableMap<SignalProtocolStateAddress, ByteArray>,
        val sessions: MutableMap<SignalProtocolStateAddress, ByteArray>,
        val preKeys: MutableMap<SignalPreKeyId, ByteArray>,
        val signedPreKeys: MutableMap<SignalPreKeyId, ByteArray>,
        val kyberPreKeys: MutableMap<SignalPreKeyId, ByteArray>,
        val consumedKyberBaseKeys: MutableSet<ConsumedKyberBaseKey>,
    )

    private data class ConsumedKyberBaseKey(
        val kyberPreKeyId: SignalPreKeyId,
        val signedPreKeyId: SignalPreKeyId,
        val baseKeyBytes: ByteArrayKey,
    )

    private data class PendingRemoteIdentityWrite(
        val address: SignalProtocolStateAddress,
        val identityKeyBytes: ByteArray,
    )

    private class ByteArrayKey(
        bytes: ByteArray,
    ) {
        private val ownedBytes = bytes.copyOf()

        override fun equals(other: Any?): Boolean = other is ByteArrayKey && ownedBytes.contentEquals(other.ownedBytes)

        override fun hashCode(): Int = ownedBytes.contentHashCode()
    }
}
