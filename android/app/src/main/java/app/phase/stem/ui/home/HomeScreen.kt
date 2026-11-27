package app.phase.stem.ui.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.phase.stem.data.ProjectEntity
import app.phase.stem.data.TrackEntity
import app.phase.stem.data.TrackStatus
import app.phase.stem.ui.components.EmptyState
import app.phase.stem.ui.components.GlassPanel
import app.phase.stem.ui.components.HardwareKey
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.MetalTitle
import app.phase.stem.ui.components.SectionLabel
import app.phase.stem.ui.components.TrackArt
import app.phase.stem.ui.components.TrackRow
import app.phase.stem.ui.components.appSettings
import app.phase.stem.ui.components.rememberHaptics
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onImport: () -> Unit,
    onOpenTrack: (TrackEntity) -> Unit,
    onOpenProject: (ProjectEntity) -> Unit,
    onSettings: () -> Unit,
    onLibrary: () -> Unit,
    onMashup: (String?) -> Unit,
) {
    val graph = LocalGraph.current
    val tracks by graph.db.tracks().all().collectAsState(initial = emptyList())
    val recent by graph.db.tracks().recentlyPlayed(10).collectAsState(initial = emptyList())
    val projects by graph.db.projects().all().collectAsState(initial = emptyList())
    val mashups by graph.db.mashups().all().collectAsState(initial = emptyList())
    val importErrors by graph.importer.errors.collectAsState()
    val settings = appSettings()
    val processing = tracks.filter { it.status in setOf(TrackStatus.IMPORTING, TrackStatus.QUEUED, TrackStatus.SEPARATING) }
    val byId = tracks.associateBy { it.id }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(app.phase.stem.R.drawable.logo_phase), "Phase",
                    modifier = Modifier.height(64.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Settings, "Settings", tint = PhaseColors.Mist)
                }
            }
        }
        item { ImportDial(onImport) }
        item {
            val separated = tracks.count { it.isSeparated }
            HardwareKey(
                if (separated >= 2) "CREATE MASHUP" else "MASHUP · needs 2 separated songs",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                icon = Icons.Rounded.Layers, active = separated >= 2, color = Color(0xFFFF4D9A),
                enabled = separated >= 1,
            ) { onMashup(null) }
        }
        if (!settings.canSeparate) item {
            GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), glow = Color(0xFFFFB547)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Color(0xFFFFB547))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Server mode needs an address", style = PhaseType.body, color = PhaseColors.Chrome)
                        Text(
                            "You chose server separation but no server is set. Add one, or switch back to on-device AI in Settings.",
                            style = PhaseType.labelSmall, color = PhaseColors.Steel,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                HardwareKey("SET UP", active = true, color = Color(0xFFFFB547), onClick = onSettings)
            }
        }
        if (importErrors.isNotEmpty()) item {
            GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), glow = PhaseColors.Danger) {
                importErrors.takeLast(3).forEach { Text("${it.name}: ${it.message}", style = PhaseType.labelSmall, color = PhaseColors.Danger) }
                Spacer(Modifier.height(8.dp))
                HardwareKey("DISMISS") { graph.importer.clearErrors() }
            }
        }
        if (processing.isNotEmpty()) {
            item { SectionLabel("Processing", Modifier.padding(horizontal = 20.dp)) }
            items(processing, key = { "p" + it.id }) { t ->
                TrackRow(t, Modifier.padding(horizontal = 14.dp), onClick = { if (t.isPlayable) onOpenTrack(t) })
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionLabel("Recently played", Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.id }) { t -> RecentCard(t) { onOpenTrack(t) } }
                }
            }
        }
        if (projects.isNotEmpty() || mashups.isNotEmpty()) {
            item { SectionLabel("Your mixes", Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            items(mashups.take(5), key = { "x" + it.id }) { x ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp)).clickable { onMashup(x.id) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FF4D9A)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Layers, null, tint = Color(0xFFFF4D9A))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(x.name, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("MASHUP", style = PhaseType.labelSmall, color = Color(0xFFFF4D9A))
                    }
                    IconButton(onClick = { graph.deleteMashup(x.id) }) {
                        Icon(Icons.Rounded.Delete, "Delete mashup", tint = PhaseColors.Steel)
                    }
                }
            }
            items(projects.take(5), key = { "m" + it.id }) { p ->
                val t = byId[p.trackId]
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp)).clickable { onOpenProject(p) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackArt(t, Modifier.size(44.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(p.name, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t?.title ?: "Missing track", style = PhaseType.labelSmall, color = PhaseColors.Steel, maxLines = 1)
                    }
                    IconButton(onClick = { graph.deleteProject(p.id) }) {
                        Icon(Icons.Rounded.Delete, "Delete mix", tint = PhaseColors.Steel)
                    }
                }
            }
        }
        item {
            SectionLabel("Recently imported", Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                if (tracks.size > 6) Text("SEE ALL", style = PhaseType.labelSmall, color = PhaseColors.Mist, modifier = Modifier.clickable(onClick = onLibrary).padding(6.dp))
            }
        }
        val imported = tracks.filter { it.status != TrackStatus.IMPORTING }.take(6)
        if (imported.isEmpty()) item {
            EmptyState(Icons.Rounded.LibraryMusic, "Your studio is empty", "Import MP3, WAV, FLAC, M4A, AAC or OGG files from your phone to begin.")
        }
        items(imported, key = { "i" + it.id }) { t -> TrackRow(t, Modifier.padding(horizontal = 14.dp), onClick = { onOpenTrack(t) }) }
    }
}

/** The big tactile import control. */
@Composable
private fun ImportDial(onImport: () -> Unit) {
    val haptics = rememberHaptics()
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(176.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF26262E), Color(0xFF0B0B0E))))
                .border(3.dp, PhaseColors.metal, CircleShape)
                .clickable { haptics.confirm(); onImport() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(128.dp).clip(CircleShape).border(1.dp, Color(0x22FFFFFF), CircleShape))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Add, null, tint = PhaseColors.Chrome, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(4.dp))
                Text("IMPORT", style = PhaseType.label, color = PhaseColors.Chrome)
                Text("FROM PHONE", style = PhaseType.labelSmall, color = PhaseColors.Steel)
            }
        }
    }
}

@Composable
private fun RecentCard(t: TrackEntity, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        TrackArt(t, Modifier.size(132.dp), corner = 16.dp)
        Spacer(Modifier.height(6.dp))
        Text(t.title, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (t.isSeparated) "4 STEMS" else "FULL MIX", style = PhaseType.labelSmall, color = if (t.isSeparated) PhaseColors.Ok else PhaseColors.Steel)
    }
}
