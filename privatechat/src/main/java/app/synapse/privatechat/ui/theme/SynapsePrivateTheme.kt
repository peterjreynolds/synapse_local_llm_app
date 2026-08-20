package app.synapse.privatechat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val SynapsePrivateColors =
    darkColorScheme(
        primary = Color(0xFF62E6FF),
        onPrimary = Color(0xFF001F27),
        primaryContainer = Color(0xFF083D49),
        onPrimaryContainer = Color(0xFFC4F5FF),
        secondary = Color(0xFFB9A7FF),
        onSecondary = Color(0xFF211653),
        secondaryContainer = Color(0xFF332766),
        onSecondaryContainer = Color(0xFFE7DFFF),
        tertiary = Color(0xFFB7D9F5),
        onTertiary = Color(0xFF102638),
        background = Color(0xFF080A0D),
        onBackground = Color(0xFFE9EEF4),
        surface = Color(0xFF0E1116),
        onSurface = Color(0xFFE9EEF4),
        surfaceVariant = Color(0xFF1A2029),
        onSurfaceVariant = Color(0xFFB8C2CE),
        surfaceContainerLowest = Color(0xFF06080A),
        surfaceContainerLow = Color(0xFF0B0E12),
        surfaceContainer = Color(0xFF11161C),
        surfaceContainerHigh = Color(0xFF171D25),
        surfaceContainerHighest = Color(0xFF202833),
        outline = Color(0xFF667381),
        outlineVariant = Color(0xFF303A45),
        error = Color(0xFFF39A80),
        onError = Color(0xFF2D0803),
    )

private val SynapsePrivateTypography =
    Typography(
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                letterSpacing = (-0.3).sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
    )

private val SynapsePrivateShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(30.dp),
    )

@Composable
fun SynapsePrivateTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSynapsePrivateDesignTokens provides SynapsePrivateDesignTokens(),
    ) {
        MaterialTheme(
            colorScheme = SynapsePrivateColors,
            typography = SynapsePrivateTypography,
            shapes = SynapsePrivateShapes,
            content = content,
        )
    }
}
