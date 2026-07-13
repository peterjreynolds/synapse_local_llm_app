package app.synapse.localllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RemoteChatApp(
    remoteViewModel: RemoteChatViewModel,
    localViewModel: SynapseViewModel,
) {
    val state by remoteViewModel.uiState.collectAsStateWithLifecycle()
    var showLocalWhileSignedOut by rememberSaveable { mutableStateOf(false) }
    if (state.account == null && showLocalWhileSignedOut) {
        SignedOutLocalApp(
            localViewModel = localViewModel,
            onBackToSignIn = { showLocalWhileSignedOut = false },
        )
    } else if (state.account == null) {
        RemoteLoginScreen(
            state = state,
            onSignIn = remoteViewModel::signIn,
            onOpenLocalAi = { showLocalWhileSignedOut = true },
        )
    } else {
        RemoteSignedInShell(
            state = state,
            remoteViewModel = remoteViewModel,
            localViewModel = localViewModel,
        )
    }
}

@Composable
private fun RemoteLoginScreen(
    state: RemoteChatUiState,
    onSignIn: (String, String) -> Unit,
    onOpenLocalAi: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    fun submit() {
        if (!state.isActionRunning) onSignIn(username, password)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Synapse Chat",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Private internet chat for approved accounts. Local AI remains on this phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { value -> username = value },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Username") },
                singleLine = true,
                enabled = !state.isActionRunning,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { value -> password = value },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.isActionRunning,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            state.notice?.let { notice ->
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            Button(
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = username.isNotBlank() && password.isNotEmpty() && !state.isActionRunning,
            ) {
                if (state.isActionRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Sign in")
                }
            }
            OutlinedButton(
                onClick = onOpenLocalAi,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isActionRunning,
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = null)
                Text(" Open Local AI without signing in")
            }
            Text(
                text = "Accounts are provisioned by the app owner; passwords are never stored in the APK.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SignedOutLocalApp(
    localViewModel: SynapseViewModel,
    onBackToSignIn: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onBackToSignIn) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to remote sign in")
            }
            Column {
                Text("Synapse Local AI", fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Local-only workspace — no remote account",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.HorizontalDivider()
        Box(modifier = Modifier.weight(1f)) {
            SynapseApp(viewModel = localViewModel)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RemoteSignedInShell(
    state: RemoteChatUiState,
    remoteViewModel: RemoteChatViewModel,
    localViewModel: SynapseViewModel,
) {
    val localState by localViewModel.uiState.collectAsStateWithLifecycle()
    var selectedSection by rememberSaveable { mutableStateOf(RemoteAppSection.CHATS) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.selectedRoomId) {
        if (state.selectedRoomId != null) selectedSection = RemoteAppSection.CHATS
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            remoteViewModel.clearNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(selectedSection.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (selectedSection == RemoteAppSection.LOCAL_AI) {
                                "Local-only workspace"
                            } else {
                                "Synced as @${state.account?.usernameNormalized.orEmpty()}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                RemoteAppSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = selectedSection == section,
                        onClick = {
                            selectedSection = section
                            if (section != RemoteAppSection.CHATS) remoteViewModel.selectRoom(null)
                        },
                        icon = { Icon(section.icon, contentDescription = section.title) },
                        label = { Text(section.navigationLabel) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (selectedSection) {
                RemoteAppSection.CHATS -> RemoteChatsPane(state, remoteViewModel)
                RemoteAppSection.PEOPLE -> RemotePeoplePane(state, remoteViewModel::openDirectRoom)
                RemoteAppSection.PROFILE -> RemoteProfilePane(
                    state = state,
                    viewModel = remoteViewModel,
                    appUpdate = localState.appUpdate,
                    onCheckAppUpdate = { localViewModel.checkForAppUpdate(automatic = false) },
                )
                RemoteAppSection.LOCAL_AI -> SynapseApp(viewModel = localViewModel)
            }
        }
    }
}

private enum class RemoteAppSection(
    val title: String,
    val navigationLabel: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    CHATS("Synapse Chat", "Chats", Icons.AutoMirrored.Filled.Chat),
    PEOPLE("People", "People", Icons.Default.Groups),
    PROFILE("Profile & security", "Profile", Icons.Default.Person),
    LOCAL_AI("Synapse Local AI", "Local AI", Icons.Default.SmartToy),
}
