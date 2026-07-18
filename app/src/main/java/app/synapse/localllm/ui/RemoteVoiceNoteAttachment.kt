package app.synapse.localllm.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.synapse.localllm.data.remote.AndroidRemoteAudioWaveformExtractor
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun RemoteVoiceNoteAttachment(
    attachment: RemoteCachedAttachment,
    download: RemoteAttachmentDownloadUi?,
    onDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onCancelDownload: (RemoteAttachmentId, Boolean) -> Unit,
) {
    LaunchedEffect(attachment.attachmentId) {
        if (download?.localUri == null && download?.failureReason == null) {
            onDownload(attachment.attachmentId, false)
        }
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        val localUri = download?.localUri
        if (localUri == null) {
            RemoteVoiceNoteDownloadState(
                attachment = attachment,
                download = download,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
            )
        } else {
            RemoteVoiceNotePlayback(
                localUri = localUri,
                durationMillis = requireNotNull(attachment.durationMillis),
            )
        }
    }
}

@Composable
private fun RemoteVoiceNoteDownloadState(
    attachment: RemoteCachedAttachment,
    download: RemoteAttachmentDownloadUi?,
    onDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onCancelDownload: (RemoteAttachmentId, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Voice message", style = MaterialTheme.typography.labelLarge)
                Text(
                    attachment.durationMillis?.let(::formatAttachmentDuration).orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (download != null && download.failureReason == null) {
                IconButton(onClick = { onCancelDownload(attachment.attachmentId, false) }) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel voice message download")
                }
            } else {
                IconButton(onClick = { onDownload(attachment.attachmentId, false) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry voice message download")
                }
            }
        }
        if (download?.failureReason == null) {
            val progress = download?.let { transfer ->
                remoteVoiceProgressFraction(transfer.transferredBytes, transfer.totalBytes)
            }
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                )
            }
        } else {
            Text(
                download.failureReason,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun RemoteVoiceNotePlayback(
    localUri: String,
    durationMillis: Long,
) {
    val context = LocalContext.current
    val waveformExtractor = remember(context) { AndroidRemoteAudioWaveformExtractor(context) }
    var waveform by remember(localUri) { mutableStateOf<List<Float>>(emptyList()) }
    var waveformReady by remember(localUri) { mutableStateOf(false) }
    var isPlaying by remember(localUri) { mutableStateOf(false) }
    var playbackPositionMillis by remember(localUri) { mutableStateOf(0L) }
    var playbackFailure by remember(localUri) { mutableStateOf<String?>(null) }
    val player = remember(localUri) {
        runCatching { MediaPlayer.create(context, localUri.toUri()) }
            .getOrNull()
    }
    val measuredDurationMillis = player?.duration
        ?.takeIf { duration -> duration > 0 }
        ?.toLong()
        ?: durationMillis

    LaunchedEffect(localUri) {
        waveform = runCatching { waveformExtractor.extract(localUri) }.getOrDefault(emptyList())
        waveformReady = true
    }
    LaunchedEffect(player) {
        if (player == null) playbackFailure = "Android could not play this voice message."
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener {
            playbackPositionMillis = measuredDurationMillis
            isPlaying = false
        }
        player?.setOnErrorListener { _, _, _ ->
            playbackFailure = "Android could not play this voice message."
            isPlaying = false
            true
        }
        onDispose { player?.release() }
    }
    LaunchedEffect(player, isPlaying) {
        while (isActive && isPlaying && player != null) {
            playbackPositionMillis = runCatching { player.currentPosition.toLong() }
                .getOrDefault(playbackPositionMillis)
            delay(PLAYBACK_PROGRESS_INTERVAL_MILLIS)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = {
                val activePlayer = player ?: return@IconButton
                if (activePlayer.isPlaying) {
                    activePlayer.pause()
                    playbackPositionMillis = activePlayer.currentPosition.toLong()
                    isPlaying = false
                } else {
                    if (playbackPositionMillis >= measuredDurationMillis) {
                        activePlayer.seekTo(0)
                        playbackPositionMillis = 0L
                    }
                    activePlayer.start()
                    isPlaying = true
                }
            },
            enabled = player != null && playbackFailure == null,
            modifier = Modifier.size(42.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause voice message" else "Play voice message",
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (waveformReady && waveform.isNotEmpty()) {
                RemoteVoiceWaveform(
                    peaks = waveform,
                    progress = remoteVoiceProgressFraction(playbackPositionMillis, measuredDurationMillis) ?: 0f,
                    onSeek = { progress ->
                        val newPosition = (measuredDurationMillis * progress).roundToInt()
                        player?.seekTo(newPosition)
                        playbackPositionMillis = newPosition.toLong()
                    },
                )
            } else {
                LinearProgressIndicator(
                    progress = {
                        remoteVoiceProgressFraction(playbackPositionMillis, measuredDurationMillis) ?: 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                playbackFailure ?: "${formatAttachmentDuration(playbackPositionMillis)} / " +
                    formatAttachmentDuration(measuredDurationMillis),
                style = MaterialTheme.typography.labelSmall,
                color = if (playbackFailure == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun RemoteVoiceWaveform(
    peaks: List<Float>,
    progress: Float,
    onSeek: (Float) -> Unit,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val remainingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(onSeek) {
                detectTapGestures { position ->
                    if (size.width > 0) onSeek((position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (peaks.size - 1)) / peaks.size).coerceAtLeast(1.dp.toPx())
        peaks.forEachIndexed { index, amplitude ->
            val barProgress = (index + 1f) / peaks.size
            val height = (size.height * amplitude.coerceAtLeast(MINIMUM_VISIBLE_WAVEFORM_PEAK))
                .coerceAtMost(size.height)
            drawRoundRect(
                color = if (barProgress <= progress) playedColor else remainingColor,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = index * (barWidth + gap),
                    y = (size.height - height) / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

internal fun remoteVoiceProgressFraction(
    transferredOrElapsed: Long,
    total: Long,
): Float? = total.takeIf { it > 0L }
    ?.let { (transferredOrElapsed.toFloat() / it.toFloat()).coerceIn(0f, 1f) }

private const val MINIMUM_VISIBLE_WAVEFORM_PEAK = 0.08f
private const val PLAYBACK_PROGRESS_INTERVAL_MILLIS = 100L
