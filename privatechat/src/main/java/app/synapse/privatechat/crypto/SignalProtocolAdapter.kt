package app.synapse.privatechat.crypto

import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant

/** Narrow adapter over the pinned libsignal 0.101.0 public API. */
class SignalProtocolAdapter(
    private val localAddress: SignalDeviceAddress,
    private val stateRepository: SignalProtocolStateRepository,
) {
    private val libSignalStore = LibSignalProtocolStore(stateRepository)
    private val secureRandom = SecureRandom()
    private val operationMonitor = Any()

    fun initializeLocalDevice(generatedAt: Instant = Instant.now()): LocalSignalDeviceInitializationReceipt =
        synchronized(operationMonitor) {
            classifiedOperation {
                validateGenerationTime(generatedAt)
                stateRepository.writeTransaction {
                    if (stateRepository.loadLocalIdentity() != null) {
                        throw SignalProtocolException(
                            kind = SignalProtocolFailureKind.STATE_CONFLICT,
                            message = "A local Signal identity already exists",
                        )
                    }

                    val identityKeyPair = IdentityKeyPair.generate()
                    val registrationId = KeyHelper.generateRegistrationId(false)
                    val storedIdentity =
                        StoredLocalSignalIdentity.fromPersistence(
                            address = localAddress,
                            serializedIdentityKeyPair = identityKeyPair.serialize(),
                            registrationId = registrationId,
                        )
                    if (!stateRepository.insertLocalIdentityIfAbsent(storedIdentity)) {
                        throw SignalProtocolException(
                            kind = SignalProtocolFailureKind.STATE_CONFLICT,
                            message = "A local Signal identity was created concurrently",
                        )
                    }

                    val publicBundle = generatePublicPreKeyBundleWithinTransaction(generatedAt)
                    LocalSignalDeviceInitializationReceipt(
                        publicPreKeyBundle = publicBundle,
                        identityFingerprint = identityKeyPair.publicKey.fingerprint,
                    )
                }
            }
        }

    fun generatePublicPreKeyBundle(generatedAt: Instant = Instant.now()): SignalPublicPreKeyBundle =
        synchronized(operationMonitor) {
            classifiedOperation {
                validateGenerationTime(generatedAt)
                stateRepository.writeTransaction {
                    requireBoundLocalIdentity()
                    generatePublicPreKeyBundleWithinTransaction(generatedAt)
                }
            }
        }

    fun establishPairwiseSession(remoteBundle: SignalPublicPreKeyBundle) {
        synchronized(operationMonitor) {
            classifiedOperation {
                requireDistinctProtocolAddress(remoteBundle.address)
                requireBoundLocalIdentity()
                stateRepository.writeTransaction {
                    val remoteAddress = remoteBundle.address.toLibSignalAddress()
                    val builder =
                        SessionBuilder(
                            libSignalStore,
                            libSignalStore,
                            libSignalStore,
                            libSignalStore,
                            remoteAddress,
                            localAddress.toLibSignalAddress(),
                        )
                    builder.process(remoteBundle.toLibSignalPreKeyBundle())
                    require(libSignalStore.containsSession(remoteAddress)) {
                        "libsignal did not persist the established session"
                    }
                }
            }
        }
    }

    fun encryptForDevice(
        recipient: SignalDeviceAddress,
        plaintext: ByteArray,
    ): SignalEnvelope =
        synchronized(operationMonitor) {
            validatePlaintext(plaintext)
            classifiedOperation {
                requireBoundLocalIdentity()
                requireDistinctProtocolAddress(recipient)
                stateRepository.writeTransaction {
                    encryptForDeviceWithinTransaction(recipient, plaintext.copyOf())
                }
            }
        }

    fun encryptForRecipientDevices(
        recipients: List<SignalDeviceAddress>,
        plaintext: ByteArray,
    ): PairwiseSignalFanOut =
        synchronized(operationMonitor) {
            validatePlaintext(plaintext)
            requireValidFanOutRecipientCount(recipients.size)
            val immutableRecipients = recipients.toList()
            requireValidFanOutRecipients(immutableRecipients)
            classifiedOperation {
                requireBoundLocalIdentity()
                stateRepository.writeTransaction {
                    PairwiseSignalFanOut(
                        immutableRecipients.map { recipient ->
                            encryptForDeviceWithinTransaction(recipient, plaintext.copyOf())
                        },
                    )
                }
            }
        }

    fun decryptFromDevice(envelope: SignalEnvelope): ByteArray =
        synchronized(operationMonitor) {
            if (envelope.recipient != localAddress) {
                throw SignalProtocolException(
                    kind = SignalProtocolFailureKind.INVALID_INPUT,
                    message = "Signal envelope is addressed to a different local device",
                )
            }
            requireDistinctProtocolAddress(envelope.sender)
            classifiedOperation {
                requireBoundLocalIdentity()
                stateRepository.writeTransaction {
                    val cipher = createSessionCipher(envelope.sender)
                    val plaintext =
                        when (envelope.ciphertextType) {
                            SignalCiphertextType.PREKEY -> {
                                val message = PreKeySignalMessage(envelope.serializedCiphertext)
                                requireCurrentLibSignalVersion(message.messageVersion)
                                cipher.decrypt(message)
                            }

                            SignalCiphertextType.WHISPER -> {
                                val message = SignalMessage(envelope.serializedCiphertext)
                                requireCurrentLibSignalVersion(message.messageVersion)
                                cipher.decrypt(message)
                            }
                        }
                    if (plaintext.isEmpty() || plaintext.size > SignalProtocolWireLimits.MAX_PLAINTEXT_BYTES) {
                        throw SignalProtocolException(
                            kind = SignalProtocolFailureKind.INVALID_INPUT,
                            message = "Decrypted Signal plaintext violates the message size contract",
                        )
                    }
                    plaintext
                }
            }
        }

    fun safetyNumberFor(remoteAddress: SignalDeviceAddress): NumericSafetyNumber =
        synchronized(operationMonitor) {
            classifiedOperation {
                requireDistinctProtocolAddress(remoteAddress)
                stateRepository.writeTransaction {
                    requireBoundLocalIdentity()
                    val remoteIdentity =
                        libSignalStore.getIdentity(remoteAddress.toLibSignalAddress())
                            ?: throw SignalProtocolException(
                                kind = SignalProtocolFailureKind.STATE_CONFLICT,
                                message = "Remote identity must be trusted before a safety number is generated",
                            )
                    val fingerprint =
                        NumericFingerprintGenerator(SAFETY_NUMBER_ITERATIONS).createFor(
                            SAFETY_NUMBER_VERSION,
                            localAddress.accountId.toString().toByteArray(StandardCharsets.UTF_8),
                            libSignalStore.identityKeyPair.publicKey,
                            remoteAddress.accountId.toString().toByteArray(StandardCharsets.UTF_8),
                            remoteIdentity,
                        )
                    NumericSafetyNumber.fromLibSignal(
                        fingerprint.displayableFingerprint.displayText,
                    )
                }
            }
        }

    fun acceptVerifiedIdentityReplacement(command: AcceptVerifiedIdentityReplacementCommand): IdentityReplacementReceipt =
        synchronized(operationMonitor) {
            classifiedOperation {
                requireDistinctProtocolAddress(command.address)
                IdentityKey(command.expectedIdentityKeyBytes)
                val replacementIdentity = IdentityKey(command.replacementIdentityKeyBytes)
                stateRepository.writeTransaction {
                    val stateAddress = command.address.toStateAddress()
                    val currentIdentity =
                        libSignalStore
                            .getIdentity(command.address.toLibSignalAddress())
                            ?.serialize()
                            ?: throw SignalProtocolException(
                                kind = SignalProtocolFailureKind.STATE_CONFLICT,
                                message = "Remote identity does not exist",
                            )
                    if (!currentIdentity.contentEquals(command.expectedIdentityKeyBytes)) {
                        throw SignalProtocolException(
                            kind = SignalProtocolFailureKind.STATE_CONFLICT,
                            message = "Remote identity changed before acceptance",
                        )
                    }
                    if (
                        !stateRepository.replaceRemoteIdentity(
                            address = stateAddress,
                            expectedIdentityKeyBytes = currentIdentity,
                            replacementIdentityKeyBytes = command.replacementIdentityKeyBytes,
                        )
                    ) {
                        throw SignalProtocolException(
                            kind = SignalProtocolFailureKind.STATE_CONFLICT,
                            message = "Remote identity replacement was not persisted",
                        )
                    }
                    val deletedSessionCount = if (stateRepository.deleteSession(stateAddress)) 1 else 0
                    IdentityReplacementReceipt(
                        address = command.address,
                        previousIdentityFingerprint = IdentityKey(currentIdentity).fingerprint,
                        acceptedIdentityFingerprint = replacementIdentity.fingerprint,
                        deletedSessionCount = deletedSessionCount,
                    )
                }
            }
        }

    private fun generatePublicPreKeyBundleWithinTransaction(generatedAt: Instant): SignalPublicPreKeyBundle {
        val identityKeyPair = libSignalStore.identityKeyPair
        val oneTimePreKeyId = allocatePreKeyId(libSignalStore::containsPreKey)
        val signedPreKeyId = allocatePreKeyId(libSignalStore::containsSignedPreKey)
        val kyberPreKeyId = allocatePreKeyId(libSignalStore::containsKyberPreKey)

        val oneTimePreKeyPair = ECKeyPair.generate()
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature =
            identityKeyPair.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize())
        val kyberPreKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPreKeySignature =
            identityKeyPair.privateKey.calculateSignature(kyberPreKeyPair.publicKey.serialize())

        libSignalStore.storePreKey(
            oneTimePreKeyId.raw,
            PreKeyRecord(oneTimePreKeyId.raw, oneTimePreKeyPair),
        )
        libSignalStore.storeSignedPreKey(
            signedPreKeyId.raw,
            SignedPreKeyRecord(
                signedPreKeyId.raw,
                generatedAt.toEpochMilli(),
                signedPreKeyPair,
                signedPreKeySignature,
            ),
        )
        libSignalStore.storeKyberPreKey(
            kyberPreKeyId.raw,
            KyberPreKeyRecord(
                kyberPreKeyId.raw,
                generatedAt.toEpochMilli(),
                kyberPreKeyPair,
                kyberPreKeySignature,
            ),
        )

        return SignalPublicPreKeyBundle.fromWire(
            protocolVersion = SignalPublicPreKeyBundle.CURRENT_PROTOCOL_VERSION,
            address = localAddress,
            registrationId = libSignalStore.localRegistrationId,
            identityKeyBytes = identityKeyPair.publicKey.serialize(),
            oneTimePreKey =
                SignalOneTimePreKey.fromWire(
                    id = oneTimePreKeyId.raw,
                    publicKeyBytes = oneTimePreKeyPair.publicKey.serialize(),
                ),
            signedPreKey =
                SignalSignedPreKey.fromWire(
                    id = signedPreKeyId.raw,
                    publicKeyBytes = signedPreKeyPair.publicKey.serialize(),
                    signatureBytes = signedPreKeySignature,
                ),
            kyberPreKey =
                SignalKyberPreKey.fromWire(
                    id = kyberPreKeyId.raw,
                    publicKeyBytes = kyberPreKeyPair.publicKey.serialize(),
                    signatureBytes = kyberPreKeySignature,
                ),
        )
    }

    private fun encryptForDeviceWithinTransaction(
        recipient: SignalDeviceAddress,
        plaintext: ByteArray,
    ): SignalEnvelope {
        val ciphertextMessage = createSessionCipher(recipient).encrypt(plaintext)
        val ciphertextType =
            when (ciphertextMessage.type) {
                CiphertextMessage.PREKEY_TYPE -> SignalCiphertextType.PREKEY
                CiphertextMessage.WHISPER_TYPE -> SignalCiphertextType.WHISPER
                else ->
                    throw SignalProtocolException(
                        kind = SignalProtocolFailureKind.CRYPTOGRAPHIC_OPERATION_FAILED,
                        message = "libsignal returned an unsupported ciphertext type",
                    )
            }
        val serializedCiphertext = ciphertextMessage.serialize()
        validateSerializedCiphertext(ciphertextType, serializedCiphertext)
        return SignalEnvelope.fromWire(
            protocolVersion = SignalEnvelope.CURRENT_PROTOCOL_VERSION,
            sender = localAddress,
            recipient = recipient,
            ciphertextTypeCode = ciphertextType.wireCode,
            serializedCiphertext = serializedCiphertext,
        )
    }

    private fun validateSerializedCiphertext(
        ciphertextType: SignalCiphertextType,
        serializedCiphertext: ByteArray,
    ) {
        val messageVersion =
            when (ciphertextType) {
                SignalCiphertextType.PREKEY -> PreKeySignalMessage(serializedCiphertext).messageVersion
                SignalCiphertextType.WHISPER -> SignalMessage(serializedCiphertext).messageVersion
            }
        requireCurrentLibSignalVersion(messageVersion)
    }

    private fun createSessionCipher(remoteAddress: SignalDeviceAddress): SessionCipher =
        SessionCipher(
            libSignalStore,
            libSignalStore,
            libSignalStore,
            libSignalStore,
            libSignalStore,
            localAddress.toLibSignalAddress(),
            remoteAddress.toLibSignalAddress(),
        )

    private fun SignalPublicPreKeyBundle.toLibSignalPreKeyBundle(): PreKeyBundle {
        val optionalPreKey = oneTimePreKey
        return PreKeyBundle(
            registrationId.raw,
            address.protocolDeviceId.raw,
            optionalPreKey?.id?.raw ?: PreKeyBundle.NULL_PRE_KEY_ID,
            optionalPreKey?.let { ECPublicKey(it.publicKeyBytes) },
            signedPreKey.id.raw,
            ECPublicKey(signedPreKey.publicKeyBytes),
            signedPreKey.signatureBytes,
            IdentityKey(identityKeyBytes),
            kyberPreKey.id.raw,
            KEMPublicKey(kyberPreKey.publicKeyBytes),
            kyberPreKey.signatureBytes,
        )
    }

    private fun SignalDeviceAddress.toLibSignalAddress(): SignalProtocolAddress = SignalProtocolAddress(libSignalName, protocolDeviceId.raw)

    private fun SignalDeviceAddress.toStateAddress(): SignalProtocolStateAddress = SignalProtocolStateAddress.fromDeviceAddress(this)

    private fun requireBoundLocalIdentity(): StoredLocalSignalIdentity {
        val localIdentity =
            stateRepository.loadLocalIdentity()
                ?: throw SignalProtocolException(
                    kind = SignalProtocolFailureKind.STATE_CONFLICT,
                    message = "Local Signal identity has not been initialized",
                )
        if (localIdentity.address != localAddress) {
            throw SignalProtocolStateCorruptedException(
                "Stored Signal identity is bound to a different local device",
            )
        }
        return localIdentity
    }

    private fun requireDistinctProtocolAddress(remoteAddress: SignalDeviceAddress) {
        if (remoteAddress.toStateAddress() == localAddress.toStateAddress()) {
            throw SignalProtocolException(
                kind = SignalProtocolFailureKind.INVALID_INPUT,
                message = "Remote Signal address must differ from the local address",
            )
        }
    }

    private fun requireValidFanOutRecipients(recipients: List<SignalDeviceAddress>) {
        requireValidFanOutRecipientCount(recipients.size)
        if (recipients.map(SignalDeviceAddress::transportDeviceId).distinct().size != recipients.size) {
            throw SignalProtocolException(
                kind = SignalProtocolFailureKind.INVALID_INPUT,
                message = "Pairwise fan-out contains a duplicate transport device",
            )
        }
        if (recipients.map { it.toStateAddress() }.distinct().size != recipients.size) {
            throw SignalProtocolException(
                kind = SignalProtocolFailureKind.INVALID_INPUT,
                message = "Pairwise fan-out contains a duplicate Signal protocol address",
            )
        }
        recipients.forEach(::requireDistinctProtocolAddress)
    }

    private fun requireValidFanOutRecipientCount(recipientCount: Int) {
        if (recipientCount !in 1..PairwiseSignalFanOut.MAX_RECIPIENT_DEVICES) {
            throw SignalProtocolException(
                kind = SignalProtocolFailureKind.INVALID_INPUT,
                message =
                    "Pairwise fan-out requires between 1 and " +
                        "${PairwiseSignalFanOut.MAX_RECIPIENT_DEVICES} recipient devices",
            )
        }
    }

    private fun validatePlaintext(plaintext: ByteArray) {
        if (plaintext.isEmpty() || plaintext.size > SignalProtocolWireLimits.MAX_PLAINTEXT_BYTES) {
            throw SignalProtocolException(
                kind = SignalProtocolFailureKind.INVALID_INPUT,
                message = "Signal plaintext must be between 1 and ${SignalProtocolWireLimits.MAX_PLAINTEXT_BYTES} bytes",
            )
        }
    }

    private fun validateGenerationTime(generatedAt: Instant) {
        if (generatedAt.isBefore(Instant.EPOCH)) {
            throw SignalProtocolException(
                kind = SignalProtocolFailureKind.INVALID_INPUT,
                message = "Signal pre-key generation time must not precede the Unix epoch",
            )
        }
    }

    private fun allocatePreKeyId(isAlreadyStored: (Int) -> Boolean): SignalPreKeyId {
        repeat(MAX_PRE_KEY_ID_ALLOCATION_ATTEMPTS) {
            val candidate = secureRandom.nextInt(SignalPreKeyId.MAX_VALUE + 1)
            if (!isAlreadyStored(candidate)) {
                return SignalPreKeyId.fromWire(candidate)
            }
        }
        throw SignalProtocolException(
            kind = SignalProtocolFailureKind.STATE_CONFLICT,
            message = "Could not allocate an unused Signal pre-key ID",
        )
    }

    private fun requireCurrentLibSignalVersion(messageVersion: Int) {
        if (messageVersion != LIBSIGNAL_MESSAGE_VERSION) {
            throw SignalProtocolException(
                kind = SignalProtocolFailureKind.UNSUPPORTED_VERSION,
                message = "Signal ciphertext uses an unsupported protocol version",
            )
        }
    }

    private inline fun <T> classifiedOperation(block: () -> T): T =
        try {
            block()
        } catch (error: SignalProtocolException) {
            throw error
        } catch (error: DuplicateMessageException) {
            throw classifiedFailure(SignalProtocolFailureKind.REPLAY_DETECTED, error)
        } catch (error: ReusedBaseKeyException) {
            throw classifiedFailure(SignalProtocolFailureKind.REPLAY_DETECTED, error)
        } catch (error: UntrustedIdentityException) {
            throw classifiedFailure(SignalProtocolFailureKind.IDENTITY_REPLACEMENT_BLOCKED, error)
        } catch (error: SignalIdentityReplacementRejectedException) {
            throw classifiedFailure(SignalProtocolFailureKind.IDENTITY_REPLACEMENT_BLOCKED, error)
        } catch (error: InvalidVersionException) {
            throw classifiedFailure(SignalProtocolFailureKind.UNSUPPORTED_VERSION, error)
        } catch (error: LegacyMessageException) {
            throw classifiedFailure(SignalProtocolFailureKind.UNSUPPORTED_VERSION, error)
        } catch (error: InvalidKeyIdException) {
            throw classifiedFailure(SignalProtocolFailureKind.PRE_KEY_MISSING, error)
        } catch (error: NoSessionException) {
            throw classifiedFailure(SignalProtocolFailureKind.SESSION_MISSING, error)
        } catch (error: InvalidKeyException) {
            throw classifiedFailure(SignalProtocolFailureKind.MALFORMED_CIPHERTEXT, error)
        } catch (error: InvalidMessageException) {
            throw classifiedFailure(SignalProtocolFailureKind.MALFORMED_CIPHERTEXT, error)
        } catch (error: SignalProtocolStateCorruptedException) {
            throw classifiedFailure(SignalProtocolFailureKind.STATE_CORRUPTED, error)
        } catch (error: IllegalArgumentException) {
            throw classifiedFailure(SignalProtocolFailureKind.INVALID_INPUT, error)
        }

    private fun classifiedFailure(
        kind: SignalProtocolFailureKind,
        cause: Throwable,
    ): SignalProtocolException =
        SignalProtocolException(
            kind = kind,
            message =
                when (kind) {
                    SignalProtocolFailureKind.INVALID_INPUT -> "Signal Protocol input was rejected"
                    SignalProtocolFailureKind.UNSUPPORTED_VERSION -> "Signal Protocol version was rejected"
                    SignalProtocolFailureKind.MALFORMED_CIPHERTEXT -> "Signal ciphertext was malformed"
                    SignalProtocolFailureKind.REPLAY_DETECTED -> "Signal ciphertext replay was rejected"
                    SignalProtocolFailureKind.IDENTITY_REPLACEMENT_BLOCKED ->
                        "Remote Signal identity replacement requires explicit verification"
                    SignalProtocolFailureKind.SESSION_MISSING -> "Signal session is missing"
                    SignalProtocolFailureKind.PRE_KEY_MISSING -> "Required Signal pre-key is missing"
                    SignalProtocolFailureKind.STATE_CORRUPTED -> "Stored Signal Protocol state is corrupted"
                    SignalProtocolFailureKind.STATE_CONFLICT -> "Signal Protocol state changed concurrently"
                    SignalProtocolFailureKind.CRYPTOGRAPHIC_OPERATION_FAILED ->
                        "Signal cryptographic operation failed"
                },
            cause = cause,
        )

    private companion object {
        const val LIBSIGNAL_MESSAGE_VERSION: Int = 4
        const val SAFETY_NUMBER_VERSION: Int = 2
        const val SAFETY_NUMBER_ITERATIONS: Int = 5_200
        const val MAX_PRE_KEY_ID_ALLOCATION_ATTEMPTS: Int = 64
    }
}
