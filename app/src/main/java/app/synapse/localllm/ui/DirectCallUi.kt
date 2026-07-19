package app.synapse.localllm.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.synapse.localllm.data.calling.DirectCallVideoRendererController
import app.synapse.localllm.data.calling.DirectCallVideoRendererTarget
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind

internal enum class DirectCallVideoStageMode {
    HIDDEN,
    OUTGOING_LOCAL_PREVIEW,
    ACCEPTED_CALL_MEDIA,
}

@Composable
internal fun DirectCallOverlay(
    state: DirectCallUiState,
    peer: RemoteCachedProfile?,
    videoRendererController: DirectCallVideoRendererController,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onPermissionDenied: (RemoteDirectCallMediaKind) -> Unit,
    onDismissFailure: () -> Unit,
) {
    val context = LocalContext.current
    val mediaKind = state.session?.mediaKind ?: RemoteDirectCallMediaKind.AUDIO
    val requiredPermissions = if (mediaKind == RemoteDirectCallMediaKind.VIDEO) {
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    } else {
        listOf(Manifest.permission.RECORD_AUDIO)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (requiredPermissions.all { permission -> grants[permission] == true }) {
            onAccept()
        } else {
            onPermissionDenied(mediaKind)
        }
    }
    val videoStageMode = directCallVideoStageMode(state)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (mediaKind == RemoteDirectCallMediaKind.VIDEO) Color.Black else MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            if (videoStageMode != DirectCallVideoStageMode.HIDDEN) {
                DirectCallVideoStage(
                    videoRendererController = videoRendererController,
                    mode = videoStageMode,
                    showConnectedLocalPreview = state.isCameraEnabled,
                )
            } else {
                DirectCallIdentity(
                    state = state,
                    peer = peer,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                )
            }

            if (videoStageMode != DirectCallVideoStageMode.HIDDEN) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Text(
                        peer?.displayName ?: "Synapse contact",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        directCallStatusLabel(state.phase, mediaKind),
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.notice?.let { notice ->
                        Text(notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            DirectCallControls(
                state = state,
                mediaKind = mediaKind,
                onAccept = {
                    if (
                        requiredPermissions.all { permission ->
                            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
                        }
                    ) {
                        onAccept()
                    } else {
                        permissionLauncher.launch(requiredPermissions.toTypedArray())
                    }
                },
                onDecline = onDecline,
                onEnd = onEnd,
                onToggleCamera = onToggleCamera,
                onSwitchCamera = onSwitchCamera,
                onToggleMicrophone = onToggleMicrophone,
                onToggleSpeaker = onToggleSpeaker,
                onDismissFailure = onDismissFailure,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun DirectCallIdentity(
    state: DirectCallUiState,
    peer: RemoteCachedProfile?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RemoteProfileAvatar(
            profile = peer,
            displayName = peer?.displayName ?: "Synapse contact",
            size = 88.dp,
        )
        Text(
            peer?.displayName ?: "Synapse contact",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (state.session?.mediaKind == RemoteDirectCallMediaKind.VIDEO) Color.White else Color.Unspecified,
        )
        Text(
            directCallStatusLabel(state.phase, state.session?.mediaKind ?: RemoteDirectCallMediaKind.AUDIO),
            color = if (state.session?.mediaKind == RemoteDirectCallMediaKind.VIDEO) {
                Color.White.copy(alpha = 0.75f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        state.notice?.let { notice -> Text(notice, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun DirectCallVideoStage(
    videoRendererController: DirectCallVideoRendererController,
    mode: DirectCallVideoStageMode,
    showConnectedLocalPreview: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (mode == DirectCallVideoStageMode.ACCEPTED_CALL_MEDIA) {
            AndroidView(
                factory = { context ->
                    videoRendererController.createRendererView(
                        context,
                        DirectCallVideoRendererTarget.REMOTE_PARTICIPANT,
                    )
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = videoRendererController::releaseRendererView,
            )
        }
        val showLocalPreview = mode == DirectCallVideoStageMode.OUTGOING_LOCAL_PREVIEW ||
            showConnectedLocalPreview
        if (showLocalPreview) {
            val localPreviewModifier = if (mode == DirectCallVideoStageMode.OUTGOING_LOCAL_PREVIEW) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 60.dp, end = 14.dp)
                    .width(108.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            }
            AndroidView(
                factory = { context ->
                    videoRendererController.createRendererView(
                        context,
                        DirectCallVideoRendererTarget.LOCAL_PREVIEW,
                    )
                },
                modifier = localPreviewModifier,
                onRelease = videoRendererController::releaseRendererView,
            )
        }
    }
}

@Composable
private fun DirectCallControls(
    state: DirectCallUiState,
    mediaKind: RemoteDirectCallMediaKind,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismissFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (state.phase) {
            DirectCallUiPhase.INCOMING_RINGING -> Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(onClick = onDecline, enabled = !state.isActionRunning) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Decline call")
                }
                FilledIconButton(onClick = onAccept, enabled = !state.isActionRunning) {
                    Icon(
                        if (mediaKind == RemoteDirectCallMediaKind.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                        contentDescription = "Answer call",
                    )
                }
            }
            DirectCallUiPhase.CONNECTING,
            DirectCallUiPhase.ACTIVE,
            -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FilledIconButton(onClick = onToggleMicrophone) {
                    Icon(
                        if (state.isMicrophoneMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (state.isMicrophoneMuted) "Unmute" else "Mute",
                    )
                }
                if (mediaKind == RemoteDirectCallMediaKind.VIDEO) {
                    FilledIconButton(onClick = onToggleCamera) {
                        Icon(
                            if (state.isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = if (state.isCameraEnabled) "Turn camera off" else "Turn camera on",
                        )
                    }
                    FilledIconButton(onClick = onSwitchCamera, enabled = state.isCameraEnabled) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera")
                    }
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
            DirectCallUiPhase.OUTGOING_RINGING,
            DirectCallUiPhase.STARTING,
            DirectCallUiPhase.ENDING,
            -> Button(onClick = onEnd, enabled = !state.isActionRunning) {
                Icon(Icons.Default.CallEnd, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("End call")
            }
            DirectCallUiPhase.FAILED -> OutlinedButton(onClick = onDismissFailure) {
                Text("Close")
            }
            DirectCallUiPhase.IDLE -> Spacer(Modifier.height(1.dp))
        }
    }
}

internal fun directCallStatusLabel(
    phase: DirectCallUiPhase,
    mediaKind: RemoteDirectCallMediaKind = RemoteDirectCallMediaKind.AUDIO,
): String = when (phase) {
    DirectCallUiPhase.IDLE -> ""
    DirectCallUiPhase.STARTING -> "Starting call…"
    DirectCallUiPhase.OUTGOING_RINGING -> "Ringing…"
    DirectCallUiPhase.INCOMING_RINGING -> if (mediaKind == RemoteDirectCallMediaKind.VIDEO) {
        "Incoming video call"
    } else {
        "Incoming voice call"
    }
    DirectCallUiPhase.CONNECTING -> "Connecting securely…"
    DirectCallUiPhase.ACTIVE -> if (mediaKind == RemoteDirectCallMediaKind.VIDEO) "Video call" else "Voice call"
    DirectCallUiPhase.ENDING -> "Ending call…"
    DirectCallUiPhase.FAILED -> "Call unavailable"
}

internal fun directCallVideoStageMode(state: DirectCallUiState): DirectCallVideoStageMode {
    if (state.session?.mediaKind != RemoteDirectCallMediaKind.VIDEO) return DirectCallVideoStageMode.HIDDEN
    return when (state.phase) {
        DirectCallUiPhase.OUTGOING_RINGING -> DirectCallVideoStageMode.OUTGOING_LOCAL_PREVIEW
        DirectCallUiPhase.CONNECTING,
        DirectCallUiPhase.ACTIVE,
        -> DirectCallVideoStageMode.ACCEPTED_CALL_MEDIA
        DirectCallUiPhase.IDLE,
        DirectCallUiPhase.STARTING,
        DirectCallUiPhase.INCOMING_RINGING,
        DirectCallUiPhase.ENDING,
        DirectCallUiPhase.FAILED,
        -> DirectCallVideoStageMode.HIDDEN
    }
}
