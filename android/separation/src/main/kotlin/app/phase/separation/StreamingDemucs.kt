package app.phase.separation

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Random-access stereo input (float -1..1). Reads outside [0, frames) return silence. */
interface StereoSource {
    val frames: Long
    fun read(start: Long, count: Int, left: FloatArray, right: FloatArray)
}

/** Receives finished stem audio in order, [count] frames at a time. */
interface StemSink {
    /** stem index in model order (drums, bass, other, vocals). */
    fun write(stem: Int, left: FloatArray, right: FloatArray, count: Int)
}

/** One forward pass of the exported network on a [DemucsSpec.SEGMENT] chunk. */
interface SegmentModel : AutoCloseable {
    /** mix [2*SEGMENT], mag [4*FREQS*FRAMES] → spec [4*4*FREQS*FRAMES], wave [4*2*SEGMENT]. */
    fun run(mix: FloatArray, mag: FloatArray, spec: FloatArray, wave: FloatArray)
}

class CancelledException : Exception("cancelled")

/**
 * Whole-song separation equivalent to `demucs.apply.apply_model(model, mix,
 * shifts=0, split=True, overlap=0.25)` with the standard input
 * normalisation, but streaming: memory is O(segment), not O(song), so a
 * 10-minute track needs the same ~50 MB of working buffers as a 10-second one.
 */
class StreamingDemucs(private val model: SegmentModel, private val overlap: Float = 0.25f) {
    private val seg = DemucsSpec.SEGMENT
    private val dsp = DemucsDsp()
    private val stride = ((1 - overlap) * seg).toInt()
    private val weight = FloatArray(seg).also { w ->
        val half = seg / 2
        for (i in 0 until half) w[i] = (i + 1).toFloat()
        for (i in half until seg) w[i] = (seg - i).toFloat()
        val m = w.max()
        for (i in w.indices) w[i] /= m
    }

    data class Progress(val done: Int, val total: Int)

    fun chunkCount(frames: Long): Int = ((frames + stride - 1) / stride).toInt()

    fun separate(src: StereoSource, sink: StemSink, onProgress: (Progress) -> Unit = {}, isCancelled: () -> Boolean = { false }) {
        val n = src.frames
        require(n > 0) { "empty input" }

        // Pass 1: normalisation statistics of the mono reference (torch.std is unbiased).
        var sum = 0.0
        var sumSq = 0.0
        val blk = 1 shl 16
        val l = FloatArray(blk)
        val r = FloatArray(blk)
        var pos = 0L
        while (pos < n) {
            val c = min(blk.toLong(), n - pos).toInt()
            src.read(pos, c, l, r)
            for (i in 0 until c) {
                val m = (l[i] + r[i]) * 0.5
                sum += m
                sumSq += m * m
            }
            pos += c
        }
        val mean = (sum / n).toFloat()
        val variance = if (n > 1) (sumSq - sum * sum / n) / (n - 1) else 0.0
        val std = sqrt(max(variance, 0.0)).toFloat().let { if (it < 1e-8f) 1f else it }

        val mix = FloatArray(2 * seg)
        val mag = FloatArray(4 * DemucsSpec.FREQS * DemucsSpec.FRAMES)
        val spec = FloatArray(DemucsSpec.SOURCES * 4 * DemucsSpec.FREQS * DemucsSpec.FRAMES)
        val wave = FloatArray(DemucsSpec.SOURCES * 2 * seg)
        val res = FloatArray(DemucsSpec.SOURCES * 2 * seg)
        val cl = FloatArray(seg)
        val cr = FloatArray(seg)

        // Overlap-add accumulator over [accBase, accBase + cap).
        val cap = seg
        val acc = Array(DemucsSpec.SOURCES * 2) { FloatArray(cap) }
        val accW = FloatArray(cap)
        var accBase = 0L
        val outL = FloatArray(cap)
        val outR = FloatArray(cap)

        val total = chunkCount(n)
        var off = 0L
        var idx = 0
        while (off < n) {
            if (isCancelled()) throw CancelledException()
            val length = min(n - off, seg.toLong()).toInt()
            // TensorChunk.padded: centre the chunk, using real neighbouring audio.
            val start = off - (seg - length) / 2
            src.read(start, seg, cl, cr)
            for (i in 0 until seg) {
                // Demucs pads *after* normalising, so padding is exact silence.
                val inside = start + i in 0 until n
                mix[i] = if (inside) (cl[i] - mean) / std else 0f
                mix[seg + i] = if (inside) (cr[i] - mean) / std else 0f
            }
            dsp.specIn(mix, mag)
            model.run(mix, mag, spec, wave)
            dsp.specOut(spec, wave, res)

            val d = (seg - length) / 2 // center_trim
            val rel = (off - accBase).toInt()
            for (k in 0 until DemucsSpec.SOURCES * 2) {
                val a = acc[k]
                val o = k * seg + d
                for (j in 0 until length) a[rel + j] += weight[j] * res[o + j]
            }
            for (j in 0 until length) accW[rel + j] += weight[j]

            // Everything before the next chunk's start is final.
            val next = min(off + stride, n)
            val done = (next - accBase).toInt()
            for (s in 0 until DemucsSpec.SOURCES) {
                val al = acc[s * 2]
                val ar = acc[s * 2 + 1]
                for (j in 0 until done) {
                    val w = accW[j]
                    outL[j] = al[j] / w * std + mean
                    outR[j] = ar[j] / w * std + mean
                }
                sink.write(s, outL, outR, done)
            }
            for (a in acc) shift(a, done)
            shift(accW, done)
            accBase = next
            off += stride
            idx++
            onProgress(Progress(idx, total))
        }
    }

    private fun shift(a: FloatArray, by: Int) {
        System.arraycopy(a, by, a, 0, a.size - by)
        java.util.Arrays.fill(a, a.size - by, a.size, 0f)
    }
}
