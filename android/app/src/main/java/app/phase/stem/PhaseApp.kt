package app.phase.stem

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.phase.stem.audio.PlaybackService
import app.phase.stem.audio.PlayerController
import app.phase.stem.data.PhaseDatabase
import app.phase.stem.data.SettingsStore
import app.phase.stem.data.Storage
import app.phase.stem.data.TrackStatus
import app.phase.stem.importer.TrackImporter
import app.phase.stem.lyrics.LrcLibProvider
import app.phase.stem.lyrics.LyricsProvider
import app.phase.stem.separation.OnDeviceSeparationProvider
import app.phase.stem.separation.RemoteSeparationProvider
import app.phase.stem.separation.SeparationProvider
import app.phase.stem.separation.SeparationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph — small enough not to need a DI framework. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val db = PhaseDatabase.create(app)
    val storage = Storage(app)
    val settings = SettingsStore(app)
    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val player = PlayerController(app, db, storage, settings, scope)
    val importer = TrackImporter(app, db, storage, settings, scope)
    val lyricsProviders: List<LyricsProvider> = listOf(LrcLibProvider(http))

    private val onDeviceProvider by lazy { OnDeviceSeparationProvider(app) }

    /** The active separation backend: on-device by default, a server if chosen. */
    fun separationProvider(): SeparationProvider =
        if (settings.current.onDevice) onDeviceProvider
        else RemoteSeparationProvider(settings.current.serverUrl, settings.current.serverToken, http)

    fun separate(trackId: String) {
        scope.launch {
            withContext(Dispatchers.IO) { db.tracks().setStatus(trackId, TrackStatus.QUEUED, 0f, "Queued for AI separation") }
            SeparationWorker.enqueue(app, trackId)
        }
    }

    fun cancelSeparation(trackId: String) = SeparationWorker.cancel(app, trackId)

    fun toggleFavorite(trackId: String, fav: Boolean) = scope.launch(Dispatchers.IO) { db.tracks().setFavorite(trackId, fav) }

    fun deleteTrack(trackId: String) = scope.launch {
        if (player.state.value.track?.id == trackId) player.close()
        SeparationWorker.cancel(app, trackId)
        withContext(Dispatchers.IO) {
            db.projects().deleteForTrack(trackId)
            db.tracks().delete(trackId)
            storage.deleteTrack(trackId)
        }
    }

    /** Frees stem cache for a track; it can be re-separated later. */
    fun clearStems(trackId: String) = scope.launch { clearStemsNow(trackId) }

    /** Drops cached stems and separates again (e.g. with a different model). */
    fun reseparate(trackId: String) = scope.launch {
        clearStemsNow(trackId)
        withContext(Dispatchers.IO) { db.tracks().setStatus(trackId, TrackStatus.QUEUED, 0f, "Queued for AI separation") }
        SeparationWorker.enqueue(app, trackId)
    }

    private suspend fun clearStemsNow(trackId: String) {
        if (player.state.value.track?.id == trackId) player.close()
        withContext(Dispatchers.IO) {
            storage.deleteStems(trackId)
            db.tracks().get(trackId)?.let { db.tracks().update(it.copy(status = TrackStatus.READY, stemSampleRate = 0, progress = 0f, statusMessage = null)) }
        }
    }
}

class PhaseApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch(Dispatchers.IO) { graph.importer.recoverInterrupted() }

        // Background playback service follows play state.
        graph.player.onPlayStateChanged = { playing -> if (playing) PlaybackService.start(this) }

        // Hot-swap the player from full mix to stems the moment separation ends.
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        graph.scope.launch {
            graph.player.state.map { it.track?.id }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else graph.db.tracks().observe(id) }
                .filterNotNull()
                .collect { graph.player.refreshIfSeparated(it) }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = graph.player.onBackground()
        })
    }
}
