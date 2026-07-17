package app.synapse.localllm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.synapse.localllm.domain.security.AppLockPin

@Composable
internal fun AppLockScreen(
    state: AppLockUiState,
    onUnlock: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    BlockScreenshotsWhileVisible()
    ClearSensitiveInputsOnStop { pin = "" }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state.isLoading) {
            CircularProgressIndicator()
            return@Box
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text("Synapse locked", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Enter the four-digit PIN for this phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { value -> pin = normalizeAppLockPinInput(value) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PIN") },
                singleLine = true,
                enabled = !state.isActionRunning && state.isCredentialAvailable,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (pin.length == AppLockPin.PIN_LENGTH) onUnlock(pin)
                    },
                ),
            )
            state.notice?.let { notice ->
                Text(notice, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { onUnlock(pin) },
                modifier = Modifier.fillMaxWidth(),
                enabled = pin.length == AppLockPin.PIN_LENGTH &&
                    !state.isActionRunning &&
                    state.isCredentialAvailable,
            ) {
                if (state.isActionRunning) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Text("Unlock")
                }
            }
        }
    }
}

internal fun normalizeAppLockPinInput(rawValue: String): String =
    rawValue.filter(Char::isDigit).take(AppLockPin.PIN_LENGTH)
