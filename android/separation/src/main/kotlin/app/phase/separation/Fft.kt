package app.phase.separation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iterative radix-2 complex FFT (in place, float). Sized for the STFT used by
 * HT-Demucs (n = 4096); twiddles and bit-reversal tables are precomputed.
 */
class Fft(val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
    private val rev = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(n >= 2 && n and (n - 1) == 0) { "n must be a power of two" }
    }

    /** Forward transform X[k] = sum x[j] e^{-2πijk/n}. */
    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, -1f)

    /** Unscaled inverse transform (caller divides by n). */
    fun inverse(re: FloatArray, im: FloatArray) = transform(re, im, 1f)

    private fun transform(re: FloatArray, im: FloatArray, sign: Float) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sign * sinT[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
