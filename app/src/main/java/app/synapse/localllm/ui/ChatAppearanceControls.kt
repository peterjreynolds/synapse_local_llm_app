package app.synapse.localllm.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.synapse.localllm.R
import app.synapse.localllm.domain.appearance.ChatBackground
import app.synapse.localllm.domain.appearance.ChatBubblePalette

internal data class ChatBubblePalettePresentation(
    val label: String,
    val outgoingBubbleColor: Color,
    val incomingBubbleColor: Color,
    val contentColor: Color,
)

internal fun ChatBubblePalette.presentation(): ChatBubblePalettePresentation = when (this) {
    ChatBubblePalette.SYNAPSE -> ChatBubblePalettePresentation(
        label = "Synapse",
        outgoingBubbleColor = Color(0xFF5A3D9E),
        incomingBubbleColor = Color(0xFF0D2A1B),
        contentColor = Color(0xFFF7F3FF),
    )
    ChatBubblePalette.OCEAN -> ChatBubblePalettePresentation(
        label = "Ocean",
        outgoingBubbleColor = Color(0xFF075985),
        incomingBubbleColor = Color(0xFF153E4A),
        contentColor = Color(0xFFF1FAFF),
    )
    ChatBubblePalette.VIOLET -> ChatBubblePalettePresentation(
        label = "Violet",
        outgoingBubbleColor = Color(0xFF6D28D9),
        incomingBubbleColor = Color(0xFF3B2354),
        contentColor = Color(0xFFFCF7FF),
    )
    ChatBubblePalette.ROSE -> ChatBubblePalettePresentation(
        label = "Rose",
        outgoingBubbleColor = Color(0xFF9F1239),
        incomingBubbleColor = Color(0xFF4A1D2A),
        contentColor = Color(0xFFFFF5F7),
    )
    ChatBubblePalette.AMBER -> ChatBubblePalettePresentation(
        label = "Amber",
        outgoingBubbleColor = Color(0xFF92400E),
        incomingBubbleColor = Color(0xFF46311B),
        contentColor = Color(0xFFFFF8E7),
    )
    ChatBubblePalette.MONOCHROME -> ChatBubblePalettePresentation(
        label = "Mono",
        outgoingBubbleColor = Color(0xFF374151),
        incomingBubbleColor = Color(0xFF20252E),
        contentColor = Color(0xFFF8FAFC),
    )
}

internal data class ChatBackgroundPresentation(
    val label: String,
    val solidColor: Color,
    @param:DrawableRes val drawableResourceId: Int? = null,
)

internal fun ChatBackground.presentation(): ChatBackgroundPresentation = when (this) {
    ChatBackground.PITCH_BLACK -> ChatBackgroundPresentation("Black", Color(0xFF050607))
    ChatBackground.GRAPHITE_SOLID -> ChatBackgroundPresentation("Graphite", Color(0xFF15171B))
    ChatBackground.DEEP_NAVY_SOLID -> ChatBackgroundPresentation("Deep navy", Color(0xFF071523))
    ChatBackground.FOREST_SOLID -> ChatBackgroundPresentation("Forest", Color(0xFF071A13))
    ChatBackground.PLUM_SOLID -> ChatBackgroundPresentation("Plum", Color(0xFF1A0C1F))
    ChatBackground.AURORA_FLOW -> ChatBackgroundPresentation(
        "Aurora flow",
        Color(0xFF07111B),
        R.drawable.chat_wallpaper_aurora_flow,
    )
    ChatBackground.MIDNIGHT_CONSTELLATION -> ChatBackgroundPresentation(
        "Constellation",
        Color(0xFF030814),
        R.drawable.chat_wallpaper_midnight_constellation,
    )
    ChatBackground.GRAPHITE_WAVES -> ChatBackgroundPresentation(
        "Graphite waves",
        Color(0xFF0A0C0F),
        R.drawable.chat_wallpaper_graphite_waves,
    )
    ChatBackground.FOREST_MIST -> ChatBackgroundPresentation(
        "Forest mist",
        Color(0xFF071714),
        R.drawable.chat_wallpaper_forest_mist,
    )
    ChatBackground.OCEAN_CAUSTICS -> ChatBackgroundPresentation(
        "Ocean caustics",
        Color(0xFF020D18),
        R.drawable.chat_wallpaper_ocean_caustics,
    )
    ChatBackground.VIOLET_NEBULA -> ChatBackgroundPresentation(
        "Violet nebula",
        Color(0xFF100818),
        R.drawable.chat_wallpaper_violet_nebula,
    )
    ChatBackground.EMBER_GEOMETRY -> ChatBackgroundPresentation(
        "Ember geometry",
        Color(0xFF0A0807),
        R.drawable.chat_wallpaper_ember_geometry,
    )
    ChatBackground.MOONLIT_TOPOGRAPHY -> ChatBackgroundPresentation(
        "Topography",
        Color(0xFF09111B),
        R.drawable.chat_wallpaper_moonlit_topography,
    )
    ChatBackground.SAGE_LINEN -> ChatBackgroundPresentation(
        "Sage linen",
        Color(0xFF151D16),
        R.drawable.chat_wallpaper_sage_linen,
    )
    ChatBackground.CYBER_RAIN -> ChatBackgroundPresentation(
        "Cyber rain",
        Color(0xFF021416),
        R.drawable.chat_wallpaper_cyber_rain,
    )
}

@Composable
internal fun ChatBackgroundLayer(background: ChatBackground) {
    val presentation = background.presentation()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(presentation.solidColor),
    ) {
        presentation.drawableResourceId?.let { resourceId ->
            Image(
                painter = painterResource(resourceId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.72f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun ChatAppearanceDialog(
    state: ChatAppearanceUiState,
    onBubblePaletteSelected: (ChatBubblePalette) -> Unit,
    onBackgroundSelected: (ChatBackground) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat appearance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Bubble colors", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChatBubblePalette.entries.forEach { palette ->
                        BubblePaletteOption(
                            palette = palette,
                            selected = palette == state.appearance.bubblePalette,
                            enabled = !state.isSaving,
                            onSelected = { onBubblePaletteSelected(palette) },
                        )
                    }
                }
                Text("Background color or image", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChatBackground.entries.forEach { background ->
                        BackgroundOption(
                            background = background,
                            selected = background == state.appearance.background,
                            enabled = !state.isSaving,
                            onSelected = { onBackgroundSelected(background) },
                        )
                    }
                }
                Text(
                    "Appearance is saved only for this conversation on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.notice?.let { notice ->
                    Text(notice, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = onReset, enabled = !state.isSaving) { Text("Reset") }
        },
    )
}

@Composable
private fun BubblePaletteOption(
    palette: ChatBubblePalette,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
) {
    val presentation = palette.presentation()
    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onSelected)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Surface(color = presentation.incomingBubbleColor, shape = CircleShape) {
                Box(Modifier.size(24.dp))
            }
            Surface(color = presentation.outgoingBubbleColor, shape = CircleShape) {
                Box(Modifier.size(24.dp))
            }
        }
        Text(presentation.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun BackgroundOption(
    background: ChatBackground,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
) {
    val presentation = background.presentation()
    Column(
        modifier = Modifier
            .width(82.dp)
            .clickable(enabled = enabled, onClick = onSelected),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(112.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(presentation.solidColor)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            presentation.drawableResourceId?.let { resourceId ->
                Image(
                    painter = painterResource(resourceId),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(presentation.label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}
