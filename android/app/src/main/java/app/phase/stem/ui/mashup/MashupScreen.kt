package app.phase.stem.ui.mashup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import app.phase.stem.audio.AudioDecoder
import app.phase.stem.audio.NativeEngine
import app.phase.stem.data.GestureAction
import app.phase.stem.ui.components.PadCorner
import app.phase.stem.ui.components.StemPad
import kotlin.math.roundToInt
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
fun MashupScreen(mashupId: String?, seedTrackId: String?, onBack: () -> Unit, onOpenPlayer: () -> Unit, onEffects: (Bus) -> Unit) {
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
    var confirmDelete by remember { mutableStateOf(false) }
    // Live stem activity for the pads' glow.
    val levels = remember { List(5) { mutableFloatStateOf(0f) } }
    // Seconds each paused song has been held back (to draw it where it really is).
    val holds = remember { List(MashupState.MAX_DECKS) { mutableFloatStateOf(0f) } }
    LaunchedEffect(Unit) {
        val buf = FloatArray(10)
        while (true) {
            withFrameNanos { }
            NativeEngine.nMeters(buf)
            for (i in 0 until 5) levels[i].floatValue = buf[i * 2]
            for (i in 0 until MashupState.MAX_DECKS) holds[i].floatValue = NativeEngine.nDeckHold(i).toFloat()
        }
    }
    val allTracks by graph.db.tracks().all().collectAsState(initial = emptyList())
    val lengths = allTracks.associate { it.id to (it.durationMs / 1000f).coerceAtLeast(1f) }
    // One shared timeline scale for every song bar; frozen while dragging.
    var dragging by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(60f) }
    state.mashup?.let { mm ->
        if (!dragging) {
            val end = mm.decks.indices.maxOfOrNull { i ->
                mm.offsetSeconds(i) + holds[i].floatValue + mm.regionSeconds(i, lengths[mm.decks[i].trackId] ?: 1f)
            } ?: 60f
            scale = (kotlin.math.ceil((end + 20f) / 30f) * 30f).coerceAtLeast(60f)
        }
    }
    Column(Modifier.fillMaxSize().background(PhaseColors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PhaseColors.Mist) }
            MetalTitle("MASHUP", PhaseType.title.copy(letterSpacing = PhaseType.label.letterSpacing), Modifier.weight(1f))
            if (m != null) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete mashup", tint = PhaseColors.Steel) }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(PhaseColors.accent).clickable { haptics.confirm(); player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = PhaseColors.Void) }
                Spacer(Modifier.width(8.dp))
            }
        }
        if (m == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(if (state.loading) "LOADING…" else "Pick two songs to mix", style = PhaseType.label, color = PhaseColors.Steel)
                    Spacer(Modifier.height(16.dp))
                    HardwareKey("ADD SONG", icon = Icons.Rounded.Add, active = true) { adding = true }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { MasterStrip(m, state.position, state.duration) }
                itemsIndexed(m.decks, key = { i, d -> "$i-${d.trackId}" }) { i, d ->
                    DeckSection(
                        m, i, d, state.position, levels, onEffects,
                        TimelineInfo(scale, holds[i].floatValue, lengths[d.trackId] ?: 1f) { dragging = it },
                    )
                }
                if (m.decks.size < MashupState.MAX_DECKS) item {
                    HardwareKey(
                        if (m.decks.size < 2) "ADD SECOND SONG" else "ADD ANOTHER SONG",
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp), icon = Icons.Rounded.Add, active = m.decks.size < 2,
                    ) { adding = true }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            HardwareKey("PERFORM · FX · RECORD", Modifier.fillMaxWidth().padding(12.dp), icon = Icons.Rounded.GraphicEq, active = true) { onOpenPlayer() }
        }
    }

    if (adding) AddSongDialog(exclude = m?.decks?.map { it.trackId }.orEmpty(), onDismiss = { adding = false }) { t ->
        adding = false
        val cur = player.state.value.mashup
        val next = if (cur == null) MashupState(UUID.randomUUID().toString(), t.title.take(20) + " mashup", listOf(deckFrom(t)))
        else cur.copy(decks = cur.decks + deckFrom(t))
        player.openMashup(next, player.mix.value.toJson())
    }
    if (confirmDelete && m != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${m.name}”?") },
        text = { Text("The songs stay in your library.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                graph.deleteMashup(m.id)
                onBack()
            }) { Text("Delete", color = PhaseColors.Danger) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

private val DECK_COLORS = listOf(Color(0xFF3EE6E0), Color(0xFFFF4D9A), Color(0xFFFFB547), Color(0xFF8B6CFF))

@Composable
private fun MasterStrip(m: MashupState, position: Double, duration: Double) {
    val player = LocalGraph.current.player
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        BasicTextField(
            m.name, { v -> player.updateMashup { it.copy(name = v.take(40)) } }, singleLine = true,
            textStyle = PhaseType.title.copy(color = PhaseColors.Chrome), cursorBrush = androidx.compose.ui.graphics.SolidColor(PhaseColors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val bpm = m.bpm
            HardwareKey("−", compact = true, enabled = bpm != null) { player.updateMashup { it.copy(masterBpm = (bpm!! - 1f).coerceAtLeast(60f)) } }
            Text(bpm?.let { "%.0f BPM".format(it) } ?: "— BPM", style = PhaseType.readout, color = PhaseColors.Chrome, textAlign = TextAlign.Center, modifier = Modifier.width(76.dp))
            HardwareKey("+", compact = true, enabled = bpm != null) { player.updateMashup { it.copy(masterBpm = (bpm!! + 1f).coerceAtMost(200f)) } }
            Spacer(Modifier.weight(1f))
            Text("${formatTime(position)} / ${formatTime(duration)}", style = PhaseType.readout, color = PhaseColors.Mist)
        }
    }
}

@Composable
private fun DeckSection(
    m: MashupState,
    index: Int,
    d: DeckState,
    position: Double,
    levels: List<androidx.compose.runtime.MutableFloatState>,
    onEffects: (Bus) -> Unit,
    timeline: TimelineInfo,
) {
    val graph = LocalGraph.current
    val player = graph.player
    val track by graph.db.tracks().observe(d.trackId).collectAsState(initial = null)
    val color = DECK_COLORS[index % DECK_COLORS.size]
    fun edit(f: (DeckState) -> DeckState) = player.updateMashup { s -> s.copy(decks = s.decks.mapIndexed { i, x -> if (i == index) f(x) else x }) }
    val ratio = m.ratio(d)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Song header
        Row(verticalAlignment = Alignment.CenterVertically) {
            // This song's own play/pause: paused songs hold their place and drop back in on the beat.
            val haptics = rememberHaptics()
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (d.playing) color else Color(0xFF1B1B21))
                    .border(2.dp, color, CircleShape)
                    .clickable { haptics.confirm(); player.toggleDeck(index) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (d.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (d.playing) "Pause song ${'A' + index}" else "Play song ${'A' + index}",
                    tint = if (d.playing) PhaseColors.Void else color,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(color), contentAlignment = Alignment.Center) {
                Text(('A' + index).toString(), style = PhaseType.label, color = PhaseColors.Void)
            }
            Spacer(Modifier.width(8.dp))
            TrackArt(track, Modifier.size(34.dp), corner = 8.dp)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(d.title, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        d.sourceBpm?.let { "%.0f BPM".format(it) },
                        if (d.pitch != 0f) "KEY %+.0f".format(d.pitch) else null,
                    ).joinToString(" · ").ifBlank { "—" },
                    style = PhaseType.labelSmall, color = PhaseColors.Steel,
                )
            }
            HardwareKey("−", compact = true) { edit { it.copy(pitch = (it.pitch - 1f).coerceAtLeast(-12f)) } }
            Text("KEY", style = PhaseType.labelSmall, color = PhaseColors.Steel, modifier = Modifier.padding(horizontal = 4.dp))
            HardwareKey("+", compact = true) { edit { it.copy(pitch = (it.pitch + 1f).coerceAtMost(12f)) } }
            if (m.decks.size > 1) IconButton(onClick = {
                player.openMashup(m.copy(decks = m.decks.filterIndexed { i, _ -> i != index }), player.mix.value.toJson())
            }) { Icon(Icons.Rounded.Close, "Remove song", tint = PhaseColors.Steel) }
        }
        Spacer(Modifier.height(8.dp))
        // Four stem pads for this song
        val corners = listOf(PadCorner.TopStart, PadCorner.TopEnd, PadCorner.BottomStart, PadCorner.BottomEnd)
        val soloIdx = d.stemMuted.indices.singleOrNull { !d.stemMuted[it] }?.takeIf { d.stemMuted.count { m -> m } == 3 }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(listOf(Bus.VOCALS, Bus.DRUMS), listOf(Bus.BASS, Bus.OTHER)).forEach { row ->
                Row(Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { b ->
                        val i = b.index
                        StemPad(
                            bus = b, color = stemColor(b), corner = corners[i],
                            gain = d.stemGains[i], muted = d.stemMuted[i], soloed = soloIdx == i,
                            audible = !d.stemMuted[i] && d.stemGains[i] > 0f, enabled = true, effects = emptyList(),
                            level = levels[i],
                            onGain = { g -> edit { it.copy(stemGains = it.stemGains.toMutableList().also { l -> l[i] = g }) } },
                            onAction = { a ->
                                when (a) {
                                    GestureAction.TOGGLE_MUTE -> edit { it.copy(stemMuted = it.stemMuted.toMutableList().also { l -> l[i] = !l[i] }) }
                                    GestureAction.TOGGLE_SOLO -> edit {
                                        if (soloIdx == i) it.copy(stemMuted = listOf(false, false, false, false))
                                        else it.copy(stemMuted = List(4) { k -> k != i })
                                    }
                                    GestureAction.OPEN_EFFECTS -> onEffects(b)
                                    GestureAction.RESET_STEM -> edit {
                                        it.copy(
                                            stemGains = it.stemGains.toMutableList().also { l -> l[i] = 1f },
                                            stemMuted = it.stemMuted.toMutableList().also { l -> l[i] = false },
                                        )
                                    }
                                    GestureAction.NONE -> Unit
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxSize(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TimelineBar(m, index, d, ratio, position, color, timeline) { f -> edit(f) }
    }
}

/** Shared timeline scale + this song's live state, for its bar. */
class TimelineInfo(val scaleSec: Float, val holdSec: Float, val songLength: Float, val onDrag: (Boolean) -> Unit)

/**
 * This song on the shared mashup timeline (all bars use the same scale, so
 * they line up vertically). Drag the middle to move the song in time
 * (snaps to beats), drag either edge to trim it, tap to jump there. The
 * white line is the playhead; faint lines mark bars.
 */
@Composable
private fun TimelineBar(
    m: MashupState,
    index: Int,
    d: DeckState,
    ratio: Float,
    position: Double,
    color: Color,
    tl: TimelineInfo,
    edit: ((DeckState) -> DeckState) -> Unit,
) {
    val graph = LocalGraph.current
    val player = graph.player
    val haptics = rememberHaptics()
    val len = tl.songLength
    val peaks by produceState(FloatArray(0), d.trackId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AudioDecoder.loadPeaks(graph.storage.mixPeaks(d.trackId), 400)
        }
    }
    val inS = d.trimIn.coerceIn(0f, len)
    val outS = if (d.trimOut > inS) d.trimOut.coerceAtMost(len) else len
    val start = m.offsetSeconds(index) + tl.holdSec          // timeline time where the region starts
    val regionLen = (outS - inS) / ratio
    val period = m.beatPeriod
    val S = tl.scaleSec
    val latest by rememberUpdatedState(Triple(d, start, S))

    Canvas(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0E0E12))
            .pointerInput(d.trackId) {
                detectTapGestures { o ->
                    haptics.tick()
                    player.seek((o.x / size.width * latest.third).toDouble())
                }
            }
            .pointerInput(d.trackId) {
                val edge = 22.dp.toPx()
                var mode = 0 // 0 move, 1 trim in, 2 trim out
                var startDeck = d
                var sx = 0f
                var lastStep = 0
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        val (dd, st, sc) = latest
                        startDeck = dd
                        sx = o.x
                        lastStep = 0
                        val ddIn = dd.trimIn.coerceIn(0f, len)
                        val ddOut = if (dd.trimOut > ddIn) dd.trimOut.coerceAtMost(len) else len
                        val x0 = st / sc * size.width
                        val x1 = (st + (ddOut - ddIn) / ratio) / sc * size.width
                        mode = when {
                            kotlin.math.abs(o.x - x0) < edge -> 1
                            kotlin.math.abs(o.x - x1) < edge -> 2
                            else -> 0
                        }
                        tl.onDrag(true)
                        haptics.tick()
                    },
                    onDragEnd = { tl.onDrag(false) },
                    onDragCancel = { tl.onDrag(false) },
                ) { change, _ ->
                    change.consume()
                    val sc = latest.third
                    val dt = (change.position.x - sx) / size.width * sc   // seconds on the timeline
                    val sd = startDeck
                    val sdIn = sd.trimIn.coerceIn(0f, len)
                    val sdOut = if (sd.trimOut > sdIn) sd.trimOut.coerceAtMost(len) else len
                    when (mode) {
                        0 -> { // move the whole song, snapped to beats
                            val steps = kotlin.math.round(dt / period).toInt()
                            if (steps != lastStep) { haptics.tick(); lastStep = steps }
                            edit { it.copy(startBeats = sd.startBeats + steps) }
                        }
                        1 -> { // trim the start; the rest of the song stays where it is
                            val newIn = (sdIn + dt * ratio).coerceIn(0f, sdOut - 2f)
                            val keep = kotlin.math.round(((newIn - sdIn) / ratio) / period).toInt()
                            edit { it.copy(trimIn = (newIn * 10).roundToInt() / 10f, startBeats = sd.startBeats + keep) }
                        }
                        else -> { // trim the end
                            val newOut = (sdOut + dt * ratio).coerceIn(sdIn + 2f, len)
                            edit { it.copy(trimOut = if (newOut >= len - 0.15f) 0f else (newOut * 10).roundToInt() / 10f) }
                        }
                    }
                }
            },
    ) {
        val w = size.width
        val h = size.height
        fun x(t: Float) = t / S * w
        // Bar lines every 4 beats.
        if (m.bpm != null) {
            var t = m.gridOffset
            var k = 0
            while (t < S) {
                if (k % 4 == 0) drawLine(Color(0x22FFFFFF), Offset(x(t), 0f), Offset(x(t), h), 1f)
                t += period; k++
            }
        }
        // Whole song (faint) and the part that plays (bright).
        val songStart = start - inS / ratio
        if (peaks.isNotEmpty()) {
            val n = peaks.size
            val bw = (x(songStart + len / ratio) - x(songStart)) / n
            for (i in 0 until n) {
                val songT = i.toFloat() / n * len
                val px = x(songStart + songT / ratio)
                if (px < -bw || px > w) continue
                val inside = songT in inS..outS
                val bh = h * 0.78f * peaks[i].coerceAtLeast(0.03f)
                drawRect(
                    if (inside) color.copy(alpha = if (d.playing) 0.9f else 0.4f) else Color(0x1FFFFFFF),
                    Offset(px, (h - bh) / 2), Size(maxOf(1f, bw * 0.7f), bh),
                )
            }
        }
        val x0 = x(start)
        val x1 = x(start + regionLen)
        drawRect(color.copy(alpha = 0.10f), Offset(x0, 0f), Size(x1 - x0, h))
        drawRect(color, Offset(x0, 0f), Size(x1 - x0, h), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        // Edge handles
        listOf(x0, x1).forEach { hx ->
            drawRoundRect(PhaseColors.Chrome, Offset(hx - 4f, h * 0.25f), Size(8f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(4f, 4f))
        }
        // Playhead (same x on every song bar)
        val px = x(position.toFloat())
        drawLine(Color.White, Offset(px, 0f), Offset(px, h), 3f)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text(
            "IN AT ${formatTime(start.toDouble())} · PLAYS ${formatTime(inS.toDouble())}–${formatTime(outS.toDouble())}" +
                if (!d.playing) " · PAUSED" else "",
            style = PhaseType.labelSmall, color = if (d.playing) PhaseColors.Mist else color, modifier = Modifier.weight(1f),
        )
        HardwareKey("◀", compact = true) { edit { it.copy(startBeats = it.startBeats - 4) } }
        Spacer(Modifier.width(4.dp))
        HardwareKey("▶", compact = true) { edit { it.copy(startBeats = it.startBeats + 4) } }
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
