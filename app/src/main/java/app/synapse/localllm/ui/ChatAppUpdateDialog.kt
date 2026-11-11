package app.synapse.localllm.ui

import android.net.Uri
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatAppUpdateDialog(
    appUpdate: AppUpdateUiState,
    onDownload: () -> Unit,
    onInstall: (Uri) -> Unit,
    onLater: () -> Unit,
) {
    val update = appUpdate.availableUpdate ?: return
    val status = appUpdate.status
    if (
        status !in setOf(
            AppUpdateStatus.AVAILABLE,
            AppUpdateStatus.DOWNLOADING,
            AppUpdateStatus.READY_TO_INSTALL,
            AppUpdateStatus.FAILED,
        )
    ) {
        return
    }
    val downloading = status == AppUpdateStatus.DOWNLOADING

    AlertDialog(
        onDismissRequest = { if (!downloading) onLater() },
        title = { Text("New Synapse Chat update") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${update.releaseName} (version ${update.versionCode}) is available. " +
                        "Download the APK, then approve installation in Android.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (status) {
                    AppUpdateStatus.DOWNLOADING -> {
                        if (appUpdate.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { appUpdate.progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("Downloading ${(appUpdate.progressFraction * 100).toInt()}%")
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("Downloading update...")
                        }
                    }

                    AppUpdateStatus.READY_TO_INSTALL ->
                        Text("Download complete. Tap Install to open the Android installer.")

                    AppUpdateStatus.FAILED ->
                        Text(appUpdate.message ?: "Download failed. Please try again.")

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (status) {
                AppUpdateStatus.AVAILABLE ->
                    Button(onClick = onDownload) { Text("Download") }

                AppUpdateStatus.READY_TO_INSTALL ->
                    Button(
                        onClick = { appUpdate.installerUri?.let(onInstall) },
                        enabled = appUpdate.installerUri != null,
                    ) {
                        Text("Install")
                    }

                AppUpdateStatus.FAILED ->
                    Button(onClick = onDownload) { Text("Retry") }

                AppUpdateStatus.DOWNLOADING ->
                    Text("Downloading...", style = MaterialTheme.typography.labelLarge)

                else -> Unit
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = onLater) { Text("Later") }
            }
        },
    )
}
