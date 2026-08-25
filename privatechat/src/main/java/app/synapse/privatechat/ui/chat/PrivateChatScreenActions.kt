package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateRoomArchiveState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import app.synapse.privatechat.domain.chat.PrivateRoomMemberRole
import app.synapse.privatechat.domain.chat.PrivateRoomMemberSnapshot
import app.synapse.privatechat.domain.chat.PrivateRoomMuteState
import app.synapse.privatechat.domain.chat.PrivateRoomPinState
import app.synapse.privatechat.ui.account.PrivateAccountSignOutUiState

data class PrivateAccountSessionUiActions(
    val signOutState: PrivateAccountSignOutUiState,
    val signOut: () -> Unit,
)

data class PrivateChatNavigationActions(
    val selectRoom: (PrivateRoomId) -> Unit,
    val showRoomList: () -> Unit,
    val showProfile: () -> Unit,
    val showCreateConversation: () -> Unit,
    val showGroupManagement: () -> Unit,
    val dismissOverlay: () -> Unit,
)

data class PrivateMessageUiActions(
    val changeComposerText: (String) -> Unit,
    val submitComposer: () -> Unit,
    val beginReply: (PrivateMessageId) -> Unit,
    val beginEdit: (PrivateMessageId) -> Unit,
    val cancelComposerContext: () -> Unit,
    val toggleReaction: (PrivateMessageId, String) -> Unit,
    val deleteForEveryone: (PrivateMessageId) -> Unit,
)

data class PrivateRoomUiActions(
    val changeRetention: (PrivateMessageRetention) -> Unit,
    val changeArchiveState: (PrivateRoomArchiveState) -> Unit,
    val changePinState: (PrivateRoomPinState) -> Unit,
    val changeMuteState: (PrivateRoomMuteState) -> Unit,
    val createOneUseInvitation: () -> Unit,
    val dismissInvitation: () -> Unit,
)

data class PrivateSocialUiActions(
    val changeReadReceiptSharing: (PrivateActivitySharingState) -> Unit,
    val changeTypingIndicatorSharing: (PrivateActivitySharingState) -> Unit,
    val saveProfile: (String) -> Unit,
    val createRoom: (PrivateRoomKind, String, PrivateMessageRetention) -> Unit,
    val redeemRoomInvitation: (String) -> Unit,
    val changePresenceSharing: (PrivatePresenceSharingState) -> Unit,
    val changeGroupMemberRole: (PrivateRoomMemberSnapshot, PrivateRoomMemberRole) -> Unit,
    val removeGroupMember: (PrivateRoomMemberSnapshot) -> Unit,
    val createOneUseAccountInvitation: () -> Unit,
    val dismissAccountInvitation: () -> Unit,
)
