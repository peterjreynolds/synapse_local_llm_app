package app.synapse.privatechat.security.storage

/**
 * Whole-state encrypted storage with two independently destructible Android Keystore slots.
 *
 * Additive replacements may reuse the active key. Any replacement that removes or supersedes
 * sensitive plaintext must use [replaceAfterCryptographicErasure], which commits under a fresh
 * alternate key before destroying the retired key.
 */
internal interface CryptographicallyErasableEncryptedStateStorage {
    fun readDecryptedState(): ByteArray?

    fun replaceEncryptedState(plaintext: ByteArray)

    fun replaceAfterCryptographicErasure(retainedPlaintext: ByteArray?)

    fun deletePhysically()
}

internal class RotatingAesGcmEncryptedStateKeySlot(
    private val keyProvider: DestructibleEncryptedStateKeyProvider,
    authenticatedContext: String,
) {
    private val existingKeyCipher =
        Aes256GcmEncryptedStateCipher(
            keyProvider = keyProvider,
            keyCreationAllowed = { false },
            authenticatedContext = authenticatedContext,
        )
    private val freshKeyCipher =
        Aes256GcmEncryptedStateCipher(
            keyProvider = keyProvider,
            keyCreationAllowed = { true },
            authenticatedContext = authenticatedContext,
        )

    fun encryptWithExistingKey(plaintext: ByteArray): ByteArray = existingKeyCipher.encrypt(plaintext)

    fun encryptWithFreshKey(plaintext: ByteArray): ByteArray {
        deleteKey()
        return try {
            freshKeyCipher.encrypt(plaintext)
        } catch (error: Exception) {
            try {
                deleteKey()
            } catch (deleteFailure: Exception) {
                error.addSuppressed(deleteFailure)
            }
            throw error
        }
    }

    fun decrypt(ciphertext: ByteArray): ByteArray = existingKeyCipher.decrypt(ciphertext)

    fun deleteKey() = keyProvider.deleteKey()
}

internal enum class RotatingEncryptedStateKeySlotId(
    val wireValue: Byte,
) {
    PRIMARY(1),
    SECONDARY(2),
    ;

    fun alternate(): RotatingEncryptedStateKeySlotId =
        when (this) {
            PRIMARY -> SECONDARY
            SECONDARY -> PRIMARY
        }

    companion object {
        fun fromWire(value: Byte): RotatingEncryptedStateKeySlotId =
            entries.firstOrNull { slot -> slot.wireValue == value }
                ?: throw EncryptedStateUnavailableException("Rotating encrypted state key slot is invalid")
    }
}

