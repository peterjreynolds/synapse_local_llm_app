package app.synapse.localllm.domain.remote

import kotlinx.coroutines.flow.Flow

data class RemoteDirectRoomSnapshot(
    val room: RemoteCachedDirectRoom,
    val currentMembership: RemoteCachedMembership,
)

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

interface RemoteConversationGateway {
    fun observeDirectRooms(accountUid: RemoteAccountUid): Flow<List<RemoteDirectRoomSnapshot>>

    fun observeMessages(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<List<RemoteCachedMessage>>

    suspend fun openDirectRoom(command: OpenRemoteDirectRoomCommand): OpenRemoteDirectRoomReceipt

    suspend fun sendMessage(command: SendRemoteMessageCommand): RemoteMessageSendReceipt

    suspend fun markRoomRead(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    )
}
