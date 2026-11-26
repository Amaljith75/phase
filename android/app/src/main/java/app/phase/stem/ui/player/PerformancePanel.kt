package app.phase.stem.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.phase.stem.audio.Bus
import app.phase.stem.audio.MixState
import app.phase.stem.audio.Param
import app.phase.stem.audio.PlayerController
import app.phase.stem.audio.SoundMode
import app.phase.stem.audio.SoundPad
import app.phase.stem.ui.components.HardwareKey
import app.phase.stem.ui.components.Led
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.rememberHaptics
import app.phase.stem.ui.components.stemColor
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType

private val TABS = listOf("MODES", "DJ", "EDM", "PADS")
private val NOTES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val NEON = listOf(Color(0xFFFF4D9A), Color(0xFF3EE6E0), Color(0xFFFFB547), Color(0xFF8B6CFF), Color(0xFF5BFF8F), Color(0xFFFF6A3D))

/**
 * The performance deck below the waveform: one-tap sound modes, DJ moves
 * (filter, beat rolls, tape stop, pump/gate), EDM effects (robot vocoder,
 * crush, phaser, flanger) and synthesised sound pads.
 */
@Composable
fun PerformancePanel(modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    val haptics = rememberHaptics()
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TABS.forEachIndexed { i, t ->
                val sel = i == tab
                Text(
                    t, style = PhaseType.labelSmall, textAlign = TextAlign.Center,
                    color = if (sel) PhaseColors.Void else PhaseColors.Steel,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) PhaseColors.Chrome else Color.Transparent)
                        .clickable { haptics.tick(); tab = i }
                        .padding(vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> ModesTab()
                1 -> DjTab()
                2 -> EdmTab()
                else -> PadsTab()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModesTab() {
    val player = LocalGraph.current.player
    val mix by player.mix.collectAsState()
    val current = SoundMode.detect(mix)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SoundMode.entries.forEach { m ->
                HardwareKey(m.label, active = current == m, color = if (m == SoundMode.SLOWED) Color(0xFF8B6CFF) else PhaseColors.Chrome, compact = false) {
                    player.soundMode(m)
                }
            }
        }
        // Pitch: master transpose, independent of speed.
        val pitch = mix[Bus.MASTER, Param.PITCH]
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PITCH", style = PhaseType.label, color = PhaseColors.Steel, modifier = Modifier.width(56.dp))
            HardwareKey("−", compact = true) { player.setParam(Bus.MASTER, Param.PITCH, pitch - 1f) }
            Text(
                (if (pitch > 0) "+" else "") + "%.0f st".format(pitch), style = PhaseType.readout,
                color = if (pitch != 0f) Color(0xFFFFB547) else PhaseColors.Chrome, textAlign = TextAlign.Center, modifier = Modifier.width(60.dp),
            )
            HardwareKey("+", compact = true) { player.setParam(Bus.MASTER, Param.PITCH, pitch + 1f) }
            HardwareKey("0", compact = true) { player.setParam(Bus.MASTER, Param.PITCH, 0f) }
            Spacer(Modifier.weight(1f))
            HardwareKey("KEY LOCK", active = mix.keepPitch, compact = true) { player.setKeepPitch(!mix.keepPitch) }
        }
    }
}

