package app.synapse.localllm.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.synapse.localllm.domain.remote.CreateOwnerAccountCommand
import app.synapse.localllm.domain.remote.OwnerAccountSummary
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.security.AppLockPin
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.delay

@Composable
fun OwnerAdminPane(
    viewModel: OwnerAdminViewModel,
    appLockState: AppLockUiState,
    appLockViewModel: AppLockViewModel,
    requestOwnerIdentityConfirmation: ((Boolean) -> Unit) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchPrefix by remember { mutableStateOf("") }
    var localNotice by remember { mutableStateOf<String?>(null) }
    var oneTimeSecret by remember { mutableStateOf<OneTimeOwnerSecret?>(null) }
    var showManagedAccountCreation by rememberSaveable { mutableStateOf(false) }

    var createUsername by remember { mutableStateOf("") }
    var createDisplayName by remember { mutableStateOf("") }
    var createTemporaryPassword by remember { mutableStateOf("") }
    var createOwnerPassword by remember { mutableStateOf("") }
    var createRequiresPasswordChange by remember { mutableStateOf(true) }

    var resetTemporaryPassword by remember { mutableStateOf("") }
    var resetOwnerPassword by remember { mutableStateOf("") }
    var resetRequiresPasswordChange by remember { mutableStateOf(true) }

    var deleteConfirmation by remember { mutableStateOf("") }
    var deleteOwnerPassword by remember { mutableStateOf("") }
    var ownerActionPin by remember { mutableStateOf("") }

    BlockScreenshotsWhileVisible()
    ClearSensitiveInputsOnStop {
        createTemporaryPassword = ""
        createOwnerPassword = ""
        resetTemporaryPassword = ""
        resetOwnerPassword = ""
        deleteOwnerPassword = ""
        ownerActionPin = ""
        oneTimeSecret = null
    }
    LaunchedEffect(oneTimeSecret) {
        if (oneTimeSecret != null) {
            delay(ONE_TIME_SECRET_VISIBILITY_MILLIS)
            oneTimeSecret = null
        }
    }
    LaunchedEffect(state.selectedAccountUid) {
        resetTemporaryPassword = ""
        resetOwnerPassword = ""
        deleteConfirmation = ""
        deleteOwnerPassword = ""
        ownerActionPin = ""
    }

    fun confirmDeviceCredential(action: () -> Unit) {
        localNotice = null
        requestOwnerIdentityConfirmation { confirmed ->
            if (confirmed) action() else localNotice = "Owner confirmation was cancelled."
        }
    }

    fun confirmOwnerPin(action: () -> Unit) {
        localNotice = null
        if (!appLockState.isEnabled) {
            localNotice = "Create an app PIN before using owner account actions."
            return
        }
        if (ownerActionPin.length != AppLockPin.PIN_LENGTH) {
            localNotice = "Enter your four-digit app PIN."
            return
        }
        appLockViewModel.verifySensitiveAction(ownerActionPin) {
            ownerActionPin = ""
            confirmDeviceCredential(action)
        }
    }

    val selectedAccount = state.accounts.firstOrNull { account ->
        account.accountUid == state.selectedAccountUid
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Owner controls", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Manage people and invite codes. System details stay tucked away until you need them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.notice?.let { notice ->
            OwnerNoticeCard(notice)
        }
        localNotice?.let { notice ->
            OwnerNoticeCard(notice)
        }
        oneTimeSecret?.let { secret ->
            OneTimeSecretCard(secret)
        }

        OwnerSection("Accounts") {
            if (selectedAccount == null) {
                OutlinedTextField(
                    value = searchPrefix,
                    onValueChange = { searchPrefix = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search username") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.refresh(searchPrefix.ifBlank { null }) },
                        enabled = !state.isActionRunning,
                    ) {
                        Text("Search")
                    }
                    OutlinedButton(
                        onClick = {
                            searchPrefix = ""
                            viewModel.refresh()
                        },
                        enabled = !state.isActionRunning,
                    ) {
                        Text("Refresh all")
                    }
                }
                state.accounts.forEach { account ->
                    OwnerAccountCard(
                        account = account,
                        enabled = !state.isActionRunning,
                        onSelect = { viewModel.selectAccount(account.accountUid) },
                        onApprove = { viewModel.reviewRegistration(account.accountUid, approve = true) },
                        onReject = { viewModel.reviewRegistration(account.accountUid, approve = false) },
                    )
                }
                if (state.accounts.isEmpty()) Text("No matching accounts.")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.selectAccount(null) },
                        enabled = !state.isActionRunning,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to accounts")
                    }
                    Column {
                        Text(selectedAccount.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "@${selectedAccount.usernameNormalized}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (appLockState.isEnabled) {
                    Text(
                        "Enter your app PIN before each owner action.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppLockPinField(
                        value = ownerActionPin,
                        label = "Owner action PIN",
                        enabled = !appLockState.isActionRunning && !state.isActionRunning,
                        onValueChanged = { ownerActionPin = it },
                    )
                    appLockState.notice?.let { notice ->
                        Text(notice, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Create an owner-action PIN", fontWeight = FontWeight.SemiBold)
                            Text(
                                "The same device-local PIN locks Synapse and protects account changes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AppLockSettings(appLockState, appLockViewModel)
                        }
                    }
                }
                OwnerAccountOperations(
                    account = selectedAccount,
                    state = state,
                    ownerPinReady = appLockState.isEnabled &&
                        ownerActionPin.length == AppLockPin.PIN_LENGTH,
                    resetTemporaryPassword = resetTemporaryPassword,
                    resetOwnerPassword = resetOwnerPassword,
                    resetRequiresPasswordChange = resetRequiresPasswordChange,
                    deleteConfirmation = deleteConfirmation,
                    deleteOwnerPassword = deleteOwnerPassword,
                    onResetTemporaryPasswordChanged = { resetTemporaryPassword = it },
                    onResetOwnerPasswordChanged = { resetOwnerPassword = it },
                    onResetRequiresPasswordChangeChanged = { resetRequiresPasswordChange = it },
                    onDeleteConfirmationChanged = { deleteConfirmation = it },
                    onDeleteOwnerPasswordChanged = { deleteOwnerPassword = it },
                    onGeneratePassword = { resetTemporaryPassword = generateOwnerTemporaryPassword() },
                    onConfirmSensitiveAction = ::confirmOwnerPin,
                    onTemporaryPasswordRevealed = { temporaryPassword ->
                        oneTimeSecret = OneTimeOwnerSecret("Temporary password", temporaryPassword)
                        resetTemporaryPassword = ""
                        resetOwnerPassword = ""
                    },
                    onDeleteCompleted = {
                        deleteConfirmation = ""
                        deleteOwnerPassword = ""
                    },
                    viewModel = viewModel,
                )
            }
        }

        if (selectedAccount == null) {
            OwnerDisclosureSection(
                title = "Create a managed account",
                supportingText =
                    "Only use this when you are setting someone up yourself. Most people should use an invite code.",
                expanded = showManagedAccountCreation,
                onToggle = { showManagedAccountCreation = !showManagedAccountCreation },
            ) {
                OutlinedTextField(
                    value = createUsername,
                    onValueChange = { createUsername = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = createDisplayName,
                    onValueChange = { createDisplayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display name") },
                    singleLine = true,
                )
                SensitivePasswordField(
                    value = createTemporaryPassword,
                    label = "Temporary password",
                    onValueChange = { createTemporaryPassword = it },
                )
                OutlinedButton(
                    onClick = { createTemporaryPassword = generateOwnerTemporaryPassword() },
                    enabled = !state.isActionRunning,
                ) {
                    Text("Generate temporary password")
                }
                SensitivePasswordField(
                    value = createOwnerPassword,
                    label = "Your current owner password",
                    onValueChange = { createOwnerPassword = it },
                )
                CheckboxLine(
                    checked = createRequiresPasswordChange,
                    label = "Require password change on first sign-in",
                    onCheckedChange = { createRequiresPasswordChange = it },
                )
                Button(
                    onClick = {
                        if (
                            createUsername.isBlank() ||
                            createDisplayName.isBlank() ||
                            createTemporaryPassword.length !in 12..128 ||
                            createOwnerPassword.isEmpty()
                        ) {
                            localNotice = "Complete the account fields with a 12-128 character password."
                        } else {
                            val submittedPassword = createTemporaryPassword
                            confirmDeviceCredential {
                                viewModel.createAccount(
                                    ownerPassword = createOwnerPassword,
                                    command = CreateOwnerAccountCommand(
                                        username = createUsername,
                                        displayName = createDisplayName,
                                        temporaryPassword = submittedPassword,
                                        requirePasswordChange = createRequiresPasswordChange,
                                    ),
                                    onCreated = {
                                        oneTimeSecret = OneTimeOwnerSecret("Temporary password", submittedPassword)
                                        createTemporaryPassword = ""
                                        createOwnerPassword = ""
                                    },
                                )
                            }
                        }
                    },
                    enabled = !state.isActionRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Create account")
                }
            }

            OwnerAdminInvitationSection(
                state = state,
                viewModel = viewModel,
                onInvitationCodeCreated = { invitationCode ->
                    oneTimeSecret = OneTimeOwnerSecret("Invitation code", invitationCode)
                },
                onNotice = { notice -> localNotice = notice },
            )

            OwnerAdminSystemDetails(state)
        }
    }
}

@Composable
private fun OwnerAccountCard(
    account: OwnerAccountSummary,
    enabled: Boolean,
    onSelect: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(account.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    "@${account.usernameNormalized} · ${ownerAccountStateLabel(account.state)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (account.state == RemoteAccountState.PENDING_APPROVAL) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onApprove, enabled = enabled) { Text("Approve") }
                        OutlinedButton(onClick = onReject, enabled = enabled) { Text("Reject") }
                    }
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open account settings",
            )
        }
    }
}

