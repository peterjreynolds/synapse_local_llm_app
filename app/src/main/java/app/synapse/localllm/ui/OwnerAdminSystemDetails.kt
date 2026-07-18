package app.synapse.localllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.domain.remote.OwnerCleanupJobSummary
import app.synapse.localllm.domain.remote.OwnerCleanupState

@Composable
internal fun OwnerAdminSystemDetails(state: OwnerAdminUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    OwnerDisclosureSection(
        title = "System details",
        supportingText = "Service status and account security history for troubleshooting.",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        OwnerOperationsSection(state)
        HorizontalDivider()
        Text("Account security history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        state.auditEvents.forEach { event ->
            val targetAccount = state.accounts.firstOrNull { account -> account.accountUid == event.targetUid }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ownerAuditEventLabel(event.eventType), fontWeight = FontWeight.SemiBold)
                Text(
                    targetAccount?.let { account -> "${account.displayName} · @${account.usernameNormalized}" }
                        ?: "System-wide owner action",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    formatOwnerTimestamp(event.createdAtMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
        if (state.auditEvents.isEmpty()) Text("No owner security actions recorded.")
    }
}

@Composable
private fun OwnerOperationsSection(state: OwnerAdminUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Service status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Synapse ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                BuildConfig.SYNAPSE_APK_CHANNEL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val operations = state.operationsSummary
        if (operations == null) {
            Text("Service status is loading.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text("Service online", fontWeight = FontWeight.SemiBold)
        Text("${operations.activeDeviceCount} of ${operations.totalDeviceCount} registered devices are active")
        Text(
            if (state.localOutbox.failedCount > 0) {
                "${state.localOutbox.failedCount} outgoing messages need attention"
            } else if (state.localOutbox.pendingCount + state.localOutbox.inFlightCount > 0) {
                "${state.localOutbox.pendingCount + state.localOutbox.inFlightCount} outgoing messages are being sent"
            } else {
                "No outgoing messages are waiting"
            },
        )
        Text(
            if (operations.failedNotificationDeliveryCount > 0) {
                "${operations.failedNotificationDeliveryCount} notification deliveries need attention"
            } else if (operations.pendingNotificationDeliveryCount > 0) {
                "${operations.pendingNotificationDeliveryCount} notification deliveries are pending"
            } else {
                "Notifications are delivering normally"
            },
        )
        Text(
            if (operations.integrity.issueCount == 0) {
                "${operations.activeRoomCount} conversations · no room problems found"
            } else {
                "${operations.integrity.issueCount} room problems need attention"
            },
        )
        if (operations.integrity.issueCodes.isNotEmpty()) {
            Text(
                operations.integrity.issueCodes.joinToString { issueCode ->
                    issueCode.lowercase().replace('_', ' ')
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(formatCleanupStatus("Attachment housekeeping", operations.attachmentCleanup))
        Text(formatCleanupStatus("Data housekeeping", operations.operationalDataCleanup))
        Text(
            "Checked ${formatOwnerTimestamp(operations.generatedAtMillis)} · service revision ${operations.backendRevision}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatCleanupStatus(
    label: String,
    cleanup: OwnerCleanupJobSummary,
): String {
    val affected = cleanup.affectedDocumentCount ?: 0
    return when (cleanup.state) {
        OwnerCleanupState.NEVER_RUN -> "$label has not needed a completed run yet"
        OwnerCleanupState.RUNNING -> "$label is running now"
        OwnerCleanupState.SUCCEEDED ->
            "$label completed successfully · $affected record${if (affected == 1) "" else "s"} handled"

        OwnerCleanupState.FAILED -> "$label needs attention"
    }
}

internal fun ownerAuditEventLabel(eventType: String): String = when (eventType) {
    "ACCOUNT_CREATED_BY_OWNER" -> "Account created"
    "ACCOUNT_DELETED" -> "Account deleted"
    "ACCOUNT_DISABLED" -> "Account disabled"
    "ACCOUNT_ENABLED" -> "Account enabled"
    "ACCOUNT_PASSWORD_RESET" -> "Temporary password set"
    "ACCOUNT_REGISTERED_WITH_INVITE" -> "Account registered with invite"
    "ACCOUNT_SESSIONS_REVOKED" -> "Account signed out on all devices"
    "DEVICE_REGISTRATION_REMOVED", "OWN_DEVICE_REGISTRATION_REMOVED" -> "Device registration removed"
    "DEVICE_TEST_PUSH_SENT" -> "Test notification sent"
    "INVITATION_CREATED" -> "Invite code created"
    "INVITATION_REVOKED" -> "Invite code revoked"
    "REGISTRATION_APPROVAL_POLICY_UPDATED" -> "New-account approval setting changed"
    "REGISTRATION_APPROVED" -> "Registration approved"
    "REGISTRATION_REJECTED" -> "Registration rejected"
    "REQUIRED_PASSWORD_CHANGE_COMPLETED" -> "Required password change completed"
    "ROOM_AI_CONFIGURATION_UPDATED" -> "Conversation AI setting changed"
    else -> eventType
        .lowercase()
        .split('_')
        .filter(String::isNotBlank)
        .joinToString(" ")
        .replaceFirstChar(Char::uppercase)
}
