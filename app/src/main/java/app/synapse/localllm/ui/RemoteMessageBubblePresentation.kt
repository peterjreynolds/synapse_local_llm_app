package app.synapse.localllm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import coil3.compose.AsyncImage

internal fun remoteMessageBubbleShape(
    position: RemoteMessageGroupPosition,
    isCurrentAccount: Boolean,
): Shape {
    val outer = 18.dp
    val joined = 5.dp
    if (position == RemoteMessageGroupPosition.SINGLE) return RoundedCornerShape(outer)
    return if (isCurrentAccount) {
        RoundedCornerShape(
            topStart = outer,
            topEnd = if (position == RemoteMessageGroupPosition.START) outer else joined,
            bottomEnd = if (position == RemoteMessageGroupPosition.END) outer else joined,
            bottomStart = outer,
        )
    } else {
        RoundedCornerShape(
            topStart = if (position == RemoteMessageGroupPosition.START) outer else joined,
            topEnd = outer,
            bottomEnd = outer,
            bottomStart = if (position == RemoteMessageGroupPosition.END) outer else joined,
        )
    }
}

internal fun remoteParticipantNameColor(roomId: String, senderUid: String): Color =
    REMOTE_PARTICIPANT_NAME_COLORS[
        remoteParticipantColorIndex(roomId, senderUid, REMOTE_PARTICIPANT_NAME_COLORS.size)
    ]

internal fun remoteMessageSenderDisplayName(
    message: RemoteCachedMessage,
    currentAccountUid: String?,
    profiles: List<RemoteCachedProfile>,
): String = when {
    message.senderUid.raw == currentAccountUid -> "You"
    message.authorKind == "SYNAPSE_AI" -> "Synapse"
    message.authorKind == "REMOTE_AI" -> remoteAiSenderDisplayName(message)
    else -> profiles.firstOrNull { profile -> profile.profileUid == message.senderUid }?.displayName ?: "Participant"
}

@Composable
internal fun RemoteMessageDateDivider(label: String, contentColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = contentColor.copy(alpha = 0.18f))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.76f),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = contentColor.copy(alpha = 0.18f))
    }
}

@Composable
internal fun RemoteUnreadMessageDivider() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text(
            text = "New messages",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun RemoteMessageReplyPreview(
    repliedMessage: RemoteCachedMessage?,
    senderDisplayName: String?,
    downloads: Map<String, RemoteAttachmentDownloadUi>,
    onDownloadThumbnail: (RemoteCachedMessage, RemoteAttachmentId) -> Unit,
    onClick: () -> Unit,
) {
    val attachment = repliedMessage?.attachments?.firstOrNull()
    val imageAttachment = attachment?.takeIf { candidate -> candidate.kind == RemoteAttachmentKind.IMAGE }
    val thumbnailDownload = imageAttachment?.let { image ->
        downloads[remoteAttachmentDownloadKey(image.attachmentId, thumbnail = true)]
    }
    LaunchedEffect(repliedMessage?.messageId, imageAttachment?.attachmentId, thumbnailDownload) {
        if (
            repliedMessage != null &&
            imageAttachment != null &&
            thumbnailDownload?.localUri == null &&
            thumbnailDownload?.failureReason == null
        ) {
            onDownloadThumbnail(repliedMessage, imageAttachment.attachmentId)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            if (thumbnailDownload?.localUri != null) {
                AsyncImage(
                    model = thumbnailDownload.localUri,
                    contentDescription = imageAttachment.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(5.dp).size(42.dp),
                )
            } else if (attachment != null) {
                Icon(
                    imageVector = when (attachment.kind) {
                        RemoteAttachmentKind.IMAGE -> Icons.Default.Image
                        RemoteAttachmentKind.VIDEO -> Icons.Default.Movie
                        RemoteAttachmentKind.DOCUMENT -> Icons.Default.Description
                        RemoteAttachmentKind.AUDIO -> Icons.Default.AudioFile
                        RemoteAttachmentKind.VOICE_NOTE -> Icons.Default.Mic
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 6.dp)) {
                Text(
                    text = senderDisplayName ?: "Original message",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = repliedMessage?.let(::remoteReplyExcerpt) ?: "Open replied message",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun remoteReplyExcerpt(message: RemoteCachedMessage): String = when {
    message.deletedAt != null -> "Message deleted"
    message.body.isNotBlank() -> message.body.take(140)
    message.attachments.firstOrNull()?.kind == RemoteAttachmentKind.IMAGE -> "Photo"
    message.attachments.firstOrNull()?.kind == RemoteAttachmentKind.VIDEO -> "Video"
    message.attachments.firstOrNull()?.kind == RemoteAttachmentKind.VOICE_NOTE -> "Voice message"
    message.attachments.firstOrNull()?.kind == RemoteAttachmentKind.AUDIO -> "Audio"
    message.attachments.isNotEmpty() -> message.attachments.first().displayName
    else -> "Message"
}

private val REMOTE_PARTICIPANT_NAME_COLORS = listOf(
    Color(0xFF65E6F4),
    Color(0xFFC7B4FF),
    Color(0xFF76E5B7),
    Color(0xFFFFD27A),
    Color(0xFFFFAA98),
    Color(0xFF91CAFF),
    Color(0xFFFFA4D5),
    Color(0xFFBCEB78),
)
