package app.synapse.localllm.domain.remote

import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class OpenRemoteDirectRoomCommand(
    val accountUid: RemoteAccountUid,
    val targetUid: RemoteProfileUid,
)

data class OpenRemoteDirectRoomReceipt(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
)

data class SendRemoteMessageCommand(
    val message: RemoteCachedMessage,
)

data class RemoteMessageSendReceipt(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
)

data class ReviseRemoteMessageCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val mutationId: String,
    val expectedRevision: Long,
    val body: String? = null,
)

data class RemoteMessageRevisionReceipt(
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val revision: Long,
)

data class ToggleRemoteReactionCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val emoji: String,
    val reacted: Boolean,
)

data class RemoteReactionReceipt(
    val roomId: RemoteRoomId,
    val messageId: RemoteMessageId,
    val emoji: String,
    val reacted: Boolean,
    val reactionCount: Int,
)

data class AcknowledgeRemoteMessagesCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val messageIds: List<RemoteMessageId>,
    val read: Boolean,
)

data class RemoteMessageAcknowledgementReceipt(
    val roomId: RemoteRoomId,
    val acknowledgedCount: Int,
    val read: Boolean,
)

data class LoadRemoteMessagesPageCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val beforeCreatedAt: Instant,
    val beforeMessageId: RemoteMessageId,
    val limit: Int,
)

data class RemoteMessagePage(
    val messages: List<RemoteCachedMessage>,
    val reachedStart: Boolean,
)

data class UpdateRemoteRoomPreferencesCommand(
    val accountUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val isArchived: Boolean,
    val isPinned: Boolean,
    val muteDuration: RemoteRoomMuteDuration?,
)

data class RemoteRoomPreferencesReceipt(
    val roomId: RemoteRoomId,
    val isArchived: Boolean,
    val isPinned: Boolean,
    val muteDuration: RemoteRoomMuteDuration?,
    val mutedUntil: Instant?,
)

data class RemoteTypingParticipant(
    val profileUid: RemoteProfileUid,
    val expiresAt: Instant,
)

interface RemoteConversationGateway {
    fun assistantAvailability(roomId: RemoteRoomId): RemoteAssistantAvailability? = null

    fun observeRooms(accountUid: RemoteAccountUid): Flow<List<RemoteCachedRoom>>

    fun observeMessages(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<List<RemoteCachedMessage>>

    fun observeOwnReactionSelections(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<Map<RemoteMessageId, String>>

    suspend fun openDirectRoom(command: OpenRemoteDirectRoomCommand): OpenRemoteDirectRoomReceipt

    suspend fun sendMessage(command: SendRemoteMessageCommand): RemoteMessageSendReceipt

    suspend fun editMessage(command: ReviseRemoteMessageCommand): RemoteMessageRevisionReceipt

    suspend fun deleteMessage(command: ReviseRemoteMessageCommand): RemoteMessageRevisionReceipt

    suspend fun toggleReaction(command: ToggleRemoteReactionCommand): RemoteReactionReceipt

    suspend fun acknowledgeMessages(
        command: AcknowledgeRemoteMessagesCommand,
    ): RemoteMessageAcknowledgementReceipt

    suspend fun loadMessagesBefore(command: LoadRemoteMessagesPageCommand): RemoteMessagePage

    suspend fun loadMessage(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        messageId: RemoteMessageId,
    ): RemoteCachedMessage?

    fun observeTypingParticipants(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<List<RemoteTypingParticipant>>

    suspend fun setTyping(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        isTyping: Boolean,
    )

    suspend fun markRoomRead(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    )

    suspend fun updateRoomPreferences(
        command: UpdateRemoteRoomPreferencesCommand,
    ): RemoteRoomPreferencesReceipt

    suspend fun getNotificationPreferences(
        accountUid: RemoteAccountUid,
    ): RemoteNotificationPreferences

    suspend fun updateNotificationPreferences(
        accountUid: RemoteAccountUid,
        preferences: RemoteNotificationPreferences,
    ): RemoteNotificationPreferences
}
