package app.phase.stem.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType

/**
 * Waveform scrubber: tap or drag to seek. Shows played/unplayed halves and
 * the A/B loop region. Seeks are committed on release so dragging doesn't
 * spam the engine with crossfades.
 */
@Composable
fun WaveformScrubber(
    peaks: FloatArray,
    position: Double,
    duration: Double,
    loopA: Double,
    loopB: Double,
    loopOn: Boolean,
    accent: Color,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    var scrub by remember { mutableFloatStateOf(-1f) }
    val dur by rememberUpdatedState(duration)
    val shownPos = if (scrub >= 0f) scrub * duration else position
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        haptics.tick()
                        onSeek((o.x / size.width).coerceIn(0f, 1f) * dur)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            if (scrub >= 0) onSeek(scrub.toDouble() * dur)
                            scrub = -1f
                        },
                        onDragCancel = { scrub = -1f },
                    ) { change, _ ->
                        change.consume()
                        scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val frac = if (duration > 0) (shownPos / duration).toFloat().coerceIn(0f, 1f) else 0f
            // Loop region
            if (duration > 0 && loopB > loopA) {
                val x0 = (loopA / duration).toFloat() * w
                val x1 = (loopB / duration).toFloat() * w
                drawRect(accent.copy(alpha = if (loopOn) 0.14f else 0.06f), Offset(x0, 0f), Size(x1 - x0, h))
                drawLine(accent.copy(alpha = 0.8f), Offset(x0, 0f), Offset(x0, h), 2f)
                drawLine(accent.copy(alpha = 0.8f), Offset(x1, 0f), Offset(x1, h), 2f)
            } else if (duration > 0 && loopA >= 0 && loopB == -1.0) {
                val x0 = (loopA / duration).toFloat() * w
                drawLine(accent, Offset(x0, 0f), Offset(x0, h), 2f)
            }
            val bars = if (peaks.isEmpty()) 0 else (w / 5f).toInt().coerceAtLeast(1)
            val barW = w / (bars.coerceAtLeast(1)) * 0.6f
            for (i in 0 until bars) {
                val idx = (i.toLong() * peaks.size / bars).toInt()
                val v = peaks[idx].coerceIn(0.02f, 1f)
                val bh = h * 0.92f * v
                val x = i * (w / bars)
                val played = x / w <= frac
                drawRoundRect(
                    if (played) PhaseColors.Chrome else PhaseColors.Edge,
                    Offset(x, (h - bh) / 2), Size(barW, bh), CornerRadius(barW / 2, barW / 2),
                )
            }
            if (peaks.isEmpty()) drawLine(PhaseColors.Edge, Offset(0f, h / 2), Offset(w, h / 2), 2f)
            // Playhead
            val px = frac * w
            drawLine(accent.copy(alpha = 0.35f), Offset(px, 0f), Offset(px, h), 8f)
            drawLine(PhaseColors.Chrome, Offset(px, 0f), Offset(px, h), 2.5f)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(formatTime(shownPos), style = PhaseType.labelSmall, color = if (scrub >= 0) accent else PhaseColors.Mist)
            Spacer(Modifier.weight(1f))
            Text("-" + formatTime((duration - shownPos).coerceAtLeast(0.0)), style = PhaseType.labelSmall, color = PhaseColors.Steel)
        }
    }
}
