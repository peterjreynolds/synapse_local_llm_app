package app.synapse.privatechat.ui.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun PrivateDiagnosticsExportButton(exportReport: () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingReport by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val report = pendingReport
            pendingReport = null
            if (uri != null && report != null) {
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val stream =
                                context.contentResolver.openOutputStream(uri, "wt")
                                    ?: throw IOException("Diagnostic destination could not be opened")
                            stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                        }
                        notice = "Error log saved. You can share this file for troubleshooting."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        notice = "Error log could not be saved. Choose another destination and try again."
                    } finally {
                        saving = false
                    }
                }
            }
        }
    Column {
        TextButton(enabled = !saving && pendingReport == null, onClick = {
            pendingReport = exportReport()
            notice = null
            launcher.launch("Synapse-Private-error-log.txt")
        }) {
            Text(if (saving) "Saving error log…" else "Save error log (no messages or passwords)")
        }
        notice?.let { Text(it) }
    }
}
