package app.phase.stem.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.phase.stem.audio.Bus
import app.phase.stem.audio.MixState
import app.phase.stem.audio.Param
import app.phase.stem.ui.components.GlassPanel
import app.phase.stem.ui.components.HardwareKey
import app.phase.stem.ui.components.Knob
import app.phase.stem.ui.components.Led
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.MetalTitle
import app.phase.stem.ui.components.rememberHaptics
import app.phase.stem.ui.components.stemColor
import app.phase.stem.ui.player.PresetsDialog
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType

private data class FxSection(val title: String, val params: List<Param>, val activeWhen: (MixState, Bus) -> Boolean)

private val sections = listOf(
    FxSection("Level", listOf(Param.GAIN, Param.PAN)) { m, b -> m[b, Param.GAIN] != 1f || m[b, Param.PAN] != 0f },
    FxSection("Filters", listOf(Param.HPF_FREQ, Param.LPF_FREQ)) { m, b -> m[b, Param.HPF_FREQ] > 21f || m[b, Param.LPF_FREQ] < 19900f },
    FxSection("Reverb", listOf(Param.REVERB_MIX, Param.REVERB_SIZE, Param.REVERB_DAMP)) { m, b -> m[b, Param.REVERB_MIX] > 0f },
    FxSection("Delay", listOf(Param.DELAY_MIX, Param.DELAY_TIME, Param.DELAY_FEEDBACK)) { m, b -> m[b, Param.DELAY_MIX] > 0f },
    FxSection("Echo · ping-pong", listOf(Param.ECHO_MIX, Param.ECHO_TIME, Param.ECHO_FEEDBACK)) { m, b -> m[b, Param.ECHO_MIX] > 0f },
    FxSection("Chorus", listOf(Param.CHORUS_MIX, Param.CHORUS_RATE, Param.CHORUS_DEPTH)) { m, b -> m[b, Param.CHORUS_MIX] > 0f },
    FxSection("Distortion", listOf(Param.DRIVE, Param.DIST_MIX)) { m, b -> m[b, Param.DIST_MIX] > 0f },
    FxSection("Pitch shift", listOf(Param.PITCH)) { m, b -> m[b, Param.PITCH] != 0f },
    FxSection("Equalizer", listOf(Param.EQ_LOW, Param.EQ_MID, Param.EQ_HIGH)) { m, b ->
        listOf(Param.EQ_LOW, Param.EQ_MID, Param.EQ_HIGH).any { p -> m[b, p] != 0f }
    },
)

@Composable
fun EffectsScreen(initialBus: Bus, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val player = graph.player
    val mix by player.mix.collectAsState()
    val state by player.state.collectAsState()
    val haptics = rememberHaptics()
    var bus by remember { mutableStateOf(if (!state.stemMode) Bus.MASTER else initialBus) }
    var presets by remember { mutableStateOf(false) }
    val color = stemColor(bus)

    Column(Modifier.fillMaxSize().background(PhaseColors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PhaseColors.Mist) }
            MetalTitle("FX STUDIO", PhaseType.title.copy(letterSpacing = PhaseType.label.letterSpacing), Modifier.weight(1f))
            IconButton(onClick = { presets = true }) { Icon(Icons.Rounded.Bookmarks, "Presets", tint = PhaseColors.Mist) }
            IconButton(onClick = { haptics.reject(); player.resetBus(bus) }) { Icon(Icons.Rounded.RestartAlt, "Reset bus", tint = PhaseColors.Mist) }
        }
        // Bus selector
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Bus.entries.forEach { b ->
                val c = stemColor(b)
                val enabled = b == Bus.MASTER || state.stemMode
                val sel = b == bus
                val shape = RoundedCornerShape(12.dp)
                Column(
                    Modifier.weight(1f).clip(shape)
                        .background(if (sel) c.copy(alpha = 0.16f) else Color(0xFF15151A))
                        .border(1.dp, if (sel) c else PhaseColors.Edge, shape)
                        .clickable(enabled = enabled) { haptics.key(); bus = b }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(b.short, style = PhaseType.label, color = if (!enabled) PhaseColors.Edge else if (sel) c else PhaseColors.Mist)
                    Spacer(Modifier.height(4.dp))
                    Led(mix.activeEffects(b).isNotEmpty(), c, size = 5.dp)
                }
            }
        }
        if (!state.stemMode) {
            Text(
                "Stem effects unlock after AI separation. Master effects apply to the full mix.",
                style = PhaseType.labelSmall, color = PhaseColors.Steel, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (bus.isStem) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HardwareKey("MUTE", active = mix.isMuted(bus), color = PhaseColors.Danger) { player.toggleMute(bus) }
                    HardwareKey("SOLO", active = mix.isSoloed(bus), color = color) { player.toggleSolo(bus) }
                }
            } else {
                MasterTransport(mix, color)
            }
            sections.forEach { s ->
                val active = s.activeWhen(mix, bus)
                GlassPanel(Modifier.fillMaxWidth(), glow = if (active) color else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Led(active, color)
                        Spacer(Modifier.width(8.dp))
                        Text(s.title.uppercase(), style = PhaseType.label, color = if (active) PhaseColors.Chrome else PhaseColors.Steel, modifier = Modifier.weight(1f))
                        Text(
                            "RESET", style = PhaseType.labelSmall, color = PhaseColors.Steel,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { haptics.tick(); player.resetEffects(bus, s.params) }.padding(6.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        s.params.forEach { p ->
                            Knob(p, mix[bus, p], color, onChange = { player.setParam(bus, p, it) }, label = knobLabel(p))
                        }
                    }
                }
            }
            Text(
                "Drag knobs vertically · double-tap a knob to reset · all processing is real-time in the native engine",
                style = PhaseType.labelSmall, color = PhaseColors.Edge, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
    if (presets) PresetsDialog(onDismiss = { presets = false })
}

private fun knobLabel(p: Param) = when (p) {
    Param.GAIN -> "Volume"
    Param.HPF_FREQ -> "Low cut"
    Param.LPF_FREQ -> "High cut"
    Param.REVERB_SIZE -> "Size"
    Param.PITCH -> "Semitones"
    else -> p.label
}

@Composable
private fun MasterTransport(mix: MixState, color: Color) {
    val player = LocalGraph.current.player
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("MASTER TRANSPORT", style = PhaseType.label, color = PhaseColors.Steel)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Speed %.2fx".format(mix.speed), style = PhaseType.readout, color = PhaseColors.Chrome, modifier = Modifier.weight(1f))
            listOf(0.75f, 1f, 1.25f).forEach { s ->
                HardwareKey("%.2f".format(s), Modifier.padding(start = 6.dp), active = mix.speed == s, color = color) { player.setSpeed(s) }
            }
        }
        androidx.compose.material3.Slider(
            value = mix.speed, onValueChange = { player.setSpeed(Math.round(it * 20) / 20f) }, valueRange = 0.5f..2f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = PhaseColors.Chrome, activeTrackColor = color),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep pitch when changing speed", style = PhaseType.body, modifier = Modifier.weight(1f))
            Switch(mix.keepPitch, { player.setKeepPitch(it) }, colors = SwitchDefaults.colors(checkedTrackColor = color))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reverse playback", style = PhaseType.body, modifier = Modifier.weight(1f))
            Switch(mix.reverse, { player.setReverse(it) }, colors = SwitchDefaults.colors(checkedTrackColor = color))
        }
    }
}
