package app.synapse.localllm.ui

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import coil3.compose.AsyncImage

@Composable
internal fun RemotePendingAttachmentList(
    attachments: List<RemotePendingAttachmentUi>,
    onRetry: (RemoteAttachmentId) -> Unit,
    onCancel: (RemoteAttachmentId) -> Unit,
) {
    if (attachments.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { pending ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Text(pending.selection.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${pending.selection.kind.name.lowercase().replaceFirstChar(Char::uppercase)} · " +
                            formatAttachmentBytes(pending.selection.byteCount),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    when (pending.state) {
                        RemoteAttachmentTransferState.UPLOADING -> {
                            val progress = if (pending.selection.byteCount > 0L) {
                                pending.transferredBytes.toFloat() / pending.selection.byteCount.toFloat()
                            } else {
                                0f
                            }
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        RemoteAttachmentTransferState.READY ->
                            Text("Ready", style = MaterialTheme.typography.labelSmall)

                        RemoteAttachmentTransferState.FAILED -> {
                            Text(
                                pending.failureReason ?: "Upload failed.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            TextButton(onClick = { onRetry(pending.selection.attachmentId) }) { Text("Retry") }
                        }
                    }
                    TextButton(onClick = { onCancel(pending.selection.attachmentId) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
internal fun RemoteMessageAttachmentCard(
    attachment: RemoteCachedAttachment,
    downloads: Map<String, RemoteAttachmentDownloadUi>,
    onDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onCancelDownload: (RemoteAttachmentId, Boolean) -> Unit,
) {
    val thumbnailDownload = downloads[remoteAttachmentDownloadKey(attachment.attachmentId, thumbnail = true)]
    val contentDownload = downloads[remoteAttachmentDownloadKey(attachment.attachmentId, thumbnail = false)]
    var showImage by rememberSaveable(attachment.attachmentId.raw) { mutableStateOf(false) }
    if (attachment.kind == RemoteAttachmentKind.IMAGE) {
        LaunchedEffect(attachment.attachmentId, thumbnailDownload?.failureReason) {
            if (thumbnailDownload?.localUri == null && thumbnailDownload?.failureReason == null) {
                onDownload(attachment.attachmentId, true)
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (attachment.kind == RemoteAttachmentKind.IMAGE && thumbnailDownload?.localUri != null) {
                AsyncImage(
                    model = thumbnailDownload.localUri,
                    contentDescription = attachment.displayName,
                    modifier = Modifier.fillMaxWidth().height(180.dp).clickable {
                        if (contentDownload?.localUri == null) {
                            onDownload(attachment.attachmentId, false)
                        } else {
                            showImage = true
                        }
                    },
                    contentScale = ContentScale.Crop,
                )
            }
            Text(attachment.displayName, fontWeight = FontWeight.SemiBold)
            Text(
                "${attachment.mimeType} · ${formatAttachmentBytes(attachment.byteCount)}" +
                    (attachment.durationMillis?.let { duration -> " · ${formatAttachmentDuration(duration)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
            )
            val download = contentDownload
            if (download?.localUri == null) {
                if (download == null || download.failureReason != null) {
                    TextButton(onClick = { onDownload(attachment.attachmentId, false) }) {
                        Text(if (download == null) "Download" else "Retry download")
                    }
                } else {
                    TextButton(onClick = { onCancelDownload(attachment.attachmentId, false) }) {
                        Text("Cancel download")
                    }
                }
                download?.failureReason?.let { failure ->
                    Text(failure, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                if (download != null && download.totalBytes > 0L && download.transferredBytes < download.totalBytes) {
                    LinearProgressIndicator(
                        progress = {
                            (download.transferredBytes.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (
                attachment.kind == RemoteAttachmentKind.AUDIO ||
                attachment.kind == RemoteAttachmentKind.VOICE_NOTE
            ) {
                RemoteAudioPlaybackButton(download.localUri)
            } else if (attachment.kind == RemoteAttachmentKind.IMAGE) {
                TextButton(onClick = { showImage = true }) { Text("View full image") }
            } else {
                Text("Downloaded to the private app cache.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (showImage && contentDownload?.localUri != null) {
        AlertDialog(
            onDismissRequest = { showImage = false },
            title = { Text(attachment.displayName) },
            text = {
                AsyncImage(
                    model = contentDownload.localUri,
                    contentDescription = attachment.displayName,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit,
                )
            },
            confirmButton = { TextButton(onClick = { showImage = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun RemoteAudioPlaybackButton(localUri: String) {
    val context = LocalContext.current
    var isPlaying by rememberSaveable(localUri) { mutableStateOf(false) }
    val player = remember(localUri) {
        MediaPlayer.create(context, localUri.toUri())?.apply {
            setOnCompletionListener { isPlaying = false }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    TextButton(
        onClick = {
            if (player?.isPlaying == true) {
                player.pause()
                isPlaying = false
            } else {
                player?.start()
                isPlaying = player?.isPlaying == true
            }
        },
        enabled = player != null,
    ) {
        Text(if (isPlaying) "Pause" else "Play")
    }
}

private fun formatAttachmentBytes(byteCount: Long): String =
    when {
        byteCount >= 1024L * 1024L -> "%.1f MB".format(byteCount.toDouble() / (1024L * 1024L))
        byteCount >= 1024L -> "%.1f KB".format(byteCount.toDouble() / 1024L)
        else -> "$byteCount B"
    }

private fun formatAttachmentDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

internal val REMOTE_ATTACHMENT_MIME_TYPES = arrayOf(
    "application/msword",
    "application/pdf",
    "application/rtf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "audio/mp4",
    "audio/mpeg",
    "audio/ogg",
    "audio/wav",
    "audio/x-wav",
    "image/jpeg",
    "image/png",
    "image/webp",
    "text/csv",
    "text/markdown",
    "text/plain",
)