@Composable
private fun DjTab() {
    val player = LocalGraph.current.player
    val mix by player.mix.collectAsState()
    val state by player.state.collectAsState()
    val haptics = rememberHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FxSlider("FILTER", mix[Bus.MASTER, Param.DJ_FILTER], -1f..1f, Color(0xFF3EE6E0), centered = true) {
            player.setParam(Bus.MASTER, Param.DJ_FILTER, if (kotlin.math.abs(it) < 0.04f) 0f else it)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ROLL", style = PhaseType.label, color = PhaseColors.Steel, modifier = Modifier.width(56.dp))
            listOf(1f to "1", 0.5f to "1/2", 0.25f to "1/4", 0.125f to "1/8").forEach { (beats, label) ->
                HoldPad(label, active = state.rollBeats == beats, color = Color(0xFFFF4D9A), modifier = Modifier.weight(1f)) { down ->
                    if (down) haptics.confirm()
                    player.setRoll(if (down) beats else 0f)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HardwareKey("TAPE STOP", Modifier.weight(1f), color = PhaseColors.Danger) { haptics.heavy(); player.tapeStop() }
            HardwareKey("ECHO OUT", Modifier.weight(1f), active = mix[Bus.MASTER, Param.ECHO_MIX] > 0f, color = Color(0xFFFFB547)) {
                val on = mix[Bus.MASTER, Param.ECHO_MIX] > 0f
                player.setParam(Bus.MASTER, Param.ECHO_MIX, if (on) 0f else 0.55f)
                player.setParam(Bus.MASTER, Param.ECHO_FEEDBACK, 0.7f)
                (state.mashup?.bpm ?: state.track?.bpm)?.let { player.setParam(Bus.MASTER, Param.ECHO_TIME, 60000f / it * 0.75f) }
            }
        }
        val pumpBuses = if (state.stemMode) listOf(Bus.VOCALS, Bus.BASS, Bus.OTHER) else listOf(Bus.MASTER)
        FxSlider("PUMP", mix[pumpBuses.first(), Param.PUMP], 0f..1f, Color(0xFF8B6CFF)) { v -> pumpBuses.forEach { player.setParam(it, Param.PUMP, v) } }
        FxSlider("GATE", mix[Bus.MASTER, Param.GATE], 0f..1f, Color(0xFF5BFF8F)) { player.setParam(Bus.MASTER, Param.GATE, it) }
        if ((state.mashup?.bpm ?: state.track?.bpm) == null) {
            Text("Tempo unknown — beat effects assume 120 BPM", style = PhaseType.labelSmall, color = PhaseColors.Steel)
        }
    }
}

@Composable
private fun EdmTab() {
    val player = LocalGraph.current.player
    val mix by player.mix.collectAsState()
    val state by player.state.collectAsState()
    val robotBus = if (state.stemMode) Bus.VOCALS else Bus.MASTER
    val robotOn = mix[robotBus, Param.VOCODER] > 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HardwareKey("ROBOT VOICE", active = robotOn, color = Color(0xFFFFB547)) {
                player.setParam(robotBus, Param.VOCODER, if (robotOn) 0f else 0.9f)
            }
            HardwareKey("−", compact = true) { player.setVocoderKey((state.vocoderRoot - 1).coerceIn(36, 59), state.vocoderMinor) }
            Text(
                NOTES[state.vocoderRoot % 12] + if (state.vocoderMinor) "m" else "", style = PhaseType.readout,
                color = PhaseColors.Chrome, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp),
            )
            HardwareKey("+", compact = true) { player.setVocoderKey((state.vocoderRoot + 1).coerceIn(36, 59), state.vocoderMinor) }
            HardwareKey(if (state.vocoderMinor) "MIN" else "MAJ", compact = true) { player.setVocoderKey(state.vocoderRoot, !state.vocoderMinor) }
        }
        FxSlider("CRUSH", mix[Bus.MASTER, Param.CRUSH], 0f..1f, Color(0xFFFF6A3D)) { player.setParam(Bus.MASTER, Param.CRUSH, it) }
        FxSlider("PHASER", mix[Bus.MASTER, Param.PHASER], 0f..1f, Color(0xFF3EE6E0)) { player.setParam(Bus.MASTER, Param.PHASER, it) }
        FxSlider("FLANGER", mix[Bus.MASTER, Param.FLANGER], 0f..1f, Color(0xFF8B6CFF)) { player.setParam(Bus.MASTER, Param.FLANGER, it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HardwareKey("FRENCH HOUSE", Modifier.weight(1f), color = Color(0xFFFFB547)) { edmPreset(player, mix, state.stemMode, french = true) }
            HardwareKey("TECHNO", Modifier.weight(1f), color = Color(0xFF3EE6E0)) { edmPreset(player, mix, state.stemMode, french = false) }
            HardwareKey("CLEAR", Modifier.weight(1f)) { clearEdm(player, state.stemMode) }
        }
    }
}

