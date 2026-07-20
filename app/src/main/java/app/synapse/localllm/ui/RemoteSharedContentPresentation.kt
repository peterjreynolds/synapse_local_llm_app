package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedProfile
import app.synapse.localllm.domain.remote.RemoteMessageId
import java.net.URI
import java.time.Instant

internal enum class RemoteSharedContentCategory(
    val label: String,
) {
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    FILES("Files"),
    LINKS("Links"),
}

internal sealed interface RemoteSharedContentItem {
    val key: String
    val messageId: RemoteMessageId
    val senderDisplayName: String
    val sentAt: Instant
    val category: RemoteSharedContentCategory

    data class Attachment(
        override val key: String,
        override val messageId: RemoteMessageId,
        override val senderDisplayName: String,
        override val sentAt: Instant,
        override val category: RemoteSharedContentCategory,
        val attachment: RemoteCachedAttachment,
        val message: RemoteCachedMessage,
    ) : RemoteSharedContentItem

    data class Link(
        override val key: String,
        override val messageId: RemoteMessageId,
        override val senderDisplayName: String,
        override val sentAt: Instant,
        override val category: RemoteSharedContentCategory = RemoteSharedContentCategory.LINKS,
        val url: String,
    ) : RemoteSharedContentItem
}

internal fun buildRemoteSharedContent(
    messages: List<RemoteCachedMessage>,
    profiles: List<RemoteCachedProfile>,
    currentAccountUid: String?,
): List<RemoteSharedContentItem> {
    val profilesByUid = profiles.associateBy(RemoteCachedProfile::profileUid)
    return messages
        .asSequence()
        .filter { message -> message.deletedAt == null }
        .flatMap { message ->
            val senderDisplayName = when {
                message.senderUid.raw == currentAccountUid -> "You"
                message.authorKind == "SYNAPSE_AI" -> "Synapse"
                message.authorKind == "REMOTE_AI" -> remoteAiSenderDisplayName(message)
                else -> profilesByUid[message.senderUid]?.displayName ?: "Conversation member"
            }
            val sentAt = message.serverCreatedAt ?: message.clientCreatedAt
            val attachmentItems = message.attachments.map { attachment ->
                RemoteSharedContentItem.Attachment(
                    key = "${message.messageId.raw}:attachment:${attachment.attachmentId.raw}",
                    messageId = message.messageId,
                    senderDisplayName = senderDisplayName,
                    sentAt = sentAt,
                    category = attachment.sharedContentCategory(),
                    attachment = attachment,
                    message = message,
                )
            }
            val linkItems = extractRemoteSharedLinks(message.body).mapIndexed { index, url ->
                RemoteSharedContentItem.Link(
                    key = "${message.messageId.raw}:link:$index",
                    messageId = message.messageId,
                    senderDisplayName = senderDisplayName,
                    sentAt = sentAt,
                    url = url,
                )
            }
            (attachmentItems + linkItems).asSequence()
        }
        .sortedWith(
            compareByDescending<RemoteSharedContentItem>(RemoteSharedContentItem::sentAt)
                .thenBy(RemoteSharedContentItem::key),
        )
        .toList()
}

internal fun extractRemoteSharedLinks(body: String): List<String> =
    REMOTE_SHARED_LINK_PATTERN
        .findAll(body)
        .mapNotNull { match -> normalizeRemoteSharedLink(match.value) }
        .distinct()
        .take(MAXIMUM_LINKS_PER_MESSAGE)
        .toList()

private fun RemoteCachedAttachment.sharedContentCategory(): RemoteSharedContentCategory = when (kind) {
    RemoteAttachmentKind.IMAGE -> RemoteSharedContentCategory.PHOTOS
    RemoteAttachmentKind.VIDEO -> RemoteSharedContentCategory.VIDEOS
    RemoteAttachmentKind.AUDIO,
    RemoteAttachmentKind.VOICE_NOTE,
    -> RemoteSharedContentCategory.AUDIO
    RemoteAttachmentKind.DOCUMENT -> RemoteSharedContentCategory.FILES
}

private fun normalizeRemoteSharedLink(candidate: String): String? {
    val normalized = candidate
        .trim()
        .trimEnd('.', ',', '!', '?', ':', ';', ')', ']', '}')
        .takeIf { value -> value.length <= MAXIMUM_SHARED_LINK_LENGTH }
        ?: return null
    val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
    if (!parsed.isAbsolute || parsed.host.isNullOrBlank()) return null
    if (parsed.scheme?.lowercase() !in setOf("http", "https")) return null
    return parsed.toASCIIString()
}

private const val MAXIMUM_LINKS_PER_MESSAGE = 32
private const val MAXIMUM_SHARED_LINK_LENGTH = 2_048
private val REMOTE_SHARED_LINK_PATTERN = Regex(
    pattern = """https?://[^\s<>\"']+""",
    option = RegexOption.IGNORE_CASE,
)
