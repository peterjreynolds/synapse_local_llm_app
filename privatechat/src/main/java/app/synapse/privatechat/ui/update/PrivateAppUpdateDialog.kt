package app.synapse.privatechat.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.update.PrivateAppInstallerLaunchOutcome
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadReceipt
import java.util.Locale

@Composable
fun PrivateAppUpdateDialog(
    state: PrivateAppUpdateUiState,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onInstall: () -> Unit,
    onInstallerLaunchStarted: (Long) -> Unit,
    onOpenInstaller: (PrivateAppUpdateDownloadReceipt) -> PrivateAppInstallerLaunchOutcome,
    onInstallerLaunchOutcome: (PrivateAppInstallerLaunchOutcome) -> Unit,
) {
    val pendingInstaller =
        (state as? PrivateAppUpdateUiState.ReadyToInstall)
            ?.takeIf(PrivateAppUpdateUiState.ReadyToInstall::installerLaunchPending)
    LaunchedEffect(pendingInstaller?.installerRequestId) {
        val request = pendingInstaller ?: return@LaunchedEffect
        onInstallerLaunchStarted(request.installerRequestId)
        onInstallerLaunchOutcome(onOpenInstaller(request.receipt))
    }

    val update =
        when (state) {
            is PrivateAppUpdateUiState.Available -> state.update
            is PrivateAppUpdateUiState.Downloading -> state.update
            is PrivateAppUpdateUiState.ReadyToInstall -> state.update
            is PrivateAppUpdateUiState.Failed -> state.update
            PrivateAppUpdateUiState.Checking,
            PrivateAppUpdateUiState.Idle,
            -> return
        }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("New version available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Synapse Private ${update.versionName} is available.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                when (state) {
                    is PrivateAppUpdateUiState.Downloading -> {
                        LinearProgressIndicator(
                            progress = {
                                (state.downloadedBytes.toFloat() / update.apkByteCount.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            if (state.verifying) {
                                "Verifying download, package, version, device support, and signer…"
                            } else {
                                "Downloading ${formatUpdateByteProgress(state.downloadedBytes, update.apkByteCount)}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is PrivateAppUpdateUiState.ReadyToInstall ->
                        Text(state.userMessage, style = MaterialTheme.typography.bodyMedium)

                    is PrivateAppUpdateUiState.Failed ->
                        Text(
                            text = state.userMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                    is PrivateAppUpdateUiState.Available,
                    PrivateAppUpdateUiState.Checking,
                    PrivateAppUpdateUiState.Idle,
                    ->
                        Text(
                            "Download the verified update now, or choose Later to keep using this version.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                }
            }
        },
        confirmButton = {
            when (state) {
                is PrivateAppUpdateUiState.Available,
                is PrivateAppUpdateUiState.Failed,
                -> Button(onClick = onDownload) { Text("Download") }

                is PrivateAppUpdateUiState.ReadyToInstall -> Button(onClick = onInstall) { Text("Install") }
                is PrivateAppUpdateUiState.Downloading,
                PrivateAppUpdateUiState.Checking,
                PrivateAppUpdateUiState.Idle,
                -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}

private fun formatUpdateByteProgress(
    downloadedBytes: Long,
    totalBytes: Long,
): String {
    val downloadedMegabytes = downloadedBytes.toDouble() / BYTES_PER_MEGABYTE
    val totalMegabytes = totalBytes.toDouble() / BYTES_PER_MEGABYTE
    return String.format(Locale.US, "%.1f MB / %.1f MB", downloadedMegabytes, totalMegabytes)
}

private const val BYTES_PER_MEGABYTE = 1_048_576.0
