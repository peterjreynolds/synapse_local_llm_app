package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState

@Composable
internal fun PrivateProfileDialog(
    socialState: PrivateSocialUiState,
    presencePublication: PrivatePresencePublicationUiState,
    accountInvitation: PrivateAccountInvitationUiState,
    operation: PrivateChatOperationUiState,
    onSaveProfile: (String) -> Unit,
    onChangePresenceSharing: (PrivatePresenceSharingState) -> Unit,
    onCreateAccountInvitation: () -> Unit,
    onDismiss: () -> Unit,
) {
    val social = socialState as? PrivateSocialUiState.Available
    val operationRunning = operation is PrivateChatOperationUiState.Running
    var displayName by remember(social?.snapshot?.profile?.accountId) {
        mutableStateOf(
            social
                ?.snapshot
                ?.profile
                ?.displayName
                .orEmpty(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profile and privacy") },
        text = {
            if (social == null) {
                Text(privateSocialUnavailableMessage(socialState))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Signed in as @${social.snapshot.profile.username}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { changedName -> displayName = changedName },
                        label = { Text("Display name") },
                        enabled = !operationRunning,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Share online status")
                            Text(
                                text = privatePresencePublicationLabel(presencePublication),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = social.snapshot.presenceSharing == PrivatePresenceSharingState.ENABLED,
                            onCheckedChange = { checked ->
                                onChangePresenceSharing(
                                    if (checked) {
                                        PrivatePresenceSharingState.ENABLED
                                    } else {
                                        PrivatePresenceSharingState.DISABLED
                                    },
                                )
                            },
                            enabled = !operationRunning,
                        )
                    }
                    Text(
                        text = "Online, typing, and read activity is optional and expires instead of becoming history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onCreateAccountInvitation,
                        enabled = !operationRunning && accountInvitation !is PrivateAccountInvitationUiState.Creating,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Invite a friend to Synapse Private")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveProfile(displayName) },
                enabled = social != null && !operationRunning && displayName.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !operationRunning) {
                Text("Close")
            }
        },
    )
}

private fun privateSocialUnavailableMessage(state: PrivateSocialUiState): String =
    when (state) {
        PrivateSocialUiState.Loading -> "The profile is still loading."
        PrivateSocialUiState.TransportUnavailable -> "Profile transport is unavailable. No profile data was loaded."
        PrivateSocialUiState.UnexpectedFailure -> "The profile response could not be verified."
        PrivateSocialUiState.NotRequested -> "Sign in before opening profile settings."
        is PrivateSocialUiState.Available -> ""
    }

private fun privatePresencePublicationLabel(state: PrivatePresencePublicationUiState): String =
    when (state) {
        PrivatePresencePublicationUiState.NotSharing -> "Off"
        PrivatePresencePublicationUiState.Background -> "Paused while the app is in the background"
        PrivatePresencePublicationUiState.Publishing -> "Waiting for confirmation"
        is PrivatePresencePublicationUiState.Confirmed -> "Confirmed for a short-lived window"
        PrivatePresencePublicationUiState.TransportUnavailable -> "Not currently published: connection unavailable"
        PrivatePresencePublicationUiState.UnexpectedFailure -> "Not currently published: confirmation failed"
    }
