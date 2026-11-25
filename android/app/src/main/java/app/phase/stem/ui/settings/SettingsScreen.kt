package app.phase.stem.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.phase.stem.BuildConfig
import app.phase.stem.audio.Bus
import app.phase.stem.audio.NativeEngine
import app.phase.stem.data.AppSettings
import app.phase.stem.data.GestureAction
import app.phase.stem.separation.ProviderStatus
import app.phase.stem.ui.components.GlassPanel
import app.phase.stem.ui.components.HardwareKey
import app.phase.stem.ui.components.Led
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.MetalTitle
import app.phase.stem.ui.components.appSettings
import app.phase.stem.ui.components.formatBytes
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DEFAULT_MODELS = listOf(
    "htdemucs" to "Hybrid Transformer Demucs (default)",
    "htdemucs_ft" to "HT Demucs fine-tuned (best, 4x slower)",
    "hdemucs_mmi" to "Hybrid Demucs v3",
    "mdx_extra" to "MDX extra",
)

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val graph = LocalGraph.current
    val s = appSettings()
    val store = graph.settings
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(s.serverUrl) }
    var token by remember { mutableStateOf(s.serverToken) }
    var status by remember { mutableStateOf<ProviderStatus?>(null) }
    var testing by remember { mutableStateOf(false) }
    var storageTick by remember { mutableStateOf(0) }

    fun test() {
        store.update { it.copy(serverUrl = serverUrl.trim(), serverToken = token.trim()) }
        testing = true
        scope.launch {
            status = graph.separationProvider().status()
            testing = false
        }
    }
    LaunchedEffect(Unit) { if (s.serverUrl.isNotBlank()) test() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            MetalTitle("SETTINGS", PhaseType.title.copy(letterSpacing = PhaseType.label.letterSpacing), Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        }
        // -------------------------------------------------- processing
        item {
            Section("AI separation") {
                Text(
                    "Stems are separated by a Phase server running Demucs (see backend/ in the repo). Run it on your computer (same Wi-Fi) or any cloud machine, then enter its address.",
                    style = PhaseType.labelSmall, color = PhaseColors.Steel,
                )
                Spacer(Modifier.height(10.dp))
                Field("Server URL", serverUrl, { serverUrl = it }, "http://192.168.1.20:8000")
                Spacer(Modifier.height(8.dp))
                Field("Access token (optional)", token, { token = it }, "", secret = true)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HardwareKey(if (testing) "TESTING…" else "SAVE & TEST", active = true, enabled = !testing) { test() }
                    Spacer(Modifier.width(12.dp))
                    status?.let { st ->
                        Led(st.reachable, if (st.reachable) PhaseColors.Ok else PhaseColors.Danger)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (st.reachable) "Connected · ${st.device?.uppercase() ?: "?"}" else st.message,
                            style = PhaseType.labelSmall, color = if (st.reachable) PhaseColors.Ok else PhaseColors.Danger, maxLines = 2,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val models = status?.models?.takeIf { it.isNotEmpty() }?.map { it.id to it.name } ?: DEFAULT_MODELS
                Choice("Model", s.separationModel, models) { v -> store.update { it.copy(separationModel = v) } }
                Toggle("Separate automatically after import", s.autoSeparate) { v -> store.update { it.copy(autoSeparate = v) } }
            }
        }
        // -------------------------------------------------- audio
        item {
            Section("Audio") {
                Toggle("Low-latency output", s.lowLatency, "Exclusive AAudio stream with the smallest stable buffer. Turn off to save battery.") { v ->
                    store.update { it.copy(lowLatency = v) }
                    graph.player.restartOutput()
                }
                val info by produceState<NativeEngine.StreamInfo?>(null, s.lowLatency) {
                    while (true) {
                        value = NativeEngine.streamInfo()
                        delay(1000)
                    }
                }
                info?.takeIf { it.sampleRate > 0 }?.let { i ->
                    Text(
                        "${i.sampleRate} Hz · burst ${i.framesPerBurst} · buffer ${i.bufferSize}/${i.bufferCapacity} · ${if (i.aaudio) "AAudio" else "OpenSL ES"} · " +
                            "${if (i.exclusive) "exclusive" else "shared"}${if (i.latencyMs >= 0) " · ~%.0f ms".format(i.latencyMs) else ""} · xruns ${i.xruns}",
                        style = PhaseType.labelSmall, color = PhaseColors.Steel, modifier = Modifier.padding(vertical = 6.dp),
                    )
                } ?: Text("Output idle — open a track to start the audio stream.", style = PhaseType.labelSmall, color = PhaseColors.Steel)
                Choice("Export format", s.exportFormat, listOf("wav" to "WAV 16-bit (lossless)", "mp3" to "MP3")) { v -> store.update { it.copy(exportFormat = v) } }
                Choice("MP3 bitrate", s.mp3Bitrate.toString(), listOf("128", "192", "256", "320").map { it to "$it kbps" }) { v -> store.update { it.copy(mp3Bitrate = v.toInt()) } }
                Choice("Live recording format", s.recordFormat, listOf("wav" to "WAV", "mp3" to "MP3")) { v -> store.update { it.copy(recordFormat = v) } }
            }
        }
        // -------------------------------------------------- gestures
        item {
            Section("Gestures") {
                val opts = GestureAction.entries.map { it.name to it.label }
                Choice("Swipe right", s.swipeRight.name, opts) { v -> store.update { it.copy(swipeRight = GestureAction.valueOf(v)) } }
                Choice("Swipe left", s.swipeLeft.name, opts) { v -> store.update { it.copy(swipeLeft = GestureAction.valueOf(v)) } }
                Choice("Double tap", s.doubleTap.name, opts) { v -> store.update { it.copy(doubleTap = GestureAction.valueOf(v)) } }
                Choice("Long press", s.longPress.name, opts) { v -> store.update { it.copy(longPress = GestureAction.valueOf(v)) } }
                Toggle("Swipe up/down changes volume", s.verticalVolume) { v -> store.update { it.copy(verticalVolume = v) } }
                Toggle("Haptic feedback", s.haptics) { v -> store.update { it.copy(haptics = v) } }
            }
        }
        // -------------------------------------------------- appearance
        item {
            Section("Stem colours") {
                Bus.stems.forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.label.uppercase(), style = PhaseType.label, color = Color(s.stemColors[b.index]), modifier = Modifier.width(80.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppSettings.PALETTE.forEach { c ->
                                val sel = s.stemColors[b.index] == c
                                Box(
                                    Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                                        .border(if (sel) 3.dp else 1.dp, if (sel) PhaseColors.Chrome else Color(0x33000000), CircleShape)
                                        .clickable { store.update { st -> st.copy(stemColors = st.stemColors.toMutableList().also { it[b.index] = c }) } },
                                )
                            }
                        }
                    }
                }
                HardwareKey("RESET COLOURS") { store.update { it.copy(stemColors = AppSettings.DEFAULT_STEM_COLORS) } }
                Spacer(Modifier.height(8.dp))
                Toggle("Keep screen on in player", s.keepScreenOn) { v -> store.update { it.copy(keepScreenOn = v) } }
            }
        }
        // -------------------------------------------------- lyrics
        item {
            Section("Lyrics") {
                Toggle("Online lyrics lookup (LRCLIB)", s.onlineLyrics, "Sends the song title, artist and duration to lrclib.net when you tap Find online.") { v ->
                    store.update { it.copy(onlineLyrics = v) }
                }
            }
        }
        // -------------------------------------------------- storage
        item {
            val usage by produceState(Triple(0L, 0L, 0L), storageTick) {
                value = withContext(Dispatchers.IO) { Triple(graph.storage.totalTrackBytes(), graph.storage.stemCacheBytes(), graph.storage.exportBytes()) }
            }
            Section("Storage") {
                Text("Library ${formatBytes(usage.first)} (of which stems ${formatBytes(usage.second)}) · exports ${formatBytes(usage.third)}", style = PhaseType.body, color = PhaseColors.Mist)
                Spacer(Modifier.height(10.dp))
                Text("Stems are cached so separated songs replay instantly. Clearing them frees space; tracks can be re-separated.", style = PhaseType.labelSmall, color = PhaseColors.Steel)
                Spacer(Modifier.height(10.dp))
                HardwareKey("CLEAR ALL STEM CACHE", color = PhaseColors.Danger) {
                    scope.launch {
                        val ids = withContext(Dispatchers.IO) { graph.db.tracks().allOnce().filter { it.isSeparated }.map { it.id } }
                        ids.forEach { graph.clearStems(it).join() }
                        storageTick++
                    }
                }
            }
        }
        item {
            Section("About") {
                Text("Phase ${BuildConfig.VERSION_NAME}", style = PhaseType.body, color = PhaseColors.Chrome)
                Text(
                    "Native C++ engine on Oboe/AAudio · AI separation by Demucs (MIT) · MP3 encoding by shine (LGPL-2) · lyrics by LRCLIB.",
                    style = PhaseType.labelSmall, color = PhaseColors.Steel,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title.uppercase(), style = PhaseType.label, color = PhaseColors.Steel)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, placeholder = { Text(placeholder) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = PhaseColors.Edge, focusedBorderColor = PhaseColors.Mist),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Toggle(title: String, value: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PhaseType.body, color = PhaseColors.Chrome)
            subtitle?.let { Text(it, style = PhaseType.labelSmall, color = PhaseColors.Steel) }
        }
        Switch(value, onChange)
    }
}

@Composable
private fun Choice(title: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = PhaseType.body, color = PhaseColors.Chrome, modifier = Modifier.weight(1f))
        Box {
            Text(options.firstOrNull { it.first == value }?.second ?: value, style = PhaseType.labelSmall, color = PhaseColors.Mist)
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEach { (k, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(k) }) }
            }
        }
    }
}
