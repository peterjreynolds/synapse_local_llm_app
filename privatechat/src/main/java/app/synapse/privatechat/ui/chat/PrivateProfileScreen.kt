package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.ui.account.PrivateAccountSignOutUiState
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem

@Composable
internal fun PrivateProfileScreen(
    socialState: PrivateSocialUiState,
    roomFeedState: PrivateRoomFeedUiState,
    presencePublication: PrivatePresencePublicationUiState,
    accountInvitation: PrivateAccountInvitationUiState,
    operation: PrivateChatOperationUiState,
    accountSessionActions: PrivateAccountSessionUiActions,
    socialActions: PrivateSocialUiActions,
    onDismissOperationNotice: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    val social = socialState as? PrivateSocialUiState.Available
    val profile = social?.snapshot?.profile
    val operationRunning = operation is PrivateChatOperationUiState.Running
    val signOutRunning = accountSessionActions.signOutState is PrivateAccountSignOutUiState.SigningOut
    val availableRoomFeed = roomFeedState as? PrivateRoomFeedUiState.Available
    val activityPreferences = availableRoomFeed?.snapshot?.activitySharingPreferences
    val activitySharingMutationEnabled =
        !operationRunning && availableRoomFeed?.connectionState == PrivateChatConnectionUiState.CONNECTED
    var displayName by remember(profile?.accountId) {
        mutableStateOf(profile?.displayName.orEmpty())
    }

    Surface(
        modifier = Modifier.fillMaxSize().semantics { paneTitle = "Profile & privacy" },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    enabled = !operationRunning && !signOutRunning,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats")
                }
                Text(
                    text = "Profile & privacy",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            PrivateChatOperationNotice(
                operation = operation,
                onDismiss = onDismissOperationNotice,
                modifier = Modifier.padding(horizontal = tokens.spacing.large, vertical = tokens.spacing.small),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = tokens.spacing.large),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.medium),
            ) {
                Spacer(Modifier.height(tokens.spacing.compact))
                if (social == null) {
                    PrivateProfileSection(title = "Profile") {
                        Text(
                            text = privateSocialUnavailableMessage(socialState),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    PrivateIdentityCard(
                        displayName = displayName,
                        username = social.snapshot.profile.username,
                        enabled = !operationRunning,
                        onDisplayNameChanged = { changedName -> displayName = changedName },
                        onSave = { socialActions.saveProfile(displayName) },
                    )
                    PrivateProfileSection(title = "Privacy") {
                        PrivateProfileSwitch(
                            label = "Online status",
                            detail =
                                privatePresencePublicationLabel(
                                    sharingState = social.snapshot.presenceSharing,
                                    publicationState = presencePublication,
                                ),
                            checked = social.snapshot.presenceSharing == PrivatePresenceSharingState.ENABLED,
                            enabled = !operationRunning,
                            onCheckedChange = { enabled ->
                                socialActions.changePresenceSharing(
                                    if (enabled) {
                                        PrivatePresenceSharingState.ENABLED
                                    } else {
                                        PrivatePresenceSharingState.DISABLED
                                    },
                                )
                            },
                        )
                        activityPreferences?.let { preferences ->
                            PrivateActivitySharingControls(
                                preferences = preferences,
                                enabled = activitySharingMutationEnabled,
                                onChangeReadReceiptSharing = socialActions.changeReadReceiptSharing,
                                onChangeTypingIndicatorSharing = socialActions.changeTypingIndicatorSharing,
                            )
                        }
                        Text(
                            text = "Online, typing, and read activity is optional and expires instead of becoming history.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PrivateProfileSection(title = "Invitations") {
                        Text(
                            text = "Create a one-use account invite for one trusted friend.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                socialActions.createOneUseAccountInvitation()
                            },
                            enabled =
                                !operationRunning &&
                                    accountInvitation !is PrivateAccountInvitationUiState.Creating,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Text("Create account invite", modifier = Modifier.padding(start = tokens.spacing.small))
                        }
                    }
                }
                PrivateProfileSection(title = "Account") {
                    TextButton(
                        onClick = accountSessionActions.signOut,
                        enabled = !operationRunning && !signOutRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Text(
                            text = if (signOutRunning) "Signing out…" else "Sign out of this device",
                            modifier = Modifier.padding(start = tokens.spacing.small),
                        )
                    }
                    privateSignOutFailureMessage(accountSessionActions.signOutState)?.let { failureMessage ->
                        Text(
                            text = failureMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(tokens.spacing.spacious))
            }
        }
    }
}

@Composable
private fun PrivateIdentityCard(
    displayName: String,
    username: String,
    enabled: Boolean,
    onDisplayNameChanged: (String) -> Unit,
    onSave: () -> Unit,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    PrivateProfileSection(title = "Profile") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.medium),
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = privateAvatarInitial(displayName),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayNameChanged,
            label = { Text("Display name") },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSave,
            enabled = enabled && displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save profile")
        }
    }
}

@Composable
private fun PrivateProfileSection(
    title: String,
    content: @Composable () -> Unit,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.large),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.medium),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun PrivateProfileSwitch(
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).semantics(mergeDescendants = true) {
                    contentDescription = "$label. $detail"
                    stateDescription = if (checked) "Enabled" else "Disabled"
                }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

private fun privateSignOutFailureMessage(state: PrivateAccountSignOutUiState): String? =
    when (state) {
        PrivateAccountSignOutUiState.Idle,
        PrivateAccountSignOutUiState.SigningOut,
        PrivateAccountSignOutUiState.AlreadySignedOut,
        is PrivateAccountSignOutUiState.LocallySignedOut,
        -> null

        is PrivateAccountSignOutUiState.Rejected -> state.userMessage
        PrivateAccountSignOutUiState.TransportUnavailable ->
            "Sign-out could not reach the account service. You are still signed in."

        PrivateAccountSignOutUiState.LocalStateUnavailable ->
            "The secure session could not be cleared. You are still signed in."

        PrivateAccountSignOutUiState.VerificationFailed ->
            "The sign-out receipt could not be verified. You are still signed in."
    }

private fun privateSocialUnavailableMessage(state: PrivateSocialUiState): String =
    when (state) {
        PrivateSocialUiState.Loading -> "Your profile is still loading."
        PrivateSocialUiState.TransportUnavailable -> "Your profile is temporarily unavailable."
        PrivateSocialUiState.UnexpectedFailure -> "Your profile response could not be verified."
        PrivateSocialUiState.NotRequested -> "Sign in before opening profile settings."
        is PrivateSocialUiState.Available -> ""
    }

internal fun privatePresencePublicationLabel(
    sharingState: PrivatePresenceSharingState,
    publicationState: PrivatePresencePublicationUiState,
): String {
    if (sharingState == PrivatePresenceSharingState.DISABLED) return "Off"
    return when (publicationState) {
        PrivatePresencePublicationUiState.NotSharing -> "On while Synapse is open"
        PrivatePresencePublicationUiState.Background -> "On · paused while the app is in the background"
        PrivatePresencePublicationUiState.Publishing -> "On · updating…"
        is PrivatePresencePublicationUiState.Confirmed -> "On · visible for a short-lived window"
        PrivatePresencePublicationUiState.TransportUnavailable -> "On · reconnecting before sharing"
        PrivatePresencePublicationUiState.UnexpectedFailure -> "On · confirmation failed"
    }
}
