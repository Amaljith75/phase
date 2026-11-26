package app.phase.stem.analysis

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Tempo estimation: transient-emphasised onset envelope -> autocorrelation
 * over 60..200 BPM, weighted by a log-normal tempo prior centred on 120 BPM
 * (resolves the usual half/double-time ambiguity), refined by parabolic
 * interpolation. Works best on a drums stem but is usable on a full mix.
 */
object BpmDetector {
    private const val HOP = 512

    /** Tempo plus the time of the first beat (seconds from the start of the file). */
    data class Tempo(val bpm: Float, val beatOffset: Float)

    /** Analyses up to [maxSeconds] from the middle of an int16 stereo PCM file. */
    fun detect(pcm: File, sampleRate: Int, maxSeconds: Int = 60): Float? = analyze(pcm, sampleRate, maxSeconds)?.bpm

    fun analyze(pcm: File, sampleRate: Int, maxSeconds: Int = 60): Tempo? {
        if (!pcm.exists() || sampleRate <= 0) return null
        RandomAccessFile(pcm, "r").use { raf ->
            val ch = raf.channel
            val totalFrames = ch.size() / 4
            val want = min(totalFrames, sampleRate.toLong() * maxSeconds)
            val start = ((totalFrames - want) / 2).coerceAtLeast(0)
            val buf = ch.map(FileChannel.MapMode.READ_ONLY, start * 4, want * 4).order(ByteOrder.LITTLE_ENDIAN)
            val mono = FloatArray(want.toInt())
            for (i in mono.indices) {
                mono[i] = (buf.getShort(i * 4) + buf.getShort(i * 4 + 2)) / 65536f
            }
            val bpm = detect(mono, sampleRate) ?: return null
            val phase = beatPhase(mono, sampleRate, bpm)
            val period = 60.0 / bpm
            val off = ((start.toDouble() / sampleRate + phase) % period + period) % period
            return Tempo(bpm, off.toFloat())
        }
    }

    /** Seconds (within one beat period, from the analysed buffer's start) of the strongest beat grid. */
    fun beatPhase(mono: FloatArray, sampleRate: Int, bpm: Float): Double {
        val env = onsetEnvelope(mono)
        val fps = sampleRate.toDouble() / HOP
        val period = fps * 60.0 / bpm
        val steps = max(1, period.toInt())
        var best = 0
        var bestSum = -1.0
        for (p in 0 until steps) {
            var sum = 0.0
            var k = p.toDouble()
            while (k < env.size - 1) {
                val i = k.toInt()
                sum += env[i] + 0.5 * env[i + 1]
                k += period
            }
            if (sum > bestSum) { bestSum = sum; best = p }
        }
        return best / fps
    }

    fun detect(mono: FloatArray, sampleRate: Int): Float? {
        val env = onsetEnvelope(mono)
        if (env.size < 64) return null
        val fps = sampleRate.toDouble() / HOP
        val minLag = (fps * 60.0 / 200.0).toInt().coerceAtLeast(1)
        val maxLag = min((fps * 60.0 / 60.0).toInt(), env.size - 1)
        if (maxLag <= minLag + 2) return null

        val ac = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag + 1) {
            if (lag >= env.size) break
            var s = 0.0
            for (i in lag until env.size) s += env[i] * env[i - lag]
            ac[lag] = s / (env.size - lag)
        }
        var best = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * fps / lag
            val prior = exp(-0.5 * (ln(bpm / 120.0) / ln(2.0) / 0.9).pow(2))
            // Reward lags whose double also correlates (metrical support).
            val support = if (lag * 2 <= maxLag + 1) 0.5 * ac[lag * 2] else 0.0
            val score = (ac[lag] + support) * prior
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }
        if (best <= 0 || bestScore <= 0) return null
        // Parabolic refinement around the peak.
        val y0 = ac[best - 1]
        val y1 = ac[best]
        val y2 = ac[best + 1]
        val denom = y0 - 2 * y1 + y2
        val shift = if (abs(denom) > 1e-12) (0.5 * (y0 - y2) / denom).coerceIn(-0.5, 0.5) else 0.0
        val bpm = 60.0 * fps / (best + shift)
        return (Math.round(bpm * 10) / 10.0).toFloat()
    }

    private fun onsetEnvelope(x: FloatArray): DoubleArray {
        val frames = x.size / HOP
        if (frames < 2) return DoubleArray(0)
        val energy = DoubleArray(frames)
        var prev = 0f
        for (f in 0 until frames) {
            var e = 0.0
            val o = f * HOP
            for (i in 0 until HOP) {
                val v = x[o + i]
                val d = v - prev // first difference emphasises transients over sustained bass
                prev = v
                e += (d * d).toDouble()
            }
            energy[f] = ln(1e-9 + sqrt(e / HOP))
        }
        val env = DoubleArray(frames)
        for (f in 1 until frames) env[f] = max(0.0, energy[f] - energy[f - 1])
        val mean = env.average()
        for (f in env.indices) env[f] = max(0.0, env[f] - mean)
        return env
    }
}

/** Tap tempo: averages recent tap intervals; resets after a 2 s pause. */
class TapTempo {
    private val taps = ArrayDeque<Long>()

    fun tap(nowMs: Long): Float? {
        if (taps.isNotEmpty() && nowMs - taps.last() > 2000) taps.clear()
        taps.addLast(nowMs)
        while (taps.size > 8) taps.removeFirst()
        if (taps.size < 2) return null
        val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        return (Math.round(60000.0 / avg * 10) / 10.0).toFloat()
    }

    fun reset() = taps.clear()
}
