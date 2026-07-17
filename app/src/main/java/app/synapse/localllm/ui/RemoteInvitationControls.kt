package app.synapse.localllm.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
internal fun RemoteInvitationControls(
    state: RemoteAccountUiState,
    onCreateInvitation: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val invitation = state.generatedInvitation

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Invite someone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Every active account can create a one-use code. It expires after seven days, and Peter's approval setting still applies to the new account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        invitation?.let { receipt ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Share this code", fontWeight = FontWeight.SemiBold)
                    SelectionContainer {
                        Text(
                            text = receipt.invitationCode,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "One use · expires ${formatInvitationExpiry(receipt.expiresAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                "Synapse Chat invitation",
                                                receipt.invitationCode,
                                            ),
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Text(" Copy")
                        }
                        OutlinedButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Join me on Synapse Chat with this one-use invite code: ${receipt.invitationCode}",
                                    )
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Synapse invite"))
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Text(" Share")
                        }
                    }
                }
            }
        }
        Button(
            onClick = onCreateInvitation,
            enabled = !state.isActionRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null)
            Text(if (invitation == null) " Create invite code" else " Create another code")
        }
        Text(
            "Codes are shown only in memory on this phone. Copy or share each code before leaving the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatInvitationExpiry(expiresAtMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(expiresAtMillis))
