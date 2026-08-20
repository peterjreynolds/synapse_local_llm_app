package app.synapse.privatechat.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class SynapsePrivateSpacing(
    val hairline: Dp = 1.dp,
    val compact: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val spacious: Dp = 24.dp,
    val expansive: Dp = 32.dp,
)

@Immutable
data class SynapsePrivateRadii(
    val compact: Dp = 8.dp,
    val control: Dp = 14.dp,
    val bubble: Dp = 18.dp,
    val panel: Dp = 22.dp,
    val pill: Dp = 28.dp,
)

@Immutable
data class SynapsePrivateDesignTokens(
    val spacing: SynapsePrivateSpacing = SynapsePrivateSpacing(),
    val radii: SynapsePrivateRadii = SynapsePrivateRadii(),
    val minimumTouchTarget: Dp = 48.dp,
)

internal val LocalSynapsePrivateDesignTokens =
    staticCompositionLocalOf { SynapsePrivateDesignTokens() }

object SynapsePrivateDesignSystem {
    val tokens: SynapsePrivateDesignTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalSynapsePrivateDesignTokens.current
}
