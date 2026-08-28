package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.emoji2.emojipicker.RecentEmojiProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateFullEmojiPicker(
    title: String,
    onDismiss: () -> Unit,
    onEmojiPicked: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        AndroidView(
            factory = { context ->
                EmojiPickerView(context).apply {
                    emojiGridColumns = PRIVATE_EMOJI_PICKER_COLUMNS
                    setRecentEmojiProvider(PrivateNonPersistingRecentEmojiProvider)
                }
            },
            update = { picker ->
                picker.setOnEmojiPickedListener { selection ->
                    onEmojiPicked(selection.emoji)
                }
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PRIVATE_EMOJI_PICKER_HEIGHT),
        )
        Spacer(Modifier.height(12.dp))
    }
}

internal object PrivateNonPersistingRecentEmojiProvider : RecentEmojiProvider {
    // EmojiPickerView's default provider writes selections to SharedPreferences.
    override fun recordSelection(emoji: String) = Unit

    override suspend fun getRecentEmojiList(): List<String> = emptyList()
}

private val PRIVATE_EMOJI_PICKER_HEIGHT = 360.dp
private const val PRIVATE_EMOJI_PICKER_COLUMNS = 9
