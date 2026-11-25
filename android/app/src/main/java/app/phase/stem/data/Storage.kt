package app.phase.stem.data

import android.content.Context
import app.phase.stem.audio.Bus
import java.io.File

/**
 * On-disk layout. Everything lives in app-private storage so imported music
 * never leaves the device unless the user separates it on a server they
 * configured, or explicitly shares an export.
 *
 * files/tracks/<id>/source.<ext>   copy of the imported file
 *                  /mix.pcm        decoded full mix (int16 stereo)
 *                  /mix.peaks      waveform peaks
 *                  /art.jpg        embedded artwork
 *                  /stems/<stem>.pcm  separated stems (cache)
 * files/exports/                   rendered mixes and recordings
 */
class Storage(context: Context) {
    val root: File = context.filesDir
    val tracksDir = File(root, "tracks").apply { mkdirs() }
    val exportsDir = File(root, "exports").apply { mkdirs() }

    fun trackDir(id: String) = File(tracksDir, id)
    fun sourceFile(t: TrackEntity) = File(trackDir(t.id), t.sourceFileName)
    fun mixPcm(id: String) = File(trackDir(id), "mix.pcm")
    fun mixPeaks(id: String) = File(trackDir(id), "mix.peaks")
    fun artwork(id: String) = File(trackDir(id), "art.jpg")
    fun stemsDir(id: String) = File(trackDir(id), "stems")
    fun stemPcm(id: String, bus: Bus) = File(stemsDir(id), "${bus.label.lowercase()}.pcm")
    fun stemPeaks(id: String, bus: Bus) = File(stemsDir(id), "${bus.label.lowercase()}.peaks")
    fun stemDownloadDir(id: String) = File(stemsDir(id), "download")

    fun hasAllStems(id: String) = Bus.stems.all { stemPcm(id, it).let { f -> f.exists() && f.length() > 0 } }

    fun deleteStems(id: String) = stemsDir(id).deleteRecursively()
    fun deleteTrack(id: String) = trackDir(id).deleteRecursively()

    fun sizeOf(dir: File): Long = if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun stemCacheBytes(): Long = tracksDir.listFiles()?.sumOf { sizeOf(File(it, "stems")) } ?: 0
    fun totalTrackBytes(): Long = sizeOf(tracksDir)
    fun exportBytes(): Long = sizeOf(exportsDir)
}
