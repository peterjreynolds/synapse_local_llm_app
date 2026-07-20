package app.synapse.localllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import coil3.compose.AsyncImage

@Composable
internal fun RemoteFullscreenImageViewer(
    images: List<RemoteCachedAttachment>,
    initialPage: Int,
    downloads: Map<String, RemoteAttachmentDownloadUi>,
    onDownload: (RemoteAttachmentId, Boolean) -> Unit,
    onSave: (RemoteCachedAttachment) -> Unit,
    onShare: (RemoteCachedAttachment) -> Unit,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, images.lastIndex.coerceAtLeast(0)),
        pageCount = images::size,
    )
    val currentImage = images.getOrNull(pagerState.currentPage) ?: return
    LaunchedEffect(currentImage.attachmentId) {
        val currentDownload = downloads[
            remoteAttachmentDownloadKey(currentImage.attachmentId, thumbnail = false)
        ]
        if (currentDownload?.localUri == null && currentDownload?.failureReason == null) {
            onDownload(currentImage.attachmentId, false)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF020304))
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close image viewer", tint = Color.White)
                }
                Text(
                    text = currentImage.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${pagerState.currentPage + 1}/${images.size}",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                key = { page -> images[page].attachmentId.raw },
            ) { page ->
                val image = images[page]
                val download = downloads[remoteAttachmentDownloadKey(image.attachmentId, thumbnail = false)]
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        download?.localUri != null -> RemoteZoomableImage(
                            localUri = download.localUri,
                            contentDescription = image.displayName,
                        )

                        download?.failureReason != null -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = download.failureReason,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalIconButton(onClick = { onDownload(image.attachmentId, false) }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry image download")
                            }
                        }

                        else -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { onSave(currentImage) }) {
                    Icon(Icons.Default.Download, contentDescription = "Save image")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Pinch or double-tap to zoom · swipe for more",
                    color = Color.White.copy(alpha = 0.68f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { onShare(currentImage) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share image")
                }
            }
        }
    }
}

@Composable
private fun RemoteZoomableImage(localUri: String, contentDescription: String) {
    var scale by rememberSaveable(localUri) { mutableFloatStateOf(1f) }
    var translationX by rememberSaveable(localUri) { mutableFloatStateOf(0f) }
    var translationY by rememberSaveable(localUri) { mutableFloatStateOf(0f) }
    AsyncImage(
        model = localUri,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.translationX = translationX
                this.translationY = translationY
            }
            .combinedClickable(
                onClick = {},
                onDoubleClick = {
                    scale = if (scale > 1f) 1f else 2.5f
                    if (scale == 1f) {
                        translationX = 0f
                        translationY = 0f
                    }
                },
            )
            .pointerInput(localUri, scale) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointerCount = event.changes.count { change -> change.pressed }
                        if (pressedPointerCount >= 2 || scale > 1f) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val pan = event.calculatePan()
                            scale = nextScale
                            if (nextScale > 1f) {
                                translationX += pan.x
                                translationY += pan.y
                            } else {
                                translationX = 0f
                                translationY = 0f
                            }
                            event.changes.forEach { change -> change.consume() }
                        }
                    } while (event.changes.any { change -> change.pressed })
                }
            },
    )
}
