package app.synapse.privatechat.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import app.synapse.privatechat.data.call.media.PrivateCallVideoRenderer
import app.synapse.privatechat.data.call.media.PrivateCallVideoTarget
import app.synapse.privatechat.domain.call.PrivateCallMediaKind

@Composable
fun PrivateCallOverlay(
    state: PrivateCallUiState,
    viewModel: PrivateCallViewModel,
) {
    val context = LocalContext.current
    val permissionRequests = remember { PrivateCallPermissionRequestOwner() }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (permissionRequests.consumeFor(viewModel.uiState.value)) {
                if (granted.isNotEmpty() && granted.values.all { it }) viewModel.startVerifiedDirectCall() else viewModel.permissionDenied()
            }
        }
    when (state) {
        PrivateCallUiState.Idle -> Unit
        is PrivateCallUiState.Preparing ->
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text(state.title) },
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text("Checking call encryption…")
                    }
                },
                confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("Cancel") } },
            )
        is PrivateCallUiState.Consent ->
            PrivateCallConsentDialog(
                state = state,
                onDismiss = viewModel::dismiss,
                onConfirm = {
                    val required =
                        buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (state.request.mediaKind == PrivateCallMediaKind.VIDEO) add(Manifest.permission.CAMERA)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    val missing = required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) {
                        viewModel.startVerifiedDirectCall()
                    } else if (permissionRequests.begin(state.request)) {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                },
            )
        is PrivateCallUiState.Ongoing -> PrivateOngoingCallDialog(state, viewModel)
        is PrivateCallUiState.Stopping ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Ending call") },
                text = { Text("Stopping microphone and camera, then notifying the other device.") },
                confirmButton = {},
            )
        is PrivateCallUiState.Finished ->
            AlertDialog(
                onDismissRequest = viewModel::dismiss,
                title = { Text("Call finished") },
                text = { Text(state.message) },
                confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("Close") } },
            )
    }
}

@Composable
internal fun PrivateCallConsentDialog(
    state: PrivateCallUiState.Consent,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var identityVerified by remember(state.request) { mutableStateOf(false) }
    var directAccepted by remember(state.request) { mutableStateOf(false) }
    val incoming = state.request is PrivateCallConsentRequest.Incoming
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (incoming) {
                    "Incoming ${state.request.mediaKind.callLabel()} call"
                } else {
                    "${state.request.mediaKind.callLabel().replaceFirstChar(
                        Char::titlecase,
                    )} call"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.request.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Direct calls are encrypted, but your network address can be visible to the other person. Some networks will not connect without a relay. Both people must keep Synapse open to receive the initial call.",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = directAccepted,
                        onCheckedChange = { directAccepted = it },
                        modifier = Modifier.semantics { contentDescription = "Accept direct calling" },
                    )
                    Text("I accept direct calling for this call.")
                }
                Text(
                    "Compare these safety numbers with your contact using a trusted channel before continuing. Each number identifies one of their devices.",
                )
                state.safetyNumbers.forEachIndexed { index, safety ->
                    Text("Device ${index + 1}", style = MaterialTheme.typography.labelMedium)
                    Text(safety.groupedDigits, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = identityVerified,
                        onCheckedChange = { identityVerified = it },
                        modifier = Modifier.semantics { contentDescription = "Verify safety numbers" },
                    )
                    Text("I have verified the displayed device safety numbers.")
                }
                Text(
                    "Microphone${if (state.request.mediaKind == PrivateCallMediaKind.VIDEO) " and camera" else ""} " +
                        "access starts only after you continue. A visible call notification provides Hang up.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = directAccepted && identityVerified && state.safetyNumbers.isNotEmpty()) {
                Text(if (incoming) "Answer" else "Start call")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (incoming) "Decline" else "Cancel") } },
    )
}

@Composable
private fun PrivateOngoingCallDialog(
    state: PrivateCallUiState.Ongoing,
    viewModel: PrivateCallViewModel,
) {
    var minimized by remember(state.callId) { mutableStateOf(false) }
    if (minimized) {
        Box(Modifier.fillMaxSize().padding(top = 52.dp, end = 12.dp), contentAlignment = Alignment.TopEnd) {
            Button(onClick = { minimized = false }) { Text("Return to call") }
        }
        return
    }
    Dialog(onDismissRequest = { minimized = true }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    when (state.stage) {
                        PrivateCallStage.CONNECTING -> "Connecting encrypted call…"
                        PrivateCallStage.RINGING -> "Ringing…"
                        PrivateCallStage.CONNECTED -> "Encrypted ${state.mediaKind.callLabel()} call"
                        PrivateCallStage.RECONNECTING -> "Reconnecting…"
                    },
                )
                if (state.mediaKind == PrivateCallMediaKind.VIDEO && state.mediaReady && viewModel.videoRenderer != null) {
                    PrivateCallVideo(viewModel.videoRenderer, PrivateCallVideoTarget.REMOTE_PARTICIPANT, Modifier.weight(1f).fillMaxWidth())
                    if (state.cameraEnabled) {
                        PrivateCallVideo(
                            viewModel.videoRenderer,
                            PrivateCallVideoTarget.LOCAL_PREVIEW,
                            Modifier.height(120.dp).fillMaxWidth(),
                        )
                    }
                } else {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(72.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledTonalIconButton(onClick = viewModel::toggleMicrophone, enabled = state.mediaReady) {
                        Icon(
                            if (state.microphoneMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (state.microphoneMuted) "Unmute microphone" else "Mute microphone",
                        )
                    }
                    FilledTonalIconButton(onClick = viewModel::toggleSpeaker, enabled = state.mediaReady) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (state.speakerEnabled) "Use earpiece" else "Use speaker",
                        )
                    }
                    if (state.mediaKind == PrivateCallMediaKind.VIDEO) {
                        FilledTonalIconButton(onClick = viewModel::toggleCamera, enabled = state.mediaReady) {
                            Icon(
                                if (state.cameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = if (state.cameraEnabled) "Turn camera off" else "Turn camera on",
                            )
                        }
                        FilledTonalIconButton(onClick = viewModel::switchCamera, enabled = state.mediaReady) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera")
                        }
                    }
                }
                Button(
                    onClick = viewModel::hangUp,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null)
                    Text("Hang up", Modifier.padding(start = 8.dp))
                }
                TextButton(onClick = { minimized = true }) { Text("Back to chat") }
            }
        }
    }
}

@Composable
private fun PrivateCallVideo(
    renderer: PrivateCallVideoRenderer,
    target: PrivateCallVideoTarget,
    modifier: Modifier,
) {
    AndroidView(
        factory = { renderer.createRendererView(it, target) },
        modifier = modifier,
        onRelease = renderer::releaseRendererView,
    )
}

private fun PrivateCallMediaKind.callLabel(): String = if (this == PrivateCallMediaKind.VIDEO) "video" else "voice"
