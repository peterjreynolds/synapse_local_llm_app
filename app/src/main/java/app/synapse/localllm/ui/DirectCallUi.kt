package app.synapse.localllm.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.synapse.localllm.domain.remote.RemoteCachedProfile

@Composable
internal fun DirectCallOverlay(
    state: DirectCallUiState,
    peer: RemoteCachedProfile?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onAccept() else onPermissionDenied()
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RemoteProfileAvatar(
                    profile = peer,
                    displayName = peer?.displayName ?: "Synapse contact",
                    size = 96.dp,
                )
                Text(
                    peer?.displayName ?: "Synapse contact",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    directCallStatusLabel(state.phase),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.notice?.let { notice -> Text(notice, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(18.dp))
                when (state.phase) {
                    DirectCallUiPhase.INCOMING_RINGING -> Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledIconButton(
                            onClick = onDecline,
                            enabled = !state.isActionRunning,
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline call")
                        }
                        FilledIconButton(
                            onClick = {
                                if (
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    onAccept()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            enabled = !state.isActionRunning,
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Answer call")
                        }
                    }
                    DirectCallUiPhase.CONNECTING,
                    DirectCallUiPhase.ACTIVE,
                    -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                            FilledIconButton(onClick = onToggleMicrophone) {
                                Icon(
                                    if (state.isMicrophoneMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (state.isMicrophoneMuted) "Unmute" else "Mute",
                                )
                            }
                            FilledIconButton(onClick = onToggleSpeaker) {
                                Icon(
                                    if (state.isSpeakerEnabled) {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeOff
                                    },
                                    contentDescription = if (state.isSpeakerEnabled) "Use earpiece" else "Use speaker",
                                )
                            }
                            FilledIconButton(onClick = onEnd) {
                                Icon(Icons.Default.CallEnd, contentDescription = "End call")
                            }
                        }
                    }
                    DirectCallUiPhase.OUTGOING_RINGING,
                    DirectCallUiPhase.STARTING,
                    DirectCallUiPhase.ENDING,
                    -> Button(onClick = onEnd, enabled = !state.isActionRunning) {
                        Icon(Icons.Default.CallEnd, contentDescription = null)
                        Text(" End call")
                    }
                    DirectCallUiPhase.FAILED -> OutlinedButton(onClick = onDismissFailure) {
                        Text("Close")
                    }
                    DirectCallUiPhase.IDLE -> Unit
                }
            }
        }
    }
}

internal fun directCallStatusLabel(phase: DirectCallUiPhase): String = when (phase) {
    DirectCallUiPhase.IDLE -> ""
    DirectCallUiPhase.STARTING -> "Starting call…"
    DirectCallUiPhase.OUTGOING_RINGING -> "Ringing…"
    DirectCallUiPhase.INCOMING_RINGING -> "Incoming voice call"
    DirectCallUiPhase.CONNECTING -> "Connecting securely…"
    DirectCallUiPhase.ACTIVE -> "Voice call"
    DirectCallUiPhase.ENDING -> "Ending call…"
    DirectCallUiPhase.FAILED -> "Call unavailable"
}
