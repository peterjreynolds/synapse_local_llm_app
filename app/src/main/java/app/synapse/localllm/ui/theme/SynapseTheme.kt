package app.synapse.localllm.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SynapseColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF7EE787),
        onPrimary = Color(0xFF031008),
        secondary = Color(0xFF00D4FF),
        onSecondary = Color(0xFF041116),
        tertiary = Color(0xFFD8C690),
        onTertiary = Color(0xFF181205),
        background = Color(0xFF050505),
        onBackground = Color(0xFFF4F1E8),
        surface = Color(0xFF0B0D0C),
        onSurface = Color(0xFFF4F1E8),
        surfaceVariant = Color(0xFF112016),
        onSurfaceVariant = Color(0xFFBFD0BE),
        error = Color(0xFFF39A80),
        onError = Color(0xFF2D0803),
    )

@Composable
fun SynapseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SynapseColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
