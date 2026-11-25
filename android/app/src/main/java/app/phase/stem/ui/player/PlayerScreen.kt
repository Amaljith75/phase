package app.phase.stem.ui.player

import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.phase.stem.analysis.TapTempo
import app.phase.stem.audio.Bus
import app.phase.stem.audio.NativeEngine
import app.phase.stem.audio.Param
import app.phase.stem.audio.QuickMode
import app.phase.stem.data.GestureAction
import app.phase.stem.data.TrackStatus
import app.phase.stem.export.ExportActions
import app.phase.stem.ui.components.GlassPanel
import app.phase.stem.ui.components.HardwareKey
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.PadCorner
import app.phase.stem.ui.components.StemPad
import app.phase.stem.ui.components.TrackArt
import app.phase.stem.ui.components.WaveformScrubber
import app.phase.stem.ui.components.appSettings
import app.phase.stem.ui.components.formatTime
import app.phase.stem.ui.components.rememberHaptics
import app.phase.stem.ui.components.stemColor
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(onBack: () -> Unit, onLyrics: () -> Unit, onEffects: (Bus) -> Unit) {
    val graph = LocalGraph.current
    val player = graph.player
    val state by player.state.collectAsState()
    val mix by player.mix.collectAsState()
    val peaks by player.peaks.collectAsState()
    val settings = appSettings()
    val haptics = rememberHaptics()
    val track = state.track

    // Per-frame meter polling feeding the pads' draw phase (no recomposition).
    val levels = remember { List(5) { mutableFloatStateOf(0f) } }
    LaunchedEffect(Unit) {
        val buf = FloatArray(10)
        while (true) {
            withFrameNanos { }
            NativeEngine.nMeters(buf)
            for (i in 0 until 5) levels[i].floatValue = buf[i * 2]
        }
    }
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    var showSpeed by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSaveProject by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val tap = remember { TapTempo() }
    var tappedBpm by remember { mutableStateOf<Float?>(null) }

    if (track == null) {
        Box(Modifier.fillMaxSize().background(PhaseColors.Void), contentAlignment = Alignment.Center) {
            Text(if (state.loading) "LOADING…" else "NO TRACK LOADED", style = PhaseType.label, color = PhaseColors.Steel)
        }
        return
    }

    Column(
        Modifier.fillMaxSize().background(PhaseColors.background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp),
    ) {
        // ---- header --------------------------------------------------------
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PhaseColors.Mist) }
            TrackArt(track, Modifier.size(40.dp), corner = 10.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(track.title, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track.artist, state.projectName?.let { "◆ $it" }).joinToString("  ").ifBlank { if (state.stemMode) "4 STEMS" else "FULL MIX" },
                    style = PhaseType.labelSmall, color = PhaseColors.Steel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = PhaseColors.Mist) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Save as project") }, onClick = { menu = false; showSaveProject = true })
                    DropdownMenuItem(text = { Text("Mix presets") }, onClick = { menu = false; showPresets = true })
                    DropdownMenuItem(text = { Text("Export mix") }, onClick = { menu = false; showExport = true })
                    DropdownMenuItem(text = { Text("Reset all stems") }, onClick = {
                        menu = false
                        Bus.entries.forEach { player.resetBus(it) }
                    })
                }
            }
        }

        // ---- separation status ---------------------------------------------
        if (!state.stemMode) SeparationBanner(track.status, track.progress, track.statusMessage, settings.serverUrl.isBlank()) {
            graph.separate(track.id)
        }

        // ---- the instrument --------------------------------------------------
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 8.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(listOf(Bus.VOCALS to PadCorner.TopStart, Bus.DRUMS to PadCorner.TopEnd), listOf(Bus.BASS to PadCorner.BottomStart, Bus.OTHER to PadCorner.BottomEnd)).forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (bus, corner) ->
                            StemPad(
                                bus = bus, color = stemColor(bus), corner = corner,
                                gain = mix[bus, Param.GAIN], muted = mix.isMuted(bus), soloed = mix.isSoloed(bus),
                                audible = mix.isAudible(bus), enabled = state.stemMode,
                                effects = mix.activeEffects(bus), level = levels[bus.index],
                                onGain = { player.setParam(bus, Param.GAIN, it) },
                                onAction = { a ->
                                    when (a) {
                                        GestureAction.TOGGLE_MUTE -> player.toggleMute(bus)
                                        GestureAction.TOGGLE_SOLO -> player.toggleSolo(bus)
                                        GestureAction.OPEN_EFFECTS -> onEffects(bus)
                                        GestureAction.RESET_STEM -> player.resetBus(bus)
                                        GestureAction.NONE -> Unit
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxSize(),
                            )
                        }
                    }
                }
            }
            CenterDisc(
                playing = state.playing, progress = if (state.duration > 0) (state.position / state.duration).toFloat() else 0f,
                recording = state.recording, level = levels[4].floatValue,
                modifier = Modifier.align(Alignment.Center),
            ) { haptics.confirm(); player.togglePlay() }
        }

        // ---- one-tap modes ---------------------------------------------------
        val activeMode = QuickMode.detect(mix)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickMode.entries.forEach { m ->
                HardwareKey(m.label, Modifier.weight(1f), active = activeMode == m && state.stemMode, enabled = state.stemMode) { player.quickMode(m) }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- waveform --------------------------------------------------------
        WaveformScrubber(
            peaks = peaks, position = state.position, duration = state.duration,
            loopA = mix.loopA, loopB = mix.loopB, loopOn = mix.loopOn, accent = stemColor(Bus.VOCALS),
            onSeek = { player.seek(it) },
        )
        Spacer(Modifier.height(10.dp))

        // ---- transport keys --------------------------------------------------
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareKey(
                when {
                    mix.loopOn -> "LOOP ON"
                    mix.loopB == -1.0 -> "SET B"
                    else -> "A/B"
                },
                icon = Icons.Rounded.Loop, active = mix.loopOn || mix.loopB == -1.0, color = stemColor(Bus.VOCALS),
            ) { player.abLoopTap() }
            HardwareKey("%.2fx".format(mix.speed), icon = Icons.Rounded.Speed, active = mix.speed != 1f) { showSpeed = true }
            HardwareKey("REV", icon = Icons.Rounded.SwapHoriz, active = mix.reverse, color = stemColor(Bus.OTHER)) { player.setReverse(!mix.reverse) }
            HardwareKey(
                tappedBpm?.let { "TAP %.1f".format(it) } ?: (track.bpm?.let { "%.1f BPM".format(it) } ?: "TAP"),
                active = tappedBpm != null,
            ) { tappedBpm = tap.tap(System.currentTimeMillis()) }
            HardwareKey(
                if (state.recording) "REC ${formatTime(state.recordSeconds)}" else "REC",
                icon = Icons.Rounded.FiberManualRecord, active = state.recording, color = PhaseColors.Danger,
            ) { if (state.recording) player.stopRecording() else player.startRecording() }
        }
        Spacer(Modifier.weight(1f))

        // ---- bottom navigation keys -----------------------------------------
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareKey("LYRICS", Modifier.weight(1f), icon = Icons.Rounded.Lyrics) { onLyrics() }
            HardwareKey("FX STUDIO", Modifier.weight(1f), icon = Icons.Rounded.Tune) { onEffects(if (state.stemMode) Bus.VOCALS else Bus.MASTER) }
            HardwareKey("EXPORT", Modifier.weight(1f), icon = Icons.Rounded.IosShare) { showExport = true }
        }
    }

    if (showSpeed) SpeedDialog(mix.speed, mix.keepPitch, onSpeed = player::setSpeed, onKeep = player::setKeepPitch) { showSpeed = false }
    if (showExport) ExportDialog(onDismiss = { showExport = false })
    if (showSaveProject) NameDialog("Save project", "${track.title} remix", onDismiss = { showSaveProject = false }) { player.saveProject(it) }
    if (showPresets) PresetsDialog(onDismiss = { showPresets = false })
}

