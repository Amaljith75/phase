package app.phase.stem.ui.lyrics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.phase.stem.audio.Bus
import app.phase.stem.lyrics.LyricLine
import app.phase.stem.lyrics.Lyrics
import app.phase.stem.lyrics.LyricsQuery
import app.phase.stem.ui.components.EmptyState
import app.phase.stem.ui.components.HardwareKey
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.MetalTitle
import app.phase.stem.ui.components.appSettings
import app.phase.stem.ui.components.formatTime
import app.phase.stem.ui.components.rememberHaptics
import app.phase.stem.ui.components.stemColor
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Mode { VIEW, EDIT, SYNC }

@Composable
fun LyricsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val player = graph.player
    val state by player.state.collectAsState()
    val settings = appSettings()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val track = state.track ?: return
    val accent = stemColor(Bus.VOCALS)

    val dbTrack by graph.db.tracks().observe(track.id).collectAsState(initial = track)
    val lyrics = remember(dbTrack?.lyricsLrc) { dbTrack?.lyricsLrc?.let { Lyrics.parse(it) } }
    var mode by remember { mutableStateOf(Mode.VIEW) }
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }

    fun save(lrc: String?) = scope.launch(Dispatchers.IO) { graph.db.tracks().saveLyrics(track.id, lrc?.takeIf { it.isNotBlank() }) }

    val importLrc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            if (text.isNullOrBlank()) status = "Could not read that file"
            else {
                val parsed = Lyrics.parse(text)
                if (parsed.lines.isEmpty()) status = "No lyric lines found" else {
                    save(parsed.toLrc())
                    status = if (parsed.isSynced) "Imported ${parsed.lines.size} synced lines" else "Imported (not time-synced — use SYNC)"
                }
            }
        }
    }

    fun searchOnline() {
        searching = true
        status = "Searching…"
        scope.launch {
            val q = LyricsQuery(track.title, track.artist, track.album, (state.duration).toInt())
            var found: String? = null
            for (p in graph.lyricsProviders) {
                val r = runCatching { p.find(q) }.onFailure { status = "${p.name}: ${it.message}" }.getOrNull()
                if (r != null) {
                    save(r.lrc)
                    found = "Found on ${r.providerName}${if (r.synced) " (synced)" else " (unsynced)"}"
                    break
                }
            }
            status = found ?: (status?.takeIf { it.contains(":") } ?: "No lyrics found online")
            searching = false
        }
    }

    Column(Modifier.fillMaxSize().background(PhaseColors.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (mode == Mode.VIEW) onBack() else mode = Mode.VIEW }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PhaseColors.Mist) }
            Column(Modifier.weight(1f)) {
                MetalTitle("LYRICS", PhaseType.title.copy(letterSpacing = 4.sp))
                Text(track.title, style = PhaseType.labelSmall, color = PhaseColors.Steel, maxLines = 1)
            }
            IconButton(onClick = { player.togglePlay() }) {
                Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = PhaseColors.Chrome)
            }
        }
        if (mode == Mode.VIEW) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.onlineLyrics) HardwareKey(if (searching) "SEARCHING" else "FIND ONLINE", icon = Icons.Rounded.Search, enabled = !searching) { searchOnline() }
                HardwareKey("IMPORT .LRC", icon = Icons.Rounded.FileOpen) { importLrc.launch(arrayOf("*/*")) }
                HardwareKey("EDIT", icon = Icons.Rounded.Edit) { mode = Mode.EDIT }
                if (lyrics != null && lyrics.lines.isNotEmpty()) HardwareKey("TAP SYNC", icon = Icons.Rounded.Timer) { mode = Mode.SYNC }
                if (lyrics?.isSynced == true) {
                    HardwareKey("−0.1s") { save(lyrics.copy(offsetMs = lyrics.offsetMs - 100).toLrc()) }
                    HardwareKey("+0.1s") { save(lyrics.copy(offsetMs = lyrics.offsetMs + 100).toLrc()) }
                }
            }
            status?.let { Text(it, style = PhaseType.labelSmall, color = PhaseColors.Steel, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        }

        when (mode) {
            Mode.VIEW -> if (lyrics == null || lyrics.lines.isEmpty()) {
                EmptyState(
                    Icons.Rounded.Search, "No lyrics yet",
                    "Not every song has lyrics available. Search online, import an .lrc file, or write your own and tap-sync them.",
                )
            } else LyricsView(lyrics, (state.position * 1000).toLong(), accent) { player.seek(it / 1000.0) }

            Mode.EDIT -> LrcEditor(lyrics?.toLrc() ?: "", onSave = { save(it); mode = Mode.VIEW; status = "Saved" })

            Mode.SYNC -> TapSync(
                base = lyrics ?: Lyrics(emptyList()),
                positionMs = { (player.state.value.position * 1000).toLong() },
                accent = accent,
                onStamp = { haptics.tick() },
                onDone = { save(it.toLrc()); mode = Mode.VIEW; status = "Timestamps saved" },
            )
        }
    }
}

