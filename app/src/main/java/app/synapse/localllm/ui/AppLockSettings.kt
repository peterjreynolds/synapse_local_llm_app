package app.synapse.localllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.security.AppLockPin

@Composable
internal fun AppLockSettings(
    state: AppLockUiState,
    viewModel: AppLockViewModel,
) {
    var currentPin by remember(state.isEnabled) { mutableStateOf("") }
    var newPin by remember(state.isEnabled) { mutableStateOf("") }
    var confirmPin by remember(state.isEnabled) { mutableStateOf("") }
    var showAccountReset by remember(state.isEnabled) { mutableStateOf(false) }
    var resetAccountPassword by remember(state.isEnabled) { mutableStateOf("") }
    var resetPin by remember(state.isEnabled) { mutableStateOf("") }
    var resetPinConfirmation by remember(state.isEnabled) { mutableStateOf("") }
    ClearSensitiveInputsOnStop {
        currentPin = ""
        newPin = ""
        confirmPin = ""
        resetAccountPassword = ""
        resetPin = ""
        resetPinConfirmation = ""
    }
    if (
        currentPin.isNotEmpty() ||
        newPin.isNotEmpty() ||
        confirmPin.isNotEmpty() ||
        resetAccountPassword.isNotEmpty() ||
        resetPin.isNotEmpty() ||
        resetPinConfirmation.isNotEmpty()
    ) {
        BlockScreenshotsWhileVisible()
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (state.isEnabled) "App PIN lock is on" else "App PIN lock is off",
            color = if (state.isEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            "This optional four-digit PIN protects Synapse on this phone. It is separate from your account password " +
                "and is required again whenever the app leaves the screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isEnabled) {
            AppLockPinField(
                value = currentPin,
                label = "Current PIN",
                enabled = !state.isActionRunning,
                onValueChanged = { currentPin = it },
            )
        }
        AppLockPinField(
            value = newPin,
            label = if (state.isEnabled) "New PIN" else "Create PIN",
            enabled = !state.isActionRunning,
            onValueChanged = { newPin = it },
        )
        AppLockPinField(
            value = confirmPin,
            label = "Confirm PIN",
            enabled = !state.isActionRunning,
            onValueChanged = { confirmPin = it },
        )
        state.notice?.let { notice ->
            Text(
                notice,
                color = if (appLockNoticeIsSuccess(notice)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (state.isEnabled) {
            Button(
                onClick = {
                    viewModel.changePin(currentPin, newPin, confirmPin)
                    currentPin = ""
                    newPin = ""
                    confirmPin = ""
                },
                enabled = appLockPinFieldsComplete(currentPin, newPin, confirmPin) && !state.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Text(" Change PIN")
            }
            OutlinedButton(
                onClick = {
                    viewModel.disable(currentPin)
                    currentPin = ""
                    newPin = ""
                    confirmPin = ""
                },
                enabled = currentPin.length == AppLockPin.PIN_LENGTH && !state.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Turn off PIN lock")
            }
            OutlinedButton(
                onClick = { showAccountReset = !showAccountReset },
                enabled = !state.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAccountReset) "Cancel PIN reset" else "Forgot PIN? Reset it")
            }
            if (showAccountReset) {
                Text(
                    "Confirm your signed-in account password, then choose a new PIN for this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = resetAccountPassword,
                    onValueChange = { resetAccountPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Account password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                AppLockPinField(
                    value = resetPin,
                    label = "New PIN",
                    enabled = !state.isActionRunning,
                    onValueChanged = { resetPin = it },
                )
                AppLockPinField(
                    value = resetPinConfirmation,
                    label = "Confirm new PIN",
                    enabled = !state.isActionRunning,
                    onValueChanged = { resetPinConfirmation = it },
                )
                Button(
                    onClick = {
                        viewModel.resetPinWithAccountPassword(
                            accountPassword = resetAccountPassword,
                            newRawPin = resetPin,
                            confirmation = resetPinConfirmation,
                        )
                        resetAccountPassword = ""
                        resetPin = ""
                        resetPinConfirmation = ""
                    },
                    enabled = resetAccountPassword.isNotEmpty() &&
                        resetPin.length == AppLockPin.PIN_LENGTH &&
                        resetPinConfirmation.length == AppLockPin.PIN_LENGTH &&
                        !state.isActionRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset PIN")
                }
            }
        } else {
            Button(
                onClick = {
                    viewModel.enable(newPin, confirmPin)
                    newPin = ""
                    confirmPin = ""
                },
                enabled = newPin.length == AppLockPin.PIN_LENGTH &&
                    confirmPin.length == AppLockPin.PIN_LENGTH &&
                    !state.isActionRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Text(" Turn on PIN lock")
            }
        }
    }
}

@Composable
internal fun AppLockPinField(
    value: String,
    label: String,
    enabled: Boolean,
    onValueChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { rawValue -> onValueChanged(normalizeAppLockPinInput(rawValue)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text("4 digits") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

internal fun appLockPinFieldsComplete(
    currentPin: String,
    newPin: String,
    confirmation: String,
): Boolean =
    currentPin.length == AppLockPin.PIN_LENGTH &&
        newPin.length == AppLockPin.PIN_LENGTH &&
        confirmation.length == AppLockPin.PIN_LENGTH

internal fun appLockNoticeIsSuccess(notice: String): Boolean =
    listOf("enabled", "changed", "disabled", "reset").any { marker ->
        notice.contains(marker, ignoreCase = true)
    }
