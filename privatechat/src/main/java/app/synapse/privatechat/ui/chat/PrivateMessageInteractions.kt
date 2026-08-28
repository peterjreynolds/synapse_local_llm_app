package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.synapse.privatechat.domain.chat.PrivateMessageOwnership
import app.synapse.privatechat.domain.chat.PrivateMessageSnapshot
import app.synapse.privatechat.domain.chat.PrivateReactionSelectionState

internal enum class PrivateMessageActionOption(
    val label: String,
) {
    REPLY("Reply"),
    COPY("Copy"),
    EDIT("Edit"),
    DELETE_FOR_EVERYONE("Delete for everyone"),
}

internal fun privateMessageActionOptions(message: PrivateMessageSnapshot): List<PrivateMessageActionOption> =
    buildList {
        add(PrivateMessageActionOption.REPLY)
        add(PrivateMessageActionOption.COPY)
        if (message.ownership == PrivateMessageOwnership.CURRENT_ACCOUNT) {
            add(PrivateMessageActionOption.EDIT)
            add(PrivateMessageActionOption.DELETE_FOR_EVERYONE)
        }
    }

@Composable
internal fun PrivateMessageActionsDialog(
    message: PrivateMessageSnapshot,
    localActionsEnabled: Boolean,
    transportActionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onReact: (String) -> Unit,
    onDeleteForEveryone: () -> Unit,
) {
    var showFullEmojiPicker by remember(message.messageId) { mutableStateOf(false) }
    val selectedReactions =
        message.reactions
            .filter { reaction -> reaction.selectionState == PrivateReactionSelectionState.SELECTED }
            .mapTo(linkedSetOf()) { reaction -> reaction.reaction.canonical }
    if (showFullEmojiPicker) {
        PrivateFullEmojiPicker(
            title = "Choose a reaction",
            onDismiss = { showFullEmojiPicker = false },
            onEmojiPicked = { emoji ->
                showFullEmojiPicker = false
                onDismiss()
                onReact(emoji)
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message actions") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "React with an emoji",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                PrivateQuickReactionPalette(
                    selectedEmojis = selectedReactions,
                    enabled = transportActionsEnabled,
                    onEmojiSelected = { emoji ->
                        onDismiss()
                        onReact(emoji)
                    },
                )
                TextButton(
                    onClick = { showFullEmojiPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = transportActionsEnabled,
                ) {
                    Text("More emojis…")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                privateMessageActionOptions(message).forEach { action ->
                    TextButton(
                        onClick = {
                            onDismiss()
                            when (action) {
                                PrivateMessageActionOption.REPLY -> onReply()
                                PrivateMessageActionOption.COPY -> onCopy()
                                PrivateMessageActionOption.EDIT -> onEdit()
                                PrivateMessageActionOption.DELETE_FOR_EVERYONE -> onDeleteForEveryone()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled =
                            when (action) {
                                PrivateMessageActionOption.REPLY,
                                PrivateMessageActionOption.COPY,
                                PrivateMessageActionOption.EDIT,
                                -> localActionsEnabled

                                PrivateMessageActionOption.DELETE_FOR_EVERYONE -> transportActionsEnabled
                            },
                        colors =
                            if (action == PrivateMessageActionOption.DELETE_FOR_EVERYONE) {
                                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.textButtonColors()
                            },
                    ) {
                        Text(action.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
internal fun PrivateQuickReactionPalette(
    selectedEmojis: Set<String>,
    enabled: Boolean,
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PRIVATE_QUICK_REACTIONS.forEach { emoji ->
            val isSelected = emoji in selectedEmojis
            Surface(
                onClick = { onEmojiSelected(emoji) },
                modifier =
                    Modifier
                        .size(40.dp)
                        .semantics {
                            contentDescription = "React with ${privateEmojiAccessibilityLabel(emoji)}"
                            selected = isSelected
                        },
                enabled = enabled,
                shape = CircleShape,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(text = emoji, fontSize = 21.sp)
                }
            }
        }
    }
}

internal val PRIVATE_QUICK_REACTIONS =
    listOf(
        "👍",
        "❤️",
        "😂",
        "😮",
        "😢",
        "😡",
    )

private fun privateEmojiAccessibilityLabel(emoji: String): String =
    when (emoji) {
        "😂" -> "laughing"
        "😮" -> "surprised"
        "😢" -> "sad"
        "😡" -> "angry"
        "👍" -> "thumbs up"
        "❤️" -> "heart"
        else -> emoji
    }