@Composable
internal fun OwnerDisclosureSection(
    title: String,
    supportingText: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun OwnerSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
internal fun SensitivePasswordField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
internal fun CheckboxLine(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun OwnerNoticeCard(notice: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(notice, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun OneTimeSecretCard(secret: OneTimeOwnerSecret) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${secret.label} — shown once", fontWeight = FontWeight.Bold)
            SelectionContainer {
                Text(secret.value, fontFamily = FontFamily.Monospace)
            }
            Text(
                "It clears after one minute, when this screen closes, or when the app backgrounds.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun BlockScreenshotsWhileVisible() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

internal fun generateOwnerTemporaryPassword(): String {
    val randomBytes = ByteArray(24)
    SecureRandom().nextBytes(randomBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
}

private fun ownerAccountStateLabel(state: RemoteAccountState): String = when (state) {
    RemoteAccountState.ACTIVE -> "Active"
    RemoteAccountState.DISABLED -> "Disabled"
    RemoteAccountState.PENDING_APPROVAL -> "Waiting for approval"
    RemoteAccountState.REJECTED -> "Rejected"
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun formatOwnerTimestamp(timestampMillis: Long?): String =
    timestampMillis?.let { Instant.ofEpochMilli(it).toString() } ?: "not recorded"

private data class OneTimeOwnerSecret(
    val label: String,
    val value: String,
)

private const val ONE_TIME_SECRET_VISIBILITY_MILLIS = 60_000L