@Composable
private fun SeparationBanner(status: TrackStatus, progress: Float, message: String?, noServer: Boolean, onSeparate: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), corner = 16.dp, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFFFB547), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val title = when (status) {
                    TrackStatus.SEPARATING -> "AI separating · ${(progress * 100).toInt()}%"
                    TrackStatus.QUEUED -> "Queued for separation"
                    TrackStatus.FAILED -> "Separation failed"
                    else -> "Full mix · stems locked"
                }
                Text(title, style = PhaseType.body, color = PhaseColors.Chrome)
                Text(
                    when {
                        status == TrackStatus.FAILED -> message ?: "Tap retry"
                        status == TrackStatus.SEPARATING || status == TrackStatus.QUEUED -> message ?: "Working…"
                        noServer -> "Configure a separation server in Settings"
                        else -> "Separate into vocals · drums · bass · other"
                    },
                    style = PhaseType.labelSmall, color = if (status == TrackStatus.FAILED) PhaseColors.Danger else PhaseColors.Steel, maxLines = 2,
                )
            }
            if (status == TrackStatus.READY || status == TrackStatus.FAILED) {
                HardwareKey(if (status == TrackStatus.FAILED) "RETRY" else "SEPARATE", active = true, color = Color(0xFFFFB547), enabled = !noServer, onClick = onSeparate)
            }
        }
        if (status == TrackStatus.SEPARATING) {
            LinearProgressIndicator(progress = { progress }, color = Color(0xFFFFB547), trackColor = PhaseColors.Edge, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(3.dp))
        }
    }
}

