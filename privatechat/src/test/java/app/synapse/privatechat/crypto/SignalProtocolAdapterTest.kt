package app.synapse.privatechat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

class SignalProtocolAdapterTest {
    @Test
    fun establishesPqxdhSessionAndExchangesPreKeyAndWhisperMessages() {
        val alice = createDevice(ALICE_ADDRESS)
        val bob = createDevice(BOB_ADDRESS)

        alice.adapter.establishPairwiseSession(bob.bundle)
        val initialPlaintext = "initial private message".toByteArray(StandardCharsets.UTF_8)
        val initialEnvelope = alice.adapter.encryptForDevice(BOB_ADDRESS, initialPlaintext)

        assertEquals(SignalCiphertextType.PREKEY, initialEnvelope.ciphertextType)
        assertArrayEquals(initialPlaintext, bob.adapter.decryptFromDevice(initialEnvelope))

        val replyPlaintext = "acknowledged private message".toByteArray(StandardCharsets.UTF_8)
        val replyEnvelope = bob.adapter.encryptForDevice(ALICE_ADDRESS, replyPlaintext)
        assertEquals(SignalCiphertextType.WHISPER, replyEnvelope.ciphertextType)
        assertArrayEquals(replyPlaintext, alice.adapter.decryptFromDevice(replyEnvelope))

        val ratchetedEnvelope = alice.adapter.encryptForDevice(BOB_ADDRESS, initialPlaintext)
        assertEquals(SignalCiphertextType.WHISPER, ratchetedEnvelope.ciphertextType)
        assertArrayEquals(initialPlaintext, bob.adapter.decryptFromDevice(ratchetedEnvelope))
    }

    @Test
    fun decryptsRatchetedMessagesOutOfOrderAndClassifiesReplay() {
        val alice = createDevice(ALICE_ADDRESS)
        val bob = createDevice(BOB_ADDRESS)
        acknowledgeSession(alice, bob)

        val outbound =
            (1..4).map { index ->
                val plaintext = "ratcheted-$index".toByteArray(StandardCharsets.UTF_8)
                plaintext to alice.adapter.encryptForDevice(BOB_ADDRESS, plaintext)
            }

        outbound.reversed().forEach { (plaintext, envelope) ->
            assertArrayEquals(plaintext, bob.adapter.decryptFromDevice(envelope))
        }
        val replayFailure =
            assertThrows(SignalProtocolException::class.java) {
                bob.adapter.decryptFromDevice(outbound.last().second)
            }
        assertEquals(SignalProtocolFailureKind.REPLAY_DETECTED, replayFailure.kind)
    }

    @Test
    fun classifiesAnInitialPreKeyMessageReplay() {
        val alice = createDevice(ALICE_ADDRESS)
        val bob = createDevice(BOB_ADDRESS)
        alice.adapter.establishPairwiseSession(bob.bundle)
        val initialEnvelope =
            alice.adapter.encryptForDevice(
                BOB_ADDRESS,
                "initial replay target".toByteArray(StandardCharsets.UTF_8),
            )

        bob.adapter.decryptFromDevice(initialEnvelope)
        val replayFailure =
            assertThrows(SignalProtocolException::class.java) {
                bob.adapter.decryptFromDevice(initialEnvelope)
            }

        assertEquals(SignalProtocolFailureKind.REPLAY_DETECTED, replayFailure.kind)
    }

    @Test
    fun cacheCommitFailureRollsBackInboundRatchetSoTheEnvelopeCanBeRetried() {
        val alice = createDevice(ALICE_ADDRESS)
        val bob = createDevice(BOB_ADDRESS)
        alice.adapter.establishPairwiseSession(bob.bundle)
        val plaintext = "cache before ratchet commit".toByteArray(StandardCharsets.UTF_8)
        val envelope = alice.adapter.encryptForDevice(BOB_ADDRESS, plaintext)

        assertThrows(TestCacheCommitException::class.java) {
            bob.adapter.decryptFromDeviceWithDurableCommit(envelope) { decrypted ->
                assertArrayEquals(plaintext, decrypted)
                throw TestCacheCommitException()
            }
        }

        val cacheReceipt =
            bob.adapter.decryptFromDeviceWithDurableCommit(envelope) { decrypted ->
                assertArrayEquals(plaintext, decrypted)
                "persisted"
            }
        assertEquals("persisted", cacheReceipt)
        val replayFailure =
            assertThrows(SignalProtocolException::class.java) {
                bob.adapter.decryptFromDevice(envelope)
            }
        assertEquals(SignalProtocolFailureKind.REPLAY_DETECTED, replayFailure.kind)
    }

