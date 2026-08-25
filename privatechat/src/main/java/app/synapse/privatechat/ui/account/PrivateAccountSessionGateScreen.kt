package app.synapse.privatechat.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun PrivateAccountSessionGateScreen(
    state: PrivateAccountSessionUiState,
    onRetry: () -> Unit,
) {
    val message =
        when (state) {
            PrivateAccountSessionUiState.Restoring -> "Opening your secure session…"
            PrivateAccountSessionUiState.SigningOut -> "Destroying local conversation and session state…"
            PrivateAccountSessionUiState.TransportUnavailable ->
                "Your saved session needs verification, but the encrypted account connection is unavailable."

            PrivateAccountSessionUiState.LocalStateUnavailable ->
                "The secure session vault could not be opened. Synapse Private will not bypass or overwrite it."

            is PrivateAccountSessionUiState.VerificationRejected -> state.userMessage
            PrivateAccountSessionUiState.VerificationFailed ->
                "The saved account session could not be verified safely."

            is PrivateAccountSessionUiState.Active,
            PrivateAccountSessionUiState.SignedOut,
            -> return
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
                ).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "SYNAPSE PRIVATE",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state is PrivateAccountSessionUiState.Restoring || state is PrivateAccountSessionUiState.SigningOut) {
                CircularProgressIndicator()
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state !is PrivateAccountSessionUiState.Restoring && state !is PrivateAccountSessionUiState.SigningOut) {
                Button(onClick = onRetry) {
                    Text("Retry secure session")
                }
            }
        }
    }
}
