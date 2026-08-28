package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomFeedSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomSummary

internal object PrivateChatMutationAvailability {
    fun connectedConversationSnapshot(state: PrivateChatUiState): PrivateConversationSnapshot? {
        val selectedRoomId = state.selectedRoomId ?: return null
        val conversation = state.conversation as? PrivateConversationUiState.Available ?: return null
        return conversation.snapshot.takeIf { snapshot ->
            conversation.connectionState == PrivateChatConnectionUiState.CONNECTED &&
                snapshot.room.roomId == selectedRoomId
        }
    }

    fun connectedSelectedRoom(state: PrivateChatUiState): PrivateRoomSummary? = connectedConversationSnapshot(state)?.room

    fun connectedRoomFeedSnapshot(state: PrivateChatUiState): PrivateRoomFeedSnapshot? {
        val roomFeed = state.roomFeed as? PrivateRoomFeedUiState.Available ?: return null
        return roomFeed.snapshot.takeIf {
            roomFeed.connectionState == PrivateChatConnectionUiState.CONNECTED
        }
    }
}
