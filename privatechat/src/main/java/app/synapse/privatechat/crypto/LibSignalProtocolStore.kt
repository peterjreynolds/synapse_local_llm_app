package app.synapse.privatechat.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.util.UUID

internal class LibSignalProtocolStore(
    private val repository: SignalProtocolStateRepository,
) : IdentityKeyStore,
    SessionStore,
    PreKeyStore,
    SignedPreKeyStore,
    KyberPreKeyStore {
    override fun getIdentityKeyPair(): IdentityKeyPair {
        val storedIdentity =
            repository.loadLocalIdentity()
                ?: throw SignalProtocolStateCorruptedException("Local Signal identity is missing")
        return parseStateRecord("local identity") {
            IdentityKeyPair(storedIdentity.serializedIdentityKeyPair)
        }
    }

    override fun getLocalRegistrationId(): Int =
        repository.loadLocalIdentity()?.registrationId?.raw
            ?: throw SignalProtocolStateCorruptedException("Local Signal registration ID is missing")

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
    ): IdentityKeyStore.IdentityChange =
        when (
            repository.storeRemoteIdentityWithoutReplacement(
                address = address.toStateAddress(),
                identityKeyBytes = identityKey.serialize(),
            )
        ) {
            RemoteIdentityWriteOutcome.STORED_NEW,
            RemoteIdentityWriteOutcome.UNCHANGED,
            -> IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED

            RemoteIdentityWriteOutcome.REPLACEMENT_REJECTED ->
                throw SignalIdentityReplacementRejectedException()
        }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean {
        val trustedIdentity = repository.loadRemoteIdentity(address.toStateAddress()) ?: return true
        validateStateBytes(
            bytes = trustedIdentity,
            maximumBytes = SignalProtocolWireLimits.CURVE_PUBLIC_KEY_BYTES,
            recordName = "remote identity",
            exactSize = true,
        )
        return trustedIdentity.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val serializedIdentity = repository.loadRemoteIdentity(address.toStateAddress()) ?: return null
        validateStateBytes(
            bytes = serializedIdentity,
            maximumBytes = SignalProtocolWireLimits.CURVE_PUBLIC_KEY_BYTES,
            recordName = "remote identity",
            exactSize = true,
        )
        return parseStateRecord("remote identity") { IdentityKey(serializedIdentity) }
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord? {
        val serializedSession = repository.loadSession(address.toStateAddress()) ?: return null
        validateStateBytes(
            bytes = serializedSession,
            maximumBytes = SignalProtocolStateLimits.MAX_SESSION_RECORD_BYTES,
            recordName = "session",
        )
        return parseStateRecord("session") { SessionRecord(serializedSession) }
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { address ->
            loadSession(address)
                ?: throw NoSessionException(address, "Required Signal session is missing")
        }

    override fun getSubDeviceSessions(name: String): List<Int> {
        val accountId = parseAccountId(name)
        return repository
            .listSessionDeviceIds(accountId)
            .asSequence()
            .map(SignalDeviceId::raw)
            .filter { it != SignalDeviceId.MIN_VALUE }
            .sorted()
            .toList()
    }

    override fun storeSession(
        address: SignalProtocolAddress,
        record: SessionRecord,
    ) {
        val serializedSession = record.serialize()
        validateStateBytes(
            bytes = serializedSession,
            maximumBytes = SignalProtocolStateLimits.MAX_SESSION_RECORD_BYTES,
            recordName = "session",
        )
        repository.storeSession(address.toStateAddress(), serializedSession)
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = repository.loadSession(address.toStateAddress()) != null

    override fun deleteSession(address: SignalProtocolAddress) {
        repository.deleteSession(address.toStateAddress())
    }

    override fun deleteAllSessions(name: String) {
        repository.deleteAllSessions(parseAccountId(name))
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val typedPreKeyId = SignalPreKeyId.fromWire(preKeyId)
        val serializedPreKey =
            repository.loadPreKey(typedPreKeyId)
                ?: throw InvalidKeyIdException("Required Signal one-time pre-key is missing")
        validateStateBytes(
            bytes = serializedPreKey,
            maximumBytes = SignalProtocolStateLimits.MAX_PRE_KEY_RECORD_BYTES,
            recordName = "one-time pre-key",
        )
        return parseStateRecord("one-time pre-key") { PreKeyRecord(serializedPreKey) }
    }

    override fun storePreKey(
        preKeyId: Int,
        record: PreKeyRecord,
    ) {
        val serializedPreKey = record.serialize()
        validateStateBytes(
            bytes = serializedPreKey,
            maximumBytes = SignalProtocolStateLimits.MAX_PRE_KEY_RECORD_BYTES,
            recordName = "one-time pre-key",
        )
        repository.storePreKey(SignalPreKeyId.fromWire(preKeyId), serializedPreKey)
    }

    override fun containsPreKey(preKeyId: Int): Boolean = repository.containsPreKey(SignalPreKeyId.fromWire(preKeyId))

    override fun removePreKey(preKeyId: Int) {
        repository.deletePreKey(SignalPreKeyId.fromWire(preKeyId))
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val typedPreKeyId = SignalPreKeyId.fromWire(signedPreKeyId)
        val serializedPreKey =
            repository.loadSignedPreKey(typedPreKeyId)
                ?: throw InvalidKeyIdException("Required Signal signed pre-key is missing")
        validateStateBytes(
            bytes = serializedPreKey,
            maximumBytes = SignalProtocolStateLimits.MAX_SIGNED_PRE_KEY_RECORD_BYTES,
            recordName = "signed pre-key",
        )
        return parseStateRecord("signed pre-key") { SignedPreKeyRecord(serializedPreKey) }
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        repository.listSignedPreKeys().map { serializedPreKey ->
            validateStateBytes(
                bytes = serializedPreKey,
                maximumBytes = SignalProtocolStateLimits.MAX_SIGNED_PRE_KEY_RECORD_BYTES,
                recordName = "signed pre-key",
            )
            parseStateRecord("signed pre-key") { SignedPreKeyRecord(serializedPreKey) }
        }

    override fun storeSignedPreKey(
        signedPreKeyId: Int,
        record: SignedPreKeyRecord,
    ) {
        val serializedPreKey = record.serialize()
        validateStateBytes(
            bytes = serializedPreKey,
            maximumBytes = SignalProtocolStateLimits.MAX_SIGNED_PRE_KEY_RECORD_BYTES,
            recordName = "signed pre-key",
        )
        repository.storeSignedPreKey(SignalPreKeyId.fromWire(signedPreKeyId), serializedPreKey)
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        repository.containsSignedPreKey(SignalPreKeyId.fromWire(signedPreKeyId))

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        repository.deleteSignedPreKey(SignalPreKeyId.fromWire(signedPreKeyId))
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val typedPreKeyId = SignalPreKeyId.fromWire(kyberPreKeyId)
        val serializedPreKey =
            repository.loadKyberPreKey(typedPreKeyId)
                ?: throw InvalidKeyIdException("Required Signal Kyber pre-key is missing")
        validateStateBytes(
            bytes = serializedPreKey,
            maximumBytes = SignalProtocolStateLimits.MAX_KYBER_PRE_KEY_RECORD_BYTES,
            recordName = "Kyber pre-key",
        )
        return parseStateRecord("Kyber pre-key") { KyberPreKeyRecord(serializedPreKey) }
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        repository.listKyberPreKeys().map { serializedPreKey ->
            validateStateBytes(
                bytes = serializedPreKey,
                maximumBytes = SignalProtocolStateLimits.MAX_KYBER_PRE_KEY_RECORD_BYTES,
                recordName = "Kyber pre-key",
            )
            parseStateRecord("Kyber pre-key") { KyberPreKeyRecord(serializedPreKey) }
        }

    override fun storeKyberPreKey(
        kyberPreKeyId: Int,
        record: KyberPreKeyRecord,
    ) {
        val serializedPreKey = record.serialize()
        validateStateBytes(
            bytes = serializedPreKey,
            maximumBytes = SignalProtocolStateLimits.MAX_KYBER_PRE_KEY_RECORD_BYTES,
            recordName = "Kyber pre-key",
        )
        repository.storeKyberPreKey(SignalPreKeyId.fromWire(kyberPreKeyId), serializedPreKey)
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = repository.containsKyberPreKey(SignalPreKeyId.fromWire(kyberPreKeyId))

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ECPublicKey,
    ) {
        val recorded =
            repository.recordKyberPreKeyUse(
                kyberPreKeyId = SignalPreKeyId.fromWire(kyberPreKeyId),
                signedPreKeyId = SignalPreKeyId.fromWire(signedPreKeyId),
                baseKeyBytes = baseKey.serialize(),
            )
        if (!recorded) {
            throw ReusedBaseKeyException("Signal Kyber base key has already been consumed")
        }
    }

    private fun SignalProtocolAddress.toStateAddress(): SignalProtocolStateAddress =
        SignalProtocolStateAddress(
            accountId = parseAccountId(name),
            protocolDeviceId = SignalDeviceId.fromWire(deviceId),
        )

    private fun parseAccountId(raw: String): UUID {
        val parsed =
            try {
                UUID.fromString(raw)
            } catch (error: IllegalArgumentException) {
                throw SignalProtocolStateCorruptedException("Signal address contains an invalid account ID", error)
            }
        if (parsed.toString() != raw) {
            throw SignalProtocolStateCorruptedException("Signal address contains a non-canonical account ID")
        }
        return parsed
    }

    private fun <T> parseStateRecord(
        recordName: String,
        parser: () -> T,
    ): T =
        try {
            parser()
        } catch (error: InvalidMessageException) {
            throw SignalProtocolStateCorruptedException("Stored $recordName is malformed", error)
        } catch (error: org.signal.libsignal.protocol.InvalidKeyException) {
            throw SignalProtocolStateCorruptedException("Stored $recordName contains an invalid key", error)
        }

    private fun validateStateBytes(
        bytes: ByteArray,
        maximumBytes: Int,
        recordName: String,
        exactSize: Boolean = false,
    ) {
        val validSize =
            if (exactSize) {
                bytes.size == maximumBytes
            } else {
                bytes.isNotEmpty() && bytes.size <= maximumBytes
            }
        if (!validSize) {
            throw SignalProtocolStateCorruptedException("Stored $recordName has an invalid size")
        }
    }
}

internal class SignalIdentityReplacementRejectedException :
    IllegalStateException("Remote Signal identity changed while libsignal was saving it")
