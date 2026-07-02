package app.synapse.localllm.domain.sms

import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.ReceiptId
import java.security.MessageDigest
import java.time.Instant

@JvmInline
value class SmsInboundMessageKey(val raw: String)

@JvmInline
value class SmsSenderAddress(val raw: String)

enum class SmsAutoReplyState {
    AUTO_REPLY_DISABLED,
    GENERATING,
    DUPLICATE_IGNORED,
    GENERATION_FAILED,
    EMPTY_REPLY_REJECTED,
    SMS_QUEUED,
    SMS_QUEUE_FAILED,
    INVALID_INBOUND_MESSAGE,
}

data class InboundSmsAutoReplyCommand(
    val inboundMessageKey: SmsInboundMessageKey,
    val senderAddress: SmsSenderAddress,
    val messageBody: String,
    val receivedAt: Instant,
)

data class SmsSenderThreadLink(
    val senderAddress: SmsSenderAddress,
    val threadId: ChatThreadId,
)

data class SmsAutoReplyReceiptRecord(
    val id: ReceiptId,
    val inboundMessageKey: SmsInboundMessageKey,
    val senderAddress: SmsSenderAddress,
    val threadId: ChatThreadId?,
    val userMessageId: ChatMessageId?,
    val assistantMessageId: ChatMessageId?,
    val state: SmsAutoReplyState,
    val failureReason: String?,
    val smsPartCount: Int,
    val decidedAt: Instant,
)

data class RecordSmsAutoReplyAcceptedCommand(
    val inboundMessageKey: SmsInboundMessageKey,
    val senderAddress: SmsSenderAddress,
    val receivedAt: Instant,
    val inboundBody: String,
)

data class LinkSmsAutoReplyTurnCommand(
    val receiptId: ReceiptId,
    val threadId: ChatThreadId,
    val userMessageId: ChatMessageId,
    val assistantMessageId: ChatMessageId,
)

data class MarkSmsAutoReplyQueuedCommand(
    val receiptId: ReceiptId,
    val replyBody: String,
    val smsPartCount: Int,
    val queuedAt: Instant,
)

interface SmsAutoReplyRepository {
    suspend fun findReceiptByInboundMessageKey(inboundMessageKey: SmsInboundMessageKey): SmsAutoReplyReceiptRecord?

    suspend fun recordAutoReplyAccepted(
        command: RecordSmsAutoReplyAcceptedCommand,
    ): SmsAutoReplyReceiptRecord?

    suspend fun recordAutoReplySkipped(
        command: RecordSmsAutoReplyAcceptedCommand,
        state: SmsAutoReplyState,
        reason: String,
    ): SmsAutoReplyReceiptRecord

    suspend fun linkAutoReplyTurn(command: LinkSmsAutoReplyTurnCommand): SmsAutoReplyReceiptRecord

    suspend fun markAutoReplyQueued(command: MarkSmsAutoReplyQueuedCommand): SmsAutoReplyReceiptRecord

    suspend fun markAutoReplyFailed(
        receiptId: ReceiptId,
        state: SmsAutoReplyState,
        reason: String,
    ): SmsAutoReplyReceiptRecord

    suspend fun markStaleGeneratingAutoRepliesFailed(
        staleBefore: Instant,
        reason: String,
    ): Int

    suspend fun findThreadLinkForSender(senderAddress: SmsSenderAddress): SmsSenderThreadLink?

    suspend fun persistThreadLinkForSender(
        senderAddress: SmsSenderAddress,
        threadId: ChatThreadId,
    ): SmsSenderThreadLink
}

data class QueueSmsReplyCommand(
    val recipientAddress: SmsSenderAddress,
    val replyBody: String,
    val receiptId: ReceiptId,
)

sealed interface QueueSmsReplyOutcome {
    data class Queued(
        val queuedAt: Instant,
        val smsPartCount: Int,
    ) : QueueSmsReplyOutcome

    data class Failed(
        val reason: String,
    ) : QueueSmsReplyOutcome
}

interface SmsOutboundGateway {
    suspend fun queueSmsReply(command: QueueSmsReplyCommand): QueueSmsReplyOutcome
}

fun parseSmsSenderAddress(rawAddress: String?): SmsSenderAddress? {
    val trimmedAddress = rawAddress?.trim().orEmpty()
    if (trimmedAddress.isBlank()) return null
    if (trimmedAddress.length > SMS_SENDER_ADDRESS_LIMIT) return null
    return SmsSenderAddress(trimmedAddress)
}

fun normalizeInboundSmsBody(rawBody: String): String =
    rawBody
        .replace("\u0000", "")
        .trim()
        .take(SMS_BODY_CHARACTER_LIMIT)

fun buildSmsInboundMessageKey(
    senderAddress: SmsSenderAddress,
    receivedAt: Instant,
    messageBody: String,
): SmsInboundMessageKey =
    SmsInboundMessageKey(
        sha256Hex(
            senderAddress.raw +
                SMS_HASH_SEPARATOR +
                receivedAt.toEpochMilli() +
                SMS_HASH_SEPARATOR +
                messageBody,
        ),
    )

fun smsBodySha256Hex(messageBody: String): String = sha256Hex(messageBody)

private fun sha256Hex(input: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val SMS_HASH_SEPARATOR = "\u001F"
private const val SMS_SENDER_ADDRESS_LIMIT = 128
private const val SMS_BODY_CHARACTER_LIMIT = 4_096
