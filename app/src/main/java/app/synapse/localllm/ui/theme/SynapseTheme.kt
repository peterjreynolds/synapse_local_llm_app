package app.synapse.localllm.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SynapseColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF74F2CE),
        onPrimary = Color(0xFF03130F),
        secondary = Color(0xFF8EA7FF),
        onSecondary = Color(0xFF081226),
        tertiary = Color(0xFFFFA8C5),
        onTertiary = Color(0xFF2B0714),
        background = Color(0xFF02040A),
        onBackground = Color(0xFFEAF0FF),
        surface = Color(0xFF10131A),
        onSurface = Color(0xFFEAF0FF),
        surfaceVariant = Color(0xFF1B202B),
        onSurfaceVariant = Color(0xFFAEB6C6),
        error = Color(0xFFFF8B8B),
        onError = Color(0xFF310909),
    )

@Composable
fun SynapseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SynapseColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
