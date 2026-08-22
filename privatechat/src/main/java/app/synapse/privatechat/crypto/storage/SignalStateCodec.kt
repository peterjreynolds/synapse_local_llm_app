package app.synapse.privatechat.crypto.storage

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalPreKeyId
import app.synapse.privatechat.crypto.SignalProtocolStateAddress
import app.synapse.privatechat.crypto.SignalProtocolStateCorruptedException
import app.synapse.privatechat.crypto.StoredLocalSignalIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

internal object SignalStateCodec {
    private const val MAGIC = 0x53505331
    private const val VERSION = 2
    private const val MAX_TOTAL_ENTRIES = 100_000
    internal const val MAX_TOTAL_PLAINTEXT_BYTES = 64 * 1_024 * 1_024
    private const val MAX_IDENTITY_BYTES = 256
    private const val MAX_SESSION_BYTES = 1024 * 1_024
    private const val MAX_PRE_KEY_BYTES = 512
    private const val MAX_SIGNED_PRE_KEY_BYTES = 1_024
    private const val MAX_KYBER_PRE_KEY_BYTES = 16 * 1_024
    private const val MAX_BASE_KEY_BYTES = 256

    fun encode(state: MutableSignalState): ByteArray {
        val output = BoundedByteArrayOutputStream(MAX_TOTAL_PLAINTEXT_BYTES)
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(state.totalEntryCount())
            data.writeBoolean(state.localIdentity != null)
            state.localIdentity?.let { identity ->
                data.writeDeviceAddress(identity.address)
                data.writeBoundedBytes(identity.serializedIdentityKeyPair, MAX_IDENTITY_BYTES)
                data.writeInt(identity.registrationId.raw)
            }
            data.writeAddressMap(state.remoteIdentities, MAX_IDENTITY_BYTES)
            data.writeAddressMap(state.sessions, MAX_SESSION_BYTES)
            data.writePreKeyMap(state.preKeys, MAX_PRE_KEY_BYTES)
            data.writePreKeyMap(state.signedPreKeys, MAX_SIGNED_PRE_KEY_BYTES)
            data.writePreKeyMap(state.kyberPreKeys, MAX_KYBER_PRE_KEY_BYTES)
            data.writeInt(state.consumedKyberBaseKeys.size)
            state.consumedKyberBaseKeys
                .sortedWith(
                    compareBy<ConsumedKyberBaseKey>({ it.kyberPreKeyId.raw }, { it.signedPreKeyId.raw }),
                ).forEach { consumed ->
                    data.writeInt(consumed.kyberPreKeyId.raw)
                    data.writeInt(consumed.signedPreKeyId.raw)
                    data.writeBoundedBytes(consumed.baseKeyBytes.copyBytes(), MAX_BASE_KEY_BYTES)
                }
        }
        return output.toByteArray().also {
            require(it.size <= MAX_TOTAL_PLAINTEXT_BYTES) { "Signal state exceeds the size limit" }
        }
    }

    fun decode(bytes: ByteArray): MutableSignalState {
        if (bytes.size > MAX_TOTAL_PLAINTEXT_BYTES) corrupt("Signal state exceeds the size limit")
        try {
            val input = ByteArrayInputStream(bytes)
            val data = DataInputStream(input)
            if (data.readInt() != MAGIC) corrupt("Signal state header is invalid")
            if (data.readInt() != VERSION) corrupt("Signal state version is unsupported")
            val entryBudget = DecodedEntryBudget(data.readBoundedCount())
            val localIdentity =
                if (data.readBoolean()) {
                    val address = data.readDeviceAddress()
                    StoredLocalSignalIdentity.fromPersistence(
                        address,
                        data.readBoundedBytes(MAX_IDENTITY_BYTES),
                        data.readInt(),
                    )
                } else {
                    null
                }
            val state =
                MutableSignalState(
                    localIdentity = localIdentity,
                    remoteIdentities = data.readAddressMap(MAX_IDENTITY_BYTES, entryBudget),
                    sessions = data.readAddressMap(MAX_SESSION_BYTES, entryBudget),
                    preKeys = data.readPreKeyMap(MAX_PRE_KEY_BYTES, entryBudget),
                    signedPreKeys = data.readPreKeyMap(MAX_SIGNED_PRE_KEY_BYTES, entryBudget),
                    kyberPreKeys = data.readPreKeyMap(MAX_KYBER_PRE_KEY_BYTES, entryBudget),
                    consumedKyberBaseKeys = data.readConsumedKyberKeys(entryBudget),
                )
            entryBudget.requireExhausted()
            if (input.available() != 0) corrupt("Signal state contains trailing bytes")
            return state
        } catch (error: SignalProtocolStateCorruptedException) {
            throw error
        } catch (error: Exception) {
            throw SignalProtocolStateCorruptedException("Signal state is malformed", error)
        }
    }

    private fun DataOutputStream.writeDeviceAddress(address: SignalDeviceAddress) {
        writeUuid(address.accountId)
        writeUuid(address.transportDeviceId)
        writeInt(address.protocolDeviceId.raw)
    }

    private fun DataInputStream.readDeviceAddress(): SignalDeviceAddress =
        SignalDeviceAddress(
            accountId = readUuid(),
            transportDeviceId = readUuid(),
            protocolDeviceId =
                app.synapse.privatechat.crypto.SignalDeviceId
                    .fromWire(readInt()),
        )

    private fun DataOutputStream.writeStateAddress(address: SignalProtocolStateAddress) {
        writeUuid(address.accountId)
        writeInt(address.protocolDeviceId.raw)
    }

    private fun DataInputStream.readStateAddress(): SignalProtocolStateAddress =
        SignalProtocolStateAddress(
            accountId = readUuid(),
            protocolDeviceId =
                app.synapse.privatechat.crypto.SignalDeviceId
                    .fromWire(readInt()),
        )

    private fun DataOutputStream.writeUuid(uuid: UUID) {
        writeLong(uuid.mostSignificantBits)
        writeLong(uuid.leastSignificantBits)
    }

    private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

    private fun DataOutputStream.writeBoundedBytes(
        bytes: ByteArray,
        maximumBytes: Int,
    ) {
        require(bytes.isNotEmpty() && bytes.size <= maximumBytes) { "Signal state record size is invalid" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedBytes(maximumBytes: Int): ByteArray {
        val size = readInt()
        if (size !in 1..maximumBytes) corrupt("Signal state record size is invalid")
        return ByteArray(size).also(::readFully)
    }

    private fun DataOutputStream.writeAddressMap(
        map: Map<SignalProtocolStateAddress, ByteArray>,
        maximumBytes: Int,
    ) {
        writeInt(map.size)
        map.toSortedMap(compareBy({ it.accountId }, { it.protocolDeviceId.raw })).forEach { (address, bytes) ->
            writeStateAddress(address)
            writeBoundedBytes(bytes, maximumBytes)
        }
    }

    private fun DataInputStream.readAddressMap(
        maximumBytes: Int,
        entryBudget: DecodedEntryBudget,
    ): MutableMap<SignalProtocolStateAddress, ByteArray> {
        val count = readBoundedCount()
        entryBudget.claim(count)
        return buildMap<SignalProtocolStateAddress, ByteArray>(count) {
            repeat(count) {
                val previous = put(readStateAddress(), readBoundedBytes(maximumBytes))
                if (previous != null) corrupt("Signal state contains a duplicate address")
            }
        }.toMutableMap()
    }

    private fun DataOutputStream.writePreKeyMap(
        map: Map<SignalPreKeyId, ByteArray>,
        maximumBytes: Int,
    ) {
        writeInt(map.size)
        map.toSortedMap(compareBy(SignalPreKeyId::raw)).forEach { (id, bytes) ->
            writeInt(id.raw)
            writeBoundedBytes(bytes, maximumBytes)
        }
    }

    private fun DataInputStream.readPreKeyMap(
        maximumBytes: Int,
        entryBudget: DecodedEntryBudget,
    ): MutableMap<SignalPreKeyId, ByteArray> {
        val count = readBoundedCount()
        entryBudget.claim(count)
        return buildMap<SignalPreKeyId, ByteArray>(count) {
            repeat(count) {
                val previous = put(SignalPreKeyId.fromWire(readInt()), readBoundedBytes(maximumBytes))
                if (previous != null) corrupt("Signal state contains a duplicate pre-key")
            }
        }.toMutableMap()
    }

    private fun DataInputStream.readConsumedKyberKeys(entryBudget: DecodedEntryBudget): MutableSet<ConsumedKyberBaseKey> {
        val count = readBoundedCount()
        entryBudget.claim(count)
        return buildSet(count) {
            repeat(count) {
                val added =
                    add(
                        ConsumedKyberBaseKey(
                            SignalPreKeyId.fromWire(readInt()),
                            SignalPreKeyId.fromWire(readInt()),
                            ByteArrayKey(readBoundedBytes(MAX_BASE_KEY_BYTES)),
                        ),
                    )
                if (!added) corrupt("Signal state contains a duplicate consumed Kyber key")
            }
        }.toMutableSet()
    }

    private fun DataInputStream.readBoundedCount(): Int {
        val count = readInt()
        if (count !in 0..MAX_TOTAL_ENTRIES) corrupt("Signal state record count is invalid")
        return count
    }

    private fun MutableSignalState.totalEntryCount(): Int {
        val count =
            listOf(
                remoteIdentities.size,
                sessions.size,
                preKeys.size,
                signedPreKeys.size,
                kyberPreKeys.size,
                consumedKyberBaseKeys.size,
            ).sumOf { sectionEntries -> sectionEntries.toLong() }
        require(count <= MAX_TOTAL_ENTRIES) { "Too many Signal state records" }
        return count.toInt()
    }

    private class DecodedEntryBudget(
        private var remainingEntries: Int,
    ) {
        fun claim(count: Int) {
            if (count > remainingEntries) corrupt("Signal state record count exceeds its declared total")
            remainingEntries -= count
        }

        fun requireExhausted() {
            if (remainingEntries != 0) corrupt("Signal state record count does not match its declared total")
        }
    }

    private fun corrupt(message: String): Nothing = throw SignalProtocolStateCorruptedException(message)
}

private class BoundedByteArrayOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    @Synchronized
    override fun write(value: Int) {
        requireCapacityFor(1)
        super.write(value)
    }

    @Synchronized
    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        requireCapacityFor(length)
        super.write(bytes, offset, length)
    }

    private fun requireCapacityFor(additionalBytes: Int) {
        require(additionalBytes >= 0 && count <= maximumBytes - additionalBytes) {
            "Signal state exceeds the size limit"
        }
    }
}
