package app.phase.separation

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/** Fixed geometry of the exported htdemucs core (see tools/model). */
object DemucsSpec {
    const val SAMPLE_RATE = 44100
    const val NFFT = 4096
    const val HOP = 1024
    const val FREQS = NFFT / 2            // 2048 (Nyquist bin dropped)
    const val SEGMENT = 343980            // 7.8 s training segment
    val FRAMES = ceil(SEGMENT / HOP.toDouble()).toInt() // 336
    const val SOURCES = 4                 // model order: drums, bass, other, vocals
    val SOURCE_NAMES = listOf("drums", "bass", "other", "vocals")
}

/**
 * The STFT/iSTFT that HT-Demucs performs around its network, reproduced
 * exactly (HTDemucs._spec/_magnitude and _mask/_ispec with torch.stft /
 * torch.istft semantics: periodic Hann, centred reflect padding,
 * "normalized" scaling). Verified against PyTorch in the unit tests.
 *
 * Layouts (row-major, batch dimension omitted):
 *  mix  [2][SEGMENT]
 *  mag  [4][FREQS][FRAMES]            channels: L.re, L.im, R.re, R.im
 *  spec [SOURCES][4][FREQS][FRAMES]   model output, same channel layout
 *  out  [SOURCES][2][SEGMENT]
 */
class DemucsDsp {
    private val n = DemucsSpec.NFFT
    private val hop = DemucsSpec.HOP
    private val seg = DemucsSpec.SEGMENT
    private val fq = DemucsSpec.FREQS
    private val frames = DemucsSpec.FRAMES
    private val fft = Fft(n)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val scale = (1.0 / sqrt(n.toDouble())).toFloat()

    // _spec: reflect pad (1536, 1536 + le*hop - len), then stft's centre pad n/2.
    private val pad = hop / 2 * 3
    private val padded1 = frames * hop + 2 * pad                 // 347136
    private val paddedLen = padded1 + n                           // 351232
    private val buf = FloatArray(paddedLen)
    private val re = FloatArray(n)
    private val im = FloatArray(n)

    // iSTFT overlap-add normalisation (data independent → precomputed).
    private val olaFrames = frames + 4
    private val olaLen = n + hop * (olaFrames - 1)
    private val ola = FloatArray(olaLen)
    private val invWsum: FloatArray = run {
        val w = DoubleArray(olaLen)
        for (t in 0 until olaFrames) for (k in 0 until n) w[t * hop + k] += (window[k] * window[k]).toDouble()
        FloatArray(olaLen) { if (w[it] > 1e-11) (1.0 / w[it]).toFloat() else 1f }
    }

    /** HTDemucs._spec + _magnitude(cac). [mix] → [mag]. */
    fun specIn(mix: FloatArray, mag: FloatArray) {
        val right = pad + frames * hop - seg
        for (c in 0 until 2) {
            val base = c * seg
            // First reflect pad (length padded1), then centre reflect pad of n/2.
            for (i in 0 until paddedLen) {
                val p1 = reflect(i - n / 2, padded1)
                val p0 = reflect(p1 - pad, seg)
                buf[i] = mix[base + p0]
            }
            check(right >= 0)
            for (t in 0 until frames) {
                val start = (t + 2) * hop // frames 2 .. 2+le of the padded STFT
                for (k in 0 until n) {
                    re[k] = buf[start + k] * window[k]
                    im[k] = 0f
                }
                fft.forward(re, im)
                val reCh = (c * 2) * fq * frames
                val imCh = (c * 2 + 1) * fq * frames
                for (f in 0 until fq) {
                    mag[reCh + f * frames + t] = re[f] * scale
                    mag[imCh + f * frames + t] = im[f] * scale
                }
            }
        }
    }

    /**
     * HTDemucs._mask(cac) + _ispec for every source/channel, added to [wave]
     * (the time-branch output) and written to [out].
     */
    fun specOut(spec: FloatArray, wave: FloatArray, out: FloatArray) {
        val istftScale = (sqrt(n.toDouble()) / n).toFloat() // normalized irfft
        val crop = n / 2 + pad
        for (s in 0 until DemucsSpec.SOURCES) {
            for (c in 0 until 2) {
                java.util.Arrays.fill(ola, 0f)
                val reCh = ((s * 4) + c * 2) * fq * frames
                val imCh = ((s * 4) + c * 2 + 1) * fq * frames
                // Zero-padded frames (2 each side) contribute nothing; skip them.
                for (t in 0 until frames) {
                    for (f in 0 until fq) {
                        re[f] = spec[reCh + f * frames + t]
                        im[f] = spec[imCh + f * frames + t]
                    }
                    re[fq] = 0f; im[fq] = 0f // restored Nyquist bin
                    im[0] = 0f                // irfft ignores the DC imaginary part
                    for (f in 1 until fq) { // Hermitian completion
                        re[n - f] = re[f]
                        im[n - f] = -im[f]
                    }
                    fft.inverse(re, im)
                    val off = (t + 2) * hop
                    for (k in 0 until n) ola[off + k] += re[k] * istftScale * window[k]
                }
                val o = (s * 2 + c) * seg
                for (i in 0 until seg) {
                    val j = crop + i
                    out[o + i] = ola[j] * invWsum[j] + wave[o + i]
                }
            }
        }
    }

    private fun reflect(i: Int, len: Int): Int {
        var x = i
        val period = 2 * (len - 1)
        x %= period
        if (x < 0) x += period
        return if (x >= len) period - x else x
    }
}