    @Test
    fun blocksIdentityReplacementUntilExpectedIdentityIsExplicitlyAccepted() {
        val alice = createDevice(ALICE_ADDRESS)
        val bob = createDevice(BOB_ADDRESS)
        acknowledgeSession(alice, bob)
        val originalSafetyNumber = alice.adapter.safetyNumberFor(BOB_ADDRESS)

        val replacementBob = createDevice(BOB_ADDRESS)
        val replacementFailure =
            assertThrows(SignalProtocolException::class.java) {
                alice.adapter.establishPairwiseSession(replacementBob.bundle)
            }
        assertEquals(
            SignalProtocolFailureKind.IDENTITY_REPLACEMENT_BLOCKED,
            replacementFailure.kind,
        )

        val receipt =
            alice.adapter.acceptVerifiedIdentityReplacement(
                AcceptVerifiedIdentityReplacementCommand(
                    address = BOB_ADDRESS,
                    expectedIdentityKeyBytes = bob.bundle.identityKeyBytes,
                    replacementIdentityKeyBytes = replacementBob.bundle.identityKeyBytes,
                ),
            )
        assertEquals(1, receipt.deletedSessionCount)
        assertNotEquals(receipt.previousIdentityFingerprint, receipt.acceptedIdentityFingerprint)

        alice.adapter.establishPairwiseSession(replacementBob.bundle)
        val replacementSafetyNumber = alice.adapter.safetyNumberFor(BOB_ADDRESS)
        assertNotEquals(originalSafetyNumber, replacementSafetyNumber)
    }

    @Test
    fun abortsWhenIdentityChangesBetweenTrustCheckAndSave() {
        val aliceRepository = InMemorySignalProtocolStateRepository()
        val aliceAdapter = SignalProtocolAdapter(ALICE_ADDRESS, aliceRepository)
        aliceAdapter.initializeLocalDevice(FIXED_GENERATION_TIME)
        val bob = createDevice(BOB_ADDRESS)
        val concurrentBob = createDevice(BOB_ADDRESS)
        aliceRepository.simulateConcurrentIdentityWriteBeforeNextStore(
            address = SignalProtocolStateAddress.fromDeviceAddress(BOB_ADDRESS),
            identityKeyBytes = concurrentBob.bundle.identityKeyBytes,
        )

        val replacementFailure =
            assertThrows(SignalProtocolException::class.java) {
                aliceAdapter.establishPairwiseSession(bob.bundle)
            }

        assertEquals(
            SignalProtocolFailureKind.IDENTITY_REPLACEMENT_BLOCKED,
            replacementFailure.kind,
        )
        assertNull(
            aliceRepository.loadSession(SignalProtocolStateAddress.fromDeviceAddress(BOB_ADDRESS)),
        )
    }

    @Test
    fun createsOneIndependentPairwiseEnvelopePerRecipientDevice() {
        val alice = createDevice(ALICE_ADDRESS)
        val bob = createDevice(BOB_ADDRESS)
        val carol = createDevice(CAROL_ADDRESS)
        alice.adapter.establishPairwiseSession(bob.bundle)
        alice.adapter.establishPairwiseSession(carol.bundle)
        val plaintext = "group fan-out".toByteArray(StandardCharsets.UTF_8)

        val fanOut =
            alice.adapter.encryptForRecipientDevices(
                recipients = listOf(BOB_ADDRESS, CAROL_ADDRESS),
                plaintext = plaintext,
            )

        assertEquals(2, fanOut.envelopes.size)
        val bobEnvelope = fanOut.envelopes.single { it.recipient == BOB_ADDRESS }
        val carolEnvelope = fanOut.envelopes.single { it.recipient == CAROL_ADDRESS }
        assertFalse(bobEnvelope.serializedCiphertext.contentEquals(carolEnvelope.serializedCiphertext))
        assertArrayEquals(plaintext, bob.adapter.decryptFromDevice(bobEnvelope))
        assertArrayEquals(plaintext, carol.adapter.decryptFromDevice(carolEnvelope))
    }

