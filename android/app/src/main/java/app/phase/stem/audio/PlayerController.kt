package app.phase.stem.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import app.phase.stem.data.ExportEntity
import app.phase.stem.data.PhaseDatabase
import app.phase.stem.data.PresetEntity
import app.phase.stem.data.ProjectEntity
import app.phase.stem.data.SettingsStore
import app.phase.stem.data.Storage
import app.phase.stem.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PlayerState(
    val track: TrackEntity? = null,
    val stemMode: Boolean = false,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val projectId: String? = null,
    val projectName: String? = null,
    val recording: Boolean = false,
    val recordSeconds: Double = 0.0,
    val exportProgress: Float? = null,
    val error: String? = null,
    val message: String? = null,
)

/**
 * Owns the single native engine session. UI talks only to this class.
 * Mix changes are applied to the engine immediately (lock-free atomics on
 * the native side) and mirrored in [mix] for the UI, then autosaved.
 */
class PlayerController(
    private val context: Context,
    private val db: PhaseDatabase,
    private val storage: Storage,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _mix = MutableStateFlow(MixState())
    val mix: StateFlow<MixState> = _mix.asStateFlow()
    private val _peaks = MutableStateFlow(FloatArray(0))
    /** Waveform peaks of the loaded track (0..1), ~20 ms resolution. */
    val peaks: StateFlow<FloatArray> = _peaks.asStateFlow()

    private val loadMutex = Mutex()
    private var ticker: Job? = null
    private var saveJob: Job? = null
    private var outputStarted = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    var onPlayStateChanged: ((Boolean) -> Unit)? = null

    // ------------------------------------------------------------ output ---

    fun ensureOutput(): Boolean {
        if (outputStarted && NativeEngine.nIsOutputRunning()) return true
        outputStarted = NativeEngine.nStartOutput(settings.current.lowLatency)
        if (!outputStarted) _state.update { it.copy(error = "Could not open the audio output") }
        return outputStarted
    }

    /** Called when the app goes to background: release the device if idle. */
    fun onBackground() {
        if (!NativeEngine.nIsPlaying() && !NativeEngine.nIsRecording()) {
            NativeEngine.nStopOutput()
            outputStarted = false
        }
    }

    fun restartOutput() {
        NativeEngine.nStopOutput()
        outputStarted = false
        if (_state.value.track != null) ensureOutput()
    }

    // ------------------------------------------------------------ loading ---

    /** Opens a track (and optionally a saved project's mix). */
    fun open(trackId: String, project: ProjectEntity? = null) {
        scope.launch {
            loadMutex.withLock {
                val t = withContext(Dispatchers.IO) { db.tracks().get(trackId) } ?: return@withLock
                if (!t.isPlayable) {
                    _state.update { it.copy(error = "Track is still importing") }
                    return@withLock
                }
                val current = _state.value
                if (current.track?.id == trackId && project == null &&
                    current.stemMode == (t.isSeparated && storage.hasAllStems(t.id))
                ) {
                    _state.update { it.copy(track = t) }
                    return@withLock
                }
                saveNow()
                if (NativeEngine.nIsRecording()) stopRecording()
                loadInternal(t, resumeAt = 0.0, play = false)
                val mixState = project?.let { MixState.fromJson(it.mixJson) } ?: MixState.fromJson(t.lastMixJson) ?: MixState()
                applyMix(mixState)
                _state.update { it.copy(projectId = project?.id, projectName = project?.name) }
                withContext(Dispatchers.IO) { db.tracks().touchPlayed(t.id, System.currentTimeMillis()) }
            }
        }
    }

    /** Hot-swaps from full mix to stems (after separation) without losing place. */
    fun refreshIfSeparated(t: TrackEntity) {
        val s = _state.value
        if (s.track?.id != t.id) return
        _state.update { it.copy(track = t) }
        if (!s.stemMode && t.isSeparated && storage.hasAllStems(t.id)) {
            scope.launch {
                loadMutex.withLock {
                    val pos = NativeEngine.nPosition()
                    val wasPlaying = NativeEngine.nIsPlaying()
                    loadInternal(t, pos, wasPlaying)
                    applyMix(_mix.value)
                    _state.update { it.copy(message = "Stems ready — swipe to remix") }
                }
            }
        }
    }

    private suspend fun loadInternal(t: TrackEntity, resumeAt: Double, play: Boolean) {
        _state.update { it.copy(loading = true, error = null) }
        val stems = t.isSeparated && storage.hasAllStems(t.id)
        val paths = if (stems) Bus.stems.map { storage.stemPcm(t.id, it).absolutePath }
        else listOf(storage.mixPcm(t.id).absolutePath)
        val rate = if (stems) t.stemSampleRate else t.sampleRate
        ensureOutput()
        val ok = withContext(Dispatchers.IO) { NativeEngine.nLoad(paths.toTypedArray(), rate) }
        if (!ok) {
            _state.update { it.copy(loading = false, error = "Could not load audio: ${NativeEngine.nLastError()}") }
            return
        }
        if (resumeAt > 0) NativeEngine.nSeek(resumeAt)
        if (play) setPlaying(true)
        _peaks.value = withContext(Dispatchers.IO) { AudioDecoder.loadPeaks(storage.mixPeaks(t.id), 2000) }
        _state.update {
            it.copy(
                track = t, stemMode = stems, loading = false, duration = NativeEngine.nDuration(),
                position = NativeEngine.nPosition(), playing = NativeEngine.nIsPlaying(),
            )
        }
        startTicker()
    }

    fun close() {
        saveNow()
        setPlaying(false)
        NativeEngine.nUnload()
        _state.value = PlayerState()
        _peaks.value = FloatArray(0)
    }

    // ---------------------------------------------------------- transport ---

    fun togglePlay() = setPlaying(!NativeEngine.nIsPlaying())

    fun setPlaying(play: Boolean) {
        if (_state.value.track == null) return
        if (play) {
            if (!ensureOutput()) return
            if (!requestFocus()) {
                _state.update { it.copy(message = "Another app is holding audio focus") }
                return
            }
            registerNoisy()
        }
        NativeEngine.nSetPlaying(play)
        _state.update { it.copy(playing = play) }
        onPlayStateChanged?.invoke(play)
        if (!play) saveSoon()
    }

    fun seek(seconds: Double) {
        NativeEngine.nSeek(seconds)
        _state.update { it.copy(position = seconds.coerceIn(0.0, it.duration)) }
    }

    fun skip(deltaSeconds: Double) = seek(NativeEngine.nPosition() + deltaSeconds)

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val playing = NativeEngine.nIsPlaying()
                if (NativeEngine.nConsumeEnded()) onPlayStateChanged?.invoke(false)
                _state.update {
                    it.copy(
                        position = NativeEngine.nPosition(), playing = playing,
                        recording = NativeEngine.nIsRecording(),
                        recordSeconds = if (it.recording) NativeEngine.nRecordingSeconds() else 0.0,
                    )
                }
                delay(if (playing) 33 else 150)
            }
        }
    }

    // --------------------------------------------------------------- mix ---

    private fun applyMix(m: MixState) {
        Bus.entries.forEach { b -> Param.entries.forEach { p -> NativeEngine.setParam(b, p, m[b, p]) } }
        NativeEngine.nSetSpeed(m.speed)
        NativeEngine.nSetKeepPitch(m.keepPitch)
        NativeEngine.nSetReverse(m.reverse)
        NativeEngine.nSetLoop(m.loopOn, m.loopA, m.loopB)
        _mix.value = m
    }

    fun setParam(bus: Bus, p: Param, v: Float) {
        NativeEngine.setParam(bus, p, v)
        _mix.update { it.with(bus, p, v) }
        saveSoon()
    }

    fun toggleMute(bus: Bus) {
        val muting = !_mix.value.isMuted(bus)
        setParam(bus, Param.MUTE, if (muting) 1f else 0f)
        // Muting a soloed stem also releases its solo.
        if (muting && _mix.value.isSoloed(bus)) setParam(bus, Param.SOLO, 0f)
    }

    fun toggleSolo(bus: Bus) {
        val soloed = _mix.value.isSoloed(bus)
        setParam(bus, Param.SOLO, if (soloed) 0f else 1f)
        if (!soloed && _mix.value.isMuted(bus)) setParam(bus, Param.MUTE, 0f)
    }

    fun resetBus(bus: Bus) {
        val m = _mix.value.resetBus(bus)
        Param.entries.forEach { p -> NativeEngine.setParam(bus, p, m[bus, p]) }
        _mix.value = m
        saveSoon()
    }

    fun resetEffects(bus: Bus, params: List<Param>) = params.forEach { setParam(bus, it, it.default) }

    fun quickMode(mode: QuickMode) {
        val m = mode.apply(_mix.value)
        Bus.stems.forEach { b ->
            NativeEngine.setParam(b, Param.MUTE, m[b, Param.MUTE])
            NativeEngine.setParam(b, Param.SOLO, m[b, Param.SOLO])
        }
        _mix.value = m
        saveSoon()
    }

    fun setSpeed(s: Float) {
        NativeEngine.nSetSpeed(s)
        _mix.update { it.copy(speed = s) }
        saveSoon()
    }

    fun setKeepPitch(k: Boolean) {
        NativeEngine.nSetKeepPitch(k)
        _mix.update { it.copy(keepPitch = k) }
        saveSoon()
    }

    fun setReverse(r: Boolean) {
        NativeEngine.nSetReverse(r)
        _mix.update { it.copy(reverse = r) }
        saveSoon()
    }

    fun setLoop(on: Boolean, a: Double = _mix.value.loopA, b: Double = _mix.value.loopB) {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        NativeEngine.nSetLoop(on, lo, hi)
        _mix.update { it.copy(loopOn = on, loopA = lo, loopB = hi) }
        saveSoon()
    }

    /** A/B loop capture: first call sets A, second sets B and enables the loop, third clears. */
    fun abLoopTap() {
        val m = _mix.value
        val pos = NativeEngine.nPosition()
        when {
            m.loopOn -> setLoop(false, 0.0, 0.0)
            m.loopB == -1.0 -> {
                if (pos - m.loopA > 0.1) setLoop(true, m.loopA, pos) else setLoop(false, 0.0, 0.0)
            }
            else -> _mix.update { it.copy(loopA = pos, loopB = -1.0, loopOn = false) }
        }
    }

    // -------------------------------------------------- projects/presets ---

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            saveNow()
        }
    }

    private fun saveNow() {
        val id = _state.value.track?.id ?: return
        val json = _mix.value.toJson()
        scope.launch(Dispatchers.IO) {
            db.tracks().saveMix(id, json)
            _state.value.projectId?.let { pid ->
                db.projects().get(pid)?.let { db.projects().upsert(it.copy(mixJson = json, updatedAt = System.currentTimeMillis())) }
            }
        }
    }

    fun saveProject(name: String) {
        val t = _state.value.track ?: return
        val now = System.currentTimeMillis()
        val p = ProjectEntity(UUID.randomUUID().toString(), t.id, name.ifBlank { "${t.title} remix" }, _mix.value.toJson(), now, now)
        scope.launch(Dispatchers.IO) { db.projects().upsert(p) }
        _state.update { it.copy(projectId = p.id, projectName = p.name, message = "Project saved") }
    }

    fun savePreset(name: String) {
        val p = PresetEntity(UUID.randomUUID().toString(), name.ifBlank { "Preset" }, _mix.value.toJson(includeTransport = false), System.currentTimeMillis())
        scope.launch(Dispatchers.IO) { db.presets().upsert(p) }
        _state.update { it.copy(message = "Preset “${p.name}” saved") }
    }

    fun applyPreset(p: PresetEntity) {
        val preset = MixState.fromJson(p.mixJson) ?: return
        applyMix(_mix.value.withBusesFrom(preset))
        _state.update { it.copy(message = "Applied “${p.name}”") }
        saveSoon()
    }

    // --------------------------------------------------- record & export ---

    private fun outName(t: TrackEntity, suffix: String, ext: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = t.title.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().take(40).ifBlank { "track" }
        return File(storage.exportsDir, "$safe - $suffix $stamp.$ext")
    }

    fun startRecording() {
        val t = _state.value.track ?: return
        if (!ensureOutput()) return
        val fmt = settings.current.recordFormat
        val f = outName(t, "live", fmt)
        if (NativeEngine.nStartRecording(f.absolutePath, fmt, settings.current.mp3Bitrate)) {
            _state.update { it.copy(recording = true, message = "Recording live mix") }
            pendingRecording = f
            if (!NativeEngine.nIsPlaying()) setPlaying(true)
        } else {
            _state.update { it.copy(error = "Recording failed: ${NativeEngine.nLastError()}") }
        }
    }

    private var pendingRecording: File? = null

    fun stopRecording() {
        val secs = NativeEngine.nStopRecording()
        val f = pendingRecording ?: return
        pendingRecording = null
        val t = _state.value.track
        _state.update { it.copy(recording = false) }
        if (secs <= 0.05 || !f.exists()) {
            f.delete()
            return
        }
        scope.launch(Dispatchers.IO) {
            db.exports().insert(
                ExportEntity(
                    UUID.randomUUID().toString(), t?.id, "${t?.title ?: "Recording"} · live take", f.absolutePath,
                    f.extension, "recording", (secs * 1000).toLong(), f.length(), System.currentTimeMillis(),
                ),
            )
        }
        _state.update { it.copy(message = "Recording saved (%.1f s)".format(secs)) }
    }

    /** Offline render of the current mix (same DSP as playback). */
    fun export(format: String = settings.current.exportFormat, loopOnly: Boolean = false, onDone: (ExportEntity?) -> Unit = {}) {
        val t = _state.value.track ?: return
        if (_state.value.exportProgress != null) return
        val m = _mix.value
        val f = outName(t, "mix", format)
        val (a, b) = if (loopOnly && m.loopOn) m.loopA to m.loopB else 0.0 to -1.0
        _state.update { it.copy(exportProgress = 0f) }
        scope.launch {
            val poll = launch {
                while (isActive) {
                    _state.update { it.copy(exportProgress = NativeEngine.nExportProgress()) }
                    delay(100)
                }
            }
            val ok = withContext(Dispatchers.Default) {
                NativeEngine.nExport(f.absolutePath, format, settings.current.mp3Bitrate, a, b, true)
            }
            poll.cancel()
            var entity: ExportEntity? = null
            if (ok) {
                val durMs = (((if (b > a) b - a else _state.value.duration)) / m.speed * 1000).toLong()
                entity = ExportEntity(
                    UUID.randomUUID().toString(), t.id, "${t.title} · ${_state.value.projectName ?: "remix"}",
                    f.absolutePath, format, "export", durMs, f.length(), System.currentTimeMillis(),
                )
                withContext(Dispatchers.IO) { db.exports().insert(entity) }
                _state.update { it.copy(exportProgress = null, message = "Exported ${f.name}") }
            } else {
                val err = NativeEngine.nLastError()
                _state.update { it.copy(exportProgress = null, error = if (err == "cancelled") null else "Export failed: $err") }
            }
            onDone(entity)
        }
    }

    fun cancelExport() = NativeEngine.nCancelExport()

    fun consumeMessage() = _state.update { it.copy(message = null, error = null) }

    // ------------------------------------------------- focus & noisy ------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocus = false; setPlaying(false) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (NativeEngine.nIsPlaying()) { pausedByFocus = true; setPlaying(false) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; setPlaying(true) }
        }
    }

    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(true)
            .build().also { focusRequest = it }
        return audioManager.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) setPlaying(false) // headphones unplugged
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }
}
