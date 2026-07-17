package app.synapse.localllm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.POST_NOTIFICATIONS_PERMISSION
import app.synapse.localllm.domain.notifications.NotificationPermissionState
import app.synapse.localllm.resolveNotificationPermissionState
import java.time.Instant

@Composable
internal fun RemoteProfilePane(
    state: RemoteChatUiState,
    viewModel: RemoteChatViewModel,
    accountState: RemoteAccountUiState,
    accountViewModel: RemoteAccountViewModel,
    appUpdate: AppUpdateUiState,
    appLockState: AppLockUiState,
    appLockViewModel: AppLockViewModel,
    onCheckAppUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val accountUid = state.account?.accountUid
    val profile = state.profiles.firstOrNull { candidate -> candidate.profileUid.raw == accountUid?.raw }
    var displayName by rememberSaveable(accountUid?.raw) { mutableStateOf("") }
    var bio by rememberSaveable(accountUid?.raw) { mutableStateOf("") }
    var currentPassword by remember(accountUid?.raw) { mutableStateOf("") }
    var newPassword by remember(accountUid?.raw) { mutableStateOf("") }
    var confirmNewPassword by remember(accountUid?.raw) { mutableStateOf("") }
    var deletionPassword by remember(accountUid?.raw) { mutableStateOf("") }
    var deletionUsername by remember(accountUid?.raw) { mutableStateOf("") }
    var localSecurityNotice by remember(accountUid?.raw) { mutableStateOf<String?>(null) }
    var notificationPermissionState by remember {
        mutableStateOf(resolveNotificationPermissionState(context))
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionState = if (granted) {
            NotificationPermissionState.GRANTED
        } else {
            NotificationPermissionState.DENIED
        }
    }
    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            viewModel.uploadAvatar(uri.toString(), mimeType)
        }
    }

    ClearSensitiveInputsOnStop {
        currentPassword = ""
        newPassword = ""
        confirmNewPassword = ""
        deletionPassword = ""
        accountViewModel.clearGeneratedInvitation()
    }
    if (
        currentPassword.isNotEmpty() ||
        newPassword.isNotEmpty() ||
        confirmNewPassword.isNotEmpty() ||
        deletionPassword.isNotEmpty() ||
        accountState.generatedInvitation != null
    ) {
        BlockScreenshotsWhileVisible()
    }

    LaunchedEffect(profile?.remoteUpdatedAt) {
        if (profile != null) {
            displayName = profile.displayName
            bio = profile.bio
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemoteProfileAvatar(
                    profile = profile,
                    displayName = profile?.displayName ?: state.account?.usernameNormalized.orEmpty(),
                )
                Column {
                    Text(profile?.displayName ?: "Synapse account", fontWeight = FontWeight.Bold)
                    Text("@${state.account?.usernameNormalized.orEmpty()}")
                    Text(
                        text = remotePresenceLabel(profile),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = displayName,
            onValueChange = { value -> displayName = value.take(64) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name") },
            singleLine = true,
        )
        OutlinedTextField(
            value = bio,
            onValueChange = { value -> bio = value.take(160) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Short bio or status") },
            minLines = 2,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { viewModel.updateProfile(displayName, bio) },
                enabled = displayName.isNotBlank() && !state.isActionRunning,
                modifier = Modifier.weight(1f),
            ) {
                Text("Save profile")
            }
            OutlinedButton(
                onClick = { avatarLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                enabled = !state.isActionRunning,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text(" Photo")
            }
        }

        HorizontalDivider()
        RemoteInvitationControls(
            state = accountState,
            onCreateInvitation = accountViewModel::createInvitation,
        )

        HorizontalDivider()
        Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = if (notificationPermissionState.allowsNotifications) {
                "Android notifications are enabled for private chat. Message text is not placed in push payloads."
            } else {
                "Android notification permission is denied. Enable it to see new-message alerts while Synapse is backgrounded."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (notificationPermissionState.canRequestRuntimePermission) {
            OutlinedButton(onClick = {
                notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
            }) {
                Icon(Icons.Default.Notifications, contentDescription = null)
                Text(" Enable notifications")
            }
        }
        NotificationPreferenceRow(
            label = "Direct messages",
            checked = state.notificationPreferences.directMessages,
            enabled = !state.isActionRunning,
            onCheckedChange = { enabled ->
                viewModel.updateNotificationPreferences(
                    state.notificationPreferences.copy(directMessages = enabled),
                )
            },
        )
        NotificationPreferenceRow(
            label = "Group messages",
            checked = state.notificationPreferences.groupMessages,
            enabled = !state.isActionRunning,
            onCheckedChange = { enabled ->
                viewModel.updateNotificationPreferences(
                    state.notificationPreferences.copy(groupMessages = enabled),
                )
            },
        )
        NotificationPreferenceRow(
            label = "Group mentions",
            checked = state.notificationPreferences.mentions,
            enabled = !state.isActionRunning,
            onCheckedChange = { enabled ->
                viewModel.updateNotificationPreferences(
                    state.notificationPreferences.copy(mentions = enabled),
                )
            },
        )
        NotificationPreferenceRow(
            label = "Alerts from muted conversations",
            checked = state.notificationPreferences.mutedRooms,
            enabled = !state.isActionRunning,
            onCheckedChange = { enabled ->
                viewModel.updateNotificationPreferences(
                    state.notificationPreferences.copy(mutedRooms = enabled),
                )
            },
        )

        HorizontalDivider()
        Text("Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AppLockSettings(
            state = appLockState,
            viewModel = appLockViewModel,
        )

        HorizontalDivider()
        Text("Account password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = currentPassword,
            onValueChange = { value -> currentPassword = value },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Current password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            value = newPassword,
            onValueChange = { value -> newPassword = value },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("New password") },
            supportingText = { Text("12-128 characters") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
            value = confirmNewPassword,
            onValueChange = { value -> confirmNewPassword = value },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Confirm new password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        localSecurityNotice?.let { notice ->
            Text(notice, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = {
                if (newPassword != confirmNewPassword) {
                    localSecurityNotice = "New passwords do not match."
                    return@OutlinedButton
                }
                localSecurityNotice = null
                viewModel.changePassword(currentPassword, newPassword)
                currentPassword = ""
                newPassword = ""
                confirmNewPassword = ""
            },
            enabled = currentPassword.isNotEmpty() &&
                newPassword.length in 12..128 &&
                confirmNewPassword.isNotEmpty() &&
                !state.isActionRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Text(" Change password")
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Registered devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedButton(
                onClick = accountViewModel::refresh,
                enabled = !accountState.isRefreshing && !accountState.isActionRunning,
            ) {
                Text("Refresh")
            }
        }
        Text(
            "Only device status is shown. Private notification identifiers are never displayed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (accountState.isRefreshing) {
            CircularProgressIndicator()
        } else if (accountState.registeredDevices.isEmpty()) {
            Text("No registered notification devices.")
        } else {
            accountState.registeredDevices.forEachIndexed { index, device ->
                val otherDeviceNumber = accountState.registeredDevices
                    .take(index + 1)
                    .count { registeredDevice -> !registeredDevice.isCurrentDevice }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (device.isCurrentDevice) "This phone" else "Other Android device $otherDeviceNumber",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(if (device.active) "Notifications active" else "Notifications inactive")
                        Text(
                            device.updatedAtMillis?.let { timestamp ->
                                "Last registered ${Instant.ofEpochMilli(timestamp)}"
                            } ?: "Registration time unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { accountViewModel.removeOwnDevice(device.deviceId) },
                            enabled = !accountState.isActionRunning,
                        ) {
                            Text(if (device.isCurrentDevice) "Remove this phone" else "Remove device")
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text(
            "Account deletion",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (!accountState.privacyStateVerified) {
            Text(
                "Checking account-deletion status…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (accountState.deletionRequestPending) {
            Text(
                "Deletion is pending owner review. You can cancel the request while the account remains active.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = accountViewModel::cancelAccountDeletionRequest,
                enabled = !accountState.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel deletion request")
            }
        } else {
            Text(
                "Request permanent account deletion. This does not immediately erase the account; an owner must complete the audited deletion.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = deletionUsername,
                onValueChange = { value -> deletionUsername = value },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Type @${state.account?.usernameNormalized.orEmpty()} to confirm") },
                singleLine = true,
            )
            OutlinedTextField(
                value = deletionPassword,
                onValueChange = { value -> deletionPassword = value },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Current password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedButton(
                onClick = {
                    accountViewModel.requestAccountDeletion(deletionPassword, deletionUsername)
                    deletionPassword = ""
                    deletionUsername = ""
                },
                enabled = deletionPassword.isNotEmpty() &&
                    deletionUsername.trim().removePrefix("@") == state.account?.usernameNormalized &&
                    !accountState.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Request account deletion")
            }
        }

        HorizontalDivider()
        Text(
            text = "Synapse ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = remoteUpdateStatusLabel(appUpdate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onCheckAppUpdate,
            enabled = appUpdate.status !in setOf(AppUpdateStatus.CHECKING, AppUpdateStatus.DOWNLOADING),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (appUpdate.status == AppUpdateStatus.CHECKING) "Checking…" else "Check for app update")
        }
        Text(
            text = "Download and install controls remain under Local AI → Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = viewModel::signOut,
            enabled = !state.isActionRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Text(" Secure logout")
        }
    }
}

@Composable
private fun NotificationPreferenceRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

internal fun remoteUpdateStatusLabel(appUpdate: AppUpdateUiState): String =
    when (appUpdate.status) {
        AppUpdateStatus.IDLE -> "Update status: not checked yet"
        AppUpdateStatus.CHECKING -> "Update status: checking ${BuildConfig.SYNAPSE_APK_CHANNEL}"
        AppUpdateStatus.AVAILABLE ->
            "Update available: ${appUpdate.availableUpdate?.releaseName ?: "new release"}"
        AppUpdateStatus.DOWNLOADING -> "Update status: downloading"
        AppUpdateStatus.READY_TO_INSTALL -> "Update status: ready to install"
        AppUpdateStatus.UP_TO_DATE -> "Update status: current on ${BuildConfig.SYNAPSE_APK_CHANNEL}"
        AppUpdateStatus.FAILED -> "Update status: ${appUpdate.message ?: "check failed"}"
}
