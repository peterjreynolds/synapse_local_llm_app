package app.synapse.localllm.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sin

@Composable
internal fun RemoteVoiceRecordingIndicator(
    reducedMotion: Boolean,
    onCancel: () -> Unit,
) {
    val startedAtMillis = remember { SystemClock.elapsedRealtime() }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    var horizontalDrag by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reducedMotion) {
        while (true) {
            elapsedMillis = SystemClock.elapsedRealtime() - startedAtMillis
            delay(if (reducedMotion) 1_000L else 180L)
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(onCancel) {
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDrag = 0f },
                    onHorizontalDrag = { change, amount ->
                        horizontalDrag += amount
                        change.consume()
                    },
                    onDragEnd = {
                        if (horizontalDrag <= -SWIPE_CANCEL_DISTANCE_PX) onCancel()
                        horizontalDrag = 0f
                    },
                    onDragCancel = { horizontalDrag = 0f },
                )
            },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatAttachmentDuration(elapsedMillis),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    RemoteVoiceWaveform(
                        elapsedMillis = elapsedMillis,
                        reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f).height(22.dp).padding(start = 10.dp),
                    )
                }
                Text(
                    text = "Tap stop to attach · swipe left to cancel",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun RemoteVoiceWaveform(
    elapsedMillis: Long,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val waveformColor = MaterialTheme.colorScheme.onErrorContainer
    val phase = if (reducedMotion) 0f else elapsedMillis / 180f
    Canvas(modifier = modifier) {
        val spacing = size.width / (WAVEFORM_BAR_COUNT * 2f)
        repeat(WAVEFORM_BAR_COUNT) { index ->
            val amplitude = (0.24f + 0.7f * kotlin.math.abs(sin(index * 0.86f + phase))).toFloat()
            val barHeight = size.height * amplitude
            val x = spacing + index * spacing * 2f
            drawLine(
                color = waveformColor,
                start = Offset(x, (size.height - barHeight) / 2f),
                end = Offset(x, (size.height + barHeight) / 2f),
                strokeWidth = 2.dp.toPx(),
                alpha = 0.82f,
            )
        }
    }
}

private const val WAVEFORM_BAR_COUNT = 18
private const val SWIPE_CANCEL_DISTANCE_PX = 96f
