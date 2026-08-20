package app.synapse.privatechat.crypto

import java.util.Locale
import java.util.UUID

@JvmInline
value class SignalDeviceId private constructor(
    val raw: Int,
) {
    companion object {
        const val MIN_VALUE: Int = 1
        const val MAX_VALUE: Int = 127

        fun fromWire(raw: Int): SignalDeviceId {
            require(raw in MIN_VALUE..MAX_VALUE) {
                "Signal device ID must be between $MIN_VALUE and $MAX_VALUE"
            }
            return SignalDeviceId(raw)
        }
    }
}

@JvmInline
value class SignalRegistrationId private constructor(
    val raw: Int,
) {
    companion object {
        const val MIN_VALUE: Int = 1
        const val MAX_VALUE: Int = 16_380

        fun fromWire(raw: Int): SignalRegistrationId {
            require(raw in MIN_VALUE..MAX_VALUE) {
                "Signal registration ID must be between $MIN_VALUE and $MAX_VALUE"
            }
            return SignalRegistrationId(raw)
        }
    }
}

@JvmInline
value class SignalPreKeyId private constructor(
    val raw: Int,
) {
    companion object {
        const val MIN_VALUE: Int = 0
        const val MAX_VALUE: Int = 0xFF_FFFF

        fun fromWire(raw: Int): SignalPreKeyId {
            require(raw in MIN_VALUE..MAX_VALUE) {
                "Signal pre-key ID must be between $MIN_VALUE and $MAX_VALUE"
            }
            return SignalPreKeyId(raw)
        }
    }
}

data class SignalDeviceAddress(
    val accountId: UUID,
    val transportDeviceId: UUID,
    val protocolDeviceId: SignalDeviceId,
) {
    init {
        require(accountId != NIL_UUID) { "Account ID must not be the nil UUID" }
        require(transportDeviceId != NIL_UUID) { "Transport device ID must not be the nil UUID" }
    }

    internal val libSignalName: String
        get() = accountId.toString()

    companion object {
        private val NIL_UUID: UUID = UUID(0L, 0L)

        fun fromWire(
            accountId: String,
            transportDeviceId: String,
            protocolDeviceId: Int,
        ): SignalDeviceAddress =
            SignalDeviceAddress(
                accountId = parseCanonicalUuid(accountId, "account ID"),
                transportDeviceId = parseCanonicalUuid(transportDeviceId, "transport device ID"),
                protocolDeviceId = SignalDeviceId.fromWire(protocolDeviceId),
            )

        private fun parseCanonicalUuid(
            raw: String,
            fieldName: String,
        ): UUID {
            require(raw == raw.lowercase(Locale.ROOT)) { "$fieldName must be a lowercase canonical UUID" }
            val parsed =
                try {
                    UUID.fromString(raw)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("$fieldName must be a canonical UUID", error)
                }
            require(parsed.toString() == raw) { "$fieldName must be a canonical UUID" }
            return parsed
        }
    }
}

class SignalOneTimePreKey private constructor(
    val id: SignalPreKeyId,
    publicKeyBytes: ByteArray,
) {
    private val encodedPublicKey = publicKeyBytes.copyOf()

    val publicKeyBytes: ByteArray
        get() = encodedPublicKey.copyOf()

    companion object {
        fun fromWire(
            id: Int,
            publicKeyBytes: ByteArray,
        ): SignalOneTimePreKey =
            SignalOneTimePreKey(
                id = SignalPreKeyId.fromWire(id),
                publicKeyBytes = validateCurvePublicKey(publicKeyBytes, "one-time pre-key"),
            )
    }
}

class SignalSignedPreKey private constructor(
    val id: SignalPreKeyId,
    publicKeyBytes: ByteArray,
    signatureBytes: ByteArray,
) {
    private val encodedPublicKey = publicKeyBytes.copyOf()
    private val encodedSignature = signatureBytes.copyOf()

    val publicKeyBytes: ByteArray
        get() = encodedPublicKey.copyOf()

    val signatureBytes: ByteArray
        get() = encodedSignature.copyOf()

    companion object {
        fun fromWire(
            id: Int,
            publicKeyBytes: ByteArray,
            signatureBytes: ByteArray,
        ): SignalSignedPreKey =
            SignalSignedPreKey(
                id = SignalPreKeyId.fromWire(id),
                publicKeyBytes = validateCurvePublicKey(publicKeyBytes, "signed pre-key"),
                signatureBytes = validateSignature(signatureBytes, "signed pre-key signature"),
            )
    }
}

