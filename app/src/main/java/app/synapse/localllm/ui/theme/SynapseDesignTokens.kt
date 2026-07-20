package app.synapse.localllm.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class SynapseSpacing(
    val hairline: Dp = 1.dp,
    val compact: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 12.dp,
    val large: Dp = 16.dp,
    val spacious: Dp = 24.dp,
)

@Immutable
data class SynapseRadii(
    val compact: Dp = 8.dp,
    val control: Dp = 14.dp,
    val bubble: Dp = 18.dp,
    val panel: Dp = 22.dp,
    val pill: Dp = 28.dp,
)

@Immutable
data class SynapseDepth(
    val resting: Dp = 0.dp,
    val raised: Dp = 2.dp,
    val overlay: Dp = 10.dp,
)

@Immutable
data class SynapseMotion(
    val quickMillis: Int = 120,
    val standardMillis: Int = 220,
    val expressiveMillis: Int = 360,
)

@Immutable
data class SynapseIconSizes(
    val compact: Dp = 18.dp,
    val standard: Dp = 22.dp,
    val prominent: Dp = 28.dp,
)

@Immutable
data class SynapseDesignTokens(
    val spacing: SynapseSpacing = SynapseSpacing(),
    val radii: SynapseRadii = SynapseRadii(),
    val depth: SynapseDepth = SynapseDepth(),
    val motion: SynapseMotion = SynapseMotion(),
    val icons: SynapseIconSizes = SynapseIconSizes(),
    val minimumTouchTarget: Dp = 48.dp,
)

internal val LocalSynapseDesignTokens = staticCompositionLocalOf { SynapseDesignTokens() }

object SynapseDesignSystem {
    val tokens: SynapseDesignTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalSynapseDesignTokens.current
}
