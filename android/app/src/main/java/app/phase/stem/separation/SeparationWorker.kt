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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs separation as a foreground WorkManager job so it survives the app
 * being backgrounded and shows progress in a notification. Stems are
 * decoded into the engine format and cached; a track is only ever
 * separated once.
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

        setForeground(foregroundInfo(track.title, 0f, "Starting"))
        val provider = graph.separationProvider()
        val download = storage.stemDownloadDir(trackId)
        var lastNotified = -1
        val report: suspend (Float, String) -> Unit = { f, stage ->
            dao.setStatus(trackId, TrackStatus.SEPARATING, f, stage)
            val pct = (f * 100).toInt()
            if (pct != lastNotified) {
                lastNotified = pct
                runCatching { setForeground(foregroundInfo(track.title, f, stage)) }
            }
        }
        return try {
            dao.setStatus(trackId, TrackStatus.SEPARATING, 0f, "Connecting")
            val files = provider.separate(storage.sourceFile(track), settings.separationModel, download) { f, stage ->
                report(f * 0.9f, stage) // reserve the last 10% for local decoding
            }
            // Decode each stem into the engine's mmap-able PCM format.
            var rate = 0
            Bus.stems.forEachIndexed { i, bus ->
                val src = files[bus] ?: throw SeparationException("Server did not return ${bus.label}")
                report(0.9f + 0.1f * i / 4, "Decoding ${bus.label.lowercase()}")
                val r = AudioDecoder.decodeFile(src, storage.stemPcm(trackId, bus), storage.stemPeaks(trackId, bus))
                if (rate != 0 && r.sampleRate != rate) throw SeparationException("Stem sample rates differ")
                rate = r.sampleRate
            }
            download.deleteRecursively()
            val bpm = runCatching { BpmDetector.detect(storage.stemPcm(trackId, Bus.DRUMS), rate) }.getOrNull()
            val fresh = dao.get(trackId) ?: return Result.failure()
            dao.update(
                fresh.copy(
                    status = TrackStatus.SEPARATED, progress = 1f, statusMessage = null,
                    stemSampleRate = rate, separationModel = settings.separationModel,
                    bpm = bpm ?: fresh.bpm,
                ),
            )
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                download.deleteRecursively()
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

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Separating stems", 0f, "Starting")

    private fun foregroundInfo(title: String, progress: Float, stage: String): ForegroundInfo {
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
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        const val KEY_TRACK = "track"
        const val CHANNEL = "separation"
        const val NOTIF_ID = 4201
        fun workName(trackId: String) = "separate-$trackId"

        fun enqueue(context: Context, trackId: String) {
            val req = OneTimeWorkRequestBuilder<SeparationWorker>()
                .setInputData(workDataOf(KEY_TRACK to trackId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("separation")
                .build()
            // One job per track; repeated requests while queued/running are ignored.
            WorkManager.getInstance(context).beginUniqueWork(workName(trackId), ExistingWorkPolicy.KEEP, req).enqueue()
        }

        fun cancel(context: Context, trackId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(trackId))
        }
    }
}
