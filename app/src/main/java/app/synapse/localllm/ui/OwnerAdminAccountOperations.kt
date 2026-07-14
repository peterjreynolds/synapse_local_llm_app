package app.synapse.localllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    OwnerSection("Manage @${account.usernameNormalized}") {
        Text("${account.displayName} · ${account.role.name} · ${account.state.name}")
        Text("Created ${formatOwnerTimestamp(account.createdAtMillis)}")
        Text("Last active ${formatOwnerTimestamp(account.lastSeenAtMillis)}")
        if (account.mustChangePassword) Text("Password change required")

        if (account.role != RemoteAccountRole.OWNER) {
            if (account.state == RemoteAccountState.ACTIVE) {
                OutlinedButton(
                    onClick = { viewModel.setAccountEnabled(account.accountUid, enabled = false) },
                    enabled = !state.isActionRunning,
                ) {
                    Text("Disable account")
                }
            } else if (account.state == RemoteAccountState.DISABLED) {
                OutlinedButton(
                    onClick = { viewModel.setAccountEnabled(account.accountUid, enabled = true) },
                    enabled = !state.isActionRunning,
                ) {
                    Text("Enable account")
                }
            }
            OutlinedButton(
                onClick = { viewModel.revokeAccountSessions(account.accountUid) },
                enabled = !state.isActionRunning,
            ) {
                Text("Force logout on all devices")
            }
        }

        HorizontalDivider()
        Text("Registered devices", fontWeight = FontWeight.SemiBold)
        state.selectedAccountDevices.forEach { device ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Android device ${device.deviceId.raw.take(10)}…")
                    Text(if (device.active) "Active" else "Inactive")
                    Text("Last update ${formatOwnerTimestamp(device.updatedAtMillis)}")
                    OutlinedButton(
                        onClick = { viewModel.sendTestPush(account.accountUid, device.deviceId) },
                        enabled = !state.isActionRunning && device.active,
                    ) {
                        Text("Send test notification")
                    }
                    OutlinedButton(
                        onClick = { viewModel.removeDevice(account.accountUid, device.deviceId) },
                        enabled = !state.isActionRunning,
                    ) {
                        Text("Remove registration")
                    }
                }
            }
        }
        if (state.selectedAccountDevices.isEmpty()) Text("No registered devices.")

        if (account.role != RemoteAccountRole.OWNER) {
            HorizontalDivider()
            Text("Set/reset password", fontWeight = FontWeight.SemiBold)
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
