package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.chat.PrivateConversationSnapshot
import app.synapse.privatechat.domain.chat.PrivateMessageId
import app.synapse.privatechat.domain.chat.PrivateMessageOwnership
import app.synapse.privatechat.domain.chat.PrivateMessageSnapshot
import app.synapse.privatechat.domain.chat.PrivateReactionSelectionState
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem
import java.time.Duration
import java.time.Instant

@Composable
internal fun PrivateMessageTimeline(
    snapshot: PrivateConversationSnapshot,
    enabled: Boolean,
    onReply: (PrivateMessageId) -> Unit,
    onEdit: (PrivateMessageId) -> Unit,
    onReact: (PrivateMessageId, String) -> Unit,
    onDelete: (PrivateMessageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    Column(modifier = modifier.fillMaxSize()) {
        if (snapshot.messages.isEmpty()) {
            PrivateConversationStatus(
                title = "No current messages",
                detail = "Confirmed messages appear here only until this conversation's retention window expires.",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(tokens.spacing.large),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.medium),
            ) {
                items(
                    items = snapshot.messages,
                    key = { message -> message.messageId.canonical },
                ) { message ->
                    PrivateMessageBubble(
                        message = message,
                        enabled = enabled,
                        onReply = { onReply(message.messageId) },
                        onEdit = { onEdit(message.messageId) },
                        onReact = { reaction -> onReact(message.messageId, reaction) },
                        onDelete = { onDelete(message.messageId) },
                    )
                }
            }
        }
        if (snapshot.typingParticipants.isNotEmpty()) {
            Text(
                text = privateTypingLabel(snapshot),
                modifier = Modifier.padding(horizontal = tokens.spacing.large, vertical = tokens.spacing.compact),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun PrivateMessageBubble(
    message: PrivateMessageSnapshot,
    enabled: Boolean,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onReact: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    val ownMessage = message.ownership == PrivateMessageOwnership.CURRENT_ACCOUNT
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier =
                Modifier
                    .align(if (ownMessage) Alignment.CenterEnd else Alignment.CenterStart)
                    .widthIn(max = 560.dp),
            color =
                if (ownMessage) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            shape = RoundedCornerShape(tokens.radii.bubble),
            border = CardDefaults.outlinedCardBorder(),
        ) {
            Column(
                modifier = Modifier.padding(tokens.spacing.medium),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.compact),
            ) {
                Text(
                    text = message.senderDisplayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                )
                message.replyPreview?.let { reply ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(modifier = Modifier.padding(tokens.spacing.small)) {
                            Text(reply.senderDisplayName, style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = reply.body.plaintext,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(message.body.plaintext, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text =
                        buildString {
                            append("Expires in ")
                            append(privateRemainingTimeLabel(message.expiresAt))
                            if (message.editedAt != null) append(" · edited")
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.reactions.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.compact),
                    ) {
                        message.reactions.forEach { reaction ->
                            FilterChip(
                                selected = reaction.selectionState == PrivateReactionSelectionState.SELECTED,
                                onClick = { onReact(reaction.reaction.canonical) },
                                enabled = enabled,
                                label = { Text("${reaction.reaction.canonical} ${reaction.count}") },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(tokens.spacing.compact),
                ) {
                    TextButton(onClick = onReply, enabled = enabled) {
                        Text("Reply")
                    }
                    QUICK_REACTIONS.forEach { reaction ->
                        TextButton(onClick = { onReact(reaction) }, enabled = enabled) {
                            Text(reaction)
                        }
                    }
                    if (ownMessage) {
                        TextButton(onClick = onEdit, enabled = enabled) {
                            Text("Edit")
                        }
                        TextButton(onClick = onDelete, enabled = enabled) {
                            Text("Delete for everyone")
                        }
                    }
                }
            }
        }
    }
}

private fun privateTypingLabel(snapshot: PrivateConversationSnapshot): String {
    val names = snapshot.typingParticipants.map { participant -> participant.displayName }.distinct()
    return when (names.size) {
        1 -> "${names.single()} is typing…"
        2 -> "${names[0]} and ${names[1]} are typing…"
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} others are typing…"
    }
}

internal fun privateRemainingTimeLabel(
    expiresAt: Instant,
    now: Instant = Instant.now(),
): String {
    val remaining = Duration.between(now, expiresAt).coerceAtLeast(Duration.ZERO)
    val minutes = remaining.toMinutes()
    return when {
        minutes < 1L -> "less than a minute"
        minutes < 60L -> "${minutes}m"
        minutes < 1_440L -> "${remaining.toHours()}h"
        else -> "${remaining.toDays()}d"
    }
}

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂")
