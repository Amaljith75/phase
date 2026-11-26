package app.phase.stem.ui.mashup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.phase.stem.audio.Bus
import app.phase.stem.audio.DeckState
import app.phase.stem.audio.MashupState
import app.phase.stem.data.TrackEntity
import app.phase.stem.ui.components.GlassPanel
import app.phase.stem.ui.components.HardwareKey
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.MetalTitle
import app.phase.stem.ui.components.TrackArt
import app.phase.stem.ui.components.formatTime
import app.phase.stem.ui.components.rememberHaptics
import app.phase.stem.ui.components.stemColor
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import java.util.UUID

fun deckFrom(t: TrackEntity) = DeckState(t.id, t.title, t.bpm, t.beatOffset)

/**
 * Mashup studio: layer up to four separated songs on one beat-synced
 * timeline and balance every stem of every song. The stem pads, effects,
 * recording and export in the player then work on the whole mashup.
 *
 * [mashupId]: an existing mashup, or null to start one from [seedTrackId].
 */
@Composable
fun MashupScreen(mashupId: String?, seedTrackId: String?, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val graph = LocalGraph.current
    val player = graph.player
    val state by player.state.collectAsState()
    val haptics = rememberHaptics()
    var adding by remember { mutableStateOf(false) }

    // Runs once per screen instance (survives navigating to the player and back).
    var initialised by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(mashupId, seedTrackId) {
        if (initialised) return@LaunchedEffect
        initialised = true
        val loaded = player.state.value.mashup
        if (mashupId != null) {
            if (loaded?.id == mashupId) return@LaunchedEffect
            val e = graph.db.mashups().get(mashupId) ?: return@LaunchedEffect
            MashupState.fromJson(e.id, e.decksJson)?.let { player.openMashup(it.copy(name = e.name), e.mixJson) }
            return@LaunchedEffect
        }
        val seed = seedTrackId?.let { graph.db.tracks().get(it) }?.takeIf { it.isSeparated }
        if (loaded != null) {
            // Already mashing up: add the chosen song if there is room.
            if (seed != null && loaded.decks.none { it.trackId == seed.id } && loaded.decks.size < MashupState.MAX_DECKS) {
                player.openMashup(loaded.copy(decks = loaded.decks + deckFrom(seed)), player.mix.value.toJson())
            }
            return@LaunchedEffect
        }
        val decks = buildList {
            // Start from the song that is playing, then the chosen one.
            player.state.value.track?.takeIf { it.isSeparated && it.id != seed?.id }?.let { add(deckFrom(it)) }
            seed?.let { add(deckFrom(it)) }
        }
        if (decks.isNotEmpty()) {
            val name = decks.joinToString(" × ") { it.title.take(14) }
            player.openMashup(MashupState(UUID.randomUUID().toString(), name, decks), player.mix.value.toJson())
        }
    }

    val m = state.mashup
    Column(Modifier.fillMaxSize().background(PhaseColors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PhaseColors.Mist) }
            MetalTitle("MASHUP", PhaseType.title.copy(letterSpacing = PhaseType.label.letterSpacing), Modifier.weight(1f))
            if (m != null) {
                IconButton(onClick = { haptics.confirm(); player.togglePlay() }) {
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = PhaseColors.Chrome)
                }
            }
        }
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(if (state.loading) "LOADING…" else "Pick separated songs to layer", style = PhaseType.label, color = PhaseColors.Steel)
                    Spacer(Modifier.height(16.dp))
                    HardwareKey("ADD SONG", icon = Icons.Rounded.Add, active = true) { adding = true }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { MasterCard(m, state.position, state.duration) }
                itemsIndexed(m.decks, key = { i, d -> "$i-${d.trackId}" }) { i, d -> DeckCard(m, i, d) }
                if (m.decks.size < MashupState.MAX_DECKS) item {
                    HardwareKey("ADD SONG (${m.decks.size}/${MashupState.MAX_DECKS})", Modifier.fillMaxWidth().padding(horizontal = 16.dp), icon = Icons.Rounded.Add) { adding = true }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HardwareKey("PERFORM · FX · RECORD", Modifier.weight(1f), icon = Icons.Rounded.GraphicEq, active = true) { onOpenPlayer() }
            }
        }
    }

    if (adding) AddSongDialog(exclude = m?.decks?.map { it.trackId }.orEmpty(), onDismiss = { adding = false }) { t ->
        adding = false
        val cur = player.state.value.mashup
        val next = if (cur == null) MashupState(UUID.randomUUID().toString(), t.title.take(20) + " mashup", listOf(deckFrom(t)))
        else cur.copy(decks = cur.decks + deckFrom(t))
        player.openMashup(next, player.mix.value.toJson())
    }
}

@Composable
private fun MasterCard(m: MashupState, position: Double, duration: Double) {
    val player = LocalGraph.current.player
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            m.name, { v -> player.updateMashup { it.copy(name = v.take(40)) } }, singleLine = true, label = { Text("Mashup name") },
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = PhaseColors.Edge, focusedBorderColor = PhaseColors.Mist),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TEMPO", style = PhaseType.label, color = PhaseColors.Steel, modifier = Modifier.width(56.dp))
            val bpm = m.bpm
            HardwareKey("−", compact = true, enabled = bpm != null) { player.updateMashup { it.copy(masterBpm = (bpm!! - 1f).coerceAtLeast(60f)) } }
            Text(bpm?.let { "%.1f".format(it) } ?: "—", style = PhaseType.readout, color = PhaseColors.Chrome, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
            HardwareKey("+", compact = true, enabled = bpm != null) { player.updateMashup { it.copy(masterBpm = (bpm!! + 1f).coerceAtMost(200f)) } }
            Spacer(Modifier.weight(1f))
            HardwareKey("FOLLOW SONG 1", active = m.masterBpm == null, compact = true) { player.updateMashup { it.copy(masterBpm = null) } }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${formatTime(position)} / ${formatTime(duration)} · songs are tempo-synced and beat-aligned automatically",
            style = PhaseType.labelSmall, color = PhaseColors.Steel,
        )
    }
}

