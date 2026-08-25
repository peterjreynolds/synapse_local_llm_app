package app.synapse.privatechat.ui.chat

import androidx.compose.runtime.Composable

@Composable
internal fun PrivateSocialOverlay(
    state: PrivateChatUiState,
    accountSessionActions: PrivateAccountSessionUiActions,
    navigationActions: PrivateChatNavigationActions,
    socialActions: PrivateSocialUiActions,
) {
    when (state.overlay) {
        PrivateChatOverlay.HIDDEN -> Unit
        PrivateChatOverlay.PROFILE ->
            PrivateProfileDialog(
                socialState = state.social,
                presencePublication = state.presencePublication,
                accountInvitation = state.accountInvitation,
                operation = state.operation,
                accountSessionActions = accountSessionActions,
                onSaveProfile = socialActions.saveProfile,
                onChangePresenceSharing = socialActions.changePresenceSharing,
                onCreateAccountInvitation = {
                    navigationActions.dismissOverlay()
                    socialActions.createOneUseAccountInvitation()
                },
                onDismiss = navigationActions.dismissOverlay,
            )

        PrivateChatOverlay.CREATE_CONVERSATION ->
            PrivateCreateConversationDialog(
                operation = state.operation,
                onCreateRoom = socialActions.createRoom,
                onRedeemRoomInvitation = socialActions.redeemRoomInvitation,
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
