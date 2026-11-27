package app.phase.stem

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.phase.stem.audio.Bus
import app.phase.stem.ui.components.LocalGraph
import app.phase.stem.ui.components.ProvideGraph
import app.phase.stem.ui.components.TrackArt
import app.phase.stem.ui.effects.EffectsScreen
import app.phase.stem.ui.home.HomeScreen
import app.phase.stem.ui.library.LibraryScreen
import app.phase.stem.ui.lyrics.LyricsScreen
import app.phase.stem.ui.player.PlayerScreen
import app.phase.stem.ui.settings.SettingsScreen
import app.phase.stem.ui.theme.PhaseColors
import app.phase.stem.ui.theme.PhaseTheme
import app.phase.stem.ui.theme.PhaseType

/** MIME types offered in the system picker (OGG is reported several ways). */
private val AUDIO_MIME = arrayOf(
    "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/wave", "audio/flac", "audio/x-flac",
    "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/x-aac", "audio/ogg", "application/ogg", "audio/opus", "audio/*",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
        )
        super.onCreate(savedInstanceState)
        val graph = (application as PhaseApp).graph
        setContent {
            PhaseTheme {
                ProvideGraph(graph) { PhaseRoot() }
            }
        }
    }
}

@Composable
private fun PhaseRoot() {
    val graph = LocalGraph.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val playerState by graph.player.state.collectAsState()
    val appSettings by graph.settings.state.collectAsState()
    LaunchedEffect(appSettings.uiAccent, appSettings.uiBackground) {
        app.phase.stem.ui.theme.UiStyle.accent = Color(appSettings.uiAccent)
        app.phase.stem.ui.theme.UiStyle.background =
            runCatching { app.phase.stem.ui.theme.UiBackground.valueOf(appSettings.uiBackground) }.getOrDefault(app.phase.stem.ui.theme.UiBackground.CARBON)
    }
    val context = androidx.compose.ui.platform.LocalContext.current

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            graph.importer.import(uris)
            // Progress/playback notifications need this on Android 13+.
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val importAction = { picker.launch(AUDIO_MIME) }

    LaunchedEffect(playerState.message, playerState.error) {
        val msg = playerState.error ?: playerState.message
        if (msg != null) {
            graph.player.consumeMessage()
            snackbar.showSnackbar(msg)
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "home"
    val showChrome = route in setOf("home", "library", "settings")

    Box(Modifier.fillMaxSize().background(PhaseColors.background)) {
        val pad = PaddingValues(top = 0.dp, bottom = if (showChrome) (if (playerState.track != null) 150.dp else 86.dp) else 0.dp)
        NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize().then(if (showChrome) Modifier.statusBarsPadding() else Modifier)) {
            composable("home") {
                HomeScreen(
                    pad, onImport = importAction,
                    onMashup = { id -> nav.navigate(if (id != null) "mashup?id=$id" else "mashup") },
                    onOpenTrack = { graph.player.open(it.id); nav.navigate("player") },
                    onOpenProject = { p -> graph.player.open(p.trackId, p); nav.navigate("player") },
                    onSettings = { nav.navigateTab("settings") },
                    onLibrary = { nav.navigateTab("library") },
                )
            }
            composable("library") {
                LibraryScreen(
                    pad, onImport = importAction,
                    onMashup = { id, seed -> nav.navigate(if (id != null) "mashup?id=$id" else "mashup?seed=$seed") },
                    onOpenTrack = { graph.player.open(it.id); nav.navigate("player") },
                    onOpenProject = { p -> graph.player.open(p.trackId, p); nav.navigate("player") },
                )
            }
            composable("settings") { SettingsScreen(pad) }
            composable("player") {
                PlayerScreen(
                    onBack = { nav.popBackStack() },
                    onLyrics = { nav.navigate("lyrics") },
                    onEffects = { bus -> nav.navigate("effects/${bus.index}") },
                    onMashup = { nav.navigate("mashup") },
                )
            }
            composable(
                "mashup?id={id}&seed={seed}",
                arguments = listOf(
                    androidx.navigation.navArgument("id") { nullable = true; defaultValue = null },
                    androidx.navigation.navArgument("seed") { nullable = true; defaultValue = null },
                ),
            ) { entry ->
                app.phase.stem.ui.mashup.MashupScreen(
                    mashupId = entry.arguments?.getString("id"),
                    seedTrackId = entry.arguments?.getString("seed"),
                    onBack = { nav.popBackStack() },
                    onOpenPlayer = { if (!nav.popBackStack("player", false)) nav.navigate("player") },
                    onEffects = { bus -> nav.navigate("effects/${bus.index}") },
                )
            }
            composable("lyrics") { LyricsScreen(onBack = { nav.popBackStack() }) }
            composable("effects/{bus}") { entry ->
                val bus = Bus.of(entry.arguments?.getString("bus")?.toIntOrNull() ?: 0)
                EffectsScreen(bus, onBack = { nav.popBackStack() })
            }
        }
        if (showChrome) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
                if (playerState.track != null) MiniPlayer { nav.navigate("player") }
                BottomBar(route) { nav.navigateTab(it) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (showChrome) 150.dp else 16.dp))
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo("home") { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(route: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(shape)
            .background(Color(0xF0121216)).border(1.dp, Color(0x1AFFFFFF), shape).height(62.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(Triple("home", "HOME", Icons.Rounded.Home), Triple("library", "LIBRARY", Icons.Rounded.LibraryMusic), Triple("settings", "SETTINGS", Icons.Rounded.Settings))
            .forEach { (r, label, icon) ->
                val sel = route == r
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { onSelect(r) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, label, tint = if (sel) PhaseColors.accent else PhaseColors.Steel, modifier = Modifier.size(22.dp))
                    Text(label, style = PhaseType.labelSmall, color = if (sel) PhaseColors.accent else PhaseColors.Steel)
                }
            }
    }
}

@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val graph = LocalGraph.current
    val s by graph.player.state.collectAsState()
    val t = s.track ?: return
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(Color(0xF01A1A20))
            .border(1.dp, Color(0x22FFFFFF), shape).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArt(t, Modifier.size(42.dp), corner = 10.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(t.title, style = PhaseType.body, color = PhaseColors.Chrome, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (s.stemMode) "4 STEMS" else "FULL MIX", style = PhaseType.labelSmall, color = PhaseColors.Steel)
            }
            IconButton(onClick = { graph.player.togglePlay() }) {
                Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = PhaseColors.Chrome)
            }
        }
        LinearProgressIndicator(
            progress = { if (s.duration > 0) (s.position / s.duration).toFloat() else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp), color = PhaseColors.accent, trackColor = Color.Transparent,
        )
    }
}
