package app.phase.separation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited (windowed-sinc, Blackman, 32 zero-crossings each side)
 * resampling view over another [StereoSource]. Used to feed 48 kHz (etc.)
 * songs to the 44.1 kHz model. Random access, no state.
 */
class ResampledSource(private val src: StereoSource, private val srcRate: Int, private val dstRate: Int) : StereoSource {
    private val ratio = srcRate.toDouble() / dstRate
    private val cutoff = min(1.0, 1.0 / ratio) * 0.97
    private val half = 32
    private val taps = (half / cutoff).toInt() + 1
    override val frames: Long = (src.frames / ratio).toLong()

    // Pre-tabulated kernel, oversampled for fractional offsets.
    private val os = 512
    private val table = FloatArray(taps * os + 1).also { t ->
        for (i in t.indices) {
            val x = i.toDouble() / os
            val w = if (x >= taps) 0.0 else 0.42 + 0.5 * cos(PI * x / taps) + 0.08 * cos(2 * PI * x / taps)
            val sinc = if (x == 0.0) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
            t[i] = (cutoff * sinc * w).toFloat()
        }
    }
    private var bufL = FloatArray(0)
    private var bufR = FloatArray(0)

    private fun kernel(x: Double): Float {
        val a = kotlin.math.abs(x) * os
        val i = a.toInt()
        if (i >= table.size - 1) return 0f
        val f = (a - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * f
    }

    override fun read(start: Long, count: Int, left: FloatArray, right: FloatArray) {
        val p0 = start * ratio
        val first = floor(p0).toLong() - taps
        val last = floor((start + count) * ratio).toLong() + taps + 1
        val len = (last - first).toInt()
        if (bufL.size < len) {
            bufL = FloatArray(len)
            bufR = FloatArray(len)
        }
        src.read(first, len, bufL, bufR)
        for (i in 0 until count) {
            val p = (start + i) * ratio
            val c = floor(p).toLong()
            var sl = 0f
            var sr = 0f
            for (k in -taps + 1..taps) {
                val j = c + k
                val w = kernel(p - j)
                val bi = (j - first).toInt()
                sl += bufL[bi] * w
                sr += bufR[bi] * w
            }
            left[i] = sl
            right[i] = sr
        }
    }
}

/** Interleaved little-endian int16 stereo PCM (the app's decoded format). */
class Int16StereoSource(private val data: java.nio.ShortBuffer) : StereoSource {
    override val frames: Long = (data.limit() / 2).toLong()
    override fun read(start: Long, count: Int, left: FloatArray, right: FloatArray) {
        val k = 1f / 32768f
        for (i in 0 until count) {
            val f = start + i
            if (f < 0 || f >= frames) {
                left[i] = 0f; right[i] = 0f
            } else {
                val idx = (f * 2).toInt()
                left[i] = data.get(idx) * k
                right[i] = data.get(idx + 1) * k
            }
        }
    }
}
