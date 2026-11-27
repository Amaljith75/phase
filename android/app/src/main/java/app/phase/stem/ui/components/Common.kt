package app.phase.stem.ui.components

import android.graphics.BitmapFactory
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phase.stem.AppGraph
import app.phase.stem.audio.Bus
import app.phase.stem.data.AppSettings
import app.phase.stem.data.TrackEntity
import app.phase.stem.data.TrackStatus
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

@Composable
fun ProvideGraph(graph: AppGraph, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalGraph provides graph, content = content)

@Composable
fun appSettings(): AppSettings = LocalGraph.current.settings.state.collectAsState().value

@Composable
fun stemColor(bus: Bus): Color {
    val s = appSettings()
    return if (bus == Bus.MASTER) PhaseColors.Chrome else Color(s.stemColors[bus.index])
}

/** Haptic vocabulary for the instrument, gated by the user's setting. */
class Haptics(private val view: View, private val enabled: Boolean) {
    private fun perform(c: Int) { if (enabled) view.performHapticFeedback(c) }
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    fun reject() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    fun heavy() = perform(HapticFeedbackConstants.LONG_PRESS)
    fun key() = perform(HapticFeedbackConstants.VIRTUAL_KEY)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = appSettings().haptics
    return remember(view, enabled) { Haptics(view, enabled) }
}

/** Dark glass surface with a hairline highlight edge. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    glow: Color? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1B21), Color(0xFF111115))))
            .then(
                if (glow != null) Modifier.background(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent)),
                ) else Modifier,
            )
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF))), shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = PhaseType.label, color = PhaseColors.Steel, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Small LED dot used as an "active" indicator. */
@Composable
fun Led(on: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (on) color else PhaseColors.Edge)
            .then(if (on) Modifier.border(1.dp, color.copy(alpha = 0.4f), CircleShape) else Modifier),
    )
}

/** Pill button styled like a hardware key. */
@Composable
fun HardwareKey(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    color: Color = PhaseColors.accent,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (active) color.copy(alpha = 0.16f) else Color(0xFF17171C))
            .border(1.dp, if (active) color.copy(alpha = 0.7f) else PhaseColors.Edge, shape)
            .clickable(enabled = enabled) { haptics.key(); onClick() }
            .padding(horizontal = if (compact) 4.dp else 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (active) color else if (enabled) PhaseColors.Mist else PhaseColors.Edge, modifier = Modifier.size(if (compact) 14.dp else 16.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(if (compact) 3.dp else 6.dp))
        }
        if (text.isNotEmpty()) {
            Text(
                text, style = if (compact) PhaseType.labelSmall.copy(letterSpacing = 0.5.sp) else PhaseType.labelSmall,
                color = if (active) color else if (enabled) PhaseColors.Mist else PhaseColors.Edge, maxLines = 1, softWrap = false,
            )
        }
    }
}

@Composable
fun MetalTitle(text: String, style: TextStyle = PhaseType.display, modifier: Modifier = Modifier) {
    Text(text, style = style.copy(brush = PhaseColors.metal), modifier = modifier)
}

/** Loads cached artwork off the main thread; falls back to a generated tile. */
@Composable
fun TrackArt(track: TrackEntity?, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    val graph = LocalGraph.current
    val bmp by produceState<ImageBitmap?>(null, track?.id, track?.hasArtwork) {
        value = if (track?.hasArtwork == true) withContext(Dispatchers.IO) {
            runCatching {
                val f = graph.storage.artwork(track.id)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                var sample = 1
                while (opts.outWidth / sample > 512) sample *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        } else null
    }
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape).background(PhaseColors.Slate).border(1.dp, Color(0x22FFFFFF), shape), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else {
            val hue = ((track?.title?.hashCode() ?: 0) and 0xFFFFFF)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF2A2A33), Color(0xFF000000 or hue.toLong()).copy(alpha = 0.35f), Color(0xFF101014))),
                ),
            )
            Icon(Icons.Rounded.GraphicEq, null, tint = PhaseColors.Mist.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
        }
    }
}

/** Compact processing status pill for a track. */
@Composable
fun StatusPill(t: TrackEntity, modifier: Modifier = Modifier) {
    val (text, color) = when (t.status) {
        TrackStatus.IMPORTING -> "IMPORT ${(t.progress * 100).toInt()}%" to PhaseColors.Mist
        TrackStatus.READY -> "FULL MIX" to PhaseColors.Steel
        TrackStatus.QUEUED -> "QUEUED" to PhaseColors.Mist
        TrackStatus.SEPARATING -> "AI ${(t.progress * 100).toInt()}%" to Color(0xFFFFB547)
        TrackStatus.SEPARATED -> "4 STEMS" to PhaseColors.Ok
        TrackStatus.FAILED -> "FAILED" to PhaseColors.Danger
    }
    val shape = RoundedCornerShape(6.dp)
    Text(
        text, style = PhaseType.labelSmall, color = color, maxLines = 1,
        modifier = modifier.clip(shape).border(1.dp, color.copy(alpha = 0.5f), shape).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun TrackRow(
    t: TrackEntity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackArt(t, Modifier.size(52.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(t.title, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(t.artist, formatTime(t.durationMs / 1000.0).takeIf { t.durationMs > 0 }).joinToString(" · ").ifBlank { "Unknown artist" },
                        style = PhaseType.labelSmall.copy(letterSpacing = 0.5.sp), color = PhaseColors.Steel, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusPill(t)
                }
            }
            trailing?.invoke()
        }
        if (t.status == TrackStatus.SEPARATING || t.status == TrackStatus.IMPORTING) {
            LinearProgressIndicator(
                progress = { t.progress }, color = Color(0xFFFFB547), trackColor = PhaseColors.Edge,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 64.dp).height(2.dp),
            )
            t.statusMessage?.let { Text(it, style = PhaseType.labelSmall, color = PhaseColors.Steel, modifier = Modifier.padding(start = 64.dp, top = 4.dp)) }
        } else if (t.status == TrackStatus.FAILED && t.statusMessage != null) {
            Text(t.statusMessage, style = PhaseType.labelSmall, color = PhaseColors.Danger, maxLines = 2, modifier = Modifier.padding(start = 64.dp, top = 4.dp))
        }
    }
}

fun formatTime(sec: Double): String {
    if (sec.isNaN() || sec < 0) return "0:00"
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = PhaseColors.Steel, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = PhaseType.title, color = PhaseColors.Chrome)
        Spacer(Modifier.height(6.dp))
        Text(body, style = PhaseType.body, color = PhaseColors.Steel)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun BoxScope.CenterSpinnerText(text: String) {
    Text(text, style = PhaseType.label, color = PhaseColors.Steel, modifier = Modifier.align(Alignment.Center))
}
