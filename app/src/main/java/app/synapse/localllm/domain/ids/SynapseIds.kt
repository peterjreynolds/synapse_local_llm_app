package app.synapse.localllm.domain.ids

import java.util.UUID

@JvmInline
value class ChatThreadId(val raw: String)

@JvmInline
value class ChatMessageId(val raw: String)

@JvmInline
value class AttachmentId(val raw: String)

@JvmInline
value class TraceEventId(val raw: String)

@JvmInline
value class MemoryObjectId(val raw: String)

@JvmInline
value class MemoryVersionId(val raw: String)

@JvmInline
value class ReceiptId(val raw: String)

@JvmInline
value class AssistantGenerationTraceId(val raw: String)

class SynapseIdFactory {
    fun createChatThreadId(): ChatThreadId = ChatThreadId(createPrefixedUuid("thread"))

    fun createChatMessageId(): ChatMessageId = ChatMessageId(createPrefixedUuid("message"))

    fun createAttachmentId(): AttachmentId = AttachmentId(createPrefixedUuid("attachment"))

    fun createTraceEventId(): TraceEventId = TraceEventId(createPrefixedUuid("trace"))

    fun createMemoryObjectId(): MemoryObjectId = MemoryObjectId(createPrefixedUuid("memory"))

    fun createMemoryVersionId(): MemoryVersionId = MemoryVersionId(createPrefixedUuid("version"))

    fun createReceiptId(): ReceiptId = ReceiptId(createPrefixedUuid("receipt"))

    fun createAssistantGenerationTraceId(): AssistantGenerationTraceId =
        AssistantGenerationTraceId(createPrefixedUuid("generation"))

    private fun createPrefixedUuid(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
