package app.synapse.localllm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
internal fun RemoteConfirmedDeletedMessageEffect(
    playEffect: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val effectRequested by rememberUpdatedState(playEffect)
    val progress = remember { Animatable(if (playEffect) 0f else 1f) }
    LaunchedEffect(playEffect, reducedMotion) {
        if (!effectRequested) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = if (reducedMotion) 260 else CONFIRMED_DELETION_EFFECT_DURATION_MILLIS,
                    easing = LinearEasing,
                ),
            )
        }
    }
    val currentProgress = progress.value
    Box(
        modifier = modifier
            .widthIn(min = 132.dp)
            .height(44.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (playEffect && currentProgress < 0.82f) {
            if (reducedMotion) {
                Text(
                    text = "Message deleted",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .alpha((1f - currentProgress).coerceIn(0f, 1f))
                        .graphicsLayer {
                            scaleX = 1f - (0.08f * currentProgress)
                            scaleY = scaleX
                        },
                )
            } else {
                val particleColor = MaterialTheme.colorScheme.primary
                val secondaryColor = MaterialTheme.colorScheme.secondary
                Canvas(modifier = Modifier.matchParentSize()) {
                    repeat(DELETION_PARTICLE_COUNT) { index ->
                        val lane = index % 4
                        val sourceX = size.width * (0.12f + (index / 4) * 0.17f)
                        val sourceY = size.height * (0.24f + lane * 0.17f)
                        val drift = size.width * currentProgress * (0.12f + lane * 0.035f)
                        val lift = size.height * currentProgress * ((lane - 1.5f) * 0.16f)
                        drawCircle(
                            color = if (index % 2 == 0) particleColor else secondaryColor,
                            radius = (3.2f - currentProgress * 2.2f).coerceAtLeast(0.8f),
                            center = androidx.compose.ui.geometry.Offset(sourceX + drift, sourceY + lift),
                            alpha = (1f - currentProgress).coerceIn(0f, 1f),
                        )
                    }
                    drawLine(
                        color = particleColor,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height * 0.52f),
                        end = androidx.compose.ui.geometry.Offset(size.width * currentProgress, size.height * 0.48f),
                        strokeWidth = 2.dp.toPx(),
                        alpha = (1f - currentProgress * 0.75f).coerceIn(0f, 1f),
                    )
                }
            }
        } else {
            Text(
                text = "Message deleted",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal const val CONFIRMED_DELETION_EFFECT_DURATION_MILLIS = 900
private const val DELETION_PARTICLE_COUNT = 16
