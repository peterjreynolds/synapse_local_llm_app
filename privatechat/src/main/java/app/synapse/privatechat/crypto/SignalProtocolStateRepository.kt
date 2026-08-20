package app.synapse.privatechat.crypto

import java.util.UUID

data class SignalProtocolStateAddress(
    val accountId: UUID,
    val protocolDeviceId: SignalDeviceId,
) {
    internal val libSignalName: String
        get() = accountId.toString()

    companion object {
        fun fromDeviceAddress(address: SignalDeviceAddress): SignalProtocolStateAddress =
            SignalProtocolStateAddress(
                accountId = address.accountId,
                protocolDeviceId = address.protocolDeviceId,
            )
    }
}

class StoredLocalSignalIdentity private constructor(
    val address: SignalDeviceAddress,
    serializedIdentityKeyPair: ByteArray,
    val registrationId: SignalRegistrationId,
) {
    private val encodedIdentityKeyPair = serializedIdentityKeyPair.copyOf()

    val serializedIdentityKeyPair: ByteArray
        get() = encodedIdentityKeyPair.copyOf()

    companion object {
        fun fromPersistence(
            address: SignalDeviceAddress,
            serializedIdentityKeyPair: ByteArray,
            registrationId: Int,
        ): StoredLocalSignalIdentity {
            require(serializedIdentityKeyPair.isNotEmpty()) {
                "Serialized local identity key pair must not be empty"
            }
            require(serializedIdentityKeyPair.size <= SignalProtocolStateLimits.MAX_IDENTITY_KEY_PAIR_BYTES) {
                "Serialized local identity key pair exceeds the state limit"
            }
            return StoredLocalSignalIdentity(
                address = address,
                serializedIdentityKeyPair = serializedIdentityKeyPair,
                registrationId = SignalRegistrationId.fromWire(registrationId),
            )
        }
    }
}

enum class RemoteIdentityWriteOutcome {
    STORED_NEW,
    UNCHANGED,
    REPLACEMENT_REJECTED,
}

/**
 * Durable boundary for all private Signal Protocol state.
 *
 * Production implementations must encrypt private record bytes before persistence and provide a
 * real rollback-capable transaction. This package intentionally provides no production fallback.
 * Returned and accepted byte arrays must be treated as owned copies.
 */
interface SignalProtocolStateRepository {
    fun <T> writeTransaction(block: () -> T): T

    fun loadLocalIdentity(): StoredLocalSignalIdentity?

    fun insertLocalIdentityIfAbsent(identity: StoredLocalSignalIdentity): Boolean

    fun loadRemoteIdentity(address: SignalProtocolStateAddress): ByteArray?

    fun storeRemoteIdentityWithoutReplacement(
        address: SignalProtocolStateAddress,
        identityKeyBytes: ByteArray,
    ): RemoteIdentityWriteOutcome

    fun replaceRemoteIdentity(
        address: SignalProtocolStateAddress,
        expectedIdentityKeyBytes: ByteArray,
        replacementIdentityKeyBytes: ByteArray,
    ): Boolean

    fun loadSession(address: SignalProtocolStateAddress): ByteArray?

    fun storeSession(
        address: SignalProtocolStateAddress,
        serializedSession: ByteArray,
    )

    fun listSessionDeviceIds(accountId: UUID): List<SignalDeviceId>

    fun deleteSession(address: SignalProtocolStateAddress): Boolean

    fun deleteAllSessions(accountId: UUID): Int

    fun loadPreKey(preKeyId: SignalPreKeyId): ByteArray?

    fun storePreKey(
        preKeyId: SignalPreKeyId,
        serializedPreKey: ByteArray,
    )

    fun containsPreKey(preKeyId: SignalPreKeyId): Boolean

    fun deletePreKey(preKeyId: SignalPreKeyId): Boolean

    fun loadSignedPreKey(preKeyId: SignalPreKeyId): ByteArray?

    fun listSignedPreKeys(): List<ByteArray>

    fun storeSignedPreKey(
        preKeyId: SignalPreKeyId,
        serializedSignedPreKey: ByteArray,
    )

    fun containsSignedPreKey(preKeyId: SignalPreKeyId): Boolean

    fun deleteSignedPreKey(preKeyId: SignalPreKeyId): Boolean

    fun loadKyberPreKey(preKeyId: SignalPreKeyId): ByteArray?

    fun listKyberPreKeys(): List<ByteArray>

    fun storeKyberPreKey(
        preKeyId: SignalPreKeyId,
        serializedKyberPreKey: ByteArray,
    )

    fun containsKyberPreKey(preKeyId: SignalPreKeyId): Boolean

    /** Returns false when this exact Kyber/signed/base-key tuple was already consumed. */
    fun recordKyberPreKeyUse(
        kyberPreKeyId: SignalPreKeyId,
        signedPreKeyId: SignalPreKeyId,
        baseKeyBytes: ByteArray,
    ): Boolean
}

internal object SignalProtocolStateLimits {
    const val MAX_IDENTITY_KEY_PAIR_BYTES: Int = 256
    const val MAX_SESSION_RECORD_BYTES: Int = 1024 * 1_024
    const val MAX_PRE_KEY_RECORD_BYTES: Int = 512
    const val MAX_SIGNED_PRE_KEY_RECORD_BYTES: Int = 1_024
    const val MAX_KYBER_PRE_KEY_RECORD_BYTES: Int = 16 * 1_024
}

internal class SignalProtocolStateCorruptedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
