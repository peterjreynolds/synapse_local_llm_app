package app.synapse.privatechat.ui.chat

import androidx.compose.runtime.Composable

@Composable
internal fun PrivateSocialOverlay(
    state: PrivateChatUiState,
    accountSessionActions: PrivateAccountSessionUiActions,
    navigationActions: PrivateChatNavigationActions,
    socialActions: PrivateSocialUiActions,
    onDismissOperationNotice: () -> Unit,
) {
    when (state.overlay) {
        PrivateChatOverlay.HIDDEN -> Unit
        PrivateChatOverlay.PROFILE ->
            PrivateProfileScreen(
                socialState = state.social,
                roomFeedState = state.roomFeed,
                presencePublication = state.presencePublication,
                accountInvitation = state.accountInvitation,
                operation = state.operation,
                accountSessionActions = accountSessionActions,
                socialActions = socialActions,
                onDismissOperationNotice = onDismissOperationNotice,
                onDismiss = navigationActions.dismissOverlay,
            )

        PrivateChatOverlay.CREATE_CONVERSATION ->
            PrivateCreateConversationDialog(
                operation = state.operation,
                transportMutationsEnabled =
                    PrivateChatMutationAvailability.connectedRoomFeedSnapshot(state) != null,
                onCreateRoom = socialActions.createRoom,
                onRedeemRoomInvitation = socialActions.redeemRoomInvitation,
                onDismissOperationNotice = onDismissOperationNotice,
                onDismiss = navigationActions.dismissOverlay,
            )

        PrivateChatOverlay.MANAGE_GROUP ->
            PrivateGroupMembersDialog(
                conversationState = state.conversation,
                operation = state.operation,
                onChangeRole = socialActions.changeGroupMemberRole,
                onRemoveMember = socialActions.removeGroupMember,
                onDismiss = navigationActions.dismissOverlay,
            )
    }
}
