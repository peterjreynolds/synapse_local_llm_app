package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAssistantAvailability
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageSendReceipt
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.SendRemoteMessageCommand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConversationGatewayRouterTest {
    @Test
    fun cinderSendFailsClosedWithoutCallingFirebaseOrInventingAReply() = runTest {
        val firebaseGateway = mockk<RemoteConversationGateway>(relaxed = true) {
            every { observeMessages(any(), any()) } returns emptyFlow()
        }
        val router = RemoteConversationGatewayRouter(
            synchronizedConversationGateway = firebaseGateway,
            assistantConversationGateway = UnavailableRemoteAssistantConversationGateway(),
        )
        val command = SendRemoteMessageCommand(humanMessage(RemoteAssistantConversationCatalog.cinder.roomId))

        val failure = runCatching { router.sendMessage(command) }.exceptionOrNull()
        assertTrue(failure is RemoteChatException)
        val userMessage = requireNotNull((failure as? RemoteChatException)?.userMessage)

        assertEquals(
            "Cinder is not connected yet. An authenticated Cinder chat backend must be configured.",
            userMessage,
        )
        assertEquals(
            RemoteAssistantAvailability.Unavailable(userMessage),
            router.assistantAvailability(RemoteAssistantConversationCatalog.cinder.roomId),
        )
        assertTrue(
            router.observeMessages(ACCOUNT_UID, RemoteAssistantConversationCatalog.cinder.roomId)
                .toList()
                .isEmpty(),
        )
        verify(exactly = 0) {
            firebaseGateway.observeMessages(ACCOUNT_UID, RemoteAssistantConversationCatalog.cinder.roomId)
        }
        coVerify(exactly = 0) { firebaseGateway.sendMessage(any()) }
    }

    @Test
    fun ordinaryRemoteRoomsStillUseTheExistingSynchronizedGateway() = runTest {
        val firebaseGateway = mockk<RemoteConversationGateway>(relaxed = true)
        val command = SendRemoteMessageCommand(humanMessage(NORMAL_ROOM_ID))
        val expectedReceipt = RemoteMessageSendReceipt(ACCOUNT_UID, NORMAL_ROOM_ID, command.message.messageId)
        coEvery { firebaseGateway.sendMessage(command) } returns expectedReceipt
        val router = RemoteConversationGatewayRouter(
            synchronizedConversationGateway = firebaseGateway,
            assistantConversationGateway = UnavailableRemoteAssistantConversationGateway(),
        )

        assertEquals(expectedReceipt, router.sendMessage(command))
        coVerify(exactly = 1) { firebaseGateway.sendMessage(command) }
    }

    private fun humanMessage(roomId: RemoteRoomId) = RemoteCachedMessage(
        accountUid = ACCOUNT_UID,
        roomId = roomId,
        messageId = RemoteMessageId("message-1"),
        idempotencyKey = RemoteIdempotencyKey("message-1"),
        senderUid = RemoteProfileUid(ACCOUNT_UID.raw),
        authorKind = "HUMAN",
        body = "Hello",
        replyToMessageId = null,
        editedAt = null,
        deletedAt = null,
        revision = 1,
        reactionCounts = emptyMap(),
        deliveredToCount = 0,
        readByCount = 0,
        deliveryState = RemoteMessageDeliveryState.PENDING,
        clientCreatedAt = Instant.EPOCH,
        serverCreatedAt = null,
        failureReason = null,
    )

    private companion object {
        val ACCOUNT_UID = RemoteAccountUid("peter-uid")
        val NORMAL_ROOM_ID = RemoteRoomId("group_${"a".repeat(32)}")
    }
}
