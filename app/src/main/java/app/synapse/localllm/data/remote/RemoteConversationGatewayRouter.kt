package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.AcknowledgeRemoteMessagesCommand
import app.synapse.localllm.domain.remote.LoadRemoteMessagesPageCommand
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAssistantAvailability
import app.synapse.localllm.domain.remote.RemoteAssistantConversationCatalog
import app.synapse.localllm.domain.remote.RemoteAssistantConversationEndpoint
import app.synapse.localllm.domain.remote.RemoteAssistantConversationGateway
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteMessageAcknowledgementReceipt
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessagePage
import app.synapse.localllm.domain.remote.RemoteMessageRevisionReceipt
import app.synapse.localllm.domain.remote.RemoteMessageSendReceipt
import app.synapse.localllm.domain.remote.RemoteReactionReceipt
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomPreferencesReceipt
import app.synapse.localllm.domain.remote.RemoteTypingParticipant
import app.synapse.localllm.domain.remote.ReviseRemoteMessageCommand
import app.synapse.localllm.domain.remote.SendRemoteMessageCommand
import app.synapse.localllm.domain.remote.ToggleRemoteReactionCommand
import app.synapse.localllm.domain.remote.UpdateRemoteRoomPreferencesCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class RemoteConversationGatewayRouter(
    private val synchronizedConversationGateway: RemoteConversationGateway,
    private val assistantConversationGateway: RemoteAssistantConversationGateway,
) : RemoteConversationGateway by synchronizedConversationGateway {
    override fun assistantAvailability(roomId: RemoteRoomId): RemoteAssistantAvailability? =
        assistantEndpoint(roomId)?.let(assistantConversationGateway::availability)

    override fun observeMessages(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<List<RemoteCachedMessage>> =
        assistantEndpoint(roomId)?.let { endpoint ->
            assistantConversationGateway.observeMessages(accountUid, endpoint)
        } ?: synchronizedConversationGateway.observeMessages(accountUid, roomId)

    override fun observeOwnReactionSelections(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<Map<RemoteMessageId, String>> =
        if (assistantEndpoint(roomId) == null) {
            synchronizedConversationGateway.observeOwnReactionSelections(accountUid, roomId)
        } else {
            emptyFlow()
        }

    override suspend fun sendMessage(command: SendRemoteMessageCommand): RemoteMessageSendReceipt =
        assistantEndpoint(command.message.roomId)?.let { endpoint ->
            assistantConversationGateway.sendMessage(endpoint, command)
        } ?: synchronizedConversationGateway.sendMessage(command)

    override suspend fun editMessage(command: ReviseRemoteMessageCommand): RemoteMessageRevisionReceipt =
        if (assistantEndpoint(command.roomId) == null) {
            synchronizedConversationGateway.editMessage(command)
        } else {
            assistantUnavailable(command.roomId)
        }

    override suspend fun deleteMessage(command: ReviseRemoteMessageCommand): RemoteMessageRevisionReceipt =
        if (assistantEndpoint(command.roomId) == null) {
            synchronizedConversationGateway.deleteMessage(command)
        } else {
            assistantUnavailable(command.roomId)
        }

    override suspend fun toggleReaction(command: ToggleRemoteReactionCommand): RemoteReactionReceipt =
        if (assistantEndpoint(command.roomId) == null) {
            synchronizedConversationGateway.toggleReaction(command)
        } else {
            assistantUnavailable(command.roomId)
        }

    override suspend fun acknowledgeMessages(
        command: AcknowledgeRemoteMessagesCommand,
    ): RemoteMessageAcknowledgementReceipt =
        if (assistantEndpoint(command.roomId) == null) {
            synchronizedConversationGateway.acknowledgeMessages(command)
        } else {
            RemoteMessageAcknowledgementReceipt(command.roomId, acknowledgedCount = 0, read = command.read)
        }

    override suspend fun loadMessagesBefore(command: LoadRemoteMessagesPageCommand): RemoteMessagePage =
        if (assistantEndpoint(command.roomId) == null) {
            synchronizedConversationGateway.loadMessagesBefore(command)
        } else {
            RemoteMessagePage(messages = emptyList(), reachedStart = true)
        }

    override suspend fun loadMessage(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        messageId: RemoteMessageId,
    ): RemoteCachedMessage? =
        if (assistantEndpoint(roomId) == null) {
            synchronizedConversationGateway.loadMessage(accountUid, roomId, messageId)
        } else {
            null
        }

    override fun observeTypingParticipants(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<List<RemoteTypingParticipant>> =
        if (assistantEndpoint(roomId) == null) {
            synchronizedConversationGateway.observeTypingParticipants(accountUid, roomId)
        } else {
            emptyFlow()
        }

    override suspend fun setTyping(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        isTyping: Boolean,
    ) {
        if (assistantEndpoint(roomId) == null) {
            synchronizedConversationGateway.setTyping(accountUid, roomId, isTyping)
        }
    }

    override suspend fun markRoomRead(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ) {
        if (assistantEndpoint(roomId) == null) {
            synchronizedConversationGateway.markRoomRead(accountUid, roomId)
        }
    }

    override suspend fun updateRoomPreferences(
        command: UpdateRemoteRoomPreferencesCommand,
    ): RemoteRoomPreferencesReceipt =
        if (assistantEndpoint(command.roomId) == null) {
            synchronizedConversationGateway.updateRoomPreferences(command)
        } else {
            assistantUnavailable(command.roomId)
        }

    private fun assistantUnavailable(roomId: RemoteRoomId): Nothing {
        val endpoint = requireNotNull(assistantEndpoint(roomId))
        val availability = assistantConversationGateway.availability(endpoint)
        val message = (availability as? RemoteAssistantAvailability.Unavailable)?.userMessage
            ?: "${endpoint.displayName} is not available right now."
        throw RemoteChatException(message)
    }

    private fun assistantEndpoint(roomId: RemoteRoomId): RemoteAssistantConversationEndpoint? =
        RemoteAssistantConversationCatalog.findByRoomId(roomId)
}

class UnavailableRemoteAssistantConversationGateway : RemoteAssistantConversationGateway {
    override fun availability(endpoint: RemoteAssistantConversationEndpoint): RemoteAssistantAvailability =
        RemoteAssistantAvailability.Unavailable(
            "${endpoint.displayName} is not connected yet. " +
                "An authenticated ${endpoint.displayName} chat backend must be configured.",
        )

    override fun observeMessages(
        accountUid: RemoteAccountUid,
        endpoint: RemoteAssistantConversationEndpoint,
    ): Flow<List<RemoteCachedMessage>> = emptyFlow()

    override suspend fun sendMessage(
        endpoint: RemoteAssistantConversationEndpoint,
        command: SendRemoteMessageCommand,
    ): RemoteMessageSendReceipt {
        require(command.message.roomId == endpoint.roomId) {
            "Assistant message command does not match the selected endpoint."
        }
        val unavailable = availability(endpoint) as RemoteAssistantAvailability.Unavailable
        throw RemoteChatException(unavailable.userMessage)
    }
}