@Composable
private fun LyricsView(lyrics: Lyrics, positionMs: Long, accent: androidx.compose.ui.graphics.Color, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val active = lyrics.activeIndex(positionMs)
    var lastUserScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) lastUserScroll = System.currentTimeMillis()
    }
    // Auto-scroll, but give manual scrolling 3 s of precedence.
    LaunchedEffect(active) {
        if (active >= 0 && System.currentTimeMillis() - lastUserScroll > 3000) {
            listState.animateScrollToItem((active - 2).coerceAtLeast(0))
            lastUserScroll = 0L
        }
    }
    if (!lyrics.isSynced) {
        Text(
            "These lyrics aren't time-synced. Use TAP SYNC to stamp each line while the song plays.",
            style = PhaseType.labelSmall, color = PhaseColors.Steel, modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp), modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lyrics.lines) { i, line ->
            val isActive = i == active
            val past = active >= 0 && i < active
            val color by animateColorAsState(
                when {
                    isActive -> PhaseColors.Chrome
                    past -> PhaseColors.Steel
                    else -> PhaseColors.Edge.copy(alpha = 1f).let { androidx.compose.ui.graphics.Color(0xFF5A5A66) }
                }, label = "c",
            )
            val scale by animateFloatAsState(if (isActive) 1.06f else 1f, label = "s")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = line.timeMs >= 0) { onSeek(line.timeMs - lyrics.offsetMs) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    Box(Modifier.width(3.dp).height(26.dp).background(accent, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    line.text.ifBlank { "♪" },
                    style = PhaseType.title.copy(fontSize = 22.sp, fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold, lineHeight = 28.sp),
                    color = color,
                    modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) },
                )
            }
        }
        item { Spacer(Modifier.height(240.dp)) }
    }
}

@Composable
private fun LrcEditor(initial: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "LRC format: [mm:ss.xx] line text. Lines without timestamps are allowed — sync them later with TAP SYNC.",
            style = PhaseType.labelSmall, color = PhaseColors.Steel,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            text, { text = it }, modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = PhaseType.readout.copy(color = PhaseColors.Chrome, fontWeight = FontWeight.Normal),
            placeholder = { Text("[00:12.30] First line\n[00:15.80] Second line") },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareKey("SAVE", active = true) { onSave(text) }
            HardwareKey("CLEAR") { text = "" }
        }
    }
}

@Composable
private fun TapSync(base: Lyrics, positionMs: () -> Long, accent: androidx.compose.ui.graphics.Color, onStamp: () -> Unit, onDone: (Lyrics) -> Unit) {
    val lines = remember { base.lines.map { it.text }.toMutableList() }
    val stamps = remember { mutableStateOf(base.lines.map { it.timeMs }.toMutableList()) }
    var idx by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(idx) { if (idx in lines.indices) listState.animateScrollToItem((idx - 2).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "Play the song and tap STAMP as each highlighted line begins.",
            style = PhaseType.labelSmall, color = PhaseColors.Steel, modifier = Modifier.padding(vertical = 6.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(lines) { i, t ->
                val ts = stamps.value.getOrNull(i) ?: -1
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ts >= 0) formatTime(ts / 1000.0) + "." + "%02d".format((ts % 1000) / 10) else "--:--",
                        style = PhaseType.readout, color = if (i < idx) accent else PhaseColors.Steel, modifier = Modifier.width(72.dp),
                    )
                    Text(t, style = PhaseType.body, color = if (i == idx) PhaseColors.Chrome else PhaseColors.Steel, fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareKey("STAMP", Modifier.weight(2f), active = true, color = accent, enabled = idx < lines.size) {
                stamps.value = stamps.value.toMutableList().also { it[idx] = positionMs() }
                idx++
                onStamp()
            }
            HardwareKey("UNDO", Modifier.weight(1f), enabled = idx > 0) {
                idx--
                stamps.value = stamps.value.toMutableList().also { it[idx] = base.lines[idx].timeMs }
            }
            // Saving requires every line stamped, so no lyric text can be dropped.
            HardwareKey("DONE", Modifier.weight(1f), enabled = idx >= lines.size) {
                val result = lines.mapIndexed { i, t -> LyricLine(stamps.value[i], t) }
                onDone(base.copy(lines = result.sortedBy { it.timeMs }))
            }
        }
    }
}
