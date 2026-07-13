package app.synapse.localllm.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.content.ContextCompat
import app.synapse.localllm.BuildConfig

@Composable
internal fun RemoteProfilePane(
    state: RemoteChatUiState,
    viewModel: RemoteChatViewModel,
    appUpdate: AppUpdateUiState,
    onCheckAppUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val accountUid = state.account?.accountUid
    val profile = state.profiles.firstOrNull { candidate -> candidate.profileUid.raw == accountUid?.raw }
    var displayName by rememberSaveable(accountUid?.raw) { mutableStateOf("") }
    var bio by rememberSaveable(accountUid?.raw) { mutableStateOf("") }
    var currentPassword by remember(accountUid?.raw) { mutableStateOf("") }
    var newPassword by remember(accountUid?.raw) { mutableStateOf("") }
    var notificationsGranted by remember {
        mutableStateOf(hasNotificationPermission(context))
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }
    val avatarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            viewModel.uploadAvatar(uri.toString(), mimeType)
        }
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
        Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = if (notificationsGranted) {
                "Android notifications are enabled for private chat. Message text is not placed in push payloads."
            } else {
                "Enable Android notifications to see new-message alerts while Synapse is backgrounded."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            OutlinedButton(onClick = {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) {
                Icon(Icons.Default.Notifications, contentDescription = null)
                Text(" Enable notifications")
            }
        }

        HorizontalDivider()
        Text("Security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
            supportingText = { Text("At least 8 characters") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedButton(
            onClick = {
                viewModel.changePassword(currentPassword, newPassword)
                currentPassword = ""
                newPassword = ""
            },
            enabled = currentPassword.isNotEmpty() && newPassword.length >= 8 && !state.isActionRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Text(" Change password")
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

private fun hasNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
