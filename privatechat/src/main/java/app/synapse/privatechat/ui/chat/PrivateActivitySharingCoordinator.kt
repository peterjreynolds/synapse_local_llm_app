package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.AcknowledgePrivateRoomReadCommand
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateClientMutationIdFactory
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomSummary
import app.synapse.privatechat.domain.chat.PrivateTypingState
import app.synapse.privatechat.domain.chat.PublishPrivateTypingStateCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class PrivateActivitySharingCoordinator(
    private val gateway: PrivateChatGateway,
    private val mutationIdFactory: PrivateClientMutationIdFactory,
    private val coroutineScope: CoroutineScope,
    private val activeAccountId: () -> PrivateAccountId?,
    private val selectedRoomId: () -> PrivateRoomId?,
    private val preferences: () -> PrivateActivitySharingPreferences?,
    private val markRoomRead: (PrivateRoomId) -> Unit,
) {
    private val acknowledgedUnreadCounts = mutableMapOf<PrivateRoomId, Int>()
    private var requestedTypingState = PrivateTypingState.INACTIVE
    private var typingPublicationJob: Job? = null

    fun reset() {
        typingPublicationJob?.cancel()
        typingPublicationJob = null
        acknowledgedUnreadCounts.clear()
        requestedTypingState = PrivateTypingState.INACTIVE
    }

    fun acknowledgeRoomReadIfEnabled(room: PrivateRoomSummary) {
        val accountId = activeAccountId() ?: return
        if (
            preferences()?.readReceipts != PrivateActivitySharingState.ENABLED ||
            room.unreadMessageCount == 0 ||
            acknowledgedUnreadCounts[room.roomId] == room.unreadMessageCount
        ) {
            return
        }
        val command =
            AcknowledgePrivateRoomReadCommand(
                accountId = accountId,
                roomId = room.roomId,
                mutationId = mutationIdFactory.createMutationId(),
            )
        coroutineScope.launch {
            val receipt =
                try {
                    (gateway.acknowledgeRoomRead(command) as? PrivateChatMutationOutcome.Confirmed)?.receipt
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            if (
                receipt != null &&
                PrivateChatReceiptValidator.matches(receipt, command) &&
                activeAccountId() == accountId
            ) {
                acknowledgedUnreadCounts[room.roomId] = room.unreadMessageCount
                markRoomRead(room.roomId)
            }
        }
    }

    fun publishTypingStateIfEnabled(
        typingState: PrivateTypingState,
        force: Boolean = false,
    ) {
        val accountId = activeAccountId() ?: return
        val roomId = selectedRoomId() ?: return
        if (!force && preferences()?.typingIndicators != PrivateActivitySharingState.ENABLED) return
        if (!force && requestedTypingState == typingState) return
        requestedTypingState = typingState
        typingPublicationJob?.cancel()
        val command =
            PublishPrivateTypingStateCommand(
                accountId = accountId,
                roomId = roomId,
                mutationId = mutationIdFactory.createMutationId(),
                typingState = typingState,
            )
        typingPublicationJob =
            coroutineScope.launch {
                val confirmed =
                    try {
                        val outcome = gateway.publishTypingState(command)
                        outcome is PrivateChatMutationOutcome.Confirmed &&
                            PrivateChatReceiptValidator.matches(outcome.receipt, command)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        false
                    }
                if (!confirmed && requestedTypingState == typingState) {
                    requestedTypingState = PrivateTypingState.INACTIVE
                }
            }
    }
}
