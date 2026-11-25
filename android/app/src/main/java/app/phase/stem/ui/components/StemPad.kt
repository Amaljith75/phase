package app.phase.stem.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import app.phase.stem.audio.Bus
import app.phase.stem.data.GestureAction
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** Which corner of the 2x2 instrument a pad occupies (glow points to the centre). */
enum class PadCorner { TopStart, TopEnd, BottomStart, BottomEnd }

private const val SEGMENTS = 12
private const val MAX_GAIN = 1.5f

/**
 * One stem of the instrument. Gestures (configurable in Settings):
 *  - drag up/down: volume, with a haptic detent per LED segment
 *  - swipe right / left: solo / mute (by default)
 *  - double tap: mute toggle; long press: effects
 * Activity glow is driven straight from the engine meters inside the draw
 * phase, so it animates at display rate without recomposition.
 */
@Composable
fun StemPad(
    bus: Bus,
    color: Color,
    corner: PadCorner,
    gain: Float,
    muted: Boolean,
    soloed: Boolean,
    audible: Boolean,
    enabled: Boolean,
    effects: List<String>,
    level: FloatState,
    onGain: (Float) -> Unit,
    onAction: (GestureAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = appSettings()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val currentGain by rememberUpdatedState(gain)
    val offsetX = remember { Animatable(0f) }
    var dragging by remember { mutableFloatStateOf(0f) } // 1 while vertical drag active
    val dim by animateFloatAsState(if (!enabled) 0.35f else if (audible) 1f else 0.38f, spring(stiffness = 400f), label = "dim")
    val shape = RoundedCornerShape(
        topStart = if (corner == PadCorner.TopStart) 34.dp else 18.dp,
        topEnd = if (corner == PadCorner.TopEnd) 34.dp else 18.dp,
        bottomStart = if (corner == PadCorner.BottomStart) 34.dp else 18.dp,
        bottomEnd = if (corner == PadCorner.BottomEnd) 34.dp else 18.dp,
    )
    val towardCentre = when (corner) {
        PadCorner.TopStart -> Offset(1f, 1f)
        PadCorner.TopEnd -> Offset(0f, 1f)
        PadCorner.BottomStart -> Offset(1f, 0f)
        PadCorner.BottomEnd -> Offset(0f, 0f)
    }
    val ledOnStart = corner == PadCorner.TopStart || corner == PadCorner.BottomStart

    Box(
        modifier
            .graphicsLayer { translationX = offsetX.value * 0.35f }
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1C1C22), Color(0xFF0F0F13))))
            .drawBehind {
                // Audio-reactive glow: radius & intensity follow the stem RMS.
                val lv = (sqrt(level.floatValue.coerceIn(0f, 1f)) * 1.6f).coerceIn(0f, 1f) * dim
                val c = Offset(size.width * towardCentre.x, size.height * towardCentre.y)
                val r = size.minDimension * (0.55f + 0.55f * lv)
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.10f + 0.45f * lv), Color.Transparent), c, r), r, c)
            }
            .border(1.dp, Brush.linearGradient(listOf(color.copy(alpha = 0.35f * dim + 0.05f), Color(0x14FFFFFF))), shape)
            .pointerInput(bus, enabled, settings) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                val swipeThreshold = size.width * 0.22f
                var lastTapAt = 0L
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dx = 0f
                    var dy = 0f
                    var axis = 0 // 1 vertical, 2 horizontal
                    var released = false
                    val moved = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                            if (ch.changedToUp()) { released = true; return@withTimeoutOrNull false }
                            val d = ch.positionChange()
                            dx += d.x
                            dy += d.y
                            if (abs(dx) > slop || abs(dy) > slop) {
                                axis = if (abs(dy) >= abs(dx)) 1 else 2
                                ch.consume()
                                return@withTimeoutOrNull true
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") false
                    }
                    if (moved == null) { // long press
                        haptics.heavy()
                        onAction(settings.longPress)
                        do {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { it.consume() }
                        } while (ev.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    if (released) {
                        val now = down.uptimeMillis
                        if (now - lastTapAt < viewConfiguration.doubleTapTimeoutMillis) {
                            lastTapAt = 0L
                            haptics.confirm()
                            onAction(settings.doubleTap)
                        } else lastTapAt = now
                        return@awaitEachGesture
                    }
                    if (moved != true) return@awaitEachGesture
                    if (axis == 1 && !settings.verticalVolume) axis = 0
                    val startGain = currentGain
                    var lastSeg = (startGain / MAX_GAIN * SEGMENTS).toInt()
                    if (axis == 1) dragging = 1f
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.changedToUp()) break
                        val d = ch.positionChange()
                        ch.consume()
                        dx += d.x
                        dy += d.y
                        when (axis) {
                            1 -> {
                                // Full pad height ~= full volume range.
                                val g = (startGain - dy / size.height * MAX_GAIN).coerceIn(0f, MAX_GAIN)
                                val seg = (g / MAX_GAIN * SEGMENTS).toInt()
                                if (seg != lastSeg) { haptics.tick(); lastSeg = seg }
                                onGain(g)
                            }
                            2 -> scope.launch { offsetX.snapTo(dx) }
                        }
                    }
                    dragging = 0f
                    if (axis == 2) {
                        when {
                            dx > swipeThreshold -> { haptics.confirm(); onAction(settings.swipeRight) }
                            dx < -swipeThreshold -> { haptics.confirm(); onAction(settings.swipeLeft) }
                        }
                        scope.launch { offsetX.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 500f)) }
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxSize().padding(14.dp)) {
            if (ledOnStart) {
                LedColumn(gain, color, dim, dragging > 0f)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = if (ledOnStart) Alignment.Start else Alignment.End) {
                Text(bus.label.uppercase(), style = PhaseType.label, color = color.copy(alpha = 0.55f + 0.45f * dim))
                Spacer(Modifier.height(2.dp))
                Text("${(gain * 100).toInt()}", style = PhaseType.title.copy(fontFamily = PhaseType.readout.fontFamily), color = PhaseColors.Chrome.copy(alpha = 0.4f + 0.6f * dim))
                Spacer(Modifier.weight(1f))
                if (effects.isNotEmpty()) {
                    Text(effects.take(3).joinToString(" "), style = PhaseType.labelSmall, color = color.copy(alpha = 0.8f), maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge("M", muted, PhaseColors.Danger)
                    Badge("S", soloed, color)
                }
            }
            if (!ledOnStart) {
                Spacer(Modifier.width(12.dp))
                LedColumn(gain, color, dim, dragging > 0f)
            }
        }
        // Swipe hint while dragging horizontally.
        val ox = offsetX.value
        if (abs(ox) > 12f) {
            val right = ox > 0
            val action = if (right) settings.swipeRight else settings.swipeLeft
            Text(
                (if (right) "${action.short()} →" else "← ${action.short()}"),
                style = PhaseType.label, color = PhaseColors.Chrome.copy(alpha = (abs(ox) / 160f).coerceIn(0f, 1f)),
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun GestureAction.short() = when (this) {
    GestureAction.TOGGLE_MUTE -> "MUTE"
    GestureAction.TOGGLE_SOLO -> "SOLO"
    GestureAction.OPEN_EFFECTS -> "FX"
    GestureAction.RESET_STEM -> "RESET"
    GestureAction.NONE -> ""
}

@Composable
private fun Badge(text: String, on: Boolean, color: Color) {
    val shape = RoundedCornerShape(5.dp)
    Text(
        text, style = PhaseType.labelSmall, color = if (on) PhaseColors.Void else PhaseColors.Edge,
        modifier = Modifier.clip(shape).background(if (on) color else Color.Transparent).border(1.dp, if (on) color else PhaseColors.Edge, shape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Vertical LED ladder showing the stem volume (top segment = 150%). */
@Composable
private fun LedColumn(gain: Float, color: Color, dim: Float, active: Boolean) {
    val lit = gain / MAX_GAIN * SEGMENTS
    val unity = (1f / MAX_GAIN * SEGMENTS).toInt()
    Box(
        Modifier.width(10.dp).fillMaxHeight().drawBehind {
            val gap = size.height * 0.018f
            val segH = (size.height - gap * (SEGMENTS - 1)) / SEGMENTS
            for (i in 0 until SEGMENTS) {
                val y = size.height - (i + 1) * segH - i * gap
                val fill = (lit - i).coerceIn(0f, 1f)
                val base = if (i >= unity) Color(0xFFFFFFFF) else color
                drawRoundRect(PhaseColors.Edge, Offset(0f, y), Size(size.width, segH), CornerRadius(3f, 3f))
                if (fill > 0f) {
                    val a = (0.35f + 0.65f * fill) * dim * (if (active) 1f else 0.9f)
                    drawRoundRect(base.copy(alpha = a), Offset(0f, y), Size(size.width, segH), CornerRadius(3f, 3f))
                }
            }
        },
    )
}
