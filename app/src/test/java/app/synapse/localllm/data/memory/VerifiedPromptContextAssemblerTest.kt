package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.chat.ChatMessageId
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatThreadId
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.ids.MemoryObjectId
import app.synapse.localllm.domain.ids.MemoryVersionId
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.RetrievalBundle
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedPromptContextAssemblerTest {
    private val assembler = VerifiedPromptContextAssembler()

    @Test
    fun assemblesSystemMemoryRecentMessagesAndUserMessage() = runTest {
        val messages = assembler.assemblePromptMessages(
            userMessage = "What should we build next?",
            priorMessages = listOf(
                chatMessage(ConversationRole.USER, "Remember I prefer native Android."),
                chatMessage(ConversationRole.ASSISTANT, "Got it."),
            ),
            retrievalBundle = RetrievalBundle(
                retrievedAt = Instant.parse("2026-06-14T16:00:00Z"),
                refs = listOf(retrievedMemory()),
                promptBlock = "- [memory-1/version-1] User prefers native Android.",
            ),
            systemPrompt = "System prompt.",
        )

        assertEquals(4, messages.size)
        assertEquals(ConversationRole.SYSTEM, messages[0].role)
        assertTrue(messages[0].content.contains("Verified local memory"))
        assertEquals("Remember I prefer native Android.", messages[1].content)
        assertEquals("Got it.", messages[2].content)
        assertEquals("What should we build next?", messages[3].content)
    }

    private fun chatMessage(role: ConversationRole, body: String): ChatMessageRecord =
        ChatMessageRecord(
            id = ChatMessageId("message-${role.name}"),
            threadId = ChatThreadId("thread-1"),
            role = role,
            body = body,
            deliveryState = MessageDeliveryState.COMPLETE,
            createdAt = Instant.parse("2026-06-14T16:00:00Z"),
            completedAt = Instant.parse("2026-06-14T16:00:00Z"),
            failureReason = null,
        )

    private fun retrievedMemory(): RetrievedMemoryRef =
        RetrievedMemoryRef(
            memoryObjectId = MemoryObjectId("memory-1"),
            memoryVersionId = MemoryVersionId("version-1"),
            kind = MemoryKind.PREFERENCE,
            text = "User prefers native Android.",
            confidence = 0.86,
            reasonCodes = listOf("recent-memory"),
        )
}
