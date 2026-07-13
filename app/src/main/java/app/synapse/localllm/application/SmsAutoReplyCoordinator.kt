package app.synapse.localllm.application

import app.synapse.localllm.domain.chat.BuiltInParticipantIds
import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.chat.CreateRoomCommand
import app.synapse.localllm.domain.chat.ParticipantKind
import app.synapse.localllm.domain.chat.RoomKind
import app.synapse.localllm.domain.chat.SubmitUserMessageCommand
import app.synapse.localllm.domain.ids.ChatMessageId
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
import app.synapse.localllm.domain.sms.SmsSenderThreadLink
import app.synapse.localllm.domain.sms.canRetrySmsAutoReplyGeneration
import app.synapse.localllm.domain.sms.normalizeInboundSmsBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

        val insertedReceipt = smsAutoReplyRepository.recordAutoReplyAccepted(acceptedCommand)
        val receipt = insertedReceipt ?: run {
            val existingReceipt = smsAutoReplyRepository.findReceiptByInboundMessageKey(command.inboundMessageKey)
                ?: return smsAutoReplyRepository.recordAutoReplySkipped(
                    command = acceptedCommand,
                    state = SmsAutoReplyState.DUPLICATE_IGNORED,
                    reason = "Inbound SMS was already recorded.",
                )
            if (!existingReceipt.state.canRetrySmsAutoReplyGeneration()) return existingReceipt
            existingReceipt
        }

        val senderLink = ensureRoomForSender(command.senderAddress)
        val autoReplySettings = settings.copy(
            systemPrompt = composeSmsAutoReplySystemPrompt(
                systemPrompt = settings.systemPrompt,
                smsAutoReplyInstructions = settings.smsAutoReplyInstructions,
            ),
            memoryWritesEnabled = false,
        )
        val outcome = try {
            turnCoordinator.sendUserTurn(
                command = SubmitUserMessageCommand(
                    threadId = senderLink.threadId,
                    body = inboundBody,
                    attachments = emptyList(),
                    authorParticipantId = senderLink.participantId,
                ),
                settings = autoReplySettings,
                aiResponseMode = AiResponseMode.REQUIRE_AI_RESPONSE,
                onTurnStarted = { turnReceipt ->
                    smsAutoReplyRepository.linkAutoReplyTurn(
                        LinkSmsAutoReplyTurnCommand(
                            receiptId = receipt.id,
                            threadId = senderLink.threadId,
                            userMessageId = turnReceipt.userMessageId,
                            assistantMessageId = turnReceipt.assistantMessageId,
                        ),
                    )
                },
            )
        } catch (exception: CancellationException) {
            withContext(NonCancellable) {
                smsAutoReplyRepository.markAutoReplyFailed(
                    receiptId = receipt.id,
                    state = SmsAutoReplyState.GENERATION_FAILED,
                    reason = SMS_AUTO_REPLY_INTERRUPTED_REASON,
                )
            }
            throw exception
        } catch (exception: Exception) {
            return smsAutoReplyRepository.markAutoReplyFailed(
                receiptId = receipt.id,
                state = SmsAutoReplyState.GENERATION_FAILED,
                reason = exception.message ?: "SMS auto-reply generation failed before a reply was queued.",
            )
        }

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

            is SynapseTurnOutcome.HumanMessageOnly ->
                smsAutoReplyRepository.markAutoReplyFailed(
                    receiptId = receipt.id,
                    state = SmsAutoReplyState.GENERATION_FAILED,
                    reason = "Synapse AI is not an active member of this SMS room.",
                )
        }
    }

    private suspend fun ensureRoomForSender(senderAddress: SmsSenderAddress): SmsSenderThreadLink {
        val existingLink = smsAutoReplyRepository.findThreadLinkForSender(senderAddress)
        if (existingLink != null) return existingLink

        val room = conversationRepository.createRoom(
            CreateRoomCommand(
                title = buildSmsRoomTitle(senderAddress),
                kind = RoomKind.DIRECT,
                placeholderHumanDisplayNames = listOf(buildSmsParticipantDisplayName(senderAddress)),
                includeSynapseAi = true,
                synapseAiAutoResponseEnabled = true,
            ),
        )
        val senderParticipant = checkNotNull(
            room.activeMembers
                .map { member -> member.participant }
                .singleOrNull { participant ->
                    participant.kind == ParticipantKind.HUMAN &&
                        participant.id != BuiltInParticipantIds.LOCAL_HUMAN
                },
        ) {
            "SMS room was created without its sender participant."
        }
        return smsAutoReplyRepository.persistThreadLinkForSender(
            senderAddress = senderAddress,
            threadId = room.id,
            participantId = senderParticipant.id,
        )
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
        const val SMS_AUTO_REPLY_INTERRUPTED_REASON =
            "SMS auto-reply generation was interrupted before the reply was queued."

        fun buildSmsRoomTitle(senderAddress: SmsSenderAddress): String =
            "SMS ${senderAddress.raw}".take(72).trimEnd()

        fun buildSmsParticipantDisplayName(senderAddress: SmsSenderAddress): String =
            senderAddress.raw
                .filterNot(Char::isISOControl)
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(64)
                .ifBlank { "SMS sender" }
    }
}
