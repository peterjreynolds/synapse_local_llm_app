package app.synapse.localllm.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import coil3.compose.AsyncImage
import java.io.File

@Composable
internal fun RemotePendingAttachmentList(
    attachments: List<RemotePendingAttachmentUi>,
    onRetry: (RemoteAttachmentId) -> Unit,
    onCancel: (RemoteAttachmentId) -> Unit,
) {
    if (attachments.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(attachments, key = { pending -> pending.selection.attachmentId.raw }) { pending ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(min = 190.dp, max = 290.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (pending.selection.kind) {
                            RemoteAttachmentKind.IMAGE -> Icons.Default.Image
                            RemoteAttachmentKind.DOCUMENT -> Icons.Default.Description
                            RemoteAttachmentKind.AUDIO -> Icons.Default.AudioFile
                            RemoteAttachmentKind.VOICE_NOTE -> Icons.Default.Mic
                        },
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            pending.selection.displayName,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            pendingAttachmentStatus(pending),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pending.state == RemoteAttachmentTransferState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (pending.state == RemoteAttachmentTransferState.UPLOADING) {
                            val progress = pending.transferredBytes.toFloat() /
                                pending.selection.byteCount.coerceAtLeast(1L).toFloat()
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (pending.state == RemoteAttachmentTransferState.FAILED) {
                        IconButton(onClick = { onRetry(pending.selection.attachmentId) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry attachment")
                        }
                    }
                    IconButton(onClick = { onCancel(pending.selection.attachmentId) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove attachment")
                    }
                }
            }
        }
    }
}

private fun pendingAttachmentStatus(pending: RemotePendingAttachmentUi): String =
    when (pending.state) {
        RemoteAttachmentTransferState.UPLOADING -> "Uploading · ${formatAttachmentBytes(pending.selection.byteCount)}"
        RemoteAttachmentTransferState.READY -> {
            val duration = pending.selection.durationMillis?.let(::formatAttachmentDuration)
            listOfNotNull(duration, formatAttachmentBytes(pending.selection.byteCount), "Ready to send").joinToString(" · ")
        }
        RemoteAttachmentTransferState.FAILED -> pending.failureReason ?: "Upload failed."
    }

@Composable
internal fun RemoteMessageAttachmentCard(
    attachment: RemoteCachedAttachment,
    downloads: Map<String, RemoteAttachmentDownloadUi>,
    onDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onCancelDownload: (RemoteAttachmentId, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val thumbnailDownload = downloads[remoteAttachmentDownloadKey(attachment.attachmentId, thumbnail = true)]
    val contentDownload = downloads[remoteAttachmentDownloadKey(attachment.attachmentId, thumbnail = false)]
    var showImage by rememberSaveable(attachment.attachmentId.raw) { mutableStateOf(false) }
    var openImageAfterDownload by rememberSaveable(attachment.attachmentId.raw) { mutableStateOf(false) }
    if (attachment.kind == RemoteAttachmentKind.IMAGE) {
        LaunchedEffect(attachment.attachmentId, thumbnailDownload?.failureReason) {
            if (thumbnailDownload?.localUri == null && thumbnailDownload?.failureReason == null) {
                onDownload(attachment.attachmentId, true)
            }
        }
        LaunchedEffect(contentDownload?.localUri, openImageAfterDownload) {
            if (openImageAfterDownload && contentDownload?.localUri != null) {
                openImageAfterDownload = false
                showImage = true
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .then(
                if (attachment.kind == RemoteAttachmentKind.IMAGE) {
                    Modifier
                } else {
                    Modifier.clickable {
                        val localUri = contentDownload?.localUri
                        if (localUri == null) {
                            onDownload(attachment.attachmentId, false)
                        } else {
                            openDownloadedAttachment(context, localUri, attachment.mimeType)
                        }
                    }
                },
            ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (attachment.kind == RemoteAttachmentKind.IMAGE && thumbnailDownload?.localUri != null) {
                AsyncImage(
                    model = thumbnailDownload.localUri,
                    contentDescription = attachment.displayName,
                    modifier = Modifier.fillMaxWidth().height(180.dp).clickable {
                        if (contentDownload?.localUri == null) {
                            openImageAfterDownload = true
                            onDownload(attachment.attachmentId, false)
                        } else {
                            showImage = true
                        }
                    },
                    contentScale = ContentScale.Crop,
                )
            } else if (attachment.kind == RemoteAttachmentKind.IMAGE) {
                Text(
                    text = if (thumbnailDownload?.failureReason == null) {
                        "Loading preview…"
                    } else {
                        "Tap to retry image preview"
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDownload(attachment.attachmentId, true) }
                        .padding(vertical = 28.dp),
                    color = if (thumbnailDownload?.failureReason == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
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
                if (download != null && download.failureReason == null) {
                    TextButton(onClick = { onCancelDownload(attachment.attachmentId, false) }) {
                        Text("Cancel download")
                    }
                } else {
                    Text(
                        text = if (download == null) "Tap to download" else "Tap to retry download",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onDownload(attachment.attachmentId, false) },
                    )
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
                Text("Downloaded · tap to open", style = MaterialTheme.typography.labelSmall)
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

private fun openDownloadedAttachment(
    context: android.content.Context,
    localUri: String,
    mimeType: String,
) {
    val sourceUri = localUri.toUri()
    val shareableUri = when (sourceUri.scheme) {
        ContentResolverScheme.FILE -> FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(requireNotNull(sourceUri.path)),
        )
        ContentResolverScheme.CONTENT -> sourceUri
        else -> return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(shareableUri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open this file type.", Toast.LENGTH_LONG).show()
    }
}

private object ContentResolverScheme {
    const val CONTENT = "content"
    const val FILE = "file"
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

internal val REMOTE_PHOTO_AND_GIF_MIME_TYPES = arrayOf(
    "image/gif",
    "image/jpeg",
    "image/png",
    "image/webp",
)

internal val REMOTE_FILE_AND_AUDIO_MIME_TYPES = arrayOf(
    "application/msword",
    "application/pdf",
    "application/rtf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "audio/mp4",
    "audio/mpeg",
    "audio/ogg",
    "audio/wav",
    "audio/x-wav",
    "text/csv",
    "text/markdown",
    "text/plain",
)
