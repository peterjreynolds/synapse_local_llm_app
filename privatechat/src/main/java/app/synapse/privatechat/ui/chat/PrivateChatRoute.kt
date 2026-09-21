package app.synapse.privatechat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.ui.account.PrivateAccountSignOutUiState
import app.synapse.privatechat.ui.call.PrivateCallAvailability
import app.synapse.privatechat.ui.call.PrivateCallOverlay
import app.synapse.privatechat.ui.call.PrivateCallUiActions
import app.synapse.privatechat.ui.call.PrivateCallUiState
import app.synapse.privatechat.ui.call.PrivateCallViewModel

@Composable
fun PrivateChatRoute(
    accountSession: PrivateAccountSessionReceipt.Active,
    viewModel: PrivateChatViewModel,
    callViewModel: PrivateCallViewModel,
    signOutState: PrivateAccountSignOutUiState,
    onSignOut: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val callState by callViewModel.uiState.collectAsStateWithLifecycle()
    val callAvailability by callViewModel.availability.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(accountSession.accountId) {
        viewModel.activateAccount(accountSession.accountId)
        callViewModel.activateAccount(accountSession.accountId)
    }
    LaunchedEffect(state.roomFeed) {
        val rooms = (state.roomFeed as? PrivateRoomFeedUiState.Available)?.snapshot?.rooms.orEmpty()
        callViewModel.updateRooms(rooms)
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        viewModel.enterForeground()
                        callViewModel.setForeground(true)
                    }
                    Lifecycle.Event.ON_STOP -> {
                        viewModel.leaveForeground()
                        callViewModel.setForeground(false)
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.enterForeground()
            callViewModel.setForeground(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.leaveForeground()
            viewModel.deactivateAccount()
            callViewModel.setForeground(false)
        }
    }

    PrivateChatScreen(
        state = state,
        callActions =
            PrivateCallUiActions(
                available =
                    callAvailability == PrivateCallAvailability.AVAILABLE &&
                        (callState is PrivateCallUiState.Idle || callState is PrivateCallUiState.Finished),
                start = callViewModel::requestCall,
            ),
        accountSessionActions =
            PrivateAccountSessionUiActions(
                signOutState = signOutState,
                signOut = onSignOut,
            ),
        navigationActions =
            PrivateChatNavigationActions(
                selectRoom = viewModel::selectRoom,
                showRoomList = viewModel::showRoomList,
                showProfile = viewModel::showProfile,
                showCreateConversation = viewModel::showCreateConversation,
                showGroupManagement = viewModel::showGroupManagement,
                dismissOverlay = viewModel::dismissOverlay,
            ),
        messageActions =
            PrivateMessageUiActions(
                changeComposerText = viewModel::updateComposerText,
                submitComposer = viewModel::submitComposer,
                beginReply = viewModel::beginReply,
                beginEdit = viewModel::beginEdit,
                cancelComposerContext = viewModel::cancelComposerContext,
                toggleReaction = viewModel::toggleReaction,
                deleteForEveryone = viewModel::deleteMessageForEveryone,
            ),
        roomActions =
            PrivateRoomUiActions(
                changeRetention = viewModel::changeRetention,
                changeArchiveState = viewModel::setRoomArchived,
                changePinState = viewModel::setRoomPinned,
                changeMuteState = viewModel::setRoomMuted,
                createOneUseInvitation = viewModel::createOneUseRoomInvitation,
                dismissInvitation = viewModel::dismissRoomInvitation,
            ),
        socialActions =
            PrivateSocialUiActions(
                changeReadReceiptSharing = viewModel::changeReadReceiptSharing,
                changeTypingIndicatorSharing = viewModel::changeTypingIndicatorSharing,
                saveProfile = viewModel::saveProfile,
                createRoom = viewModel::createRoom,
                redeemRoomInvitation = viewModel::redeemRoomInvitation,
                changePresenceSharing = viewModel::changePresenceSharing,
                changeGroupMemberRole = viewModel::changeGroupMemberRole,
                removeGroupMember = viewModel::removeGroupMember,
                createOneUseAccountInvitation = viewModel::createOneUseAccountInvitation,
                dismissAccountInvitation = viewModel::dismissAccountInvitation,
            ),
        onDismissOperationNotice = viewModel::dismissOperationNotice,
    )
    PrivateCallOverlay(state = callState, viewModel = callViewModel)
}
