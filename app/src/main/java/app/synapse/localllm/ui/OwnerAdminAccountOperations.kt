package app.synapse.localllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.remote.OwnerAccountSummary
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.ResetOwnerAccountPasswordCommand

@Composable
internal fun OwnerAccountOperations(
    account: OwnerAccountSummary,
    state: OwnerAdminUiState,
    resetTemporaryPassword: String,
    resetOwnerPassword: String,
    resetRequiresPasswordChange: Boolean,
    deleteConfirmation: String,
    deleteOwnerPassword: String,
    onResetTemporaryPasswordChanged: (String) -> Unit,
    onResetOwnerPasswordChanged: (String) -> Unit,
    onResetRequiresPasswordChangeChanged: (Boolean) -> Unit,
    onDeleteConfirmationChanged: (String) -> Unit,
    onDeleteOwnerPasswordChanged: (String) -> Unit,
    onGeneratePassword: () -> Unit,
    onConfirmSensitiveAction: (() -> Unit) -> Unit,
    onTemporaryPasswordRevealed: (String) -> Unit,
    onDeleteCompleted: () -> Unit,
    viewModel: OwnerAdminViewModel,
) {
    var showAdvancedActions by rememberSaveable(account.accountUid.raw) { mutableStateOf(false) }
    Column(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Account settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "${account.role.name.lowercase().replaceFirstChar(Char::uppercase)} · " +
                "last active ${formatOwnerTimestamp(account.lastSeenAtMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (account.mustChangePassword) {
            Text("A password change is required at the next sign-in.", color = MaterialTheme.colorScheme.error)
        }

        if (account.role != RemoteAccountRole.OWNER) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account.state == RemoteAccountState.ACTIVE) {
                    OutlinedButton(
                        onClick = { viewModel.setAccountEnabled(account.accountUid, enabled = false) },
                        enabled = !state.isActionRunning,
                    ) {
                        Text("Disable")
                    }
                } else if (account.state == RemoteAccountState.DISABLED) {
                    OutlinedButton(
                        onClick = { viewModel.setAccountEnabled(account.accountUid, enabled = true) },
                        enabled = !state.isActionRunning,
                    ) {
                        Text("Enable")
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.revokeAccountSessions(account.accountUid) },
                    enabled = !state.isActionRunning,
                ) {
                    Text("Sign out everywhere")
                }
            }

            HorizontalDivider()
            Text("Password help", fontWeight = FontWeight.SemiBold)
            Text(
                "Current passwords cannot be read. Set a temporary password here and give it to the account owner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SensitivePasswordField(
                value = resetTemporaryPassword,
                label = "New temporary password",
                onValueChange = onResetTemporaryPasswordChanged,
            )
            OutlinedButton(onClick = onGeneratePassword, enabled = !state.isActionRunning) {
                Text("Generate temporary password")
            }
            SensitivePasswordField(
                value = resetOwnerPassword,
                label = "Your current owner password",
                onValueChange = onResetOwnerPasswordChanged,
            )
            CheckboxLine(
                checked = resetRequiresPasswordChange,
                label = "Require password change on next sign-in",
                onCheckedChange = onResetRequiresPasswordChangeChanged,
            )
            Button(
                onClick = {
                    val submittedPassword = resetTemporaryPassword
                    onConfirmSensitiveAction {
                        viewModel.resetAccountPassword(
                            ownerPassword = resetOwnerPassword,
                            command = ResetOwnerAccountPasswordCommand(
                                targetUid = account.accountUid,
                                temporaryPassword = submittedPassword,
                                requirePasswordChange = resetRequiresPasswordChange,
                            ),
                        ) { onTemporaryPasswordRevealed(submittedPassword) }
                    }
                },
                enabled = !state.isActionRunning &&
                    resetTemporaryPassword.length in 12..128 &&
                    resetOwnerPassword.isNotEmpty(),
            ) {
                Text("Set temporary password")
            }
        }

        OwnerDisclosureSection(
            title = "Devices and advanced actions",
            supportingText = "Test notifications, remove a device, or permanently delete this account.",
            expanded = showAdvancedActions,
            onToggle = { showAdvancedActions = !showAdvancedActions },
        ) {
            Text("Registered devices", fontWeight = FontWeight.SemiBold)
            state.selectedAccountDevices.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Android device ${device.deviceId.raw.take(10)}…")
                        Text(
                            "${if (device.active) "Active" else "Inactive"} · " +
                                "updated ${formatOwnerTimestamp(device.updatedAtMillis)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.sendTestPush(account.accountUid, device.deviceId) },
                                enabled = !state.isActionRunning && device.active,
                            ) {
                                Text("Test notification")
                            }
                            OutlinedButton(
                                onClick = { viewModel.removeDevice(account.accountUid, device.deviceId) },
                                enabled = !state.isActionRunning,
                            ) {
                                Text("Remove")
                            }
                        }
                    }
                }
            }
            if (state.selectedAccountDevices.isEmpty()) Text("No registered devices.")

            if (account.role != RemoteAccountRole.OWNER) {
                HorizontalDivider()
                Text("Delete account", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text("Type @${account.usernameNormalized} and confirm owner access. This cannot be undone.")
                OutlinedTextField(
                    value = deleteConfirmation,
                    onValueChange = onDeleteConfirmationChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm username") },
                    singleLine = true,
                )
                SensitivePasswordField(
                    value = deleteOwnerPassword,
                    label = "Your current owner password",
                    onValueChange = onDeleteOwnerPasswordChanged,
                )
                Button(
                    onClick = {
                        onConfirmSensitiveAction {
                            viewModel.deleteAccount(
                                ownerPassword = deleteOwnerPassword,
                                targetUid = account.accountUid,
                                confirmUsername = deleteConfirmation.trim().removePrefix("@"),
                                onDeleted = onDeleteCompleted,
                            )
                        }
                    },
                    enabled = !state.isActionRunning &&
                        deleteOwnerPassword.isNotEmpty() &&
                        deleteConfirmation.trim().removePrefix("@").equals(
                            account.usernameNormalized,
                            ignoreCase = true,
                        ),
                ) {
                    Text("Permanently delete account")
                }
            }
        }
    }
}
