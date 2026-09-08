package app.synapse.privatechat.data.chat

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalEnvelope
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.crypto.local.AndroidDeviceLocalContentEnvelopeCipherFactory
import app.synapse.privatechat.crypto.storage.AndroidSignalProtocolStateRepositoryFactory
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Run recoveryPhase=seed, force-stop the test app, then recoveryPhase=recover to prove process death. */
@RunWith(AndroidJUnit4::class)
class AndroidPrivateOutboxRecoveryTest {
    @Test
    fun acceptedMessageRecoversExactCiphertextAndPeerRatchet() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase", "both")
            require(phase in setOf("seed", "recover", "both"))
            val senderContext = isolatedContext(context, "outbox-sender")
            val receiverContext = isolatedContext(context, "outbox-receiver")
            val acceptedRequest = File(senderContext.noBackupFilesDir, "accepted-request.json")
            val seedProcess = File(senderContext.noBackupFilesDir, "seed-process.txt")

            if (phase != "recover") {
                check(!acceptedRequest.exists()) { "Use a fresh test installation for the recovery fixture" }
                val sender = signalOwner(senderContext)
                val receiver = signalOwner(receiverContext)
                sender.adapterFor(SENDER).initializeLocalDevice(NOW)
                val receiverBundle = receiver.adapterFor(RECEIVER).initializeLocalDevice(NOW).publicPreKeyBundle
                sender.requireAdapterForStoredIdentity().establishPairwiseSession(receiverBundle)
                seedProcess.writeText(Process.myPid().toString())
                val failure =
                    runCatching {
                        outbox(senderContext, sender, acceptedRequest, 503).execute(
                            session("expired-response-token-material"),
                            INTENT,
                            PLAINTEXT,
                            listOf(SENDER, RECEIVER).map { PrivateChatRecipientDevice(it, SignalEnvelope.CURRENT_PROTOCOL_VERSION) },
                        )
                    }.exceptionOrNull()
                check(failure is SupabasePrivateChatRequestRejectedException)
                assertEquals(503, failure.statusCode)
                assertEquals(1, sender.requireAdapterForStoredIdentity().listPendingOutboundMutations().size)
                assertTrue(sender.requireAdapterForStoredIdentity().hasPairwiseSession(RECEIVER))
            }