@Composable
private fun DeckCard(m: MashupState, index: Int, d: DeckState) {
    val player = LocalGraph.current.player
    val graph = LocalGraph.current
    val track by graph.db.tracks().observe(d.trackId).collectAsState(initial = null)
    fun edit(f: (DeckState) -> DeckState) = player.updateMashup { s -> s.copy(decks = s.decks.mapIndexed { i, x -> if (i == index) f(x) else x }) }
    val ratio = m.ratio(d)
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackArt(track, Modifier.size(40.dp), corner = 10.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text("${index + 1}  ${d.title}", style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    d.sourceBpm?.let { b -> "%.0f → %.0f BPM · %.2fx".format(b, b * ratio, ratio) } ?: "tempo unknown · not synced",
                    style = PhaseType.labelSmall, color = if (ratio != 1f) Color(0xFFFFB547) else PhaseColors.Steel,
                )
            }
            if (m.decks.size > 1) IconButton(onClick = {
                val next = m.copy(decks = m.decks.filterIndexed { i, _ -> i != index })
                player.openMashup(next, player.mix.value.toJson())
            }) { Icon(Icons.Rounded.Close, "Remove song", tint = PhaseColors.Steel) }
        }
        Spacer(Modifier.height(6.dp))
        Bus.stems.forEach { b ->
            StemFader(b.short, d.stemGains[b.index], stemColor(b)) { v -> edit { it.copy(stemGains = it.stemGains.toMutableList().also { g -> g[b.index] = v }) } }
        }
        StemFader("VOL", d.volume, PhaseColors.Chrome) { v -> edit { it.copy(volume = v) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text("KEY", style = PhaseType.labelSmall, color = PhaseColors.Steel)
            HardwareKey("−", compact = true) { edit { it.copy(pitch = (it.pitch - 1f).coerceAtLeast(-12f)) } }
            Text((if (d.pitch > 0) "+" else "") + "%.0f".format(d.pitch), style = PhaseType.readout, color = PhaseColors.Chrome, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
            HardwareKey("+", compact = true) { edit { it.copy(pitch = (it.pitch + 1f).coerceAtMost(12f)) } }
            Spacer(Modifier.weight(1f))
            HardwareKey("SYNC", active = d.sync, compact = true) { edit { it.copy(sync = !it.sync) } }
            HardwareKey("KEY LOCK", active = d.keyLock, compact = true) { edit { it.copy(keyLock = !it.keyLock) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 6.dp)) {
            Text("START", style = PhaseType.labelSmall, color = PhaseColors.Steel)
            HardwareKey("−BAR", compact = true) { edit { it.copy(startBeats = it.startBeats - 4) } }
            HardwareKey("−1", compact = true) { edit { it.copy(startBeats = it.startBeats - 1) } }
            Text("%+d".format(d.startBeats), style = PhaseType.readout, color = PhaseColors.Chrome, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
            HardwareKey("+1", compact = true) { edit { it.copy(startBeats = it.startBeats + 1) } }
            HardwareKey("+BAR", compact = true) { edit { it.copy(startBeats = it.startBeats + 4) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 6.dp)) {
            Text("NUDGE", style = PhaseType.labelSmall, color = PhaseColors.Steel)
            HardwareKey("−10ms", compact = true) { edit { it.copy(nudgeMs = it.nudgeMs - 10f) } }
            Text("%+.0f ms".format(d.nudgeMs), style = PhaseType.readout, color = PhaseColors.Chrome, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
            HardwareKey("+10ms", compact = true) { edit { it.copy(nudgeMs = it.nudgeMs + 10f) } }
        }
    }
}

@Composable
private fun StemFader(label: String, value: Float, color: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PhaseType.labelSmall, color = color, modifier = Modifier.width(34.dp))
        Slider(
            value = value, onValueChange = { onChange(if (kotlin.math.abs(it - 1f) < 0.03f) 1f else it) }, valueRange = 0f..1.5f,
            modifier = Modifier.weight(1f).height(26.dp),
            colors = SliderDefaults.colors(thumbColor = PhaseColors.Chrome, activeTrackColor = color, inactiveTrackColor = PhaseColors.Edge),
        )
        Text("%.0f".format(value * 100), style = PhaseType.labelSmall, color = PhaseColors.Mist, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
    }
}

@Composable
private fun AddSongDialog(exclude: List<String>, onDismiss: () -> Unit, onPick: (TrackEntity) -> Unit) {
    val graph = LocalGraph.current
    val tracks by graph.db.tracks().all().collectAsState(initial = emptyList())
    val ready = tracks.filter { it.isSeparated && it.id !in exclude }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a song") },
        text = {
            if (ready.isEmpty()) Text("Only songs separated into stems can be mashed up. Import a song and wait for AI separation to finish.", style = PhaseType.body)
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(ready, key = { it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(t) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TrackArt(t, Modifier.size(40.dp).clip(CircleShape), corner = 10.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(t.title, style = PhaseType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.bpm?.let { "%.0f BPM".format(it) } ?: "tempo unknown", style = PhaseType.labelSmall, color = PhaseColors.Steel)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
