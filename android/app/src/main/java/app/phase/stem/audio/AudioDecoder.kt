package app.phase.stem.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Streams any platform-decodable audio file (MP3, WAV, FLAC, M4A/AAC, OGG
 * Vorbis/Opus) through MediaCodec into the engine's raw format: interleaved
 * little-endian int16 stereo at the file's native sample rate. Never holds
 * the whole song in memory. Also produces a waveform peak file.
 */
object AudioDecoder {

    data class Result(val sampleRate: Int, val frames: Long, val sourceChannels: Int) {
        val durationSec: Double get() = frames.toDouble() / sampleRate
    }

    /** Peak resolution: one byte (0..255 abs peak) per this many milliseconds. */
    const val PEAK_MS = 20

    suspend fun decode(
        context: Context,
        source: Uri,
        outPcm: File,
        outPeaks: File?,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val extractor = MediaExtractor()
        try {
            if (source.scheme == "file" || source.scheme == null) extractor.setDataSource(source.path!!)
            else extractor.setDataSource(context, source, null)
            return decodeWith(extractor, outPcm, outPeaks, onProgress)
        } finally {
            extractor.release()
        }
    }

    suspend fun decodeFile(file: File, outPcm: File, outPeaks: File?, onProgress: (Float) -> Unit = {}): Result {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return decodeWith(extractor, outPcm, outPeaks, onProgress)
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeWith(extractor: MediaExtractor, outPcm: File, outPeaks: File?, onProgress: (Float) -> Unit): Result {
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No audio track found in file")
        extractor.selectTrack(trackIndex)
        val inFormat = extractor.getTrackFormat(trackIndex)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            throw IOException("This device has no decoder for $mime", e)
        }
        var sampleRate = inFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
        var channels = inFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        outPcm.parentFile?.mkdirs()
        val tmp = File(outPcm.path + ".part")
        val out = BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)
        val peaks = PeakBuilder()
        var frames = 0L
        val info = MediaCodec.BufferInfo()

        try {
            codec.configure(inFormat, null, null, 0)
            codec.start()
            var inputDone = false
            var outputDone = false
            var lastReport = -1
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = f.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = f.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val n = toStereo16(buf, channels, pcmEncoding, peaks, sampleRate) { bytes, len ->
                                out.write(bytes, 0, len)
                            }
                            frames += n
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastReport) {
                                lastReport = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
            }
        } catch (e: IllegalStateException) {
            throw IOException("Decoder failed: ${e.message}", e)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            out.close()
        }
        if (frames == 0L) {
            tmp.delete()
            throw IOException("File decoded to zero samples")
        }
        if (!tmp.renameTo(outPcm)) throw IOException("Could not write ${outPcm.name}")
        outPeaks?.let { peaks.writeTo(it) }
        onProgress(1f)
        return Result(sampleRate, frames, channels)
    }

    private val scratch = ThreadLocal.withInitial { ByteArray(1 shl 16) }

    /** Converts one decoder output buffer to int16 stereo; returns frames written. */
    private inline fun toStereo16(
        buf: ByteBuffer,
        channels: Int,
        encoding: Int,
        peaks: PeakBuilder,
        sampleRate: Int,
        sink: (ByteArray, Int) -> Unit,
    ): Int {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, ENCODING_PCM_32BIT -> 4
            ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val ch = max(1, channels)
        val frames = buf.remaining() / (bytesPerSample * ch)
        var outBytes = scratch.get()!!
        if (outBytes.size < frames * 4) {
            outBytes = ByteArray(frames * 4)
            scratch.set(outBytes)
        }
        peaks.ensureRate(sampleRate)
        var o = 0
        for (i in 0 until frames) {
            val base = buf.position() + i * bytesPerSample * ch
            val l = readSample(buf, base, encoding)
            val r = if (ch >= 2) readSample(buf, base + bytesPerSample, encoding) else l
            outBytes[o++] = (l.toInt() and 0xff).toByte()
            outBytes[o++] = (l.toInt() shr 8).toByte()
            outBytes[o++] = (r.toInt() and 0xff).toByte()
            outBytes[o++] = (r.toInt() shr 8).toByte()
            peaks.add(max(abs(l.toInt()), abs(r.toInt())))
        }
        sink(outBytes, o)
        return frames
    }

    // Constants added in API 31; decoders may emit them for hi-res WAV/FLAC.
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22

    private fun readSample(buf: ByteBuffer, at: Int, encoding: Int): Short = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> (buf.getFloat(at).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        ENCODING_PCM_32BIT -> (buf.getInt(at) shr 16).toShort()
        ENCODING_PCM_24BIT_PACKED -> {
            val v = (buf.get(at).toInt() and 0xff) or ((buf.get(at + 1).toInt() and 0xff) shl 8) or (buf.get(at + 2).toInt() shl 16)
            (v shr 8).toShort()
        }
        AudioFormat.ENCODING_PCM_8BIT -> (((buf.get(at).toInt() and 0xff) - 128) shl 8).toShort()
        else -> buf.getShort(at)
    }

    private fun MediaFormat.getIntegerOr(key: String, def: Int) = if (containsKey(key)) getInteger(key) else def

    /** Accumulates per-[PEAK_MS] absolute peaks into a compact byte array. */
    private class PeakBuilder {
        private var perBucket = 882
        private var count = 0
        private var cur = 0
        private val data = java.io.ByteArrayOutputStream()
        fun ensureRate(sr: Int) { perBucket = max(1, sr * PEAK_MS / 1000) }
        fun add(v: Int) {
            if (v > cur) cur = v
            if (++count >= perBucket) flush()
        }
        private fun flush() {
            data.write((cur.coerceAtMost(32767) * 255 / 32767))
            cur = 0
            count = 0
        }
        fun writeTo(f: File) {
            if (count > 0) flush()
            f.writeBytes(data.toByteArray())
        }
    }

    /** Reads a peak file and reduces it to [buckets] values in 0..1. */
    fun loadPeaks(file: File, buckets: Int): FloatArray {
        if (!file.exists() || buckets <= 0) return FloatArray(0)
        val raw = file.readBytes()
        if (raw.isEmpty()) return FloatArray(0)
        val out = FloatArray(buckets)
        for (b in 0 until buckets) {
            val a = (b.toLong() * raw.size / buckets).toInt()
            val e = max(a + 1, ((b + 1).toLong() * raw.size / buckets).toInt())
            var m = 0
            for (i in a until minOf(e, raw.size)) m = max(m, raw[i].toInt() and 0xff)
            out[b] = m / 255f
        }
        return out
    }
}