internal class RotatingAesGcmEncryptedStateStorage(
    private val encryptedStateFile: DeletableEncryptedStateFile,
    private val primaryKeySlot: RotatingAesGcmEncryptedStateKeySlot,
    private val secondaryKeySlot: RotatingAesGcmEncryptedStateKeySlot,
    private val maximumPlaintextBytes: Int,
    private val legacySingleSlot: RotatingEncryptedStateKeySlotId? = null,
) : CryptographicallyErasableEncryptedStateStorage {
    private val monitor = Any()

    init {
        require(maximumPlaintextBytes in 1..MAXIMUM_PLAINTEXT_BYTES) {
            "Rotating encrypted state plaintext limit is unsupported"
        }
    }

    override fun readDecryptedState(): ByteArray? =
        synchronized(monitor) {
            readAuthenticatedState()?.plaintext
        }

    override fun replaceEncryptedState(plaintext: ByteArray) {
        requireSupportedPlaintext(plaintext)
        synchronized(monitor) {
            val activeState = readAuthenticatedState()
            try {
                if (activeState == null) {
                    commitWithFreshKey(RotatingEncryptedStateKeySlotId.PRIMARY, plaintext, retiredSlot = null)
                } else {
                    commitWithExistingKey(activeState.slot, plaintext)
                }
            } finally {
                activeState?.plaintext?.fill(0)
            }
        }
    }

    override fun replaceAfterCryptographicErasure(retainedPlaintext: ByteArray?) {
        retainedPlaintext?.let(::requireSupportedPlaintext)
        synchronized(monitor) {
            if (retainedPlaintext == null) {
                destroyAllKeysAndState()
                return@synchronized
            }
            val activeState = readAuthenticatedState()
            try {
                val replacementSlot = activeState?.slot?.alternate() ?: RotatingEncryptedStateKeySlotId.PRIMARY
                commitWithFreshKey(
                    targetSlot = replacementSlot,
                    plaintext = retainedPlaintext,
                    retiredSlot = activeState?.slot,
                )
            } finally {
                activeState?.plaintext?.fill(0)
            }
        }
    }

    override fun deletePhysically() {
        replaceAfterCryptographicErasure(retainedPlaintext = null)
    }

    private fun readAuthenticatedState(): ActiveEncryptedState? {
        val encodedState =
            try {
                encryptedStateFile.read(maximumEncodedStateBytes())
            } catch (error: Exception) {
                purgeAfterReadFailure(error)
            }
        if (encodedState == null) {
            destroyAllKeysAndState()
            return null
        }
        val encodedEnvelope =
            try {
                decodeEnvelope(encodedState)
            } catch (error: Exception) {
                purgeAfterReadFailure(error)
            } finally {
                encodedState.fill(0)
            }
        if (!encodedEnvelope.legacySingleSlot) {
            try {
                deleteRetiredKey(encodedEnvelope.slot.alternate())
            } catch (error: Exception) {
                encodedEnvelope.ciphertext.fill(0)
                throw EncryptedStateUnavailableException(
                    "Retired rotating encrypted state key could not be destroyed",
                    error,
                )
            }
        }
        val plaintext =
            try {
                keySlot(encodedEnvelope.slot).decrypt(encodedEnvelope.ciphertext)
            } catch (error: Exception) {
                purgeAfterReadFailure(error)
            } finally {
                encodedEnvelope.ciphertext.fill(0)
            }
        if (plaintext.size !in 1..maximumPlaintextBytes) {
            plaintext.fill(0)
            purgeAfterReadFailure(
                EncryptedStateUnavailableException("Rotating encrypted state exceeds the supported range"),
            )
        }
        if (encodedEnvelope.legacySingleSlot) {
            val replacementSlot = encodedEnvelope.slot.alternate()
            try {
                commitWithFreshKey(
                    targetSlot = replacementSlot,
                    plaintext = plaintext,
                    retiredSlot = encodedEnvelope.slot,
                )
            } catch (error: Exception) {
                plaintext.fill(0)
                throw error
            }
            return ActiveEncryptedState(replacementSlot, plaintext)
        }
        return ActiveEncryptedState(encodedEnvelope.slot, plaintext)
    }

    private fun commitWithExistingKey(
        slot: RotatingEncryptedStateKeySlotId,
        plaintext: ByteArray,
    ) {
        val ciphertext = keySlot(slot).encryptWithExistingKey(plaintext)
        commitEncodedState(slot, ciphertext)
    }

    private fun commitWithFreshKey(
        targetSlot: RotatingEncryptedStateKeySlotId,
        plaintext: ByteArray,
        retiredSlot: RotatingEncryptedStateKeySlotId?,
    ) {
        val targetKeySlot = keySlot(targetSlot)
        val ciphertext = targetKeySlot.encryptWithFreshKey(plaintext)
        try {
            commitEncodedState(targetSlot, ciphertext)
        } catch (error: Exception) {
            try {
                targetKeySlot.deleteKey()
            } catch (deleteFailure: Exception) {
                error.addSuppressed(deleteFailure)
            }
            throw error
        }
        if (retiredSlot != null) {
            try {
                deleteRetiredKey(retiredSlot)
            } catch (error: Exception) {
                throw EncryptedStateUnavailableException(
                    "Retired rotating encrypted state key could not be destroyed after rotation",
                    error,
                )
            }
        }
    }

    private fun commitEncodedState(
        slot: RotatingEncryptedStateKeySlotId,
        ciphertext: ByteArray,
    ) {
        val encodedState = encodeEnvelope(slot, ciphertext)
        try {
            encryptedStateFile.replace(encodedState)
        } finally {
            ciphertext.fill(0)
            encodedState.fill(0)
        }
    }

    private fun destroyAllKeysAndState() {
        var deletionFailure: Exception? = null
        for (slot in RotatingEncryptedStateKeySlotId.entries) {
            try {
                keySlot(slot).deleteKey()
            } catch (error: Exception) {
                deletionFailure = appendFailure(deletionFailure, error)
            }
        }
        try {
            encryptedStateFile.deletePhysically()
        } catch (error: Exception) {
            deletionFailure = appendFailure(deletionFailure, error)
        }
        if (deletionFailure != null) {
            throw EncryptedStateUnavailableException(
                "Rotating encrypted state could not be fully destroyed",
                deletionFailure,
            )
        }
    }

    private fun purgeAfterReadFailure(readFailure: Exception): Nothing {
        try {
            destroyAllKeysAndState()
        } catch (deleteFailure: Exception) {
            readFailure.addSuppressed(deleteFailure)
        }
        throw EncryptedStateUnavailableException(
            "Rotating encrypted state authentication failed and was cryptographically purged",
            readFailure,
        )
    }

    private fun deleteRetiredKey(slot: RotatingEncryptedStateKeySlotId) {
        keySlot(slot).deleteKey()
    }

    private fun keySlot(slot: RotatingEncryptedStateKeySlotId): RotatingAesGcmEncryptedStateKeySlot =
        when (slot) {
            RotatingEncryptedStateKeySlotId.PRIMARY -> primaryKeySlot
            RotatingEncryptedStateKeySlotId.SECONDARY -> secondaryKeySlot
        }

    private fun encodeEnvelope(
        slot: RotatingEncryptedStateKeySlotId,
        ciphertext: ByteArray,
    ): ByteArray =
        ByteArray(OUTER_HEADER_BYTES + ciphertext.size).also { encoded ->
            OUTER_MAGIC.copyInto(encoded)
            encoded[OUTER_MAGIC.size] = slot.wireValue
            ciphertext.copyInto(encoded, OUTER_HEADER_BYTES)
        }

    private fun decodeEnvelope(encodedState: ByteArray): EncodedEncryptedStateEnvelope {
        if (encodedState.startsWith(OUTER_MAGIC)) {
            if (encodedState.size !in minimumEncodedStateBytes()..maximumEncodedStateBytes()) corruptEnvelope()
            return EncodedEncryptedStateEnvelope(
                slot = RotatingEncryptedStateKeySlotId.fromWire(encodedState[OUTER_MAGIC.size]),
                ciphertext = encodedState.copyOfRange(OUTER_HEADER_BYTES, encodedState.size),
                legacySingleSlot = false,
            )
        }
        val legacySlot = legacySingleSlot ?: corruptEnvelope()
        if (encodedState.size !in minimumLegacyStateBytes()..maximumLegacyStateBytes()) corruptEnvelope()
        return EncodedEncryptedStateEnvelope(
            slot = legacySlot,
            ciphertext = encodedState.copyOf(),
            legacySingleSlot = true,
        )
    }

    private fun requireSupportedPlaintext(plaintext: ByteArray) {
        require(plaintext.size in 1..maximumPlaintextBytes) {
            "Rotating encrypted state exceeds the supported range"
        }
    }

    private fun minimumEncodedStateBytes(): Int = OUTER_HEADER_BYTES + minimumLegacyStateBytes()

    private fun maximumEncodedStateBytes(): Int = OUTER_HEADER_BYTES + maximumLegacyStateBytes()

    private fun minimumLegacyStateBytes(): Int = 1 + Aes256GcmEncryptedStateCipher.MAX_ENVELOPE_OVERHEAD_BYTES

    private fun maximumLegacyStateBytes(): Int = maximumPlaintextBytes + Aes256GcmEncryptedStateCipher.MAX_ENVELOPE_OVERHEAD_BYTES

    private fun appendFailure(
        existingFailure: Exception?,
        nextFailure: Exception,
    ): Exception = existingFailure?.also { failure -> failure.addSuppressed(nextFailure) } ?: nextFailure

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun corruptEnvelope(): Nothing = throw EncryptedStateUnavailableException("Rotating encrypted state envelope is invalid")

    private data class ActiveEncryptedState(
        val slot: RotatingEncryptedStateKeySlotId,
        val plaintext: ByteArray,
    )

    private data class EncodedEncryptedStateEnvelope(
        val slot: RotatingEncryptedStateKeySlotId,
        val ciphertext: ByteArray,
        val legacySingleSlot: Boolean,
    )

    private companion object {
        const val MAXIMUM_PLAINTEXT_BYTES = 64 * 1_024 * 1_024

        // Retain the original cache envelope bytes so already-written encrypted cache files remain readable.
        val OUTER_MAGIC = byteArrayOf(0x53, 0x50, 0x43, 0x01)
        const val OUTER_HEADER_BYTES = 5
    }
}
