package app.synapse.localllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteAuthenticationState
import app.synapse.localllm.domain.remote.RemoteInviteRegistrationCommand
import app.synapse.localllm.domain.remote.validateRemoteInviteRegistrationCommand
import app.synapse.localllm.dismissRemoteRoomNotification
import kotlinx.coroutines.launch

@Composable
fun RemoteChatApp(
    remoteViewModel: RemoteChatViewModel,
    remoteAccountViewModel: RemoteAccountViewModel,
    remoteGroupViewModel: RemoteGroupViewModel,
    localViewModel: SynapseViewModel,
    ownerAdminViewModel: OwnerAdminViewModel,
    appLockState: AppLockUiState,
    appLockViewModel: AppLockViewModel,
    chatAppearanceViewModel: ChatAppearanceViewModel,
    requestOwnerIdentityConfirmation: ((Boolean) -> Unit) -> Unit,
) {
    val state by remoteViewModel.uiState.collectAsStateWithLifecycle()
    var showLocalWhileSignedOut by rememberSaveable { mutableStateOf(false) }
    val authenticationState = state.authenticationState
    val account = state.account
    if (authenticationState == RemoteAuthenticationState.Resolving) {
        RemoteAccountResolvingScreen()
    } else if (authenticationState is RemoteAuthenticationState.InvalidSession) {
        RemoteInvalidSessionScreen(
            state = state,
            onRefresh = remoteViewModel::refreshAccountAccess,
            onSignOut = remoteViewModel::signOut,
        )
    } else if (
        account != null &&
        (account.state != RemoteAccountState.ACTIVE || account.mustChangePassword)
    ) {
        RemoteRestrictedAccountScreen(
            state = state,
            onChangePassword = remoteViewModel::changePassword,
            onRefresh = remoteViewModel::refreshAccountAccess,
            onSignOut = remoteViewModel::signOut,
        )
    } else if (state.account == null && showLocalWhileSignedOut) {
        SignedOutLocalApp(
            localViewModel = localViewModel,
            onBackToSignIn = { showLocalWhileSignedOut = false },
        )
    } else if (state.account == null) {
        RemoteLoginScreen(
            state = state,
            onSignIn = remoteViewModel::signIn,
            onRegister = remoteViewModel::registerWithInvite,
            onOpenLocalAi = { showLocalWhileSignedOut = true },
        )
    } else {
        RemoteSignedInShell(
            state = state,
            remoteViewModel = remoteViewModel,
            remoteAccountViewModel = remoteAccountViewModel,
            remoteGroupViewModel = remoteGroupViewModel,
            localViewModel = localViewModel,
            ownerAdminViewModel = ownerAdminViewModel,
            appLockState = appLockState,
            appLockViewModel = appLockViewModel,
            chatAppearanceViewModel = chatAppearanceViewModel,
            requestOwnerIdentityConfirmation = requestOwnerIdentityConfirmation,
        )
    }
}

