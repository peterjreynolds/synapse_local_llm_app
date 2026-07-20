package app.synapse.localllm.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteMessageId
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun RemoteSharedContentBrowser(
    state: RemoteChatUiState,
    roomTitle: String,
    selectedCategory: RemoteSharedContentCategory,
    onCategorySelected: (RemoteSharedContentCategory) -> Unit,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onJumpToMessage: (RemoteMessageId) -> Unit,
    onDownloadAttachment: (RemoteSharedContentItem.Attachment, RemoteAttachmentId, Boolean) -> Unit,
    onCancelAttachmentDownload: (RemoteAttachmentId, Boolean) -> Unit,
) {
    val allContent = remember(state.messages, state.profiles, state.account?.accountUid) {
        buildRemoteSharedContent(
            messages = state.messages,
            profiles = state.profiles,
            currentAccountUid = state.account?.accountUid?.raw,
        )
    }
    val visibleContent = remember(allContent, selectedCategory) {
        allContent.filter { item -> item.category == selectedCategory }
    }
    val groupedContent = remember(visibleContent) {
        visibleContent.groupBy { item ->
            item.sentAt.atZone(ZoneId.systemDefault()).toLocalDate()
        }
    }
    val listState = rememberLazyListState()
    val nearLoadedContentEnd by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount == 0 ||
                (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 4
        }
    }
    val canPageOlder = state.messages.any { message -> message.serverCreatedAt != null }
    val shouldFillContentWindow = visibleContent.size < MINIMUM_SHARED_CONTENT_WINDOW
    LaunchedEffect(
        selectedCategory,
        visibleContent.size,
        nearLoadedContentEnd,
        state.hasReachedMessageStart,
        state.isLoadingOlderMessages,
        state.notice,
    ) {
        if (
            canPageOlder &&
            !state.hasReachedMessageStart &&
            !state.isLoadingOlderMessages &&
            state.notice == null &&
            (shouldFillContentWindow || nearLoadedContentEnd)
        ) {
            onLoadOlder()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to messages")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Shared content", fontWeight = FontWeight.SemiBold)
                Text(
                    roomTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedCategory.ordinal,
            edgePadding = 8.dp,
        ) {
            RemoteSharedContentCategory.entries.forEach { category ->
                Tab(
                    selected = selectedCategory == category,
                    onClick = { onCategorySelected(category) },
                    text = { Text(category.label) },
                )
            }
        }
        state.notice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        notice,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(
                        onClick = onLoadOlder,
                        enabled = canPageOlder && !state.hasReachedMessageStart && !state.isLoadingOlderMessages,
                    ) { Text("Retry") }
                }
            }
        }
        if (visibleContent.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.isLoadingOlderMessages) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = "No ${selectedCategory.label.lowercase()} shared yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
        ) {
            groupedContent.forEach { (date, dateItems) ->
                item(key = "date-$date") {
                    Text(
                        remoteSharedContentDateLabel(date),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
                if (
                    selectedCategory == RemoteSharedContentCategory.PHOTOS ||
                    selectedCategory == RemoteSharedContentCategory.VIDEOS
                ) {
                    items(
                        items = dateItems.chunked(2),
                        key = { row -> row.joinToString(separator = ":") { item -> item.key } },
                    ) { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowItems.forEach { item ->
                                RemoteSharedAttachmentItem(
                                    item = item as RemoteSharedContentItem.Attachment,
                                    state = state,
                                    onJumpToMessage = onJumpToMessage,
                                    onDownloadAttachment = onDownloadAttachment,
                                    onCancelAttachmentDownload = onCancelAttachmentDownload,
                                    modifier = Modifier.weight(1f),
                                    squareCell = true,
                                )
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    items(
                        items = dateItems,
                        key = RemoteSharedContentItem::key,
                    ) { item ->
                        when (item) {
                            is RemoteSharedContentItem.Attachment -> RemoteSharedAttachmentItem(
                                item = item,
                                state = state,
                                onJumpToMessage = onJumpToMessage,
                                onDownloadAttachment = onDownloadAttachment,
                                onCancelAttachmentDownload = onCancelAttachmentDownload,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                squareCell = false,
                            )
                            is RemoteSharedContentItem.Link -> RemoteSharedLinkItem(
                                item = item,
                                onJumpToMessage = onJumpToMessage,
                            )
                        }
                    }
                }
            }
            if (state.isLoadingOlderMessages) {
                item(key = "loading-older") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            } else if (state.hasReachedMessageStart) {
                item(key = "content-start") {
                    Text(
                        "Start of conversation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteSharedAttachmentItem(
    item: RemoteSharedContentItem.Attachment,
    state: RemoteChatUiState,
    onJumpToMessage: (RemoteMessageId) -> Unit,
    onDownloadAttachment: (RemoteSharedContentItem.Attachment, RemoteAttachmentId, Boolean) -> Unit,
    onCancelAttachmentDownload: (RemoteAttachmentId, Boolean) -> Unit,
    modifier: Modifier,
    squareCell: Boolean,
) {
    Column(modifier = modifier) {
        RemoteMessageAttachmentCard(
            attachment = item.attachment,
            downloads = state.attachmentDownloads,
            onDownload = { attachmentId, thumbnail ->
                onDownloadAttachment(item, attachmentId, thumbnail)
            },
            onCancelDownload = onCancelAttachmentDownload,
            squareImageCell = squareCell,
        )
        Text(
            "${item.senderDisplayName} · ${REMOTE_SHARED_CONTENT_TIME_FORMATTER.format(item.sentAt.atZone(ZoneId.systemDefault()))}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        TextButton(onClick = { onJumpToMessage(item.messageId) }) {
            Text("View in chat")
        }
    }
}

@Composable
private fun RemoteSharedLinkItem(
    item: RemoteSharedContentItem.Link,
    onJumpToMessage: (RemoteMessageId) -> Unit,
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, item.url.toUri()))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, "No app can open this link.", Toast.LENGTH_LONG).show()
                    }
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Link, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.senderDisplayName} · ${REMOTE_SHARED_CONTENT_TIME_FORMATTER.format(item.sentAt.atZone(ZoneId.systemDefault()))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onJumpToMessage(item.messageId) }) {
                    Text("View in chat")
                }
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open link")
        }
    }
    HorizontalDivider()
}

private fun remoteSharedContentDateLabel(date: LocalDate): String =
    REMOTE_SHARED_CONTENT_DATE_FORMATTER.format(date)

private const val MINIMUM_SHARED_CONTENT_WINDOW = 18
private val REMOTE_SHARED_CONTENT_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val REMOTE_SHARED_CONTENT_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
