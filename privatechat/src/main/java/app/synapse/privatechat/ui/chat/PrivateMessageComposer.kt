package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem

@Composable
internal fun PrivateMessageComposer(
    text: String,
    mode: PrivateComposerMode,
    enabled: Boolean,
    onTextChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancelContext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    var showEmojiPicker by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.small),
    ) {
        when (mode) {
            PrivateComposerMode.NewMessage -> Unit
            is PrivateComposerMode.ReplyingTo ->
                PrivateComposerContext(
                    title = "Replying to ${mode.preview.senderDisplayName}",
                    body = mode.preview.body.plaintext,
                    onCancel = onCancelContext,
                )

            is PrivateComposerMode.Editing ->
                PrivateComposerContext(
                    title = "Editing message",
                    body = mode.originalBody.plaintext,
                    onCancel = onCancelContext,
                )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(tokens.spacing.small),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                label = { Text(if (mode is PrivateComposerMode.Editing) "Revised message" else "Message") },
                minLines = 1,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                leadingIcon = {
                    Box {
                        IconButton(
                            onClick = { showEmojiPicker = true },
                            modifier = Modifier.semantics { contentDescription = "Choose emoji" },
                            enabled = enabled,
                        ) {
                            Text("😊")
                        }
                        DropdownMenu(
                            expanded = showEmojiPicker,
                            onDismissRequest = { showEmojiPicker = false },
                        ) {
                            Text(
                                text = "Add emoji",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            PrivateEmojiPalette(
                                selectedEmojis = emptySet(),
                                enabled = enabled,
                                actionLabel = "Add",
                                onEmojiSelected = { emoji ->
                                    showEmojiPicker = false
                                    onTextChanged(text + emoji)
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                },
            )
            Button(
                onClick = onSubmit,
                enabled = enabled && text.isNotBlank(),
            ) {
                Text(if (mode is PrivateComposerMode.Editing) "Save" else "Send")
            }
        }
    }
}

@Composable
private fun PrivateComposerContext(
    title: String,
    body: String,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}