    @Test
    fun pendingOutboundCallbackFailureRollsBackSenderRatchetAndRequest() {
        val repository = InMemorySignalProtocolStateRepository()
        val alice = createDevice(ALICE_ADDRESS, repository)
        val bob = createDevice(BOB_ADDRESS)
        alice.adapter.establishPairwiseSession(bob.bundle)
        val plaintext = "atomic pending outbound".toByteArray(StandardCharsets.UTF_8)
        val mutationKey = pendingMutationKey()

        assertThrows(TestPendingCommitException::class.java) {
            alice.adapter.encryptForRecipientDevicesWithPendingOutboundCommit(
                recipients = listOf(BOB_ADDRESS),
                plaintext = plaintext,
            ) {
                throw TestPendingCommitException()
            }
        }
        assertNull(repository.loadPendingOutboundMutation(mutationKey))

        val pending =
            alice.adapter.encryptForRecipientDevicesWithPendingOutboundCommit(
                recipients = listOf(BOB_ADDRESS),
                plaintext = plaintext,
            ) { fanOut ->
                pendingMutation(mutationKey, fanOut.envelopes.single().serializedCiphertext)
            }
        assertEquals(SignalCiphertextType.PREKEY, pendingRequestEnvelope(pending).ciphertextType)
        assertArrayEquals(plaintext, bob.adapter.decryptFromDevice(pendingRequestEnvelope(pending)))
    }

    @Test
    fun pendingOutboundMutationCanBeReloadedAndConfirmedByExactDigest() {
        val repository = InMemorySignalProtocolStateRepository()
        val alice = createDevice(ALICE_ADDRESS, repository)
        val bob = createDevice(BOB_ADDRESS)
        alice.adapter.establishPairwiseSession(bob.bundle)
        val mutationKey = pendingMutationKey()
        val plaintext = "restart-safe exact request".toByteArray(StandardCharsets.UTF_8)
        val pending =
            alice.adapter.encryptForRecipientDevicesWithPendingOutboundCommit(
                recipients = listOf(BOB_ADDRESS),
                plaintext = plaintext,
            ) { fanOut -> pendingMutation(mutationKey, fanOut.envelopes.single().serializedCiphertext) }

        val reloadedAdapter = SignalProtocolAdapter(ALICE_ADDRESS, repository)
        val reloaded = requireNotNull(reloadedAdapter.loadPendingOutboundMutation(mutationKey))
        assertArrayEquals(pending.opaqueRequest, reloaded.opaqueRequest)
        val digest = reloaded.operationDigest
        reloadedAdapter.confirmPendingOutboundMutation(mutationKey, digest)
        digest.fill(0)
        assertNull(reloadedAdapter.loadPendingOutboundMutation(mutationKey))
    }

    @Test
    fun sessionInvalidationClearsPendingRequestsAndTheirAdvancedPeerSessions() {
        val repository = InMemorySignalProtocolStateRepository()
        val alice = createDevice(ALICE_ADDRESS, repository)
        val bob = createDevice(BOB_ADDRESS)
        alice.adapter.establishPairwiseSession(bob.bundle)
        val mutationKey = pendingMutationKey()
        alice.adapter.encryptForRecipientDevicesWithPendingOutboundCommit(
            recipients = listOf(BOB_ADDRESS),
            plaintext = "session-bound pending request".toByteArray(StandardCharsets.UTF_8),
        ) { fanOut -> pendingMutation(mutationKey, fanOut.envelopes.single().serializedCiphertext) }

        assertEquals(1, alice.adapter.clearPendingOutboundMutationsForSessionInvalidation())

        assertNull(repository.loadPendingOutboundMutation(mutationKey))
        assertNull(repository.loadSession(SignalProtocolStateAddress.fromDeviceAddress(BOB_ADDRESS)))
        assertEquals(0, alice.adapter.clearPendingOutboundMutationsForSessionInvalidation())
    }