class SignalKyberPreKey private constructor(
    val id: SignalPreKeyId,
    publicKeyBytes: ByteArray,
    signatureBytes: ByteArray,
) {
    private val encodedPublicKey = publicKeyBytes.copyOf()
    private val encodedSignature = signatureBytes.copyOf()

    val publicKeyBytes: ByteArray
        get() = encodedPublicKey.copyOf()

    val signatureBytes: ByteArray
        get() = encodedSignature.copyOf()

    companion object {
        fun fromWire(
            id: Int,
            publicKeyBytes: ByteArray,
            signatureBytes: ByteArray,
        ): SignalKyberPreKey {
            require(publicKeyBytes.size == SignalProtocolWireLimits.KYBER_1024_PUBLIC_KEY_BYTES) {
                "Kyber-1024 public key has an invalid size"
            }
            require(publicKeyBytes.first().toInt() and 0xFF == SignalProtocolWireLimits.KYBER_1024_TYPE) {
                "Kyber public key is not a Kyber-1024 key"
            }
            return SignalKyberPreKey(
                id = SignalPreKeyId.fromWire(id),
                publicKeyBytes = publicKeyBytes,
                signatureBytes = validateSignature(signatureBytes, "Kyber pre-key signature"),
            )
        }
    }
}

class SignalPublicPreKeyBundle private constructor(
    val protocolVersion: Int,
    val address: SignalDeviceAddress,
    val registrationId: SignalRegistrationId,
    identityKeyBytes: ByteArray,
    val oneTimePreKey: SignalOneTimePreKey?,
    val signedPreKey: SignalSignedPreKey,
    val kyberPreKey: SignalKyberPreKey,
) {
    private val encodedIdentityKey = identityKeyBytes.copyOf()

    val identityKeyBytes: ByteArray
        get() = encodedIdentityKey.copyOf()

    companion object {
        const val CURRENT_PROTOCOL_VERSION: Int = 1

        fun fromWire(
            protocolVersion: Int,
            address: SignalDeviceAddress,
            registrationId: Int,
            identityKeyBytes: ByteArray,
            oneTimePreKey: SignalOneTimePreKey?,
            signedPreKey: SignalSignedPreKey,
            kyberPreKey: SignalKyberPreKey,
        ): SignalPublicPreKeyBundle {
            require(protocolVersion == CURRENT_PROTOCOL_VERSION) {
                "Unsupported public pre-key bundle version"
            }
            return SignalPublicPreKeyBundle(
                protocolVersion = protocolVersion,
                address = address,
                registrationId = SignalRegistrationId.fromWire(registrationId),
                identityKeyBytes = validateCurvePublicKey(identityKeyBytes, "identity key"),
                oneTimePreKey = oneTimePreKey,
                signedPreKey = signedPreKey,
                kyberPreKey = kyberPreKey,
            )
        }
    }
}

enum class SignalCiphertextType(
    val wireCode: Int,
    val wireName: String,
) {
    WHISPER(2, "WHISPER"),
    PREKEY(3, "PREKEY"),
    ;

    companion object {
        fun fromWire(wireCode: Int): SignalCiphertextType =
            entries.firstOrNull { it.wireCode == wireCode }
                ?: throw IllegalArgumentException("Unsupported Signal ciphertext type")

        fun fromWire(wireName: String): SignalCiphertextType =
            entries.firstOrNull { it.wireName == wireName }
                ?: throw IllegalArgumentException("Unsupported Signal ciphertext type")
    }
}

class SignalEnvelope private constructor(
    val protocolVersion: Int,
    val sender: SignalDeviceAddress,
    val recipient: SignalDeviceAddress,
    val ciphertextType: SignalCiphertextType,
    serializedCiphertext: ByteArray,
) {
    private val encodedCiphertext = serializedCiphertext.copyOf()

    val serializedCiphertext: ByteArray
        get() = encodedCiphertext.copyOf()

    companion object {
        const val CURRENT_PROTOCOL_VERSION: Int = 1

        fun fromWire(
            protocolVersion: Int,
            sender: SignalDeviceAddress,
            recipient: SignalDeviceAddress,
            ciphertextTypeCode: Int,
            serializedCiphertext: ByteArray,
        ): SignalEnvelope {
            require(protocolVersion == CURRENT_PROTOCOL_VERSION) {
                "Unsupported Signal envelope version"
            }
            require(sender.transportDeviceId != recipient.transportDeviceId) {
                "Signal envelope sender and recipient devices must differ"
            }
            require(serializedCiphertext.isNotEmpty()) { "Signal ciphertext must not be empty" }
            require(serializedCiphertext.size <= SignalProtocolWireLimits.MAX_CIPHERTEXT_BYTES) {
                "Signal ciphertext exceeds the transport limit"
            }
            return SignalEnvelope(
                protocolVersion = protocolVersion,
                sender = sender,
                recipient = recipient,
                ciphertextType = SignalCiphertextType.fromWire(ciphertextTypeCode),
                serializedCiphertext = serializedCiphertext,
            )
        }
    }
}

