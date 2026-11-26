package app.phase.stem.importer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import app.phase.stem.analysis.BpmDetector
import app.phase.stem.audio.AudioDecoder
import app.phase.stem.data.PhaseDatabase
import app.phase.stem.data.SettingsStore
import app.phase.stem.data.Storage
import app.phase.stem.data.TrackEntity
import app.phase.stem.data.TrackStatus
import app.phase.stem.separation.SeparationWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Imports user-picked audio (Storage Access Framework URIs): copies the file
 * into private storage, reads tags/artwork, decodes the full mix for instant
 * playback, detects BPM and (optionally) queues AI separation.
 */
class TrackImporter(
    private val context: Context,
    private val db: PhaseDatabase,
    private val storage: Storage,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    data class ImportError(val name: String, val message: String)

    private val _errors = MutableStateFlow<List<ImportError>>(emptyList())
    val errors: StateFlow<List<ImportError>> = _errors
    private val mutex = Mutex() // decode one file at a time to bound memory/IO

    fun clearErrors() { _errors.value = emptyList() }

    fun import(uris: List<Uri>) {
        uris.forEach { uri -> scope.launch(Dispatchers.IO) { mutex.withLock { importOne(uri) } } }
    }

    private suspend fun importOne(uri: Uri) {
        val resolver = context.contentResolver
        var name = "Untitled"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) 0 else c.getLong(1)
            }
        }
        val mime = resolver.getType(uri)
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() && it.length <= 5 } ?: "audio"
        if (mime != null && !mime.startsWith("audio/") && mime != "application/ogg" && mime != "application/octet-stream") {
            _errors.value += ImportError(name, "Not an audio file ($mime)")
            return
        }
        val id = UUID.randomUUID().toString()
        val dir = storage.trackDir(id).apply { mkdirs() }
        val src = File(dir, "source.$ext")
        val baseTitle = name.substringBeforeLast('.')
        try {
            db.tracks().upsert(
                TrackEntity(
                    id = id, title = baseTitle, artist = null, album = null, durationMs = 0,
                    sourceFileName = src.name, mimeType = mime, hasArtwork = false,
                    importedAt = System.currentTimeMillis(), status = TrackStatus.IMPORTING,
                    statusMessage = "Copying", sizeBytes = size,
                ),
            )
            resolver.openInputStream(uri)?.use { input -> src.outputStream().use { input.copyTo(it, 1 shl 16) } }
                ?: throw IOException("Cannot open file")

            // Tags + artwork.
            var title = baseTitle
            var artist: String? = null
            var album: String? = null
            var hasArt = false
            runCatching {
                MediaMetadataRetriever().apply {
                    setDataSource(src.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                    artist = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                    album = extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
                    embeddedPicture?.let { storage.artwork(id).writeBytes(it); hasArt = true }
                    release()
                }
            }
            db.tracks().get(id)?.let {
                db.tracks().update(it.copy(title = title, artist = artist, album = album, hasArtwork = hasArt, statusMessage = "Decoding", sizeBytes = src.length()))
            }

            var lastPct = -1
            val result = AudioDecoder.decodeFile(src, storage.mixPcm(id), storage.mixPeaks(id)) { f ->
                val pct = (f * 100).toInt()
                if (pct / 5 != lastPct / 5) {
                    lastPct = pct
                    scope.launch { db.tracks().setStatus(id, TrackStatus.IMPORTING, f, "Decoding") }
                }
            }
            val auto = settings.current.autoSeparate && settings.current.canSeparate
            db.tracks().get(id)?.let {
                db.tracks().update(
                    it.copy(
                        durationMs = (result.durationSec * 1000).toLong(), sampleRate = result.sampleRate,
                        status = if (auto) TrackStatus.QUEUED else TrackStatus.READY, progress = 0f,
                        statusMessage = if (auto) "Queued for AI separation" else null,
                    ),
                )
            }
            if (auto) SeparationWorker.enqueue(context, id)
            BpmDetector.detect(storage.mixPcm(id), result.sampleRate)?.let { db.tracks().saveBpm(id, it) }
        } catch (e: Exception) {
            storage.deleteTrack(id)
            db.tracks().delete(id)
            _errors.value += ImportError(name, e.message ?: "Import failed")
        }
    }

    /** Tracks left mid-import by a process death are cleaned up on start. */
    suspend fun recoverInterrupted() {
        val all = db.tracks().all().first()
        all.filter { it.status == TrackStatus.IMPORTING }.forEach {
            storage.deleteTrack(it.id)
            db.tracks().delete(it.id)
        }
    }
}
