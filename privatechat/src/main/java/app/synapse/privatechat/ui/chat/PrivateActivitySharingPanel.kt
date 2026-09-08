package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
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
import app.synapse.privatechat.domain.chat.PrivateActivitySharingPreferences
import app.synapse.privatechat.domain.chat.PrivateActivitySharingState
import app.synapse.privatechat.domain.chat.PrivatePresenceSnapshot

@Composable
internal fun PrivateActivitySharingControls(
    preferences: PrivateActivitySharingPreferences,
    enabled: Boolean,
    onChangeReadReceiptSharing: (PrivateActivitySharingState) -> Unit,
    onChangeTypingIndicatorSharing: (PrivateActivitySharingState) -> Unit,
) {
    PrivateActivitySharingSwitch(
        label = "Read receipts",
        detail = "Let people know when you open their messages.",
        sharingState = preferences.readReceipts,
        enabled = enabled,
        onChange = onChangeReadReceiptSharing,
    )
    PrivateActivitySharingSwitch(
        label = "Typing indicators",
        detail = "Let people see while you are typing.",
        sharingState = preferences.typingIndicators,
        enabled = enabled,
        onChange = onChangeTypingIndicatorSharing,
    )
}

@Composable
private fun PrivateActivitySharingSwitch(
    label: String,
    detail: String,
    sharingState: PrivateActivitySharingState,
    enabled: Boolean,
    onChange: (PrivateActivitySharingState) -> Unit,
) {
    val sharingEnabled = sharingState == PrivateActivitySharingState.ENABLED
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = sharingEnabled,
                    enabled = enabled,
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
            enabled = enabled,
            modifier = Modifier.clearAndSetSemantics { },
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
