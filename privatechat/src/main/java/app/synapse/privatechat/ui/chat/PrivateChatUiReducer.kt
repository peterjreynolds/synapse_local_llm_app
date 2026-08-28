package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateMessageSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomSummary

internal object PrivateChatUiReducer {
    fun markRoomFeedTransportUnavailable(state: PrivateChatUiState): PrivateChatUiState =
        state.copy(
            roomFeed =
                when (val roomFeed = state.roomFeed) {
                    is PrivateRoomFeedUiState.Available ->
                        roomFeed.copy(connectionState = PrivateChatConnectionUiState.RECONNECTING)

                    else -> PrivateRoomFeedUiState.TransportUnavailable
                },
        )

    fun markConversationTransportUnavailable(state: PrivateChatUiState): PrivateChatUiState =
        state.copy(
            conversation =
                when (val conversation = state.conversation) {
                    is PrivateConversationUiState.Available ->
                        conversation.copy(connectionState = PrivateChatConnectionUiState.RECONNECTING)

                    else -> PrivateConversationUiState.TransportUnavailable
                },
        )

    fun acceptRoomFeed(
        state: PrivateChatUiState,
        snapshot: PrivateRoomFeedSnapshot,
    ): PrivateChatUiState {
        val selectedRoomStillExists =
            state.selectedRoomId == null || snapshot.rooms.any { room -> room.roomId == state.selectedRoomId }
        return if (selectedRoomStillExists) {
            state.copy(roomFeed = PrivateRoomFeedUiState.Available(snapshot))
        } else {
            state.copy(
                roomFeed = PrivateRoomFeedUiState.Available(snapshot),
                selectedRoomId = null,
                conversation = PrivateConversationUiState.NotSelected,
                composerText = "",
                composerMode = PrivateComposerMode.NewMessage,
                roomInvitation = PrivateRoomInvitationUiState.Hidden,
                overlay = PrivateChatOverlay.HIDDEN,
            )
        }
    }

    fun acceptConversation(
        state: PrivateChatUiState,
        snapshot: app.synapse.privatechat.domain.chat.PrivateConversationSnapshot,
    ): PrivateChatUiState = state.copy(conversation = PrivateConversationUiState.Available(snapshot))

    fun updatePresentedRoom(
        state: PrivateChatUiState,
        selectedRoomId: PrivateRoomId,
        transform: (PrivateRoomSummary) -> PrivateRoomSummary,
    ): PrivateChatUiState {
        val updatedRoomFeed =
            (state.roomFeed as? PrivateRoomFeedUiState.Available)?.let { roomFeed ->
                roomFeed.copy(
                    snapshot =
                        roomFeed.snapshot.copy(
                            rooms =
                                roomFeed.snapshot.rooms.map { room ->
                                    if (room.roomId == selectedRoomId) transform(room) else room
                                },
                        ),
                )
            } ?: state.roomFeed
        val updatedConversation =
            (state.conversation as? PrivateConversationUiState.Available)?.let { conversation ->
                conversation.copy(
                    snapshot =
                        conversation.snapshot.copy(
                            room =
                                if (conversation.snapshot.room.roomId == selectedRoomId) {
                                    transform(conversation.snapshot.room)
                                } else {
                                    conversation.snapshot.room
                                },
                        ),
                )
            } ?: state.conversation
        return state.copy(roomFeed = updatedRoomFeed, conversation = updatedConversation)
    }

    fun selectedRoom(state: PrivateChatUiState): PrivateRoomSummary? {
        val selectedRoomId = state.selectedRoomId ?: return null
        val conversation = state.conversation as? PrivateConversationUiState.Available
        return conversation?.snapshot?.room
            ?: (state.roomFeed as? PrivateRoomFeedUiState.Available)
                ?.snapshot
                ?.rooms
                ?.firstOrNull { room -> room.roomId == selectedRoomId }
    }

    fun findPresentedMessage(
        state: PrivateChatUiState,
        messageId: app.synapse.privatechat.domain.chat.PrivateMessageId,
    ): PrivateMessageSnapshot? =
        (state.conversation as? PrivateConversationUiState.Available)
            ?.snapshot
            ?.messages
            ?.firstOrNull { message -> message.messageId == messageId }
}
