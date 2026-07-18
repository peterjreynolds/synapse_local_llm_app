package app.synapse.localllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.remote.CreateRemoteInvitationCommand
import app.synapse.localllm.domain.remote.OwnerInvitationSummary

@Composable
internal fun OwnerAdminInvitationSection(
    state: OwnerAdminUiState,
    viewModel: OwnerAdminViewModel,
    onInvitationCodeCreated: (String) -> Unit,
    onNotice: (String) -> Unit,
) {
    var showAdvancedOptions by rememberSaveable { mutableStateOf(false) }
    var invitationLabel by rememberSaveable { mutableStateOf("") }
    var invitationHours by rememberSaveable { mutableStateOf(DEFAULT_INVITATION_LIFETIME_HOURS.toString()) }
    var invitationUses by rememberSaveable { mutableStateOf(DEFAULT_INVITATION_MAXIMUM_USES.toString()) }

    OwnerSection("Invite codes") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Approve new accounts", fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.registrationApprovalRequired) {
                        "New registrations wait for Peter's approval."
                    } else {
                        "A valid invite activates the account immediately."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.registrationApprovalRequired,
                onCheckedChange = viewModel::setRegistrationApprovalRequired,
                enabled = !state.isActionRunning,
            )
        }
        Button(
            onClick = {
                viewModel.createInvitation(defaultOwnerInvitationCommand(), onInvitationCodeCreated)
            },
            enabled = !state.isActionRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate one-use invite code")
        }
        OutlinedButton(
            onClick = { showAdvancedOptions = !showAdvancedOptions },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isActionRunning,
        ) {
            Text(if (showAdvancedOptions) "Hide invite options" else "Advanced invite options")
        }
        if (showAdvancedOptions) {
            OutlinedTextField(
                value = invitationLabel,
                onValueChange = { invitationLabel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Label, optional") },
                singleLine = true,
            )
            OutlinedTextField(
                value = invitationHours,
                onValueChange = { invitationHours = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Expires after hours") },
                singleLine = true,
            )
            OutlinedTextField(
                value = invitationUses,
                onValueChange = { invitationUses = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Number of uses") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    val hours = invitationHours.toIntOrNull()
                    val uses = invitationUses.toIntOrNull()
                    if (hours == null || uses == null) {
                        onNotice("Invite lifetime and uses must be whole numbers.")
                    } else {
                        viewModel.createInvitation(
                            CreateRemoteInvitationCommand(
                                intendedLabel = invitationLabel.trim().ifBlank { null },
                                lifetimeHours = hours,
                                maximumUses = uses,
                            ),
                            onInvitationCodeCreated,
                        )
                    }
                },
                enabled = !state.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Generate custom invite")
            }
        }
        val activeInvitations = activeOwnerInvitations(state.invitations)
        activeInvitations.forEach { invitation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(invitation.intendedLabel ?: "Active invite", fontWeight = FontWeight.SemiBold)
                    Text("${invitation.remainingUses} use${if (invitation.remainingUses == 1) "" else "s"} remaining")
                    Text(
                        "Expires ${formatOwnerTimestamp(invitation.expiresAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { viewModel.revokeInvitation(invitation.invitationId) },
                        enabled = !state.isActionRunning,
                    ) {
                        Text("Revoke invite")
                    }
                }
            }
        }
        if (activeInvitations.isEmpty()) {
            Text("No active invite codes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun defaultOwnerInvitationCommand(): CreateRemoteInvitationCommand = CreateRemoteInvitationCommand(
    intendedLabel = null,
    lifetimeHours = DEFAULT_INVITATION_LIFETIME_HOURS,
    maximumUses = DEFAULT_INVITATION_MAXIMUM_USES,
)

internal fun activeOwnerInvitations(
    invitations: List<OwnerInvitationSummary>,
    currentTimeMillis: Long = System.currentTimeMillis(),
): List<OwnerInvitationSummary> = invitations
    .filter { invitation ->
        invitation.state == ACTIVE_INVITATION_STATE &&
            invitation.remainingUses > 0 &&
            invitation.expiresAtMillis > currentTimeMillis
    }
    .sortedBy(OwnerInvitationSummary::expiresAtMillis)

private const val DEFAULT_INVITATION_LIFETIME_HOURS = 24 * 7
private const val DEFAULT_INVITATION_MAXIMUM_USES = 1
private const val ACTIVE_INVITATION_STATE = "ACTIVE"
