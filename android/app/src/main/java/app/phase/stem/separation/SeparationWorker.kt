package app.phase.stem.separation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.phase.stem.PhaseApp
import app.phase.stem.R
import app.phase.stem.analysis.BpmDetector
import app.phase.stem.audio.AudioDecoder
import app.phase.stem.audio.Bus
import app.phase.stem.data.TrackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs separation as a foreground WorkManager job so it survives the app
 * being backgrounded and shows progress in a notification. Stems are cached;
 * a track is only ever separated once.
 */
class SeparationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as PhaseApp).graph
        val trackId = inputData.getString(KEY_TRACK) ?: return Result.failure()
        val dao = graph.db.tracks()
        val track = dao.get(trackId) ?: return Result.failure()
        val storage = graph.storage
        val settings = graph.settings.current

        if (storage.hasAllStems(trackId) && track.stemSampleRate > 0) {
            dao.setStatus(trackId, TrackStatus.SEPARATED, 1f, null)
            return Result.success()
        }

        val provider = graph.separationProvider()
        val onDevice = !provider.needsNetwork
        runCatching { setForeground(foregroundInfo(track.title, 0f, "Starting", onDevice)) }
        val download = storage.stemDownloadDir(trackId)
        var lastNotified = -1
        val report: suspend (Float, String) -> Unit = { f, stage ->
            dao.setStatus(trackId, TrackStatus.SEPARATING, f, stage)
            val pct = (f * 100).toInt()
            if (pct != lastNotified) {
                lastNotified = pct
                runCatching { setForeground(foregroundInfo(track.title, f, stage, onDevice)) }
            }
        }
        if (lock.isLocked) dao.setStatus(trackId, TrackStatus.QUEUED, 0f, "Waiting for the previous song")
        return lock.withLock {
            // If Android kills the process mid-separation (usually memory
            // pressure), WorkManager re-runs the job; stop after 3 tries
            // instead of looping forever.
            val attempts = applicationContext.getSharedPreferences("separation_attempts", Context.MODE_PRIVATE)
            val n = attempts.getInt(trackId, 0) + 1
            attempts.edit().putInt(trackId, n).commit()
            var result: Result? = null
            try {
                result = if (n > 3) {
                    fail(trackId, "Separation was interrupted repeatedly — the phone probably ran out of memory. Close other apps and tap Retry.")
                } else {
                    process(trackId, track, provider, onDevice, download, report)
                }
                result
            } finally {
                // Not reached on process death, which is exactly what we count.
                if (result !is Result.Retry) attempts.edit().remove(trackId).apply()
            }
        }
    }

    private suspend fun process(
        trackId: String,
        track: app.phase.stem.data.TrackEntity,
        provider: SeparationProvider,
        onDevice: Boolean,
        download: java.io.File,
        report: suspend (Float, String) -> Unit,
    ): Result {
        val graph = (applicationContext as PhaseApp).graph
        val dao = graph.db.tracks()
        val storage = graph.storage
        val settings = graph.settings.current
        return try {
            dao.setStatus(trackId, TrackStatus.SEPARATING, 0f, if (onDevice) "Preparing" else "Connecting")
            storage.stemsDir(trackId).mkdirs()
            val job = SeparationJob(
                trackId = trackId,
                sourceFile = storage.sourceFile(track),
                mixPcm = storage.mixPcm(trackId),
                mixRate = track.sampleRate,
                model = settings.separationModel,
                workDir = download,
                stemPcm = { storage.stemPcm(trackId, it) },
                stemPeaks = { storage.stemPeaks(trackId, it) },
            )
            val rate = when (val r = provider.separate(job) { f, stage -> report(if (onDevice) f else f * 0.9f, stage) }) {
                is SeparationResult.Pcm -> r.sampleRate
                is SeparationResult.EncodedFiles -> {
                    var rate = 0
                    Bus.stems.forEachIndexed { i, bus ->
                        val src = r.stems[bus] ?: throw SeparationException("Server did not return ${bus.label}")
                        report(0.9f + 0.1f * i / 4, "Decoding ${bus.label.lowercase()}")
                        val d = AudioDecoder.decodeFile(src, storage.stemPcm(trackId, bus), storage.stemPeaks(trackId, bus))
                        if (rate != 0 && d.sampleRate != rate) throw SeparationException("Stem sample rates differ")
                        rate = d.sampleRate
                    }
                    rate
                }
            }
            download.deleteRecursively()
            if (!storage.hasAllStems(trackId)) throw SeparationException("Stems were not written")
            val bpm = runCatching { BpmDetector.detect(storage.stemPcm(trackId, Bus.DRUMS), rate) }.getOrNull()
            val fresh = dao.get(trackId) ?: return Result.failure()
            dao.update(
                fresh.copy(
                    status = TrackStatus.SEPARATED, progress = 1f, statusMessage = null,
                    stemSampleRate = rate, separationModel = if (onDevice) "htdemucs (on-device)" else settings.separationModel,
                    bpm = bpm ?: fresh.bpm,
                ),
            )
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                download.deleteRecursively()
                if (!storage.hasAllStems(trackId)) storage.deleteStems(trackId)
                dao.setStatus(trackId, TrackStatus.READY, 0f, "Separation cancelled")
            }
            throw e
        } catch (e: SeparationException) {
            if (e.retryable && runAttemptCount < 2) {
                dao.setStatus(trackId, TrackStatus.QUEUED, 0f, "Retrying: ${e.message}")
                Result.retry()
            } else {
                fail(trackId, e.message ?: "Separation failed")
            }
        } catch (e: OutOfMemoryError) {
            fail(trackId, "Ran out of memory. Close other apps and try again.")
        } catch (e: Exception) {
            fail(trackId, e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fail(trackId: String, msg: String): Result {
        val graph = (applicationContext as PhaseApp).graph
        graph.storage.deleteStems(trackId)
        graph.db.tracks().setStatus(trackId, TrackStatus.FAILED, 0f, msg)
        return Result.failure()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo("Separating stems", 0f, "Starting", (applicationContext as PhaseApp).graph.settings.current.onDevice)

    private fun foregroundInfo(title: String, progress: Float, stage: String, onDevice: Boolean): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Stem separation", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Separating · $title")
            .setContentText("$stage · ${(progress * 100).toInt()}%")
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        // Android 15 added a dedicated type for on-device media processing.
        val type = when {
            onDevice && Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, type) else ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        const val KEY_TRACK = "track"
        const val CHANNEL = "separation"
        const val NOTIF_ID = 4201
        fun workName(trackId: String) = "separate-$trackId"
        /** Only one separation runs at a time: the model saturates CPU and memory. */
        val lock = kotlinx.coroutines.sync.Mutex()

        fun enqueue(context: Context, trackId: String) {
            val app = context.applicationContext as PhaseApp
            val needsNet = !app.graph.settings.current.onDevice
            val req = OneTimeWorkRequestBuilder<SeparationWorker>()
                .setInputData(workDataOf(KEY_TRACK to trackId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (needsNet) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("separation")
                .addTag(workName(trackId))
                .build()
            // Each song can be queued only once; [lock] serialises execution.
            WorkManager.getInstance(context).enqueueUniqueWork(workName(trackId), ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, trackId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(trackId))
        }
    }
}