    @Test
    fun rejectsFanOutAboveTheTransportRecipientLimitBeforeEncryption() {
        val adapter = SignalProtocolAdapter(ALICE_ADDRESS, InMemorySignalProtocolStateRepository())
        val recipients =
            (1..PairwiseSignalFanOut.MAX_RECIPIENT_DEVICES + 1).map { index ->
                SignalDeviceAddress(
                    accountId = java.util.UUID(1L, index.toLong()),
                    transportDeviceId = java.util.UUID(2L, index.toLong()),
                    protocolDeviceId = SignalDeviceId.fromWire(1),
                )
            }

        val failure =
            assertThrows(SignalProtocolException::class.java) {
                adapter.encryptForRecipientDevices(
                    recipients = recipients,
                    plaintext = "oversized fan-out".toByteArray(StandardCharsets.UTF_8),
                )
            }

        assertEquals(SignalProtocolFailureKind.INVALID_INPUT, failure.kind)
    }

    @Test
    fun generatesTheSameNumericSafetyNumberAtBothEnds() {
        val alice = createDevice(ALICE_ADDRESS)
        val bob = createDevice(BOB_ADDRESS)
        acknowledgeSession(alice, bob)

        val aliceSafetyNumber = alice.adapter.safetyNumberFor(BOB_ADDRESS)
        val bobSafetyNumber = bob.adapter.safetyNumberFor(ALICE_ADDRESS)

        assertEquals(aliceSafetyNumber, bobSafetyNumber)
        assertEquals(60, aliceSafetyNumber.digits.length)
        assertEquals(12, aliceSafetyNumber.grouped.split(' ').size)
    }

    @Test
    fun rejectsSecondInitializationAndMissingSessionWithoutFallback() {
        val repository = InMemorySignalProtocolStateRepository()
        val adapter = SignalProtocolAdapter(ALICE_ADDRESS, repository)
        adapter.initializeLocalDevice(FIXED_GENERATION_TIME)

        val initializationFailure =
            assertThrows(SignalProtocolException::class.java) {
                adapter.initializeLocalDevice(FIXED_GENERATION_TIME)
            }
        assertEquals(SignalProtocolFailureKind.STATE_CONFLICT, initializationFailure.kind)

        val sessionFailure =
            assertThrows(SignalProtocolException::class.java) {
                adapter.encryptForDevice(
                    BOB_ADDRESS,
                    "no fallback".toByteArray(StandardCharsets.UTF_8),
                )
            }
        assertEquals(SignalProtocolFailureKind.SESSION_MISSING, sessionFailure.kind)
    }

    @Test
    fun rejectsCiphertextWhoseDeclaredTypeDoesNotMatchSerializedMessage() {
        val alice = createDevice(ALICE_ADDRESS)
        val bob = createDevice(BOB_ADDRESS)
        alice.adapter.establishPairwiseSession(bob.bundle)
        val validEnvelope =
            alice.adapter.encryptForDevice(
                BOB_ADDRESS,
                "typed envelope".toByteArray(StandardCharsets.UTF_8),
            )
        assertEquals(SignalCiphertextType.PREKEY, validEnvelope.ciphertextType)
        val mismatchedEnvelope =
            SignalEnvelope.fromWire(
                protocolVersion = validEnvelope.protocolVersion,
                sender = validEnvelope.sender,
                recipient = validEnvelope.recipient,
                ciphertextTypeCode = SignalCiphertextType.WHISPER.wireCode,
                serializedCiphertext = validEnvelope.serializedCiphertext,
            )

        val failure =
            assertThrows(SignalProtocolException::class.java) {
                bob.adapter.decryptFromDevice(mismatchedEnvelope)
            }
        assertTrue(
            failure.kind == SignalProtocolFailureKind.MALFORMED_CIPHERTEXT ||
                failure.kind == SignalProtocolFailureKind.UNSUPPORTED_VERSION,
        )
    }

