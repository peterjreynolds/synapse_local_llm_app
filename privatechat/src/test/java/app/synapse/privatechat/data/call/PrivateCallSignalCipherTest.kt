package app.synapse.privatechat.data.call

import app.synapse.privatechat.crypto.InMemorySignalProtocolStateRepository
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.crypto.SignalProtocolException
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.data.chat.PrivateBackendPollingState
import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSession
import app.synapse.privatechat.data.chat.PrivateChatPayloadCodec
import app.synapse.privatechat.data.chat.PrivateChatPlaintextPayload
import app.synapse.privatechat.data.chat.PrivateChatPollingBackend
import app.synapse.privatechat.data.chat.PrivateChatRecipientDevice
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallState
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateMessageTextValidation
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.validatePrivateMessageText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateCallSignalCipherTest {
    @Test
    fun sharesTheChatRatchetWithoutPersistingCallOutboxRecords() =
        runBlocking {
            val peers = CallCryptoPeers()
            val payload = peers.payload
            peers.callerCipher.preparePeers(peers.callerSession, listOf(PrivateChatRecipientDevice(payload.recipient, 1)))
            val envelope = peers.callerCipher.encrypt(peers.callerSession, payload)
            assertFalse(envelope.serializedCiphertext.toString(Charsets.UTF_8).contains("fingerprint"))
            assertEquals(payload, peers.recipientCipher.decrypt(peers.recipientSession, envelope))
            assertTrue(
                peers.callerOwner
                    .requireAdapterForStoredIdentity()
                    .listPendingOutboundMutations()
                    .isEmpty(),
            )
            assertTrue(
                peers.recipientOwner
                    .requireAdapterForStoredIdentity()
                    .listPendingOutboundMutations()
                    .isEmpty(),
            )
            val chat = "ordinary chat after the call".encodeToByteArray()
            val chatEnvelope = peers.callerOwner.requireAdapterForStoredIdentity().encryptForDevice(payload.recipient, chat)
            assertArrayEquals(chat, peers.recipientOwner.requireAdapterForStoredIdentity().decryptFromDevice(chatEnvelope))
            assertEquals(
                peers.callerCipher.safetyNumber(peers.callerSession, payload.recipient).groupedDigits,
                peers.recipientCipher.safetyNumber(peers.recipientSession, payload.sender).groupedDigits,
            )
        }

    @Test
    fun repeatedPollDoesNotReplayRatchetAndChangedRoutingFailsClosed(): Unit =
        runBlocking {
            val peers = CallCryptoPeers()
            peers.callerCipher.preparePeers(peers.callerSession, listOf(PrivateChatRecipientDevice(peers.payload.recipient, 1)))
            val envelope = peers.callerCipher.encrypt(peers.callerSession, peers.payload)
            val record = peers.record(envelope)
            val decoder = PrivateCallIncomingSignals(peers.recipientCipher)
            val poll = PrivateCallBackendPoll(listOf(peers.call), listOf(record))
            assertEquals(1, decoder.decode(peers.recipientSession, poll, CALL_TEST_TIME).size)
            assertTrue(decoder.decode(peers.recipientSession, poll, CALL_TEST_TIME).isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                decoder.decode(
                    peers.recipientSession,
                    poll.copy(signals = listOf(record.copy(expiresAt = record.expiresAt.minusSeconds(1)))),
                    CALL_TEST_TIME,
                )
            }
        }

    @Test
    fun authenticatedPayloadCannotBeMovedToAnotherCall() =
        runBlocking {
            val peers = CallCryptoPeers()
            peers.callerCipher.preparePeers(peers.callerSession, listOf(PrivateChatRecipientDevice(peers.payload.recipient, 1)))
            val envelope = peers.callerCipher.encrypt(peers.callerSession, peers.payload)
            val anotherId = UUID.randomUUID()
            val poll =
                PrivateCallBackendPoll(listOf(peers.call.copy(callId = anotherId)), listOf(peers.record(envelope).copy(callId = anotherId)))
            assertThrows(SignalProtocolException::class.java) {
                PrivateCallIncomingSignals(peers.recipientCipher).decode(peers.recipientSession, poll, CALL_TEST_TIME)
            }
            val originalPoll = PrivateCallBackendPoll(listOf(peers.call), listOf(peers.record(envelope)))
            assertEquals(
                peers.payload.signal,
                PrivateCallIncomingSignals(
                    peers.recipientCipher,
                ).decode(peers.recipientSession, originalPoll, CALL_TEST_TIME).single().signal,
            )
        }

    @Test
    fun chatCiphertextRejectedByCallDecoderDoesNotConsumeTheChatMessageKey() =
        runBlocking {
            val peers = CallCryptoPeers()
            peers.callerCipher.preparePeers(peers.callerSession, listOf(PrivateChatRecipientDevice(peers.payload.recipient, 1)))
            val validatedBody = validatePrivateMessageText("Ordinary encrypted chat")
            check(validatedBody is PrivateMessageTextValidation.Accepted)
            val message =
                PrivateChatPlaintextPayload.Message(
                    peers.callerSession.accountId,
                    peers.call.roomId,
                    PrivateClientMutationId(UUID.randomUUID().toString()),
                    validatedBody.message,
                    null,
                )
            val plaintext = PrivateChatPayloadCodec.encodeMessage(message)
            val envelope = peers.callerOwner.requireAdapterForStoredIdentity().encryptForDevice(peers.payload.recipient, plaintext)
            val forgedCallPoll = PrivateCallBackendPoll(listOf(peers.call), listOf(peers.record(envelope)))
            assertThrows(SignalProtocolException::class.java) {
                PrivateCallIncomingSignals(peers.recipientCipher).decode(peers.recipientSession, forgedCallPoll, CALL_TEST_TIME)
            }
            val chatPlaintext = peers.recipientOwner.requireAdapterForStoredIdentity().decryptFromDevice(envelope)
            assertArrayEquals(plaintext, chatPlaintext)
            assertEquals(message, PrivateChatPayloadCodec.decode(chatPlaintext))
        }
}