/** Central transport disc: liquid-metal rim, progress ring, play/pause. */
@Composable
private fun CenterDisc(playing: Boolean, progress: Float, recording: Boolean, level: Float, modifier: Modifier, onClick: () -> Unit) {
    val pulse by animateFloatAsState(kotlin.math.sqrt(level.coerceIn(0f, 1f)), label = "pulse")
    Box(modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f * pulse), Color.Transparent)), r * (1.0f + 0.15f * pulse))
            drawCircle(PhaseColors.Void, r * 0.96f)
            drawCircle(PhaseColors.metal, r * 0.96f, style = Stroke(r * 0.07f))
            drawArc(
                if (recording) PhaseColors.Danger else PhaseColors.Chrome, -90f, 360f * progress, false,
                topLeft = Offset(r * 0.14f, r * 0.14f), size = Size(r * 1.72f, r * 1.72f), style = Stroke(r * 0.05f, cap = StrokeCap.Round),
            )
            drawCircle(Brush.linearGradient(listOf(Color(0xFF2C2C34), Color(0xFF0C0C10))), r * 0.72f)
        }
        Box(Modifier.size(74.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = PhaseColors.Chrome, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun SpeedDialog(speed: Float, keepPitch: Boolean, onSpeed: (Float) -> Unit, onKeep: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { onSpeed(1f) }) { Text("Reset") } },
        title = { Text("Playback speed") },
        text = {
            Column {
                Text("%.2fx".format(speed), style = PhaseType.title, color = PhaseColors.Chrome)
                Slider(
                    value = speed, onValueChange = { onSpeed((Math.round(it * 20) / 20f)) }, valueRange = 0.5f..2f,
                    colors = SliderDefaults.colors(thumbColor = PhaseColors.Chrome, activeTrackColor = PhaseColors.Chrome),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep pitch", style = PhaseType.body)
                        Text("Off = varispeed (pitch follows speed, like tape)", style = PhaseType.labelSmall, color = PhaseColors.Steel)
                    }
                    Switch(keepPitch, onCheckedChange = onKeep)
                }
            }
        },
    )
}

@Composable
private fun ExportDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val state by graph.player.state.collectAsState()
    val mix by graph.player.mix.collectAsState()
    var format by remember { mutableStateOf(graph.settings.current.exportFormat) }
    var loopOnly by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<app.phase.stem.data.ExportEntity?>(null) }
    val busy = state.exportProgress != null
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Export mix") },
        text = {
            Column {
                Text("Rendered offline through the same engine and effects you hear.", style = PhaseType.labelSmall, color = PhaseColors.Steel)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HardwareKey("WAV", active = format == "wav", enabled = !busy) { format = "wav" }
                    HardwareKey("MP3 ${graph.settings.current.mp3Bitrate}k", active = format == "mp3", enabled = !busy) { format = "mp3" }
                }
                if (mix.loopOn) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Loop region only", style = PhaseType.body, modifier = Modifier.weight(1f))
                        Switch(loopOnly, onCheckedChange = { loopOnly = it }, enabled = !busy)
                    }
                }
                state.exportProgress?.let {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = PhaseColors.Chrome)
                    Text("Rendering ${(it * 100).toInt()}%", style = PhaseType.labelSmall, color = PhaseColors.Steel)
                }
                done?.let { e ->
                    Spacer(Modifier.height(12.dp))
                    Text("Saved: ${java.io.File(e.path).name}", style = PhaseType.labelSmall, color = PhaseColors.Ok)
                }
            }
        },
        confirmButton = {
            when {
                busy -> TextButton(onClick = { graph.player.cancelExport() }) { Text("Cancel") }
                done != null -> TextButton(onClick = { ExportActions.share(context, done!!) }) { Text("Share") }
                else -> TextButton(onClick = { graph.player.export(format, loopOnly) { done = it } }) { Text("Render") }
            }
        },
        dismissButton = { if (!busy) TextButton(onClick = onDismiss) { Text(if (done != null) "Close" else "Cancel") } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PresetsDialog(onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val presets by graph.db.presets().all().collectAsState(initial = emptyList())
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mix presets") },
        text = {
            Column {
                Text("Presets store stem volumes, mutes/solos and every effect setting.", style = PhaseType.labelSmall, color = PhaseColors.Steel)
                Spacer(Modifier.height(8.dp))
                if (presets.isEmpty()) Text("No presets yet.", style = PhaseType.body, color = PhaseColors.Steel)
                LazyColumn(Modifier.height(240.dp)) {
                    items(presets, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { graph.player.applyPreset(p); onDismiss() }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = PhaseColors.Mist, modifier = Modifier.size(18.dp))
                            Text(p.name, style = PhaseType.body, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            IconButton(onClick = { graph.scope.launchIO { graph.db.presets().delete(p.id) } }) {
                                Icon(Icons.Rounded.Delete, "Delete", tint = PhaseColors.Steel)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { saving = true }) { Text("Save current") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (saving) NameDialog("Save preset", "My preset", onDismiss = { saving = false }) { graph.player.savePreset(it) }
}

private fun kotlinx.coroutines.CoroutineScope.launchIO(block: suspend () -> Unit) =
    launch(kotlinx.coroutines.Dispatchers.IO) { block() }
