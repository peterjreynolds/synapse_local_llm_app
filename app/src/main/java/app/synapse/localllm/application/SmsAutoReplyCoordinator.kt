package app.synapse.localllm.application

import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.settings.SynapseSettings
import app.synapse.localllm.domain.settings.composeSmsAutoReplySystemPrompt
import app.synapse.localllm.domain.sms.InboundSmsAutoReplyCommand
import app.synapse.localllm.domain.sms.LinkSmsAutoReplyTurnCommand
import app.synapse.localllm.domain.sms.MarkSmsAutoReplyQueuedCommand
import app.synapse.localllm.domain.sms.QueueSmsReplyCommand
import app.synapse.localllm.domain.sms.QueueSmsReplyOutcome
import app.synapse.localllm.domain.sms.RecordSmsAutoReplyAcceptedCommand
import app.synapse.localllm.domain.sms.SmsAutoReplyReceiptRecord
import app.synapse.localllm.domain.sms.SmsAutoReplyRepository
import app.synapse.localllm.domain.sms.SmsAutoReplyState
import app.synapse.localllm.domain.sms.SmsOutboundGateway
import app.synapse.localllm.domain.sms.SmsSenderAddress
import app.synapse.localllm.domain.sms.normalizeInboundSmsBody
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SmsAutoReplyCoordinator(
    private val conversationRepository: ConversationRepository,
    private val smsAutoReplyRepository: SmsAutoReplyRepository,
    private val smsOutboundGateway: SmsOutboundGateway,
    private val turnCoordinator: SynapseTurnCoordinator,
) {
    private val autoReplyMutex = Mutex()

    suspend fun processInboundSms(
        command: InboundSmsAutoReplyCommand,
        settings: SynapseSettings,
    ): SmsAutoReplyReceiptRecord =
        autoReplyMutex.withLock {
            processInboundSmsOnce(command, settings)
        }

    private suspend fun processInboundSmsOnce(
        command: InboundSmsAutoReplyCommand,
        settings: SynapseSettings,
    ): SmsAutoReplyReceiptRecord {
        val inboundBody = normalizeInboundSmsBody(command.messageBody)
        val acceptedCommand = command.toAcceptedCommand(inboundBody)
        if (inboundBody.isBlank()) {
            return smsAutoReplyRepository.recordAutoReplySkipped(
                command = acceptedCommand,
                state = SmsAutoReplyState.INVALID_INBOUND_MESSAGE,
                reason = "Inbound SMS body was blank after normalization.",
            )
        }
        if (!settings.smsAutoReplyEnabled) {
            return smsAutoReplyRepository.recordAutoReplySkipped(
                command = acceptedCommand,
                state = SmsAutoReplyState.AUTO_REPLY_DISABLED,
                reason = "SMS auto-reply setting was disabled.",
            )
        }

        val receipt = smsAutoReplyRepository.recordAutoReplyAccepted(acceptedCommand)
            ?: return smsAutoReplyRepository.findReceiptByInboundMessageKey(command.inboundMessageKey)
                ?: smsAutoReplyRepository.recordAutoReplySkipped(
                    command = acceptedCommand,
                    state = SmsAutoReplyState.DUPLICATE_IGNORED,
                    reason = "Inbound SMS was already recorded.",
                )

        val threadId = ensureThreadForSender(command.senderAddress)
        val autoReplySettings = settings.copy(
            systemPrompt = composeSmsAutoReplySystemPrompt(
                systemPrompt = settings.systemPrompt,
                smsAutoReplyInstructions = settings.smsAutoReplyInstructions,
            ),
            memoryWritesEnabled = false,
        )
        val outcome = turnCoordinator.sendUserTurn(
            command = SubmitUserMessageCommand(
                threadId = threadId,
                body = buildSmsTurnBody(command.senderAddress, inboundBody),
                attachments = emptyList(),
            ),
            settings = autoReplySettings,
        )
        smsAutoReplyRepository.linkAutoReplyTurn(
            LinkSmsAutoReplyTurnCommand(
                receiptId = receipt.id,
                threadId = threadId,
                userMessageId = when (outcome) {
                    is SynapseTurnOutcome.Completed -> outcome.userMessageId
                    is SynapseTurnOutcome.Failed -> outcome.userMessageId
                },
                assistantMessageId = when (outcome) {
                    is SynapseTurnOutcome.Completed -> outcome.assistantMessageId
                    is SynapseTurnOutcome.Failed -> outcome.assistantMessageId
                },
            ),
        )

        return when (outcome) {
            is SynapseTurnOutcome.Completed ->
                queueCompletedAssistantReply(
                    receipt = receipt,
                    senderAddress = command.senderAddress,
                    assistantMessageId = outcome.assistantMessageId,
                )

            is SynapseTurnOutcome.Failed ->
                smsAutoReplyRepository.markAutoReplyFailed(
                    receiptId = receipt.id,
                    state = SmsAutoReplyState.GENERATION_FAILED,
                    reason = outcome.reason,
                )
        }
    }

    private suspend fun ensureThreadForSender(senderAddress: SmsSenderAddress): ChatThreadId {
        val existingLink = smsAutoReplyRepository.findThreadLinkForSender(senderAddress)
        if (existingLink != null) return existingLink.threadId

        val thread = conversationRepository.createThread(buildSmsThreadTitle(senderAddress))
        smsAutoReplyRepository.persistThreadLinkForSender(senderAddress, thread.id)
        return thread.id
    }

    private suspend fun queueCompletedAssistantReply(
        receipt: SmsAutoReplyReceiptRecord,
        senderAddress: SmsSenderAddress,
        assistantMessageId: ChatMessageId,
    ): SmsAutoReplyReceiptRecord {
        val replyBody = conversationRepository.findMessage(assistantMessageId)
            ?.body
            .orEmpty()
            .trim()
        if (replyBody.isBlank()) {
            return smsAutoReplyRepository.markAutoReplyFailed(
                receiptId = receipt.id,
                state = SmsAutoReplyState.EMPTY_REPLY_REJECTED,
                reason = "Assistant generated no SMS reply text.",
            )
        }

        return when (
            val queueOutcome = smsOutboundGateway.queueSmsReply(
                QueueSmsReplyCommand(
                    recipientAddress = senderAddress,
                    replyBody = replyBody,
                    receiptId = receipt.id,
                ),
            )
        ) {
            is QueueSmsReplyOutcome.Queued ->
                smsAutoReplyRepository.markAutoReplyQueued(
                    MarkSmsAutoReplyQueuedCommand(
                        receiptId = receipt.id,
                        replyBody = replyBody,
                        smsPartCount = queueOutcome.smsPartCount,
                        queuedAt = queueOutcome.queuedAt,
                    ),
                )

            is QueueSmsReplyOutcome.Failed ->
                smsAutoReplyRepository.markAutoReplyFailed(
                    receiptId = receipt.id,
                    state = SmsAutoReplyState.SMS_QUEUE_FAILED,
                    reason = queueOutcome.reason,
                )
        }
    }

    private fun InboundSmsAutoReplyCommand.toAcceptedCommand(inboundBody: String) =
        RecordSmsAutoReplyAcceptedCommand(
            inboundMessageKey = inboundMessageKey,
            senderAddress = senderAddress,
            receivedAt = receivedAt,
            inboundBody = inboundBody,
        )

    private companion object {
        fun buildSmsThreadTitle(senderAddress: SmsSenderAddress): String =
            "SMS ${senderAddress.raw}".take(72).trimEnd()

        fun buildSmsTurnBody(
            senderAddress: SmsSenderAddress,
            inboundBody: String,
        ): String =
            "Incoming SMS from ${senderAddress.raw}:\n$inboundBody"
    }
}
