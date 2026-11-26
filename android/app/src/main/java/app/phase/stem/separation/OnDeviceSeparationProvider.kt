package app.phase.stem.separation

import android.app.ActivityManager
import android.content.Context
import app.phase.separation.CancelledException
import app.phase.separation.DemucsSpec
import app.phase.separation.Int16StereoSource
import app.phase.separation.OrtSegmentModel
import app.phase.separation.ResampledSource
import app.phase.separation.StemSink
import app.phase.separation.StreamingDemucs
import app.phase.stem.audio.Bus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs Hybrid Transformer Demucs entirely on the phone with ONNX Runtime.
 * The model ships inside the app (assets/models/htdemucs.onnx, stored
 * uncompressed and memory-mapped), so separation works offline with no
 * account, server or download.
 */
class OnDeviceSeparationProvider(private val context: Context) : SeparationProvider {
    override val id = "on-device"
    override val displayName = "On-device AI (HT-Demucs)"
    override val needsNetwork = false

    override suspend fun status(): ProviderStatus = withContext(Dispatchers.IO) {
        if (!modelAvailable(context)) ProviderStatus(false, "Model not bundled in this build")
        else ProviderStatus(true, "Ready · works offline", device = "this phone (${threads()} threads)")
    }

    override suspend fun separate(job: SeparationJob, progress: SeparationProgress): SeparationResult = coroutineScope {
        if (!modelAvailable(context)) throw SeparationException("This build does not include the AI model.")
        checkMemory()
        progress(0f, "Loading AI model")

        val state = AtomicReference(0 to 1)
        val started = System.nanoTime()
        val reporter = launch {
            var last = -1
            while (isActive) {
                val (done, total) = state.get()
                if (done != last && done > 0) {
                    last = done
                    val elapsed = (System.nanoTime() - started) / 1e9
                    val remaining = elapsed / done * (total - done)
                    progress(done.toFloat() / total * 0.98f, "Separating · ${eta(remaining)}")
                }
                delay(500)
            }
        }
        val parent = coroutineContext[Job]
        try {
            withContext(Dispatchers.Default) {
                val raf = RandomAccessFile(job.mixPcm, "r")
                raf.use {
                    val map = it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()).order(ByteOrder.LITTLE_ENDIAN)
                    var src: app.phase.separation.StereoSource = Int16StereoSource(map.asShortBuffer())
                    if (job.mixRate != DemucsSpec.SAMPLE_RATE) src = ResampledSource(src, job.mixRate, DemucsSpec.SAMPLE_RATE)
                    PcmStemSink(job).use { sink ->
                        val model = try {
                            OrtSegmentModel(mapModel(context), threads())
                        } catch (e: OutOfMemoryError) {
                            throw SeparationException("Not enough memory to load the AI model on this device.")
                        }
                        model.use { m ->
                            StreamingDemucs(m).separate(
                                src, sink,
                                onProgress = { p -> state.set(p.done to p.total) },
                                isCancelled = { parent?.isActive == false },
                            )
                        }
                        sink.finish()
                    }
                }
            }
        } catch (e: CancelledException) {
            throw CancellationException("separation cancelled")
        } catch (e: ai.onnxruntime.OrtException) {
            throw SeparationException("AI engine error: ${e.message}")
        } finally {
            reporter.cancel()
        }
        progress(1f, "Done")
        SeparationResult.Pcm(DemucsSpec.SAMPLE_RATE)
    }

    private fun checkMemory() {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // The model needs ~1.5–2 GB at peak. "4 GB" phones report ~3.6 GB.
        if (mi.totalMem in 1 until 3_400_000_000L) {
            throw SeparationException("On-device separation needs a phone with 4 GB of RAM or more.")
        }
    }

    private fun eta(sec: Double): String = when {
        sec < 60 -> "<1 min left"
        else -> "~${(sec / 60).roundToInt()} min left"
    }

    /** Writes stems in model order → engine files in bus order, plus waveform peaks. */
    private class PcmStemSink(private val job: SeparationJob) : StemSink, AutoCloseable {
        // model order: drums, bass, other, vocals
        private val buses = listOf(Bus.DRUMS, Bus.BASS, Bus.OTHER, Bus.VOCALS)
        private val tmp = buses.map { File(job.stemPcm(it).path + ".part").also { f -> f.parentFile?.mkdirs() } }
        private val out = tmp.map { BufferedOutputStream(FileOutputStream(it), 1 shl 17) }
        private val peaks = buses.map { PeakWriter() }
        private var bytes = ByteArray(0)

        override fun write(stem: Int, left: FloatArray, right: FloatArray, count: Int) {
            if (bytes.size < count * 4) bytes = ByteArray(count * 4)
            val p = peaks[stem]
            var o = 0
            for (i in 0 until count) {
                val l = toShort(left[i])
                val r = toShort(right[i])
                bytes[o++] = l.toByte(); bytes[o++] = (l shr 8).toByte()
                bytes[o++] = r.toByte(); bytes[o++] = (r shr 8).toByte()
                p.add(max(abs(l), abs(r)))
            }
            out[stem].write(bytes, 0, o)
        }

        private fun toShort(x: Float): Int = (x * 32767f).roundToInt().coerceIn(-32768, 32767)

        fun finish() {
            out.forEach { it.close() }
            buses.forEachIndexed { i, b ->
                if (!tmp[i].renameTo(job.stemPcm(b))) throw IOException("Could not save ${b.label} stem")
                peaks[i].writeTo(job.stemPeaks(b))
            }
        }

        override fun close() {
            out.forEach { runCatching { it.close() } }
            tmp.forEach { if (it.exists()) it.delete() }
        }
    }

    /** Same format as AudioDecoder's peak files: one byte per 20 ms. */
    private class PeakWriter {
        private val per = DemucsSpec.SAMPLE_RATE / 50
        private var n = 0
        private var cur = 0
        private val data = java.io.ByteArrayOutputStream()
        fun add(v: Int) {
            if (v > cur) cur = v
            if (++n >= per) { data.write(cur * 255 / 32767); cur = 0; n = 0 }
        }
        fun writeTo(f: File) {
            if (n > 0) data.write(cur * 255 / 32767)
            f.writeBytes(data.toByteArray())
        }
    }

    companion object {
        const val ASSET = "models/htdemucs.onnx"

        fun modelAvailable(context: Context): Boolean =
            runCatching { context.assets.openFd(ASSET).use { it.length > 1_000_000 } }.getOrDefault(false)

        /** Memory-maps the (uncompressed) model straight out of the APK. */
        fun mapModel(context: Context): MappedByteBuffer {
            context.assets.openFd(ASSET).use { fd ->
                FileInputStream(fd.fileDescriptor).use { s ->
                    return s.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }

        /** Big cores only: ORT threads on little cores slow everything down. */
        fun threads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                cores >= 8 -> 4
                cores >= 4 -> cores / 2
                else -> max(1, cores)
            }
        }
    }
}