@Composable
private fun RemoteLoginScreen(
    state: RemoteChatUiState,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String, String, String) -> Unit,
    onOpenLocalAi: () -> Unit,
) {
    var showRegistration by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var invitationCode by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    ClearSensitiveInputsOnStop {
        password = ""
        confirmPassword = ""
        invitationCode = ""
    }

    fun submit() {
        if (state.isActionRunning) return
        localError = null
        if (!showRegistration) {
            onSignIn(username, password)
            return
        }
        if (password != confirmPassword) {
            localError = "Passwords do not match."
            return
        }
        val command = runCatching {
            validateRemoteInviteRegistrationCommand(
                RemoteInviteRegistrationCommand(
                    username = username,
                    displayName = displayName,
                    password = password,
                    invitationCode = invitationCode,
                ),
            )
        }.getOrElse { failure ->
            localError = failure.message ?: "Check the registration details."
            return
        }
        onRegister(
            command.username,
            command.displayName,
            command.password,
            command.invitationCode,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Synapse Chat",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (showRegistration) {
                    "Create a private-network account with an invitation. New accounts normally wait for owner approval."
                } else {
                    "Private internet chat for approved accounts. Local AI remains on this phone."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showRegistration) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { value -> displayName = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display name") },
                    singleLine = true,
                    enabled = !state.isActionRunning,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
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
                    imeAction = if (showRegistration) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = if (showRegistration) {
                    KeyboardActions.Default
                } else {
                    KeyboardActions(onDone = { submit() })
                },
            )
            if (showRegistration) {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { value -> confirmPassword = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm password") },
                    singleLine = true,
                    enabled = !state.isActionRunning,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                )
                OutlinedTextField(
                    value = invitationCode,
                    onValueChange = { value -> invitationCode = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Invitation code") },
                    singleLine = true,
                    enabled = !state.isActionRunning,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
            localError?.let { notice ->
                Text(
                    text = notice,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
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
                enabled = remoteLoginSubmissionEnabled(
                    showRegistration = showRegistration,
                    username = username,
                    displayName = displayName,
                    password = password,
                    confirmPassword = confirmPassword,
                    invitationCode = invitationCode,
                    isActionRunning = state.isActionRunning,
                ),
            ) {
                if (state.isActionRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(if (showRegistration) "Create account" else "Sign in")
                }
            }
            OutlinedButton(
                onClick = {
                    showRegistration = !showRegistration
                    password = ""
                    confirmPassword = ""
                    invitationCode = ""
                    localError = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isActionRunning,
            ) {
                Text(if (showRegistration) "Back to sign in" else "Create account")
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
                text = "Passwords and invitation codes stay in memory only and are cleared when this screen closes or backgrounds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ClearSensitiveInputsOnStop(onClear: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentClear by rememberUpdatedState(onClear)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) currentClear()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            currentClear()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
private fun RemoteAccountResolvingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = "Checking account access…",
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun RemoteInvalidSessionScreen(
    state: RemoteChatUiState,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    RemoteAccountStatusScreen(
        title = "Account access unavailable",
        message = (state.authenticationState as? RemoteAuthenticationState.InvalidSession)
            ?.userMessage
            ?: "Synapse could not verify this account.",
        state = state,
        onRefresh = onRefresh,
        onSignOut = onSignOut,
    )
}

@Composable
private fun RemoteRestrictedAccountScreen(
    state: RemoteChatUiState,
    onChangePassword: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    if (state.account?.mustChangePassword == true) {
        RemoteRequiredPasswordChangeScreen(state, onChangePassword, onSignOut)
        return
    }
    val accountState = state.account?.state
    val (title, message) = when (accountState) {
        RemoteAccountState.PENDING_APPROVAL ->
            "Approval pending" to "Your account was created and is waiting for owner approval."
        RemoteAccountState.REJECTED ->
            "Registration rejected" to "This account request was not approved."
        RemoteAccountState.DISABLED ->
            "Account disabled" to "This account cannot use remote chat."
        else -> "Account unavailable" to "This account cannot use remote chat."
    }
    RemoteAccountStatusScreen(title, message, state, onRefresh, onSignOut)
}

@Composable
private fun RemoteRequiredPasswordChangeScreen(
    state: RemoteChatUiState,
    onChangePassword: (String, String) -> Unit,
    onSignOut: () -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    ClearSensitiveInputsOnStop {
        currentPassword = ""
        newPassword = ""
        confirmPassword = ""
    }
    fun submit() {
        if (newPassword != confirmPassword) {
            localError = "Passwords do not match."
            return
        }
        if (newPassword.length !in 12..128) {
            localError = "New password must contain 12-128 characters."
            return
        }
        localError = null
        onChangePassword(currentPassword, newPassword)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Change temporary password",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("Choose a new private password before using remote chat.")
            OutlinedTextField(
                value = currentPassword,
                onValueChange = { currentPassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Temporary password") },
                visualTransformation = PasswordVisualTransformation(),
                enabled = !state.isActionRunning,
                singleLine = true,
            )
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("New password") },
                visualTransformation = PasswordVisualTransformation(),
                enabled = !state.isActionRunning,
                singleLine = true,
            )
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Confirm new password") },
                visualTransformation = PasswordVisualTransformation(),
                enabled = !state.isActionRunning,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            (localError ?: state.notice)?.let { notice ->
                Text(notice, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = currentPassword.isNotEmpty() &&
                    newPassword.isNotEmpty() &&
                    !state.isActionRunning,
            ) {
                Text("Change password")
            }
            OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isActionRunning,
            ) {
                Text("Sign out")
            }
        }
    }
}

@Composable
private fun RemoteAccountStatusScreen(
    title: String,
    message: String,
    state: RemoteChatUiState,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(message, style = MaterialTheme.typography.bodyLarge)
            state.account?.let { account ->
                Text(
                    text = "@${account.usernameNormalized}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.notice?.let { notice ->
                Text(notice, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = onRefresh,
                enabled = !state.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Check status")
            }
            OutlinedButton(
                onClick = onSignOut,
                enabled = !state.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sign out")
            }
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
    remoteAccountViewModel: RemoteAccountViewModel,
    remoteGroupViewModel: RemoteGroupViewModel,
    localViewModel: SynapseViewModel,
    ownerAdminViewModel: OwnerAdminViewModel,
    appLockState: AppLockUiState,
    appLockViewModel: AppLockViewModel,
    chatAppearanceViewModel: ChatAppearanceViewModel,
    requestOwnerIdentityConfirmation: ((Boolean) -> Unit) -> Unit,
) {
    val localState by localViewModel.uiState.collectAsStateWithLifecycle()
    val remoteAccountState by remoteAccountViewModel.uiState.collectAsStateWithLifecycle()
    val remoteGroupState by remoteGroupViewModel.uiState.collectAsStateWithLifecycle()
    val chatAppearanceState by chatAppearanceViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedSection by rememberSaveable { mutableStateOf(RemoteAppSection.CHATS) }
    val availableSections = availableRemoteAppSections(state.account?.role)
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(state.selectedRoomId) {
        if (state.selectedRoomId != null) selectedSection = RemoteAppSection.CHATS
    }
    LaunchedEffect(state.selectedRoomId, state.rooms) {
        state.selectedRoomId?.let { roomId -> dismissRemoteRoomNotification(context, roomId) }
        state.rooms
            .asSequence()
            .filter { room -> room.unreadCount == 0 }
            .forEach { room -> dismissRemoteRoomNotification(context, room.roomId) }
    }
    LaunchedEffect(state.account?.accountUid, state.selectedRoomId) {
        chatAppearanceViewModel.selectConversation(state.account?.accountUid, state.selectedRoomId)
    }
    DisposableEffect(chatAppearanceViewModel) {
        onDispose { chatAppearanceViewModel.selectConversation(null, null) }
    }
    LaunchedEffect(availableSections) {
        if (selectedSection !in availableSections) selectedSection = RemoteAppSection.CHATS
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            remoteViewModel.clearNotice()
        }
    }
    LaunchedEffect(remoteAccountState.notice) {
        remoteAccountState.notice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            remoteAccountViewModel.clearNotice()
        }
    }
    LaunchedEffect(remoteGroupState.notice) {
        remoteGroupState.notice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            remoteGroupViewModel.clearNotice()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.selectedRoomId == null,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Text("Synapse Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "@${state.account?.usernameNormalized.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                availableSections.forEach { section ->
                    NavigationDrawerItem(
                        selected = selectedSection == section,
                        onClick = {
                            selectedSection = section
                            if (section != RemoteAppSection.CHATS) remoteViewModel.selectRoom(null)
                            coroutineScope.launch { drawerState.close() }
                        },
                        icon = { Icon(section.icon, contentDescription = section.title) },
                        label = { Text(section.navigationLabel) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                if (state.selectedRoomId == null && selectedSection != RemoteAppSection.LOCAL_AI) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Open navigation")
                            }
                        },
                        title = {
                            Column {
                                Text(selectedSection.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = if (selectedSection == RemoteAppSection.LOCAL_AI) {
                                        "Local-only workspace"
                                    } else {
                                        "Signed in as @${state.account?.usernameNormalized.orEmpty()}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
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
                    RemoteAppSection.CHATS -> RemoteChatsPane(
                        state = state,
                        viewModel = remoteViewModel,
                        accountState = remoteAccountState,
                        groupState = remoteGroupState,
                        groupViewModel = remoteGroupViewModel,
                        appearanceState = chatAppearanceState,
                        appearanceViewModel = chatAppearanceViewModel,
                    )
                    RemoteAppSection.PEOPLE -> RemotePeoplePane(
                        state = state,
                        accountState = remoteAccountState,
                        onOpenDirectRoom = remoteViewModel::openDirectRoom,
                        onSetUserBlocked = remoteAccountViewModel::setUserBlocked,
                    )
                    RemoteAppSection.PROFILE -> RemoteProfilePane(
                        state = state,
                        viewModel = remoteViewModel,
                        accountState = remoteAccountState,
                        accountViewModel = remoteAccountViewModel,
                        appUpdate = localState.appUpdate,
                        appLockState = appLockState,
                        appLockViewModel = appLockViewModel,
                        onCheckAppUpdate = { localViewModel.checkForAppUpdate(automatic = false) },
                    )
                    RemoteAppSection.LOCAL_AI -> SynapseApp(
                        viewModel = localViewModel,
                        onOpenAppNavigation = { coroutineScope.launch { drawerState.open() } },
                    )
                    RemoteAppSection.ADMIN -> OwnerAdminPane(
                        viewModel = ownerAdminViewModel,
                        requestOwnerIdentityConfirmation = requestOwnerIdentityConfirmation,
                    )
                }
            }
        }
    }
}

internal fun remoteLoginSubmissionEnabled(
    showRegistration: Boolean,
    username: String,
    displayName: String,
    password: String,
    confirmPassword: String,
    invitationCode: String,
    isActionRunning: Boolean,
): Boolean {
    if (isActionRunning || username.isBlank() || password.isEmpty()) return false
    return !showRegistration || (
        displayName.isNotBlank() &&
            confirmPassword.isNotEmpty() &&
            invitationCode.isNotBlank()
        )
}

internal enum class RemoteAppSection(
    val title: String,
    val navigationLabel: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    CHATS("Synapse Chat", "Chats", Icons.AutoMirrored.Filled.Chat),
    PEOPLE("People", "People", Icons.Default.Groups),
    PROFILE("Profile & security", "Profile", Icons.Default.Person),
    LOCAL_AI("Synapse Local AI", "Local AI", Icons.Default.SmartToy),
    ADMIN("Owner administration", "Admin", Icons.Default.AdminPanelSettings),
}

internal fun availableRemoteAppSections(role: RemoteAccountRole?): List<RemoteAppSection> =
    RemoteAppSection.entries.filter { section ->
        section != RemoteAppSection.ADMIN || role == RemoteAccountRole.OWNER
    }
