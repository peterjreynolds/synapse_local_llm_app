package app.synapse.localllm.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.data.remote.AndroidDownloadedImageExporter
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch

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
                    if (pending.selection.kind == RemoteAttachmentKind.IMAGE) {
                        Box(modifier = Modifier.size(64.dp)) {
                            AsyncImage(
                                model = pending.selection.sourceUri,
                                contentDescription = pending.selection.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize(),
                            )
                            if (pending.state == RemoteAttachmentTransferState.UPLOADING) {
                                Surface(
                                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f),
                                    modifier = Modifier.matchParentSize(),
                                ) {
                                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                        CircularProgressIndicator(
                                            progress = {
                                                (pending.transferredBytes.toFloat() /
                                                    pending.selection.byteCount.coerceAtLeast(1L).toFloat())
                                                    .coerceIn(0f, 1f)
                                            },
                                            modifier = Modifier.size(30.dp),
                                            strokeWidth = 3.dp,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Icon(
                            imageVector = when (pending.selection.kind) {
                                RemoteAttachmentKind.IMAGE -> Icons.Default.Image
                                RemoteAttachmentKind.VIDEO -> Icons.Default.Movie
                                RemoteAttachmentKind.DOCUMENT -> Icons.Default.Description
                                RemoteAttachmentKind.AUDIO -> Icons.Default.AudioFile
                                RemoteAttachmentKind.VOICE_NOTE -> Icons.Default.Mic
                            },
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            remotePendingAttachmentTitle(pending),
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
        RemoteAttachmentTransferState.UPLOADING -> if (pending.selection.kind == RemoteAttachmentKind.VOICE_NOTE) {
            "Uploading voice message"
        } else {
            "Uploading · ${formatAttachmentBytes(pending.selection.byteCount)}"
        }
        RemoteAttachmentTransferState.READY -> {
            val duration = pending.selection.durationMillis?.let(::formatAttachmentDuration)
            if (pending.selection.kind == RemoteAttachmentKind.VOICE_NOTE) {
                listOfNotNull(duration, "Ready to send").joinToString(" · ")
            } else {
                listOfNotNull(duration, formatAttachmentBytes(pending.selection.byteCount), "Ready to send")
                    .joinToString(" · ")
            }
        }
        RemoteAttachmentTransferState.FAILED -> pending.failureReason ?: "Upload failed."
    }

internal fun remotePendingAttachmentTitle(pending: RemotePendingAttachmentUi): String =
    if (pending.selection.kind == RemoteAttachmentKind.VOICE_NOTE) "Voice message" else pending.selection.displayName

@Composable
internal fun RemoteMessageAttachmentGallery(
    attachments: List<RemoteCachedAttachment>,
    downloads: Map<String, RemoteAttachmentDownloadUi>,
    onDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onCancelDownload: (RemoteAttachmentId, Boolean) -> Unit,
) {
    if (attachments.isEmpty()) return
    val images = attachments.filter { attachment -> attachment.kind == RemoteAttachmentKind.IMAGE }
    if (images.size == 1) {
        RemoteMessageAttachmentCard(
            attachment = images.single(),
            downloads = downloads,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            imageGallery = images,
            imageGalleryIndex = 0,
        )
    } else {
        images.chunked(2).forEach { rowImages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowImages.forEach { image ->
                    RemoteMessageAttachmentCard(
                        attachment = image,
                        downloads = downloads,
                        onDownload = onDownload,
                        onCancelDownload = onCancelDownload,
                        imageGallery = images,
                        imageGalleryIndex = images.indexOf(image),
                        squareImageCell = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowImages.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
    attachments.filterNot { attachment -> attachment.kind == RemoteAttachmentKind.IMAGE }.forEach { attachment ->
        RemoteMessageAttachmentCard(
            attachment = attachment,
            downloads = downloads,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
        )
    }
}

@Composable
internal fun RemoteMessageAttachmentCard(
    attachment: RemoteCachedAttachment,
    downloads: Map<String, RemoteAttachmentDownloadUi>,
    onDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onCancelDownload: (RemoteAttachmentId, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    imageGallery: List<RemoteCachedAttachment> = listOf(attachment),
    imageGalleryIndex: Int = 0,
    squareImageCell: Boolean = false,
) {
    if (attachment.kind == RemoteAttachmentKind.VOICE_NOTE) {
        RemoteVoiceNoteAttachment(
            attachment = attachment,
            download = downloads[remoteAttachmentDownloadKey(attachment.attachmentId, thumbnail = false)],
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
        )
        return
    }
    val context = LocalContext.current
    val thumbnailDownload = downloads[remoteAttachmentDownloadKey(attachment.attachmentId, thumbnail = true)]
    val contentDownload = downloads[remoteAttachmentDownloadKey(attachment.attachmentId, thumbnail = false)]
    val imageExporter = remember(context) { AndroidDownloadedImageExporter(context) }
    val coroutineScope = rememberCoroutineScope()
    var showImage by rememberSaveable(attachment.attachmentId.raw) { mutableStateOf(false) }
    var imageAspectRatio by remember(attachment.attachmentId.raw) { mutableFloatStateOf(4f / 3f) }
    var openImageAfterDownload by rememberSaveable(attachment.attachmentId.raw) { mutableStateOf(false) }
    var saveImageAfterDownloadId by rememberSaveable(attachment.attachmentId.raw) { mutableStateOf<String?>(null) }
    var shareImageAfterDownloadId by rememberSaveable(attachment.attachmentId.raw) { mutableStateOf<String?>(null) }
    var isSavingImage by rememberSaveable(attachment.attachmentId.raw) { mutableStateOf(false) }
    var pendingLegacyImageSaveUri by rememberSaveable(attachment.attachmentId.raw) {
        mutableStateOf<String?>(null)
    }
    var pendingLegacyImageSaveAttachmentId by rememberSaveable(attachment.attachmentId.raw) {
        mutableStateOf<String?>(null)
    }

    fun saveImageToPictures(targetAttachment: RemoteCachedAttachment, localUri: String) {
        if (isSavingImage) return
        coroutineScope.launch {
            isSavingImage = true
            val notice = runCatching {
                imageExporter.exportToPictures(
                    localUri = localUri,
                    displayName = targetAttachment.displayName,
                    mimeType = targetAttachment.mimeType,
                )
            }.fold(
                onSuccess = { receipt -> "Saved ${receipt.displayName} to Pictures/Synapse." },
                onFailure = { failure -> failure.message ?: "Android could not save the image." },
            )
            isSavingImage = false
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
        }
    }

    val legacyImageExportPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val localUri = pendingLegacyImageSaveUri
        val targetAttachment = imageGallery.firstOrNull { image ->
            image.attachmentId.raw == pendingLegacyImageSaveAttachmentId
        }
        pendingLegacyImageSaveUri = null
        pendingLegacyImageSaveAttachmentId = null
        if (granted && localUri != null && targetAttachment != null) {
            saveImageToPictures(targetAttachment, localUri)
        } else if (!granted) {
            Toast.makeText(
                context,
                "Android 9 storage permission is required to save images.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun saveImageWithPermission(targetAttachment: RemoteCachedAttachment, localUri: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            saveImageToPictures(targetAttachment, localUri)
        } else {
            pendingLegacyImageSaveUri = localUri
            pendingLegacyImageSaveAttachmentId = targetAttachment.attachmentId.raw
            legacyImageExportPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun requestImageSave(targetAttachment: RemoteCachedAttachment = attachment) {
        val localUri = downloads[
            remoteAttachmentDownloadKey(targetAttachment.attachmentId, thumbnail = false)
        ]?.localUri
        if (localUri == null) {
            saveImageAfterDownloadId = targetAttachment.attachmentId.raw
            onDownload(targetAttachment.attachmentId, false)
        } else {
            saveImageWithPermission(targetAttachment, localUri)
        }
    }

    fun requestImageShare(targetAttachment: RemoteCachedAttachment) {
        val localUri = downloads[
            remoteAttachmentDownloadKey(targetAttachment.attachmentId, thumbnail = false)
        ]?.localUri
        if (localUri == null) {
            shareImageAfterDownloadId = targetAttachment.attachmentId.raw
            onDownload(targetAttachment.attachmentId, false)
        } else {
            shareDownloadedAttachment(context, localUri, targetAttachment.mimeType)
        }
    }

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
        LaunchedEffect(downloads, saveImageAfterDownloadId) {
            val targetAttachment = imageGallery.firstOrNull { image ->
                image.attachmentId.raw == saveImageAfterDownloadId
            }
            val localUri = targetAttachment?.let { image ->
                downloads[remoteAttachmentDownloadKey(image.attachmentId, thumbnail = false)]?.localUri
            }
            if (targetAttachment != null && localUri != null) {
                saveImageAfterDownloadId = null
                saveImageWithPermission(targetAttachment, localUri)
            }
        }
        LaunchedEffect(downloads, shareImageAfterDownloadId) {
            val targetAttachment = imageGallery.firstOrNull { image ->
                image.attachmentId.raw == shareImageAfterDownloadId
            }
            val localUri = targetAttachment?.let { image ->
                downloads[remoteAttachmentDownloadKey(image.attachmentId, thumbnail = false)]?.localUri
            }
            if (targetAttachment != null && localUri != null) {
                shareImageAfterDownloadId = null
                shareDownloadedAttachment(context, localUri, targetAttachment.mimeType)
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        modifier = modifier
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
        Column(modifier = Modifier.padding(if (attachment.kind == RemoteAttachmentKind.IMAGE) 0.dp else 8.dp)) {
            if (attachment.kind == RemoteAttachmentKind.IMAGE && thumbnailDownload?.localUri != null) {
                Box {
                    AsyncImage(
                        model = thumbnailDownload.localUri,
                        contentDescription = attachment.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (squareImageCell) {
                                    Modifier.aspectRatio(1f)
                                } else {
                                    Modifier
                                        .aspectRatio(imageAspectRatio.coerceIn(0.55f, 2.2f))
                                        .heightIn(max = 420.dp)
                                },
                            )
                            .combinedClickable(
                                onClick = {
                                    if (contentDownload?.localUri == null) {
                                        openImageAfterDownload = true
                                        onDownload(attachment.attachmentId, false)
                                    } else {
                                        showImage = true
                                    }
                                },
                                onLongClickLabel = "Save image to Pictures",
                                onLongClick = { requestImageSave() },
                            ),
                        contentScale = if (squareImageCell) ContentScale.Crop else ContentScale.Fit,
                        onSuccess = { success ->
                            val intrinsicSize = success.painter.intrinsicSize
                            if (
                                intrinsicSize.width.isFinite() &&
                                intrinsicSize.height.isFinite() &&
                                intrinsicSize.height > 0f
                            ) {
                                imageAspectRatio = intrinsicSize.width / intrinsicSize.height
                            }
                        },
                    )
                    if (
                        contentDownload != null &&
                        contentDownload.localUri == null &&
                        contentDownload.failureReason == null
                    ) {
                        LinearProgressIndicator(
                            progress = {
                                if (contentDownload.totalBytes <= 0L) {
                                    0f
                                } else {
                                    (contentDownload.transferredBytes.toFloat() / contentDownload.totalBytes.toFloat())
                                        .coerceIn(0f, 1f)
                                }
                            },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth(),
                        )
                    }
                }
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
                        .padding(horizontal = 10.dp, vertical = 32.dp),
                    color = if (thumbnailDownload?.failureReason == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            val download = contentDownload
            if (attachment.kind == RemoteAttachmentKind.IMAGE) {
                download?.failureReason?.let { failure ->
                    Text(
                        text = failure,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (isSavingImage) {
                    Text(
                        "Saving to Pictures…",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            } else {
                Text(attachment.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    "${attachment.mimeType} · ${formatAttachmentBytes(attachment.byteCount)}" +
                        (attachment.durationMillis?.let { duration ->
                            " · ${formatAttachmentDuration(duration)}"
                        } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (attachment.kind != RemoteAttachmentKind.IMAGE) {
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
                        Text(
                            failure,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (download != null && download.totalBytes > 0L && download.transferredBytes < download.totalBytes) {
                        LinearProgressIndicator(
                            progress = {
                                (download.transferredBytes.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (attachment.kind == RemoteAttachmentKind.AUDIO) {
                    RemoteAudioPlaybackButton(download.localUri)
                } else {
                    Text("Downloaded · tap to open", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (showImage) {
        RemoteFullscreenImageViewer(
            images = imageGallery,
            initialPage = imageGalleryIndex,
            downloads = downloads,
            onDownload = onDownload,
            onSave = ::requestImageSave,
            onShare = ::requestImageShare,
            onDismiss = { showImage = false },
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

private fun shareDownloadedAttachment(
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
    val shareIntent = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, shareableUri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(shareIntent, "Share image"))
    }.onFailure {
        Toast.makeText(context, "No app on this phone can share this image.", Toast.LENGTH_LONG).show()
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

internal fun formatAttachmentDuration(durationMillis: Long): String {
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

internal val REMOTE_VIDEO_MIME_TYPES = arrayOf(
    "video/mp4",
    "video/quicktime",
    "video/webm",
)