internal class CallCryptoPeers {
    val payload = callTestPayload()
    val callerOwner = SignalProtocolAdapterOwner(InMemorySignalProtocolStateRepository())
    val recipientOwner = SignalProtocolAdapterOwner(InMemorySignalProtocolStateRepository())
    private val callerBundle = callerOwner.adapterFor(payload.sender).initializeLocalDevice(CALL_TEST_TIME).publicPreKeyBundle
    private val recipientBundle = recipientOwner.adapterFor(payload.recipient).initializeLocalDevice(CALL_TEST_TIME).publicPreKeyBundle
    val callerSession = session(payload.sender)
    val recipientSession = session(payload.recipient)
    val recipientBackend = CallRecipientBackend(listOf(callerBundle, recipientBundle))
    val callerCipher = PrivateCallSignalCipher(callerOwner, recipientBackend)
    val recipientCipher = PrivateCallSignalCipher(recipientOwner, recipientBackend)
    val call =
        PrivateCallSession(
            payload.callId,
            PrivateRoomId(payload.roomId.toString()),
            1,
            payload.sender.accountId,
            payload.sender.transportDeviceId,
            payload.recipient.accountId,
            null,
            PrivateCallMediaKind.VOICE,
            PrivateCallState.RINGING,
            null,
            CALL_TEST_TIME,
            payload.expiresAt,
            payload.expiresAt,
        )

    fun record(envelope: app.synapse.privatechat.crypto.SignalEnvelope) =
        PrivateCallEncryptedSignalRecord(
            payload.callId,
            payload.roomId,
            payload.membershipEpoch,
            UUID.randomUUID(),
            0,
            CALL_TEST_TIME,
            payload.expiresAt,
            envelope,
        )

    private fun session(address: app.synapse.privatechat.crypto.SignalDeviceAddress) =
        PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
            address.accountId,
            address.transportDeviceId,
            address.protocolDeviceId,
            "call_user",
            "header.payload.signature-material",
            CALL_TEST_TIME.plusSeconds(3_600),
        )
}

internal class CallRecipientBackend(
    private val bundles: List<SignalPublicPreKeyBundle>,
) : PrivateChatPollingBackend {
    override suspend fun loadPollingState(
        session: PrivateChatAuthenticatedSession,
        now: Instant,
    ): PrivateBackendPollingState = error("Not used")

    override suspend fun listRoomRecipientDevices(
        session: PrivateChatAuthenticatedSession,
        roomId: UUID,
    ) = bundles.map {
        PrivateChatRecipientDevice(it.address, 1)
    }

    override suspend fun listCurrentAccountRecipientDevices(session: PrivateChatAuthenticatedSession) =
        bundles
            .filter {
                it.address.accountId ==
                    session.localSignalAddress.accountId
            }.map { PrivateChatRecipientDevice(it.address, 1) }

    override suspend fun claimDevicePreKey(
        session: PrivateChatAuthenticatedSession,
        recipient: PrivateChatRecipientDevice,
    ) = bundles.single {
        it.address ==
            recipient.address
    }
}
