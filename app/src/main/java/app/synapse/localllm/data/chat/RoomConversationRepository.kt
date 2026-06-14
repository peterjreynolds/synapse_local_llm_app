package app.synapse.localllm.data.chat

import androidx.room.withTransaction
import app.synapse.localllm.data.db.AttachmentEntity
import app.synapse.localllm.data.db.ChatDao
import app.synapse.localllm.data.db.ChatMessageEntity
import app.synapse.localllm.data.db.ChatThreadEntity
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatThreadRecord
import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.ConversationTurnReceipt
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomConversationRepository(
    private val database: SynapseDatabase,
    private val chatDao: ChatDao,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) : ConversationRepository {
    override suspend fun ensureDefaultThread(): ChatThreadRecord =
        chatDao.findLatestThread()?.toDomain()
            ?: createDefaultThread()

    override fun observeMessages(threadId: ChatThreadId): Flow<List<ChatMessageRecord>> =
        chatDao.observeMessages(threadId.raw).map { messages ->
            messages.map { message -> message.toDomain() }
        }

    override suspend fun listRecentMessages(
        threadId: ChatThreadId,
        limit: Int,
    ): List<ChatMessageRecord> =
        chatDao.listRecentMessages(threadId.raw, limit)
            .asReversed()
            .map { message -> message.toDomain() }

    override suspend fun submitUserMessage(command: SubmitUserMessageCommand): ConversationTurnReceipt {
        val submittedAt = clock.now()
        val userMessageId = idFactory.createChatMessageId()
        val assistantMessageId = idFactory.createChatMessageId()

        database.withTransaction {
            val currentThread = chatDao.findThread(command.threadId.raw)
            chatDao.upsertMessage(
                ChatMessageEntity(
                    id = userMessageId.raw,
                    threadId = command.threadId.raw,
                    role = ConversationRole.USER.name,
                    body = command.body,
                    deliveryState = MessageDeliveryState.COMPLETE.name,
                    createdAtEpochMillis = submittedAt.toEpochMilli(),
                    completedAtEpochMillis = submittedAt.toEpochMilli(),
                    failureReason = null,
                ),
            )
            chatDao.upsertMessage(
                ChatMessageEntity(
                    id = assistantMessageId.raw,
                    threadId = command.threadId.raw,
                    role = ConversationRole.ASSISTANT.name,
                    body = "",
                    deliveryState = MessageDeliveryState.STREAMING.name,
                    createdAtEpochMillis = submittedAt.toEpochMilli() + 1,
                    completedAtEpochMillis = null,
                    failureReason = null,
                ),
            )
            chatDao.upsertAttachments(
                command.attachments.map { pendingAttachment ->
                    AttachmentEntity(
                        id = idFactory.createAttachmentId().raw,
                        messageId = userMessageId.raw,
                        displayName = pendingAttachment.displayName,
                        mimeType = pendingAttachment.mimeType,
                        uri = pendingAttachment.uri,
                        byteCount = pendingAttachment.byteCount,
                        kind = pendingAttachment.kind.name,
                        createdAtEpochMillis = submittedAt.toEpochMilli(),
                    )
                },
            )
            chatDao.upsertThread(
                ChatThreadEntity(
                    id = command.threadId.raw,
                    title = buildThreadTitle(command.body),
                    createdAtEpochMillis = currentThread?.createdAtEpochMillis
                        ?: submittedAt.toEpochMilli(),
                    updatedAtEpochMillis = submittedAt.toEpochMilli(),
                ),
            )
        }

        return ConversationTurnReceipt(
            userMessageId = userMessageId,
            assistantMessageId = assistantMessageId,
            submittedAt = submittedAt,
        )
    }

    override suspend fun appendAssistantToken(messageId: ChatMessageId, token: String) {
        database.withTransaction {
            val currentMessage = chatDao.findMessage(messageId.raw) ?: return@withTransaction
            chatDao.updateMessageDelivery(
                messageId = messageId.raw,
                body = currentMessage.body + token,
                deliveryState = MessageDeliveryState.STREAMING.name,
                completedAtEpochMillis = null,
                failureReason = null,
            )
        }
    }

    override suspend fun completeAssistantMessage(messageId: ChatMessageId) {
        val currentMessage = chatDao.findMessage(messageId.raw) ?: return
        chatDao.updateMessageDelivery(
            messageId = messageId.raw,
            body = currentMessage.body,
            deliveryState = MessageDeliveryState.COMPLETE.name,
            completedAtEpochMillis = clock.now().toEpochMilli(),
            failureReason = null,
        )
    }

    override suspend fun failAssistantMessage(messageId: ChatMessageId, reason: String) {
        val currentMessage = chatDao.findMessage(messageId.raw) ?: return
        chatDao.updateMessageDelivery(
            messageId = messageId.raw,
            body = currentMessage.body,
            deliveryState = MessageDeliveryState.FAILED.name,
            completedAtEpochMillis = clock.now().toEpochMilli(),
            failureReason = reason,
        )
    }

    private suspend fun createDefaultThread(): ChatThreadRecord {
        val now = clock.now()
        val thread = ChatThreadEntity(
            id = idFactory.createChatThreadId().raw,
            title = "Synapse",
            createdAtEpochMillis = now.toEpochMilli(),
            updatedAtEpochMillis = now.toEpochMilli(),
        )
        chatDao.upsertThread(thread)
        return thread.toDomain()
    }

    private fun buildThreadTitle(body: String): String {
        val trimmedBody = body.trim()
        return when {
            trimmedBody.isBlank() -> "Synapse"
            trimmedBody.length <= TITLE_LIMIT -> trimmedBody
            else -> trimmedBody.take(TITLE_LIMIT).trimEnd() + "..."
        }
    }

    private fun ChatThreadEntity.toDomain(): ChatThreadRecord =
        ChatThreadRecord(
            id = ChatThreadId(id),
            title = title,
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
        )

    private fun ChatMessageEntity.toDomain(): ChatMessageRecord =
        ChatMessageRecord(
            id = ChatMessageId(id),
            threadId = ChatThreadId(threadId),
            role = ConversationRole.valueOf(role),
            body = body,
            deliveryState = MessageDeliveryState.valueOf(deliveryState),
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
            completedAt = completedAtEpochMillis?.let(Instant::ofEpochMilli),
            failureReason = failureReason,
        )

    private companion object {
        const val TITLE_LIMIT = 42
    }
}
