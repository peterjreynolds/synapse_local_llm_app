package app.synapse.localllm.data.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.chat.RoomConversationRepository
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.sms.RecordSmsAutoReplyAcceptedCommand
import app.synapse.localllm.domain.sms.SmsAutoReplyState
import app.synapse.localllm.domain.sms.SmsInboundMessageKey
import app.synapse.localllm.domain.sms.SmsSenderAddress
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomSmsAutoReplyRepositoryTest {
    private lateinit var database: SynapseDatabase
    private lateinit var conversationRepository: RoomConversationRepository
    private lateinit var repository: RoomSmsAutoReplyRepository
    private lateinit var clock: IncrementingSynapseClock

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = IncrementingSynapseClock()
        val idFactory = SynapseIdFactory()
        conversationRepository = RoomConversationRepository(
            database = database,
            chatDao = database.chatDao(),
            idFactory = idFactory,
            clock = clock,
        )
        repository = RoomSmsAutoReplyRepository(
            smsAutoReplyDao = database.smsAutoReplyDao(),
            idFactory = idFactory,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun acceptedReceiptDeduplicatesInboundMessageKey() = runTest {
        val command = acceptedCommand()

        val firstReceipt = repository.recordAutoReplyAccepted(command)
        val secondReceipt = repository.recordAutoReplyAccepted(command)
        val persistedReceipt = repository.findReceiptByInboundMessageKey(command.inboundMessageKey)

        assertEquals(SmsAutoReplyState.GENERATING, firstReceipt?.state)
        assertNull(secondReceipt)
        assertEquals(firstReceipt?.id, persistedReceipt?.id)
    }

    @Test
    fun senderThreadLinkPersistsThreadForSender() = runTest {
        val thread = conversationRepository.createThread("SMS +15551234567")
        val senderAddress = SmsSenderAddress("+15551234567")

        repository.persistThreadLinkForSender(senderAddress, thread.id)

        assertEquals(thread.id, repository.findThreadLinkForSender(senderAddress)?.threadId)
    }

    private fun acceptedCommand(): RecordSmsAutoReplyAcceptedCommand =
        RecordSmsAutoReplyAcceptedCommand(
            inboundMessageKey = SmsInboundMessageKey("a".repeat(64)),
            senderAddress = SmsSenderAddress("+15551234567"),
            receivedAt = Instant.parse("2026-07-02T12:00:00Z"),
            inboundBody = "Are you free later?",
        )

    private class IncrementingSynapseClock : SynapseClock {
        private var tickMillis = 0L

        override fun now(): Instant {
            val instant = Instant.parse("2026-07-02T12:00:00Z").plusMillis(tickMillis)
            tickMillis += 10
            return instant
        }
    }
}