    private fun acknowledgeSession(
        alice: TestDevice,
        bob: TestDevice,
    ) {
        alice.adapter.establishPairwiseSession(bob.bundle)
        val initialEnvelope =
            alice.adapter.encryptForDevice(
                BOB_ADDRESS,
                "session start".toByteArray(StandardCharsets.UTF_8),
            )
        bob.adapter.decryptFromDevice(initialEnvelope)
        val acknowledgement =
            bob.adapter.encryptForDevice(
                ALICE_ADDRESS,
                "session ack".toByteArray(StandardCharsets.UTF_8),
            )
        alice.adapter.decryptFromDevice(acknowledgement)
    }

    private fun createDevice(
        address: SignalDeviceAddress,
        repository: InMemorySignalProtocolStateRepository = InMemorySignalProtocolStateRepository(),
    ): TestDevice {
        val adapter = SignalProtocolAdapter(address, repository)
        val initialization = adapter.initializeLocalDevice(FIXED_GENERATION_TIME)
        return TestDevice(
            adapter = adapter,
            bundle = initialization.publicPreKeyBundle,
        )
    }

    private data class TestDevice(
        val adapter: SignalProtocolAdapter,
        val bundle: SignalPublicPreKeyBundle,
    )

    private class TestCacheCommitException : IllegalStateException("simulated encrypted-cache commit failure")

    private class TestPendingCommitException : IllegalStateException("simulated pending outbound commit failure")

    private fun pendingMutationKey(): SignalPendingOutboundMutationKey =
        SignalPendingOutboundMutationKey(
            accountId = ALICE_ADDRESS.accountId,
            transportDeviceId = ALICE_ADDRESS.transportDeviceId,
            clientMutationId = UUID.fromString("40000000-0000-4000-8000-000000000001"),
        )

    private fun pendingMutation(
        key: SignalPendingOutboundMutationKey,
        serializedEnvelope: ByteArray,
    ): StoredSignalPendingOutboundMutation =
        StoredSignalPendingOutboundMutation.create(
            key = key,
            operationDigest = ByteArray(32) { 7 },
            opaqueRequest = serializedEnvelope,
            peerRecipients = listOf(BOB_ADDRESS),
            createdAt = FIXED_GENERATION_TIME,
            expiresAt = FIXED_GENERATION_TIME.plusSeconds(60),
        )

    private fun pendingRequestEnvelope(pending: StoredSignalPendingOutboundMutation): SignalEnvelope =
        SignalEnvelope.fromWire(
            protocolVersion = SignalEnvelope.CURRENT_PROTOCOL_VERSION,
            sender = ALICE_ADDRESS,
            recipient = BOB_ADDRESS,
            ciphertextTypeCode = SignalCiphertextType.PREKEY.wireCode,
            serializedCiphertext = pending.opaqueRequest,
        )

    private companion object {
        val FIXED_GENERATION_TIME: Instant = Instant.parse("2026-08-20T00:00:00Z")
        val ALICE_ADDRESS: SignalDeviceAddress =
            SignalDeviceAddress.fromWire(
                accountId = "10000000-0000-4000-8000-000000000001",
                transportDeviceId = "10000000-0000-4000-8000-000000000002",
                protocolDeviceId = 1,
            )
        val BOB_ADDRESS: SignalDeviceAddress =
            SignalDeviceAddress.fromWire(
                accountId = "20000000-0000-4000-8000-000000000001",
                transportDeviceId = "20000000-0000-4000-8000-000000000002",
                protocolDeviceId = 1,
            )
        val CAROL_ADDRESS: SignalDeviceAddress =
            SignalDeviceAddress.fromWire(
                accountId = "30000000-0000-4000-8000-000000000001",
                transportDeviceId = "30000000-0000-4000-8000-000000000002",
                protocolDeviceId = 1,
            )
    }
}