            if (phase != "seed") {
                if (phase == "recover") assertNotEquals(seedProcess.readText(), Process.myPid().toString())
                val sender = signalOwner(senderContext)
                val receiver = signalOwner(receiverContext)
                val senderAdapter = sender.requireAdapterForStoredIdentity()
                val pending = senderAdapter.listPendingOutboundMutations().single()
                val request = PrivateEncryptedMutationCodec.decode(pending.opaqueRequest)
                check(request is PrivatePendingEncryptedMutation.SendMessage)
                val recovered =
                    outbox(senderContext, sender, acceptedRequest, 200)
                        .recoverPendingMutations(session("refreshed-access-token-material"))
                assertEquals(setOf(INTENT.clientMutationId), recovered)
                assertTrue(senderAdapter.listPendingOutboundMutations().isEmpty())
                assertTrue(senderAdapter.hasPairwiseSession(RECEIVER))
                val peerEnvelope = request.envelopes.single { it.recipientDeviceId == RECEIVER.transportDeviceId }
                val decrypted =
                    receiver.requireAdapterForStoredIdentity().decryptFromDevice(
                        SignalEnvelope.fromWire(
                            protocolVersion = peerEnvelope.protocolAdapterVersion,
                            sender = SENDER,
                            recipient = RECEIVER,
                            ciphertextTypeCode = 3,
                            serializedCiphertext = peerEnvelope.ciphertextCopy(),
                        ),
                    )
                assertArrayEquals(PLAINTEXT, decrypted)
                val acknowledgement = receiver.requireAdapterForStoredIdentity().encryptForDevice(SENDER, ACKNOWLEDGEMENT)
                assertArrayEquals(ACKNOWLEDGEMENT, senderAdapter.decryptFromDevice(acknowledgement))
                assertTrue(signalOwner(senderContext).requireAdapterForStoredIdentity().listPendingOutboundMutations().isEmpty())
            }
        }

    private fun outbox(
        context: Context,
        owner: SignalProtocolAdapterOwner,
        acceptedRequest: File,
        responseStatus: Int,
    ): PrivateEncryptedMutationOutbox {
        val executor = SupabasePrivateChatRequestExecutor(PersistedAcceptanceTransport(acceptedRequest, responseStatus)) {}
        val mutations = SupabasePrivateChatMutationTransport(executor)
        return PrivateEncryptedMutationOutbox(
            PrivateChatEnvelopeCipher(
                LibSignalPrivateChatCipher(owner),
                AndroidDeviceLocalContentEnvelopeCipherFactory.create(context),
            ),
            SupabasePrivateChatBackend(
                SupabasePrivateChatPollingApi(executor),
                SupabasePrivateContentMutationApi(mutations),
                SupabasePrivateRoomMutationApi(mutations),
                SupabasePrivateSocialMutationApi(mutations),
            ),
            Clock.fixed(NOW, ZoneOffset.UTC),
        )
    }

    private fun signalOwner(context: Context): SignalProtocolAdapterOwner =
        SignalProtocolAdapterOwner(AndroidSignalProtocolStateRepositoryFactory.create(context))

    private fun isolatedContext(
        base: Context,
        name: String,
    ): Context =
        object : ContextWrapper(base) {
            override fun getNoBackupFilesDir(): File = File(base.noBackupFilesDir, name).also { it.mkdirs() }
        }

    private fun session(token: String): PrivateChatAuthenticatedSession =
        PrivateChatAuthenticatedSession.fromAuthenticatedDevice(
            accountId = SENDER.accountId,
            transportDeviceId = SENDER.transportDeviceId,
            signalDeviceId = SENDER.protocolDeviceId,
            authenticationUsername = "recovery_test",
            accessToken = token,
            expiresAt = NOW.plusSeconds(3_600),
        )

    private class PersistedAcceptanceTransport(
        private val acceptedRequest: File,
        private val responseStatus: Int,
    ) : SupabaseHttpTransport {
        override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
            assertEquals(listOf("rest", "v1", "rpc", "send_message"), request.pathSegments)
            val encodedRequest = requireNotNull(request.jsonBody).toString()
            if (acceptedRequest.exists()) {
                assertEquals(acceptedRequest.readText(), encodedRequest)
            } else {
                check(responseStatus == 503)
                acceptedRequest.writeText(encodedRequest)
            }
            if (responseStatus != 200) return SupabaseHttpResponse(responseStatus, null)
            return SupabaseHttpResponse(
                200,
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("message_id", "60000000-0000-4000-8000-000000000006")
                            put("room_id", INTENT.roomId.toString())
                            put("client_mutation_id", INTENT.clientMutationId.toString())
                            put("expires_at", NOW.plusSeconds(86_400).toString())
                        },
                    ),
                ),
            )
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-08T12:00:00Z")
        val SENDER =
            SignalDeviceAddress.fromWire(
                "10000000-0000-4000-8000-000000000001",
                "20000000-0000-4000-8000-000000000002",
                7,
            )
        val RECEIVER =
            SignalDeviceAddress.fromWire(
                "10000000-0000-4000-8000-000000000003",
                "20000000-0000-4000-8000-000000000004",
                8,
            )
        val INTENT =
            PrivateEncryptedMutationIntent.SendMessage(
                UUID.fromString("30000000-0000-4000-8000-000000000003"),
                UUID.fromString("40000000-0000-4000-8000-000000000004"),
                null,
            )
        val PLAINTEXT = "process death recovery fixture".encodeToByteArray()
        val ACKNOWLEDGEMENT = "recipient decrypted acknowledgement".encodeToByteArray()
    }
}
