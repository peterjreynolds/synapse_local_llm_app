package app.synapse.privatechat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.ui.account.PrivateAccountSignOutUiState
import app.synapse.privatechat.ui.call.PrivateCallUiActions

@Composable
fun PrivateChatScreen(
    state: PrivateChatUiState,
    peopleState: PrivatePeopleUiState,
    onOpenDirectChat: (PrivateAccountId) -> Unit,
    accountSessionActions: PrivateAccountSessionUiActions,
    navigationActions: PrivateChatNavigationActions,
    messageActions: PrivateMessageUiActions,
    roomActions: PrivateRoomUiActions,
    socialActions: PrivateSocialUiActions,
    onDismissOperationNotice: () -> Unit,
    callActions: PrivateCallUiActions? = null,
) {
    var showPeople by remember { mutableStateOf(false) }
    LaunchedEffect(state.selectedRoomId) { if (state.selectedRoomId != null) showPeople = false }
    val overlayDismissAllowed =
        state.operation !is PrivateChatOperationUiState.Running &&
            !(
                state.overlay == PrivateChatOverlay.PROFILE &&
                    accountSessionActions.signOutState is PrivateAccountSignOutUiState.SigningOut
            )
    BackHandler(enabled = state.overlay != PrivateChatOverlay.HIDDEN) {
        if (overlayDismissAllowed) navigationActions.dismissOverlay()
    }
    BackHandler(
        enabled = state.overlay == PrivateChatOverlay.HIDDEN && state.selectedRoomId != null,
    ) {
        navigationActions.showRoomList()
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors =
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                                MaterialTheme.colorScheme.background,
                            ),
                        radius = 1_100f,
                    ),
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding(),
        ) {
            PrimaryTabRow(selectedTabIndex = if (showPeople) 1 else 0) {
                Tab(selected = !showPeople, onClick = { showPeople = false }, text = { Text("Chats") })
                Tab(selected = showPeople, onClick = {
                    navigationActions.showRoomList()
                    showPeople = true
                }, text = { Text("People") })
            }
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                if (showPeople) {
                    PrivatePeoplePane(peopleState, onOpenDirectChat)
                    return@BoxWithConstraints
                }
                val showTwoPanes = maxWidth >= PRIVATE_TWO_PANE_MINIMUM_WIDTH
                if (showTwoPanes) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        PrivateRoomListPane(
                            roomFeedState = state.roomFeed,
                            socialState = state.social,
                            selectedRoomId = state.selectedRoomId,
                            navigationActions = navigationActions,
                            modifier = Modifier.width(PRIVATE_ROOM_LIST_WIDTH),
                        )
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PrivateConversationPane(
                            state = state,
                            showBackButton = false,
                            navigationActions = navigationActions,
                            messageActions = messageActions,
                            roomActions = roomActions,
                            callActions = callActions,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (state.selectedRoomId == null) {
                    PrivateRoomListPane(
                        roomFeedState = state.roomFeed,
                        socialState = state.social,
                        selectedRoomId = null,
                        navigationActions = navigationActions,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    PrivateConversationPane(
                        state = state,
                        showBackButton = true,
                        navigationActions = navigationActions,
                        messageActions = messageActions,
                        roomActions = roomActions,
                        callActions = callActions,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            PrivateChatOperationNotice(
                operation = state.operation,
                onDismiss = onDismissOperationNotice,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        PrivateRoomInvitationDialog(
            invitationState = state.roomInvitation,
            onDismiss = roomActions.dismissInvitation,
        )
        PrivateAccountInvitationDialog(
            invitationState = state.accountInvitation,
            onDismiss = socialActions.dismissAccountInvitation,
        )
        PrivateSocialOverlay(
            state = state,
            accountSessionActions = accountSessionActions,
            navigationActions = navigationActions,
            socialActions = socialActions,
            onDismissOperationNotice = onDismissOperationNotice,
        )
    }
}

private val PRIVATE_TWO_PANE_MINIMUM_WIDTH = 760.dp
private val PRIVATE_ROOM_LIST_WIDTH = 340.dp
