package app.synapse.privatechat.ui.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.synapse.privatechat.R
import app.synapse.privatechat.domain.account.PrivateAccountAccessDraft
import app.synapse.privatechat.domain.account.PrivateAccountAccessMode
import app.synapse.privatechat.domain.account.PrivateAccountInputField
import app.synapse.privatechat.domain.account.PrivateAccountSessionReceipt
import app.synapse.privatechat.ui.theme.SynapsePrivateDesignSystem
import app.synapse.privatechat.ui.theme.SynapsePrivateTheme

@Composable
fun PrivateAccountAccessScreen(
    state: PrivateAccountAccessUiState,
    onSelectMode: (PrivateAccountAccessMode) -> Unit,
    onSubmit: (PrivateAccountAccessDraft) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    var displayName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var invitationCode by remember { mutableStateOf("") }

    fun clearSensitiveInputs() {
        password = ""
        passwordConfirmation = ""
        invitationCode = ""
    }

    ClearAccountSecretsOnStop(::clearSensitiveInputs)
    LaunchedEffect(state.submission) {
        if (state.submission is PrivateAccountSubmissionState.AccessConfirmed) {
            clearSensitiveInputs()
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors =
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                                MaterialTheme.colorScheme.background,
                            ),
                        radius = 980f,
                    ),
                ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = tokens.spacing.spacious, vertical = tokens.spacing.large),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandHeader()
            Spacer(Modifier.height(tokens.spacing.spacious))
            Card(
                modifier =
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                shape = RoundedCornerShape(tokens.radii.panel),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
                    ),
                border = CardDefaults.outlinedCardBorder(),
            ) {
                Column(
                    modifier = Modifier.padding(tokens.spacing.large),
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.medium),
                ) {
                    AccountModeSelector(
                        selectedMode = state.mode,
                        enabled = state.submission !is PrivateAccountSubmissionState.Submitting,
                        onSelectMode = { selectedMode ->
                            clearSensitiveInputs()
                            onSelectMode(selectedMode)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text =
                            if (state.mode == PrivateAccountAccessMode.REGISTER_WITH_INVITE) {
                                "Join your private circle"
                            } else {
                                "Welcome back"
                            },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text =
                            if (state.mode == PrivateAccountAccessMode.REGISTER_WITH_INVITE) {
                                "Create a human-only messenger account with a one-use invitation."
                            } else {
                                "Sign in to continue your private conversations."
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (state.mode == PrivateAccountAccessMode.REGISTER_WITH_INVITE) {
                        AccountTextField(
                            currentText = displayName,
                            onTextChanged = { changedText -> displayName = changedText },
                            label = "Display name",
                            field = PrivateAccountInputField.DISPLAY_NAME,
                            state = state,
                            imeAction = ImeAction.Next,
                        )
                    }
                    AccountTextField(
                        currentText = username,
                        onTextChanged = { changedText -> username = changedText },
                        label = "Username",
                        field = PrivateAccountInputField.USERNAME,
                        state = state,
                        imeAction = ImeAction.Next,
                    )
                    AccountTextField(
                        currentText = password,
                        onTextChanged = { changedText -> password = changedText },
                        label = "Password",
                        field = PrivateAccountInputField.PASSWORD,
                        state = state,
                        keyboardType = KeyboardType.Password,
                        imeAction =
                            if (state.mode == PrivateAccountAccessMode.SIGN_IN) {
                                ImeAction.Done
                            } else {
                                ImeAction.Next
                            },
                        obscureText = true,
                        onDone = {
                            if (state.mode == PrivateAccountAccessMode.SIGN_IN) {
                                onSubmit(
                                    PrivateAccountAccessDraft.SignIn(
                                        usernameInput = username,
                                        passwordInput = password,
                                    ),
                                )
                            }
                        },
                    )
                    if (state.mode == PrivateAccountAccessMode.REGISTER_WITH_INVITE) {
                        AccountTextField(
                            currentText = passwordConfirmation,
                            onTextChanged = { changedText -> passwordConfirmation = changedText },
                            label = "Confirm password",
                            field = PrivateAccountInputField.PASSWORD_CONFIRMATION,
                            state = state,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                            obscureText = true,
                        )
                        AccountTextField(
                            currentText = invitationCode,
                            onTextChanged = { changedText -> invitationCode = changedText },
                            label = "Invitation code",
                            field = PrivateAccountInputField.INVITATION_CODE,
                            state = state,
                            imeAction = ImeAction.Done,
                            onDone = {
                                onSubmit(
                                    PrivateAccountAccessDraft.RegisterWithInvite(
                                        displayNameInput = displayName,
                                        usernameInput = username,
                                        passwordInput = password,
                                        passwordConfirmationInput = passwordConfirmation,
                                        invitationCodeInput = invitationCode,
                                    ),
                                )
                            },
                        )
                    }

                    SubmissionNotice(
                        submission = state.submission,
                        onDismiss = onDismissNotice,
                    )
                    Button(
                        onClick = {
                            onSubmit(
                                if (state.mode == PrivateAccountAccessMode.REGISTER_WITH_INVITE) {
                                    PrivateAccountAccessDraft.RegisterWithInvite(
                                        displayNameInput = displayName,
                                        usernameInput = username,
                                        passwordInput = password,
                                        passwordConfirmationInput = passwordConfirmation,
                                        invitationCodeInput = invitationCode,
                                    )
                                } else {
                                    PrivateAccountAccessDraft.SignIn(
                                        usernameInput = username,
                                        passwordInput = password,
                                    )
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.submission !is PrivateAccountSubmissionState.Submitting,
                    ) {
                        if (state.submission is PrivateAccountSubmissionState.Submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(
                                if (state.mode == PrivateAccountAccessMode.REGISTER_WITH_INVITE) {
                                    "Create private account"
                                } else {
                                    "Sign in"
                                },
                            )
                        }
                    }
                    Text(
                        text =
                            "Passwords and invitation codes stay in memory only and are cleared " +
                                "when this screen leaves the foreground.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(tokens.spacing.spacious))
            Text(
                text = "Separate app · separate account · human conversations only",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrandHeader() {
    val tokens = SynapsePrivateDesignSystem.tokens
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(tokens.spacing.small),
    ) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(tokens.radii.panel),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = CardDefaults.outlinedCardBorder(),
        ) {
            Image(
                painter = painterResource(R.drawable.synapse_private_mark),
                contentDescription = stringResource(R.string.synapse_private_mark_description),
                modifier = Modifier.padding(tokens.spacing.small),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            text = "SYNAPSE PRIVATE",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Your people. Your conversations.",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun AccountModeSelector(
    selectedMode: PrivateAccountAccessMode,
    enabled: Boolean,
    onSelectMode: (PrivateAccountAccessMode) -> Unit,
) {
    val tokens = SynapsePrivateDesignSystem.tokens
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.radii.control))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(tokens.spacing.compact),
        horizontalArrangement = Arrangement.spacedBy(tokens.spacing.compact),
    ) {
        AccountModeButton(
            text = "Use invitation",
            selected = selectedMode == PrivateAccountAccessMode.REGISTER_WITH_INVITE,
            enabled = enabled,
            onClick = { onSelectMode(PrivateAccountAccessMode.REGISTER_WITH_INVITE) },
            modifier = Modifier.weight(1f),
        )
        AccountModeButton(
            text = "Sign in",
            selected = selectedMode == PrivateAccountAccessMode.SIGN_IN,
            enabled = enabled,
            onClick = { onSelectMode(PrivateAccountAccessMode.SIGN_IN) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AccountModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(text)
        }
    }
}

@Composable
private fun AccountTextField(
    currentText: String,
    onTextChanged: (String) -> Unit,
    label: String,
    field: PrivateAccountInputField,
    state: PrivateAccountAccessUiState,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction,
    obscureText: Boolean = false,
    onDone: () -> Unit = {},
) {
    val validationFailure = state.submission as? PrivateAccountSubmissionState.InvalidInput
    val fieldFailure = validationFailure?.takeIf { failure -> failure.field == field }
    OutlinedTextField(
        value = currentText,
        onValueChange = onTextChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        enabled = state.submission !is PrivateAccountSubmissionState.Submitting,
        isError = fieldFailure != null,
        supportingText =
            fieldFailure?.let { failure ->
                { Text(failure.userMessage) }
            },
        visualTransformation =
            if (obscureText) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
    )
}

@Composable
private fun SubmissionNotice(
    submission: PrivateAccountSubmissionState,
    onDismiss: () -> Unit,
) {
    val notice =
        when (submission) {
            is PrivateAccountSubmissionState.AccessDenied -> submission.userMessage
            is PrivateAccountSubmissionState.AccessConfirmed ->
                when (submission.receipt) {
                    is PrivateAccountSessionReceipt.Active ->
                        "Account access confirmed."

                    is PrivateAccountSessionReceipt.AwaitingApproval ->
                        "Account created and waiting for approval."
                }
            is PrivateAccountSubmissionState.InvalidInput,
            PrivateAccountSubmissionState.Idle,
            PrivateAccountSubmissionState.Submitting,
            -> null
            PrivateAccountSubmissionState.TransportUnavailable ->
                "Account connection is not configured in this scaffold build."
            PrivateAccountSubmissionState.UnexpectedFailure ->
                "Account access could not be verified. Try again when the connection is available."
        }
    if (notice == null) return
    val isSuccess = submission is PrivateAccountSubmissionState.AccessConfirmed
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color =
            if (isSuccess) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
            },
        onClick = onDismiss,
    ) {
        Text(
            text = notice,
            modifier = Modifier.padding(12.dp),
            color =
                if (isSuccess) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.error
                },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ClearAccountSecretsOnStop(clearSecrets: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentClearSecrets by rememberUpdatedState(clearSecrets)
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) currentClearSecrets()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            currentClearSecrets()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF080A0D)
@Composable
private fun PrivateAccountAccessScreenPreview() {
    SynapsePrivateTheme {
        PrivateAccountAccessScreen(
            state = PrivateAccountAccessUiState(),
            onSelectMode = {},
            onSubmit = {},
            onDismissNotice = {},
        )
    }
}
