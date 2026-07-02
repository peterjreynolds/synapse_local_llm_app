package app.synapse.localllm.data.sms

import app.synapse.localllm.data.db.SmsAutoReplyDao
import app.synapse.localllm.data.db.SmsAutoReplyReceiptEntity
import app.synapse.localllm.data.db.SmsSenderThreadEntity
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.ReceiptId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.sms.LinkSmsAutoReplyTurnCommand
import app.synapse.localllm.domain.sms.MarkSmsAutoReplyQueuedCommand
import app.synapse.localllm.domain.sms.RecordSmsAutoReplyAcceptedCommand
import app.synapse.localllm.domain.sms.SmsAutoReplyReceiptRecord
import app.synapse.localllm.domain.sms.SmsAutoReplyRepository
import app.synapse.localllm.domain.sms.SmsAutoReplyState
import app.synapse.localllm.domain.sms.SmsInboundMessageKey
import app.synapse.localllm.domain.sms.SmsSenderAddress
import app.synapse.localllm.domain.sms.SmsSenderThreadLink
import app.synapse.localllm.domain.sms.smsBodySha256Hex
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant

class RoomSmsAutoReplyRepository(
    private val smsAutoReplyDao: SmsAutoReplyDao,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) : SmsAutoReplyRepository {
    override suspend fun findReceiptByInboundMessageKey(
        inboundMessageKey: SmsInboundMessageKey,
    ): SmsAutoReplyReceiptRecord? =
        smsAutoReplyDao.findReceiptByInboundMessageKey(inboundMessageKey.raw)?.toDomain()

    override suspend fun recordAutoReplyAccepted(
        command: RecordSmsAutoReplyAcceptedCommand,
    ): SmsAutoReplyReceiptRecord? {
        val now = clock.now()
        val receipt = SmsAutoReplyReceiptEntity(
            id = idFactory.createReceiptId().raw,
            inboundMessageKey = command.inboundMessageKey.raw,
            senderAddress = command.senderAddress.raw,
            inboundBodySha256 = smsBodySha256Hex(command.inboundBody),
            inboundCharacterCount = command.inboundBody.length,
            inboundReceivedAtEpochMillis = command.receivedAt.toEpochMilli(),
            threadId = null,
            userMessageId = null,
            assistantMessageId = null,
            state = SmsAutoReplyState.GENERATING.name,
            replyBodySha256 = null,
            replyCharacterCount = 0,
            smsPartCount = 0,
            queuedAtEpochMillis = null,
            decidedAtEpochMillis = now.toEpochMilli(),
            failureReason = null,
        )
        val inserted = smsAutoReplyDao.insertReceipt(receipt)
        return if (inserted == INSERT_IGNORED) {
            null
        } else {
            receipt.toDomain()
        }
    }

    override suspend fun recordAutoReplySkipped(
        command: RecordSmsAutoReplyAcceptedCommand,
        state: SmsAutoReplyState,
        reason: String,
    ): SmsAutoReplyReceiptRecord {
        val now = clock.now()
        val receipt = SmsAutoReplyReceiptEntity(
            id = idFactory.createReceiptId().raw,
            inboundMessageKey = command.inboundMessageKey.raw,
            senderAddress = command.senderAddress.raw,
            inboundBodySha256 = smsBodySha256Hex(command.inboundBody),
            inboundCharacterCount = command.inboundBody.length,
            inboundReceivedAtEpochMillis = command.receivedAt.toEpochMilli(),
            threadId = null,
            userMessageId = null,
            assistantMessageId = null,
            state = state.name,
            replyBodySha256 = null,
            replyCharacterCount = 0,
            smsPartCount = 0,
            queuedAtEpochMillis = null,
            decidedAtEpochMillis = now.toEpochMilli(),
            failureReason = reason,
        )
        smsAutoReplyDao.insertReceipt(receipt)
        return smsAutoReplyDao.findReceiptByInboundMessageKey(command.inboundMessageKey.raw)
            ?.toDomain()
            ?: receipt.toDomain()
    }

    override suspend fun linkAutoReplyTurn(
        command: LinkSmsAutoReplyTurnCommand,
    ): SmsAutoReplyReceiptRecord {
        smsAutoReplyDao.linkReceiptTurn(
            receiptId = command.receiptId.raw,
            threadId = command.threadId.raw,
            userMessageId = command.userMessageId.raw,
            assistantMessageId = command.assistantMessageId.raw,
            state = SmsAutoReplyState.GENERATING.name,
            decidedAtEpochMillis = clock.now().toEpochMilli(),
        )
        return requireReceipt(command.receiptId)
    }

    override suspend fun markAutoReplyQueued(
        command: MarkSmsAutoReplyQueuedCommand,
    ): SmsAutoReplyReceiptRecord {
        smsAutoReplyDao.markReceiptQueued(
            receiptId = command.receiptId.raw,
            state = SmsAutoReplyState.SMS_QUEUED.name,
            replyBodySha256 = smsBodySha256Hex(command.replyBody),
            replyCharacterCount = command.replyBody.length,
            smsPartCount = command.smsPartCount,
            queuedAtEpochMillis = command.queuedAt.toEpochMilli(),
            decidedAtEpochMillis = clock.now().toEpochMilli(),
        )
        return requireReceipt(command.receiptId)
    }

    override suspend fun markAutoReplyFailed(
        receiptId: ReceiptId,
        state: SmsAutoReplyState,
        reason: String,
    ): SmsAutoReplyReceiptRecord {
        smsAutoReplyDao.markReceiptFailed(
            receiptId = receiptId.raw,
            state = state.name,
            decidedAtEpochMillis = clock.now().toEpochMilli(),
            failureReason = reason,
        )
        return requireReceipt(receiptId)
    }

    override suspend fun markStaleGeneratingAutoRepliesFailed(
        staleBefore: Instant,
        reason: String,
    ): Int =
        smsAutoReplyDao.failStaleGeneratingReceipts(
            generatingState = SmsAutoReplyState.GENERATING.name,
            failedState = SmsAutoReplyState.GENERATION_FAILED.name,
            staleBeforeEpochMillis = staleBefore.toEpochMilli(),
            decidedAtEpochMillis = clock.now().toEpochMilli(),
            failureReason = reason,
        )

    override suspend fun findThreadLinkForSender(senderAddress: SmsSenderAddress): SmsSenderThreadLink? =
        smsAutoReplyDao.findSenderThread(senderAddress.raw)?.toDomain()

    override suspend fun persistThreadLinkForSender(
        senderAddress: SmsSenderAddress,
        threadId: ChatThreadId,
    ): SmsSenderThreadLink {
        val existingLink = smsAutoReplyDao.findSenderThread(senderAddress.raw)
        val now = clock.now()
        val senderThread = SmsSenderThreadEntity(
            senderAddress = senderAddress.raw,
            threadId = threadId.raw,
            createdAtEpochMillis = existingLink?.createdAtEpochMillis ?: now.toEpochMilli(),
            updatedAtEpochMillis = now.toEpochMilli(),
        )
        smsAutoReplyDao.upsertSenderThread(senderThread)
        return senderThread.toDomain()
    }

    private suspend fun requireReceipt(receiptId: ReceiptId): SmsAutoReplyReceiptRecord =
        checkNotNull(smsAutoReplyDao.findReceiptById(receiptId.raw)?.toDomain()) {
            "SMS auto-reply receipt ${receiptId.raw} was not found after mutation."
        }

    private fun SmsAutoReplyReceiptEntity.toDomain(): SmsAutoReplyReceiptRecord =
        SmsAutoReplyReceiptRecord(
            id = ReceiptId(id),
            inboundMessageKey = SmsInboundMessageKey(inboundMessageKey),
            senderAddress = SmsSenderAddress(senderAddress),
            threadId = threadId?.let(::ChatThreadId),
            userMessageId = userMessageId?.let(::ChatMessageId),
            assistantMessageId = assistantMessageId?.let(::ChatMessageId),
            state = SmsAutoReplyState.valueOf(state),
            failureReason = failureReason,
            smsPartCount = smsPartCount,
            decidedAt = Instant.ofEpochMilli(decidedAtEpochMillis),
        )

    private fun SmsSenderThreadEntity.toDomain(): SmsSenderThreadLink =
        SmsSenderThreadLink(
            senderAddress = SmsSenderAddress(senderAddress),
            threadId = ChatThreadId(threadId),
        )

    private companion object {
        const val INSERT_IGNORED = -1L
    }
}
