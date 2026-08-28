package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.chat.PrivateActivityFeedAvailability
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivatePresenceSnapshot
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem

@Composable
internal fun PrivateActivitySharingCard(
    preferences: PrivateActivitySharingPreferences,
    socialActions: PrivateSocialUiActions,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
            ),
        shape = RoundedCornerShape(tokens.radii.control),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.small),
        ) {
            Text("Activity sharing", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "These start off. Enable only the short-lived activity details you want to share.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrivateActivitySharingSwitch(
                label = "Read receipts",
                detail = "Shares when you have opened messages in a conversation.",
                sharingState = preferences.readReceipts,
                onChange = socialActions.changeReadReceiptSharing,
            )
            PrivateActivitySharingSwitch(
                label = "Typing indicators",
                detail = "Shares while you are actively typing in a conversation.",
                sharingState = preferences.typingIndicators,
                onChange = socialActions.changeTypingIndicatorSharing,
            )
        }
    }
}

@Composable
private fun PrivateActivitySharingSwitch(
    label: String,
    detail: String,
    sharingState: PrivateActivitySharingState,
    onChange: (PrivateActivitySharingState) -> Unit,
) {
    val sharingEnabled = sharingState == PrivateActivitySharingState.ENABLED
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = sharingEnabled,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        onChange(
                            if (enabled) {
                                PrivateActivitySharingState.ENABLED
                            } else {
                                PrivateActivitySharingState.DISABLED
                            },
                        )
                    },
                ).semantics(mergeDescendants = true) {
                    contentDescription = "$label. $detail"
                    stateDescription = if (sharingEnabled) "Enabled" else "Disabled"
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
            checked = sharingEnabled,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
internal fun PrivatePresenceSummary(socialState: PrivateSocialUiState) {
    when (socialState) {
        is PrivateSocialUiState.Available -> {
            val snapshot = socialState.snapshot
            Text(
                text =
                    if (snapshot.presenceAvailability == PrivateActivityFeedAvailability.AVAILABLE) {
                        privateVisiblePresenceLabel(snapshot.visiblePresence)
                    } else {
                        "Online status temporarily unavailable. Conversations are still connected."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }

        PrivateSocialUiState.Loading ->
            Text(
                text = "Checking short-lived online status…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        PrivateSocialUiState.NotRequested,
        PrivateSocialUiState.TransportUnavailable,
        PrivateSocialUiState.UnexpectedFailure,
        ->
            Text(
                text = "Online status unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

internal fun privateVisiblePresenceLabel(visiblePresence: List<PrivatePresenceSnapshot>): String {
    if (visiblePresence.isEmpty()) return "Nobody is currently sharing online status."
    val displayedNames = visiblePresence.take(PRIVATE_VISIBLE_PRESENCE_NAME_LIMIT).map(PrivatePresenceSnapshot::displayName)
    val remainingCount = visiblePresence.size - displayedNames.size
    val remainingLabel =
        when (remainingCount) {
            0 -> ""
            1 -> ", and 1 other"
            else -> ", and $remainingCount others"
        }
    return "Online now: ${displayedNames.joinToString()}$remainingLabel"
}

private const val PRIVATE_VISIBLE_PRESENCE_NAME_LIMIT = 3
