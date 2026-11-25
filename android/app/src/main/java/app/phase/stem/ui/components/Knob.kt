package app.phase.stem.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.phase.stem.audio.Param
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rotary control. Drag vertically (200 dp = full sweep, fine-grained),
 * double-tap to reset. Frequency/time parameters move logarithmically.
 * Bipolar params (pan, pitch, EQ) draw their arc from the centre.
 */
@Composable
fun Knob(
    param: Param,
    value: Float,
    color: Color,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    label: String = param.label,
    snapSemitones: Boolean = param == Param.PITCH,
) {
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val fullSweepPx = with(density) { 220.dp.toPx() }
    val currentValue by rememberUpdatedState(value)
    var dragNorm by remember { mutableFloatStateOf(-1f) }
    val norm = param.toNormalized(value)
    val shown by animateFloatAsState(norm, spring(stiffness = 900f), label = "knob")
    val bipolar = param.min < 0f

    Column(modifier.width(size + 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(size)
                .pointerInput(param) {
                    detectTapGestures(onDoubleTap = { haptics.confirm(); onChange(param.default) })
                }
                .pointerInput(param) {
                    detectVerticalDragGestures(
                        onDragStart = { dragNorm = param.toNormalized(currentValue) },
                        onDragEnd = { dragNorm = -1f },
                        onDragCancel = { dragNorm = -1f },
                    ) { change, dy ->
                        change.consume()
                        val before = dragNorm
                        dragNorm = (dragNorm - dy / fullSweepPx).coerceIn(0f, 1f)
                        var v = param.fromNormalized(dragNorm)
                        if (snapSemitones) v = v.roundToInt().toFloat()
                        // Detent ticks at the ends and the centre of bipolar knobs.
                        val crossed = (before < 0.5f) != (dragNorm < 0.5f) && bipolar
                        if (crossed || (dragNorm == 0f && before > 0f) || (dragNorm == 1f && before < 1f)) haptics.tick()
                        onChange(v)
                    }
                },
        ) {
            val stroke = this.size.minDimension * 0.08f
            val r = this.size.minDimension / 2f - stroke
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val start = 135f
            val sweep = 270f
            val arcSize = Size(r * 2, r * 2)
            val tl = Offset(c.x - r, c.y - r)
            // Track
            drawArc(PhaseColors.Edge, start, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // Value arc (with glow underlay)
            val from = if (bipolar) start + sweep / 2 else start
            val to = start + sweep * shown
            val s0 = minOf(from, to)
            val sw = kotlin.math.abs(to - from)
            if (sw > 0.5f) {
                drawArc(color.copy(alpha = 0.25f), s0, sw, false, tl, arcSize, style = Stroke(stroke * 2.6f, cap = StrokeCap.Round))
                drawArc(color, s0, sw, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // Metal cap
            val capR = r * 0.68f
            drawCircle(Brush.linearGradient(listOf(Color(0xFF3A3A44), Color(0xFF15151A)), Offset(c.x - capR, c.y - capR), Offset(c.x + capR, c.y + capR)), capR, c)
            drawCircle(Brush.linearGradient(listOf(Color(0x55FFFFFF), Color(0x05FFFFFF)), Offset(c.x - capR, c.y - capR), Offset(c.x + capR, c.y + capR)), capR, c, style = Stroke(1.2f))
            // Indicator
            val a = Math.toRadians((start + sweep * shown).toDouble())
            val p1 = Offset(c.x + cos(a).toFloat() * capR * 0.35f, c.y + sin(a).toFloat() * capR * 0.35f)
            val p2 = Offset(c.x + cos(a).toFloat() * capR * 0.9f, c.y + sin(a).toFloat() * capR * 0.9f)
            drawLine(PhaseColors.Chrome, p1, p2, strokeWidth = stroke * 0.7f, cap = StrokeCap.Round)
        }
        Spacer(Modifier.height(4.dp))
        Text(param.format(value), style = PhaseType.readout, color = if (dragNorm >= 0) color else PhaseColors.Chrome)
        Text(label.uppercase(), style = PhaseType.labelSmall, color = PhaseColors.Steel, maxLines = 1)
    }
}
