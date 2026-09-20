package app.synapse.privatechat.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun PrivateChatNavigationDrawer(
    state: PrivateChatUiState,
    navigationActions: PrivateChatNavigationActions,
    onCreateAccountInvitation: () -> Unit,
    content: @Composable (onOpenNavigation: () -> Unit) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val profile = (state.social as? PrivateSocialUiState.Available)?.snapshot?.profile
    val closeThenNavigate: (() -> Unit) -> Unit = { navigate ->
        coroutineScope.launch {
            drawerState.close()
            navigate()
        }
    }

    LaunchedEffect(state.selectedRoomId, state.overlay) {
        if (state.selectedRoomId != null || state.overlay != PrivateChatOverlay.HIDDEN) {
            drawerState.close()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.selectedRoomId == null && state.overlay == PrivateChatOverlay.HIDDEN,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.widthIn(max = 360.dp)) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Synapse Chat",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = profile?.let { "${it.displayName} · @${it.username}" } ?: "Synapse Private",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text("Chats") },
                        icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                        selected = state.selectedRoomId == null && state.overlay == PrivateChatOverlay.HIDDEN,
                        onClick = { closeThenNavigate(navigationActions.showRoomList) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("New conversation") },
                        icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        selected = false,
                        onClick = { closeThenNavigate(navigationActions.showCreateConversation) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Profile & privacy") },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        selected = false,
                        onClick = { closeThenNavigate(navigationActions.showProfile) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    OutlinedButton(
                        onClick = { closeThenNavigate(onCreateAccountInvitation) },
                        enabled =
                            state.session is PrivateChatSessionUiState.Active &&
                                profile != null &&
                                state.operation !is PrivateChatOperationUiState.Running &&
                                state.accountInvitation !is PrivateAccountInvitationUiState.Creating,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Text("Invite a friend", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        text = "Account invites let a friend join Synapse. Use a conversation's menu to invite them into that chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        },
    ) {
        content { coroutineScope.launch { drawerState.open() } }
    }
    BackHandler(enabled = drawerState.isOpen) {
        coroutineScope.launch { drawerState.close() }
    }
}
