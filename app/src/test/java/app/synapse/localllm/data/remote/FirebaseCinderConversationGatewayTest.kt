package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAiProvenance
import app.synapse.localllm.domain.remote.RemoteAssistantAvailability
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.SendRemoteMessageCommand
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseCinderConversationGatewayTest {
    @Test
    fun availabilityAndSubmitUseAuthenticatedServerReceipts() = runTest {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val transport = object : CinderCallableTransport {
            override suspend fun call(
                callableName: String,
                payload: Map<String, Any?>,
            ): Any = when (callableName) {
                "getCinderAvailability" -> mapOf(
                    "available" to true,
                    "availableUntilMillis" to 10_000L,
                    "checkedAtMillis" to 1_000L,
                    "protocolVersion" to 1L,
                )
                "submitCinderMessage" -> mapOf(
                    "acceptance" to "ACCEPTED",
                    "messageId" to "message-1",
                    "revision" to 1L,
                    "roomId" to "assistant_cinder",
                    "sequence" to 7L,
                )
                else -> error("Unexpected callable $callableName")
            }.also { calls += callableName to payload }
        }
        val sessionController = RemoteAccountSessionCoordinator().apply { beginSession(ACCOUNT_UID) }
        val gateway = FirebaseCinderConversationGateway(
            currentFirebaseUid = { ACCOUNT_UID.raw },
            callableTransport = transport,
            sessionController = sessionController,
            currentTimeMillis = { 1_000L },
            availabilityPollMillis = 1_000L,
            messagePollMillis = 1_000L,
        )

        assertEquals(
            RemoteAssistantAvailability.Available,
            gateway.observeAvailability(ACCOUNT_UID, ENDPOINT).first(),
        )
        val receipt = gateway.sendMessage(ENDPOINT, SendRemoteMessageCommand(humanMessage()))

        assertEquals(RemoteMessageId("message-1"), receipt.messageId)
        assertEquals(listOf("getCinderAvailability", "submitCinderMessage"), calls.map { call -> call.first })
        assertEquals(emptyList<String>(), calls.last().second["attachmentIds"])
        assertEquals(null, calls.last().second["replyToMessageId"])
    }

    @Test
    fun syncMapsServerOrderedHumanAndExactCinderTurnsIntoTheSharedMessageModel() = runTest {
        val transport = object : CinderCallableTransport {
            override suspend fun call(
                callableName: String,
                payload: Map<String, Any?>,
            ): Any {
                assertEquals("syncCinderMessages", callableName)
                assertEquals(0L, payload["afterSequence"])
                return mapOf(
                    "hasMore" to false,
                    "messages" to listOf(
                        cinderMessageRecord(
                            authorKind = "HUMAN",
                            messageId = "message-1",
                            senderUid = ACCOUNT_UID.raw,
                            sequence = 1L,
                            sourceMessageId = null,
                        ),
                        cinderMessageRecord(
                            authorKind = "REMOTE_AI",
                            messageId = "cinder-response-1",
                            senderUid = ENDPOINT.participantId.raw,
                            sequence = 2L,
                            sourceMessageId = "message-1",
                        ),
                    ),
                    "nextSequence" to 2L,
                )
            }
        }
        val sessionController = RemoteAccountSessionCoordinator().apply { beginSession(ACCOUNT_UID) }
        val gateway = FirebaseCinderConversationGateway(
            currentFirebaseUid = { ACCOUNT_UID.raw },
            callableTransport = transport,
            sessionController = sessionController,
            currentTimeMillis = { 1_000L },
            availabilityPollMillis = 1_000L,
            messagePollMillis = 1_000L,
        )

        val messages = gateway.observeMessages(ACCOUNT_UID, ENDPOINT).first()

        assertEquals(listOf(1L, 2L), messages.map(RemoteCachedMessage::serverSequence))
        assertEquals("REMOTE_AI", messages.last().authorKind)
        assertEquals(ENDPOINT.participantId.raw, messages.last().aiParticipantId)
        assertEquals(RemoteAiProvenance.REMOTE_HOSTED, messages.last().aiProvenance)
        assertEquals(RemoteMessageId("message-1"), messages.last().replyToMessageId)
    }

    @Test
    fun forgedRemoteProviderAndExpiredAvailabilityFailClosed() {
        val forgedPage = mapOf(
            "hasMore" to false,
            "messages" to listOf(
                cinderMessageRecord(
                    authorKind = "REMOTE_AI",
                    messageId = "cinder-response-1",
                    senderUid = ENDPOINT.participantId.raw,
                    sequence = 1L,
                    sourceMessageId = "message-1",
                ) + ("aiProvider" to "FORGED"),
            ),
            "nextSequence" to 1L,
        )
        val availabilityFailure = runCatching {
            mapOf(
                "available" to true,
                "availableUntilMillis" to 999L,
                "checkedAtMillis" to 1_000L,
                "protocolVersion" to 1L,
            ).toCinderAvailabilityReceipt()
        }.exceptionOrNull()
        assertTrue(availabilityFailure is app.synapse.localllm.domain.remote.RemoteChatException)
        val failure = runCatching {
            forgedPage.toCinderMessageSyncPage(ACCOUNT_UID, ENDPOINT, afterSequence = 0L)
        }.exceptionOrNull()
        assertTrue(failure is app.synapse.localllm.domain.remote.RemoteChatException)
    }

    private fun humanMessage() = RemoteCachedMessage(
        accountUid = ACCOUNT_UID,
        roomId = ENDPOINT.roomId,
        messageId = RemoteMessageId("message-1"),
        idempotencyKey = RemoteIdempotencyKey("message-1"),
        senderUid = RemoteProfileUid(ACCOUNT_UID.raw),
        authorKind = "HUMAN",
        body = "Hello Cinder",
        replyToMessageId = null,
        editedAt = null,
        deletedAt = null,
        revision = 1L,
        reactionCounts = emptyMap(),
        deliveredToCount = 0,
        readByCount = 0,
        deliveryState = RemoteMessageDeliveryState.PENDING,
        clientCreatedAt = Instant.ofEpochMilli(1_000L),
        serverCreatedAt = null,
        failureReason = null,
    )

    private fun cinderMessageRecord(
        authorKind: String,
        messageId: String,
        senderUid: String,
        sequence: Long,
        sourceMessageId: String?,
    ): Map<String, Any?> {
        val remoteAi = authorKind == "REMOTE_AI"
        return mapOf(
            "aiParticipantId" to ENDPOINT.participantId.raw.takeIf { remoteAi },
            "aiProvenance" to "REMOTE_HOSTED".takeIf { remoteAi },
            "aiProvider" to "OPENCLAW_CINDER".takeIf { remoteAi },
            "assistantId" to ENDPOINT.assistantId.raw,
            "authorKind" to authorKind,
            "body" to if (remoteAi) "Cinder response" else "Human turn",
            "clientCreatedAtMillis" to 1_000L + sequence,
            "createdAtMillis" to 2_000L + sequence,
            "idempotencyKey" to messageId,
            "messageId" to messageId,
            "revision" to 1L,
            "roomId" to ENDPOINT.roomId.raw,
            "senderUid" to senderUid,
            "sequence" to sequence,
            "sourceMessageId" to sourceMessageId,
        )
    }

    private companion object {
        val ACCOUNT_UID = RemoteAccountUid("peter-uid")
        val ENDPOINT = RemoteAssistantConversationCatalog.cinder
    }
}
