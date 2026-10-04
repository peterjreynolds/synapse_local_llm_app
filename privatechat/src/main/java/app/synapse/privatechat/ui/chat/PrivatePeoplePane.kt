package app.synapse.privatechat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateDirectoryPerson

@Composable
internal fun PrivatePeoplePane(
    state: PrivatePeopleUiState,
    onChat: (PrivateAccountId) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<PrivateDirectoryPerson?>(null) }
    val people =
        state.people
            .filter { it.displayName.contains(query.trim(), ignoreCase = true) }
            .sortedWith(
                compareByDescending<PrivateDirectoryPerson> { it.activeUntil > state.now }
                    .thenBy { it.displayName.lowercase() }
                    .thenBy { it.accountId.canonical },
            )
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("People", style = MaterialTheme.typography.headlineSmall)
        Text(
            "App accounts can see when you are active while this app is open. Conversation presence sharing is separate.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search people") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.availability == PrivatePeopleAvailability.LOADING) CircularProgressIndicator()
        if (state.availability == PrivatePeopleAvailability.UNAVAILABLE) {
            Text("People refresh unavailable. Retrying automatically.", color = MaterialTheme.colorScheme.error)
        }
        if (state.activityAvailability == PrivatePeopleAvailability.UNAVAILABLE) {
            Text("Your active status could not be updated.", style = MaterialTheme.typography.bodySmall)
        }
        when (state.directChat) {
            PrivateDirectChatUiState.Opening -> Text("Opening conversation…")
            is PrivateDirectChatUiState.Confirmed -> Text("Conversation confirmed. Waiting for the chat feed…")
            PrivateDirectChatUiState.Unavailable -> Text("Conversation unavailable. Try again when connected.")
            PrivateDirectChatUiState.Idle -> Unit
        }
        if (people.isEmpty() && state.availability == PrivatePeopleAvailability.AVAILABLE) Text("No people found.")
        LazyColumn {
            items(people, key = { it.accountId.canonical }) { person ->
                ListItem(
                    headlineContent = { Text(person.displayName) },
                    supportingContent = { Text(if (person.activeUntil > state.now) "Active" else "Inactive") },
                    modifier = Modifier.clickable { selected = person },
                )
            }
        }
    }
    selected?.let { person ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(person.displayName) },
            text = { Text("Start or reopen your encrypted one-to-one conversation.") },
            confirmButton = {
                TextButton(
                    enabled = state.directChat != PrivateDirectChatUiState.Opening,
                    onClick = {
                        selected = null
                        onChat(person.accountId)
                    },
                ) { Text("Chat") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
}
