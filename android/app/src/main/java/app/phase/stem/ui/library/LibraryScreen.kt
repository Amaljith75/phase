package app.phase.stem.ui.library

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.phase.stem.data.ExportEntity
import app.phase.stem.data.ProjectEntity
import app.phase.stem.data.TrackEntity
import app.phase.stem.data.TrackStatus
import app.phase.stem.export.ExportActions
import app.phase.stem.ui.components.EmptyState
import app.phase.stem.ui.components.HardwareKey
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.MetalTitle
import app.phase.stem.ui.components.TrackArt
import app.phase.stem.ui.components.TrackRow
import app.phase.stem.ui.components.formatBytes
import app.phase.stem.ui.components.formatTime
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private enum class Tab(val label: String) { ALL("ALL"), STEMS("SEPARATED"), FAV("FAVORITES"), MIXES("MIXES") }

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    onImport: () -> Unit,
    onOpenTrack: (TrackEntity) -> Unit,
    onOpenProject: (ProjectEntity) -> Unit,
    onMashup: (id: String?, seedTrackId: String?) -> Unit,
) {
    val graph = LocalGraph.current
    val tracks by graph.db.tracks().all().collectAsState(initial = emptyList())
    val projects by graph.db.projects().all().collectAsState(initial = emptyList())
    val exports by graph.db.exports().all().collectAsState(initial = emptyList())
    val mashups by graph.db.mashups().all().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(Tab.ALL) }
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<TrackEntity?>(null) }
    val q = query.trim().lowercase()
    fun TrackEntity.matches() = q.isEmpty() || title.lowercase().contains(q) || artist?.lowercase()?.contains(q) == true || album?.lowercase()?.contains(q) == true
    val shown = when (tab) {
        Tab.ALL -> tracks
        Tab.STEMS -> tracks.filter { it.isSeparated }
        Tab.FAV -> tracks.filter { it.favorite }
        Tab.MIXES -> emptyList()
    }.filter { it.matches() }
    val byId = tracks.associateBy { it.id }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MetalTitle("LIBRARY", PhaseType.title.copy(letterSpacing = PhaseType.label.letterSpacing), Modifier.weight(1f))
                HardwareKey("IMPORT", icon = Icons.Rounded.Add, onClick = onImport)
            }
        }
        item {
            OutlinedTextField(
                query, { query = it }, singleLine = true,
                placeholder = { Text("Search title, artist, album") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = PhaseColors.Edge, focusedBorderColor = PhaseColors.Mist),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tab.entries.forEach { t -> HardwareKey(t.label, Modifier.weight(1f), active = tab == t) { tab = t } }
            }
        }
        if (tab != Tab.MIXES) {
            if (shown.isEmpty()) item {
                EmptyState(
                    Icons.Rounded.LibraryMusic,
                    if (q.isNotEmpty()) "No matches" else "Nothing here yet",
                    when (tab) {
                        Tab.FAV -> "Star tracks to keep them close."
                        Tab.STEMS -> "Separated tracks appear here once AI processing finishes."
                        else -> "Import songs from your phone storage."
                    },
                )
            }
            items(shown, key = { it.id }) { t ->
                TrackRow(t, Modifier.padding(horizontal = 14.dp), onClick = { if (t.isPlayable) onOpenTrack(t) }) {
                    IconButton(onClick = { graph.toggleFavorite(t.id, !t.favorite) }) {
                        Icon(if (t.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite", tint = if (t.favorite) PhaseColors.Accent else PhaseColors.Steel)
                    }
                    TrackMenu(t, onDelete = { confirmDelete = t }, onMashup = { onMashup(null, t.id) })
                }
            }
        } else {
            if (mashups.isNotEmpty()) item { Label("Mashups") }
            items(mashups.filter { x -> q.isEmpty() || x.name.lowercase().contains(q) }, key = { "x" + it.id }) { x ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp)).clickable { onMashup(x.id, null) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = androidx.compose.ui.graphics.Color(0xFFFF4D9A), modifier = Modifier.size(22.dp))
                    Text(x.name, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    IconButton(onClick = { graph.scope.launch(Dispatchers.IO) { graph.db.mashups().delete(x.id) } }) {
                        Icon(Icons.Rounded.Close, "Delete mashup", tint = PhaseColors.Steel)
                    }
                }
            }
            if (projects.isEmpty() && exports.isEmpty() && mashups.isEmpty()) item {
                EmptyState(Icons.Rounded.GraphicEq, "No mixes yet", "Save a project from the player, record a live take or export a mix.")
            }
            if (projects.isNotEmpty()) item { Label("Projects") }
            items(projects.filter { p -> q.isEmpty() || p.name.lowercase().contains(q) }, key = { "p" + it.id }) { p ->
                ProjectRow(p, byId[p.trackId], onOpen = { onOpenProject(p) }, onDelete = { graph.scope.launch(Dispatchers.IO) { graph.db.projects().delete(p.id) } })
            }
            if (exports.isNotEmpty()) item { Label("Exports & recordings") }
            items(exports.filter { e -> q.isEmpty() || e.title.lowercase().contains(q) }, key = { "e" + it.id }) { e -> ExportRow(e) }
        }
    }

    confirmDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${t.title}”?") },
            text = { Text("Removes the imported copy, cached stems and its projects. Your original file on the phone is not touched.") },
            confirmButton = { TextButton(onClick = { graph.deleteTrack(t.id); confirmDelete = null }) { Text("Delete", color = PhaseColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = PhaseType.label, color = PhaseColors.Steel, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
}

@Composable
private fun TrackMenu(t: TrackEntity, onDelete: () -> Unit, onMashup: () -> Unit) {
    val graph = LocalGraph.current
    var open by remember { mutableStateOf(false) }
    val hasServer = graph.settings.current.canSeparate
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = PhaseColors.Steel) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            when (t.status) {
                TrackStatus.READY, TrackStatus.FAILED -> DropdownMenuItem(
                    text = { Text(if (hasServer) "Separate stems (AI)" else "Separate stems (set server first)") }, enabled = hasServer,
                    onClick = { open = false; graph.separate(t.id) },
                )
                TrackStatus.QUEUED, TrackStatus.SEPARATING -> DropdownMenuItem(text = { Text("Cancel separation") }, onClick = { open = false; graph.cancelSeparation(t.id) })
                TrackStatus.SEPARATED -> {
                    DropdownMenuItem(text = { Text("Re-separate") }, enabled = hasServer, onClick = {
                        open = false
                        graph.reseparate(t.id)
                    })
                    DropdownMenuItem(text = { Text("Clear stem cache") }, onClick = { open = false; graph.clearStems(t.id) })
                }
                else -> Unit
            }
            if (t.isSeparated) DropdownMenuItem(text = { Text("Add to mashup") }, onClick = { open = false; onMashup() })
            DropdownMenuItem(text = { Text("Delete", color = PhaseColors.Danger) }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun ProjectRow(p: ProjectEntity, t: TrackEntity?, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = t != null, onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArt(t, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.name, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("PROJECT · ${t?.title ?: "track deleted"}", style = PhaseType.labelSmall, color = PhaseColors.Steel, maxLines = 1)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Close, "Delete project", tint = PhaseColors.Steel) }
    }
}

@Composable
private fun ExportRow(e: ExportEntity) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val file = File(e.path)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (e.kind == "recording") Icons.Rounded.FiberManualRecord else Icons.Rounded.GraphicEq, null,
                tint = if (e.kind == "recording") PhaseColors.Danger else PhaseColors.Mist, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${e.format.uppercase()} · ${formatTime(e.durationMs / 1000.0)} · ${formatBytes(e.sizeBytes)}${if (!file.exists()) " · missing" else ""}",
                    style = PhaseType.labelSmall, color = PhaseColors.Steel,
                )
            }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = PhaseColors.Steel) }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Play in system player") }, enabled = file.exists(), onClick = {
                        open = false
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, ExportActions.mimeOf(e.format)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }.onFailure { note = "No app can play this file" }
                    })
                    DropdownMenuItem(text = { Text("Share") }, enabled = file.exists(), onClick = { open = false; ExportActions.share(context, e) })
                    DropdownMenuItem(text = { Text("Save to Music folder") }, enabled = file.exists(), onClick = {
                        open = false
                        scope.launch {
                            note = runCatching { "Saved to " + ExportActions.saveToMusic(context, e) }.getOrElse { "Save failed: ${it.message}" }
                        }
                    })
                    DropdownMenuItem(text = { Text("Delete", color = PhaseColors.Danger) }, onClick = {
                        open = false
                        graph.scope.launch(Dispatchers.IO) {
                            file.delete()
                            graph.db.exports().delete(e.id)
                        }
                    })
                }
            }
        }
        note?.let { Text(it, style = PhaseType.labelSmall, color = PhaseColors.Mist, modifier = Modifier.padding(start = 42.dp, bottom = 6.dp)) }
    }
}
