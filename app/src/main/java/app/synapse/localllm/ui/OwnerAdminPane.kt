package app.synapse.localllm.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.domain.remote.CreateOwnerAccountCommand
import app.synapse.localllm.domain.remote.CreateOwnerInvitationCommand
import app.synapse.localllm.domain.remote.OwnerAccountSummary
import app.synapse.localllm.domain.remote.OwnerCleanupJobSummary
import app.synapse.localllm.domain.remote.RemoteAccountState
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.delay

@Composable
fun OwnerAdminPane(
    viewModel: OwnerAdminViewModel,
    requestOwnerIdentityConfirmation: ((Boolean) -> Unit) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedAccount = state.accounts.firstOrNull { account ->
        account.accountUid == state.selectedAccountUid
    }
    var searchPrefix by remember { mutableStateOf("") }
    var localNotice by remember { mutableStateOf<String?>(null) }
    var oneTimeSecret by remember { mutableStateOf<OneTimeOwnerSecret?>(null) }

    var createUsername by remember { mutableStateOf("") }
    var createDisplayName by remember { mutableStateOf("") }
    var createTemporaryPassword by remember { mutableStateOf("") }
    var createOwnerPassword by remember { mutableStateOf("") }
    var createRequiresPasswordChange by remember { mutableStateOf(true) }

    var invitationLabel by remember { mutableStateOf("") }
    var invitationHours by remember { mutableStateOf("24") }
    var invitationUses by remember { mutableStateOf("1") }

    var resetTemporaryPassword by remember { mutableStateOf("") }
    var resetOwnerPassword by remember { mutableStateOf("") }
    var resetRequiresPasswordChange by remember { mutableStateOf(true) }

    var deleteConfirmation by remember { mutableStateOf("") }
    var deleteOwnerPassword by remember { mutableStateOf("") }

    BlockScreenshotsWhileVisible()
    ClearSensitiveInputsOnStop {
        createTemporaryPassword = ""
        createOwnerPassword = ""
        resetTemporaryPassword = ""
        resetOwnerPassword = ""
        deleteOwnerPassword = ""
        oneTimeSecret = null
    }
    LaunchedEffect(oneTimeSecret) {
        if (oneTimeSecret != null) {
            delay(ONE_TIME_SECRET_VISIBILITY_MILLIS)
            oneTimeSecret = null
        }
    }

    fun confirmDeviceCredential(action: () -> Unit) {
        localNotice = null
        requestOwnerIdentityConfirmation { confirmed ->
            if (confirmed) action() else localNotice = "Owner confirmation was cancelled."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Owner administration", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Service health, accounts, invitations, devices, and security history.",
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

        OwnerOperationsSection(state)

        OwnerSection("Accounts") {
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
                    selected = state.selectedAccountUid == account.accountUid,
                    enabled = !state.isActionRunning,
                    onSelect = { viewModel.selectAccount(account.accountUid) },
                    onApprove = { viewModel.reviewRegistration(account.accountUid, approve = true) },
                    onReject = { viewModel.reviewRegistration(account.accountUid, approve = false) },
                )
            }
            if (state.accounts.isEmpty()) Text("No matching accounts.")
        }

        OwnerSection("Create account") {
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

        OwnerSection("Invitations") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Require owner approval")
                Switch(
                    checked = state.registrationApprovalRequired,
                    onCheckedChange = viewModel::setRegistrationApprovalRequired,
                    enabled = !state.isActionRunning,
                )
            }
            OutlinedTextField(
                value = invitationLabel,
                onValueChange = { invitationLabel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Optional label") },
                singleLine = true,
            )
            OutlinedTextField(
                value = invitationHours,
                onValueChange = { invitationHours = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Lifetime hours") },
                singleLine = true,
            )
            OutlinedTextField(
                value = invitationUses,
                onValueChange = { invitationUses = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Maximum uses") },
                singleLine = true,
            )
            Button(
                onClick = {
                    val hours = invitationHours.toIntOrNull()
                    val uses = invitationUses.toIntOrNull()
                    if (hours == null || uses == null) {
                        localNotice = "Invitation lifetime and uses must be whole numbers."
                    } else {
                        viewModel.createInvitation(
                            CreateOwnerInvitationCommand(
                                intendedLabel = invitationLabel.trim().ifBlank { null },
                                lifetimeHours = hours,
                                maximumUses = uses,
                            ),
                        ) { invitationCode ->
                            oneTimeSecret = OneTimeOwnerSecret("Invitation code", invitationCode)
                        }
                    }
                },
                enabled = !state.isActionRunning,
            ) {
                Text("Create invitation")
            }
            state.invitations.forEach { invitation ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(invitation.intendedLabel ?: "Unlabelled invitation", fontWeight = FontWeight.SemiBold)
                        Text("${invitation.state} · ${invitation.remainingUses}/${invitation.maximumUses} uses left")
                        Text("Expires ${formatOwnerTimestamp(invitation.expiresAtMillis)}")
                        OutlinedButton(
                            onClick = { viewModel.revokeInvitation(invitation.invitationId) },
                            enabled = !state.isActionRunning && invitation.state == "ACTIVE",
                        ) {
                            Text("Revoke")
                        }
                    }
                }
            }
        }

        selectedAccount?.let { account ->
            OwnerAccountOperations(
                account = account,
                state = state,
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
                onConfirmSensitiveAction = ::confirmDeviceCredential,
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

        OwnerSection("Security history") {
            state.auditEvents.forEach { event ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(event.eventType.replace('_', ' '), fontWeight = FontWeight.SemiBold)
                    Text(formatOwnerTimestamp(event.createdAtMillis))
                    Text(
                        if (event.targetUid != null) "Target ${event.targetUid.raw.take(8)}…" else "No target account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            if (state.auditEvents.isEmpty()) Text("No security history is available.")
        }
    }
}

@Composable
private fun OwnerOperationsSection(state: OwnerAdminUiState) {
    OwnerSection("Operations") {
        Text(
            "Synapse ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                BuildConfig.SYNAPSE_APK_CHANNEL,
        )
        val operations = state.operationsSummary
        if (operations == null) {
            Text("Service status is loading.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@OwnerSection
        }
        Text("Service healthy · revision ${operations.backendRevision}")
        Text("Devices ${operations.activeDeviceCount} active / ${operations.totalDeviceCount} registered")
        Text(
            "Local outbox ${state.localOutbox.pendingCount} queued · " +
                "${state.localOutbox.inFlightCount} sending · ${state.localOutbox.failedCount} failed",
        )
        Text(
            "Notification deliveries ${operations.pendingNotificationDeliveryCount} pending · " +
                "${operations.failedNotificationDeliveryCount} with failures",
        )
        Text("${operations.activeRoomCount} active rooms")
        Text(
            "Room integrity ${operations.integrity.issueCount} issues across " +
                "${operations.integrity.checkedRoomCount} checked rooms" +
                if (operations.integrity.sampleLimitReached) " (bounded sample)" else "",
        )
        if (operations.integrity.issueCodes.isNotEmpty()) {
            Text(
                operations.integrity.issueCodes.joinToString { issueCode ->
                    issueCode.lowercase().replace('_', ' ')
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(formatCleanupStatus("Attachment cleanup", operations.attachmentCleanup))
        Text(formatCleanupStatus("Operational cleanup", operations.operationalDataCleanup))
        Text(
            "Checked ${formatOwnerTimestamp(operations.generatedAtMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatCleanupStatus(
    label: String,
    cleanup: OwnerCleanupJobSummary,
): String {
    val affected = cleanup.affectedDocumentCount?.let { count -> " · $count records" }.orEmpty()
    val completed = cleanup.lastCompletedAtMillis
        ?.let { completedAt -> " · ${formatOwnerTimestamp(completedAt)}" }
        .orEmpty()
    return "$label ${cleanup.state.name.lowercase().replace('_', ' ')}$affected$completed"
}


@Composable
private fun OwnerAccountCard(
    account: OwnerAccountSummary,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(account.displayName, fontWeight = FontWeight.SemiBold)
            Text("@${account.usernameNormalized} · ${account.state.name.replace('_', ' ')}")
            if (account.state == RemoteAccountState.PENDING_APPROVAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApprove, enabled = enabled) { Text("Approve") }
                    OutlinedButton(onClick = onReject, enabled = enabled) { Text("Reject") }
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