class PairwiseSignalFanOut(
    envelopes: List<SignalEnvelope>,
) {
    val envelopes: List<SignalEnvelope>

    init {
        require(envelopes.size <= MAX_RECIPIENT_DEVICES) {
            "Pairwise fan-out cannot exceed $MAX_RECIPIENT_DEVICES recipient devices"
        }
        this.envelopes = envelopes.toList()
        val recipientDeviceIds = this.envelopes.map { it.recipient.transportDeviceId }
        val senderDevices = this.envelopes.map(SignalEnvelope::sender)

        require(this.envelopes.isNotEmpty()) { "Pairwise fan-out must contain at least one envelope" }
        require(recipientDeviceIds.distinct().size == this.envelopes.size) {
            "Pairwise fan-out must contain exactly one envelope per recipient device"
        }
        require(senderDevices.distinct().size == 1) {
            "Pairwise fan-out envelopes must have one sender device"
        }
    }

    companion object {
        const val MAX_RECIPIENT_DEVICES: Int = 128
    }
}

@JvmInline
value class NumericSafetyNumber private constructor(
    val digits: String,
) {
    val grouped: String
        get() = digits.chunked(SAFETY_NUMBER_GROUP_SIZE).joinToString(separator = " ")

    companion object {
        private const val SAFETY_NUMBER_LENGTH: Int = 60
        private const val SAFETY_NUMBER_GROUP_SIZE: Int = 5

        fun fromLibSignal(digits: String): NumericSafetyNumber {
            require(digits.length == SAFETY_NUMBER_LENGTH && digits.all(Char::isDigit)) {
                "Safety number must contain exactly $SAFETY_NUMBER_LENGTH digits"
            }
            return NumericSafetyNumber(digits)
        }
    }
}

enum class SignalProtocolFailureKind {
    INVALID_INPUT,
    UNSUPPORTED_VERSION,
    MALFORMED_CIPHERTEXT,
    REPLAY_DETECTED,
    IDENTITY_REPLACEMENT_BLOCKED,
    SESSION_MISSING,
    PRE_KEY_MISSING,
    STATE_CORRUPTED,
    STATE_CONFLICT,
    CRYPTOGRAPHIC_OPERATION_FAILED,
}

class SignalProtocolException(
    val kind: SignalProtocolFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class AcceptVerifiedIdentityReplacementCommand(
    val address: SignalDeviceAddress,
    expectedIdentityKeyBytes: ByteArray,
    replacementIdentityKeyBytes: ByteArray,
) {
    private val encodedExpectedIdentityKey = expectedIdentityKeyBytes.copyOf()
    private val encodedReplacementIdentityKey = replacementIdentityKeyBytes.copyOf()

    val expectedIdentityKeyBytes: ByteArray
        get() = encodedExpectedIdentityKey.copyOf()

    val replacementIdentityKeyBytes: ByteArray
        get() = encodedReplacementIdentityKey.copyOf()

    init {
        validateCurvePublicKey(expectedIdentityKeyBytes, "expected identity key")
        validateCurvePublicKey(replacementIdentityKeyBytes, "replacement identity key")
        require(!expectedIdentityKeyBytes.contentEquals(replacementIdentityKeyBytes)) {
            "Replacement identity key must differ from the expected identity key"
        }
    }
}

data class LocalSignalDeviceInitializationReceipt(
    val publicPreKeyBundle: SignalPublicPreKeyBundle,
    val identityFingerprint: String,
)

data class IdentityReplacementReceipt(
    val address: SignalDeviceAddress,
    val previousIdentityFingerprint: String,
    val acceptedIdentityFingerprint: String,
    val deletedSessionCount: Int,
)

internal object SignalProtocolWireLimits {
    const val CURVE_PUBLIC_KEY_BYTES: Int = 33
    const val CURVE_PUBLIC_KEY_TYPE: Int = 0x05
    const val SIGNAL_SIGNATURE_BYTES: Int = 64
    const val KYBER_1024_PUBLIC_KEY_BYTES: Int = 1_569
    const val KYBER_1024_TYPE: Int = 0x08
    const val MAX_PLAINTEXT_BYTES: Int = 64 * 1_024
    const val MAX_CIPHERTEXT_BYTES: Int = 256 * 1_024
}

private fun validateCurvePublicKey(
    bytes: ByteArray,
    fieldName: String,
): ByteArray {
    require(bytes.size == SignalProtocolWireLimits.CURVE_PUBLIC_KEY_BYTES) {
        "$fieldName has an invalid size"
    }
    require(bytes.first().toInt() and 0xFF == SignalProtocolWireLimits.CURVE_PUBLIC_KEY_TYPE) {
        "$fieldName has an invalid key type"
    }
    return bytes.copyOf()
}

private fun validateSignature(
    bytes: ByteArray,
    fieldName: String,
): ByteArray {
    require(bytes.size == SignalProtocolWireLimits.SIGNAL_SIGNATURE_BYTES) {
        "$fieldName has an invalid size"
    }
    return bytes.copyOf()
}