private val EDM_PARAMS = listOf(Param.CRUSH, Param.PHASER, Param.FLANGER, Param.GATE, Param.PUMP, Param.VOCODER, Param.DJ_FILTER)

private fun clearEdm(player: PlayerController, stems: Boolean) {
    (if (stems) Bus.entries else listOf(Bus.MASTER)).forEach { b -> EDM_PARAMS.forEach { p -> player.setParam(b, p, p.default) } }
}

/** Starting points: robot-vocal filter-house vs. driving, pumping techno. */
private fun edmPreset(player: PlayerController, mix: MixState, stems: Boolean, french: Boolean) {
    clearEdm(player, stems)
    val body = if (stems) listOf(Bus.BASS, Bus.OTHER) else listOf(Bus.MASTER)
    if (french) {
        player.setParam(if (stems) Bus.VOCALS else Bus.MASTER, Param.VOCODER, 0.85f)
        player.setParam(Bus.MASTER, Param.PHASER, 0.35f)
        body.forEach { player.setParam(it, Param.PUMP, 0.45f) }
        player.setParam(Bus.MASTER, Param.DJ_FILTER, -0.18f)
    } else {
        body.forEach { player.setParam(it, Param.PUMP, 0.6f) }
        player.setParam(Bus.MASTER, Param.FLANGER, 0.2f)
        player.setParam(Bus.MASTER, Param.CRUSH, 0.12f)
        player.setParam(Bus.MASTER, Param.EQ_LOW, (mix[Bus.MASTER, Param.EQ_LOW] + 3f).coerceAtMost(12f))
    }
}

@Composable
private fun PadsTab() {
    val player = LocalGraph.current.player
    val haptics = rememberHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SoundPad.entries.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { c, pad ->
                    val color = NEON[(r * 3 + c) % NEON.size]
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).height(52.dp).clip(shape)
                            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.06f))))
                            .border(1.dp, color.copy(alpha = 0.7f), shape)
                            .pointerInput(pad) { detectTapGestures(onPress = { haptics.heavy(); player.triggerPad(pad) }) },
                        contentAlignment = Alignment.Center,
                    ) { Text(pad.label, style = PhaseType.label, color = color) }
                }
            }
        }
    }
}

/** Momentary pad: active only while the finger is down (beat roll). */
@Composable
private fun HoldPad(label: String, active: Boolean, color: Color, modifier: Modifier, onHold: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(38.dp).clip(shape)
            .background(if (active) color.copy(alpha = 0.3f) else Color(0xFF17171C))
            .border(1.dp, if (active) color else PhaseColors.Edge, shape)
            .pointerInput(label) {
                detectTapGestures(onPress = {
                    onHold(true)
                    tryAwaitRelease()
                    onHold(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = PhaseType.label, color = if (active) color else PhaseColors.Mist) }
}

@Composable
private fun FxSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, color: Color, centered: Boolean = false, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Led(if (centered) value != 0f else value > 0f, color, size = 6.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = PhaseType.labelSmall, color = PhaseColors.Steel, modifier = Modifier.width(56.dp))
        Slider(
            value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(thumbColor = PhaseColors.Chrome, activeTrackColor = color, inactiveTrackColor = PhaseColors.Edge),
        )
        Text(
            if (centered) when {
                value < -0.02f -> "LP"
                value > 0.02f -> "HP"
                else -> "OFF"
            } else "%.0f".format(value * 100),
            style = PhaseType.labelSmall, color = PhaseColors.Mist, textAlign = TextAlign.End, modifier = Modifier.width(30.dp),
        )
    }
}
