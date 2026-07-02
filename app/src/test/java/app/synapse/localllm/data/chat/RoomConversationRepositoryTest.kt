package app.synapse.localllm.data.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.data.sms.RoomSmsAutoReplyRepository
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.sms.LinkSmsAutoReplyTurnCommand
import app.synapse.localllm.domain.sms.RecordSmsAutoReplyAcceptedCommand
import app.synapse.localllm.domain.sms.SmsInboundMessageKey
import app.synapse.localllm.domain.sms.SmsSenderAddress
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomConversationRepositoryTest {
    private lateinit var database: SynapseDatabase
    private lateinit var repository: RoomConversationRepository
    private lateinit var smsAutoReplyRepository: RoomSmsAutoReplyRepository
    private lateinit var clock: IncrementingSynapseClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = IncrementingSynapseClock()
        repository = RoomConversationRepository(
            database = database,
            chatDao = database.chatDao(),
            idFactory = SynapseIdFactory(),
            clock = clock,
        )
        smsAutoReplyRepository = RoomSmsAutoReplyRepository(
            smsAutoReplyDao = database.smsAutoReplyDao(),
            idFactory = SynapseIdFactory(),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun submitUserMessageKeepsMessagesAfterThreadSummaryUpdate() = runTest {
        val thread = repository.ensureDefaultThread()

        repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "First message",
                attachments = emptyList(),
            ),
        )
        repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "Second message",
                attachments = emptyList(),
            ),
        )

        val recentMessages = repository.listRecentMessages(thread.id, limit = 10)

        assertEquals(4, recentMessages.size)
        assertEquals(
            listOf(
                ConversationRole.USER,
                ConversationRole.ASSISTANT,
                ConversationRole.USER,
                ConversationRole.ASSISTANT,
            ),
            recentMessages.map { message -> message.role },
        )
        assertEquals(MessageDeliveryState.COMPLETE, recentMessages[0].deliveryState)
        assertEquals(MessageDeliveryState.STREAMING, recentMessages[1].deliveryState)
        assertEquals("First message", recentMessages[0].body)
        assertEquals("Second message", recentMessages[2].body)
    }

    @Test
    fun failStaleStreamingAssistantMessagesMarksInterruptedAssistantMessagesFailed() = runTest {
        val thread = repository.ensureDefaultThread()
        repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "Write a long answer",
                attachments = emptyList(),
            ),
        )

        val failedCount = repository.failStaleStreamingAssistantMessages(
            reason = "Generation was interrupted before Synapse reopened.",
            activeSmsAutoReplyAfter = Instant.parse("2026-06-15T13:00:00Z"),
        )
        val recentMessages = repository.listRecentMessages(thread.id, limit = 10)

        assertEquals(1, failedCount)
        assertEquals(MessageDeliveryState.FAILED, recentMessages[1].deliveryState)
        assertEquals(
            "Generation was interrupted before Synapse reopened.",
            recentMessages[1].failureReason,
        )
    }

    @Test
    fun failStaleStreamingAssistantMessagesSkipsRecentSmsAutoReplyTurn() = runTest {
        val thread = repository.createThread("SMS +15551234567")
        val turnReceipt = repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "Incoming SMS from +15551234567:\nOn my way",
                attachments = emptyList(),
            ),
        )
        val receipt = smsAutoReplyRepository.recordAutoReplyAccepted(acceptedCommand("recent"))!!
        smsAutoReplyRepository.linkAutoReplyTurn(
            LinkSmsAutoReplyTurnCommand(
                receiptId = receipt.id,
                threadId = thread.id,
                userMessageId = turnReceipt.userMessageId,
                assistantMessageId = turnReceipt.assistantMessageId,
            ),
        )

        val failedCount = repository.failStaleStreamingAssistantMessages(
            reason = "Generation was interrupted before Synapse reopened.",
            activeSmsAutoReplyAfter = Instant.parse("2026-06-15T13:59:00Z"),
        )
        val recentMessages = repository.listRecentMessages(thread.id, limit = 10)

        assertEquals(0, failedCount)
        assertEquals(MessageDeliveryState.STREAMING, recentMessages[1].deliveryState)
    }

    @Test
    fun failStaleStreamingAssistantMessagesFailsOldSmsAutoReplyTurn() = runTest {
        val thread = repository.createThread("SMS +15551234567")
        val turnReceipt = repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "Incoming SMS from +15551234567:\nStill there?",
                attachments = emptyList(),
            ),
        )
        val receipt = smsAutoReplyRepository.recordAutoReplyAccepted(acceptedCommand("old"))!!
        smsAutoReplyRepository.linkAutoReplyTurn(
            LinkSmsAutoReplyTurnCommand(
                receiptId = receipt.id,
                threadId = thread.id,
                userMessageId = turnReceipt.userMessageId,
                assistantMessageId = turnReceipt.assistantMessageId,
            ),
        )

        val failedCount = repository.failStaleStreamingAssistantMessages(
            reason = "Generation was interrupted before Synapse reopened.",
            activeSmsAutoReplyAfter = Instant.parse("2026-06-15T14:01:00Z"),
        )
        val recentMessages = repository.listRecentMessages(thread.id, limit = 10)

        assertEquals(1, failedCount)
        assertEquals(MessageDeliveryState.FAILED, recentMessages[1].deliveryState)
    }

    @Test
    fun observeThreadsOrdersPinnedThreadsBeforeRecentThreads() = runTest {
        val olderThread = repository.createThread()
        val newerThread = repository.createThread()

        repository.setThreadPinned(olderThread.id, pinned = true)

        val pinnedThreads = repository.observeThreads().first()
        assertEquals(
            listOf(olderThread.id, newerThread.id),
            pinnedThreads.map { thread -> thread.id },
        )
        assertEquals(true, pinnedThreads.first().isPinned)

        repository.setThreadPinned(olderThread.id, pinned = false)

        val unpinnedThreads = repository.observeThreads().first()
        assertEquals(
            listOf(newerThread.id, olderThread.id),
            unpinnedThreads.map { thread -> thread.id },
        )
        assertEquals(false, unpinnedThreads.last().isPinned)
    }

    @Test
    fun renameThreadPreservesManualTitleAfterNewMessages() = runTest {
        val thread = repository.createThread()

        repository.renameThread(thread.id, "Pinned project brain")
        repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "This message should not replace the manual title",
                attachments = emptyList(),
            ),
        )

        val threads = repository.observeThreads().first()
        assertEquals("Pinned project brain", threads.first { it.id == thread.id }.title)
    }

    @Test
    fun createThreadAcceptsDomainTitle() = runTest {
        val thread = repository.createThread("SMS +15551234567")

        assertEquals("SMS +15551234567", thread.title)
    }

    @Test
    fun findMessageReturnsPersistedMessageById() = runTest {
        val thread = repository.ensureDefaultThread()
        val receipt = repository.submitUserMessage(
            SubmitUserMessageCommand(
                threadId = thread.id,
                body = "Find this",
                attachments = emptyList(),
            ),
        )

        val message = repository.findMessage(receipt.userMessageId)

        assertEquals("Find this", message?.body)
    }

    @Test
    fun archiveAndDeleteRemoveThreadsFromRecentList() = runTest {
        val archivedThread = repository.createThread()
        val deletedThread = repository.createThread()
        val visibleThread = repository.createThread()

        repository.archiveThread(archivedThread.id)
        repository.deleteThread(deletedThread.id)

        val visibleThreads = repository.observeThreads().first()
        assertEquals(listOf(visibleThread.id), visibleThreads.map { thread -> thread.id })
    }

    private class IncrementingSynapseClock : SynapseClock {
        private var tickMillis = 0L

        override fun now(): Instant {
            val instant = Instant.parse("2026-06-15T14:00:00Z").plusMillis(tickMillis)
            tickMillis += 10
            return instant
        }
    }

    private fun acceptedCommand(keySuffix: String): RecordSmsAutoReplyAcceptedCommand =
        RecordSmsAutoReplyAcceptedCommand(
            inboundMessageKey = SmsInboundMessageKey("sms-$keySuffix"),
            senderAddress = SmsSenderAddress("+15551234567"),
            receivedAt = Instant.parse("2026-06-15T14:00:00Z"),
            inboundBody = "Incoming SMS body",
        )
}
