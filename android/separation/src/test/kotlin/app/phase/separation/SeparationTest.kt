package app.phase.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

private class ArraySource(val l: FloatArray, val r: FloatArray) : StereoSource {
    override val frames = l.size.toLong()
    override fun read(start: Long, count: Int, left: FloatArray, right: FloatArray) {
        for (i in 0 until count) {
            val f = start + i
            if (f < 0 || f >= frames) { left[i] = 0f; right[i] = 0f } else { left[i] = l[f.toInt()]; right[i] = r[f.toInt()] }
        }
    }
}

private class CollectSink(n: Int) : StemSink {
    val out = Array(4) { Array(2) { FloatArray(n) } }
    val pos = IntArray(4)
    override fun write(stem: Int, left: FloatArray, right: FloatArray, count: Int) {
        System.arraycopy(left, 0, out[stem][0], pos[stem], count)
        System.arraycopy(right, 0, out[stem][1], pos[stem], count)
        pos[stem] += count
    }
}

/** Test model: each "source" returns the input waveform scaled by its own gain. */
private class ScaledIdentityModel(val gains: FloatArray) : SegmentModel {
    override fun run(mix: FloatArray, mag: FloatArray, spec: FloatArray, wave: FloatArray) {
        java.util.Arrays.fill(spec, 0f)
        for (s in 0 until 4) for (i in mix.indices) wave[s * mix.size + i] = mix[i] * gains[s]
    }
    override fun close() {}
}

class SeparationTest {
    @Test fun fftMatchesNaiveDft() {
        val n = 64
        val fft = Fft(n)
        val x = FloatArray(n) { sin(it * 0.37f) + 0.5f * cos(it * 1.9f) }
        val re = x.copyOf()
        val im = FloatArray(n)
        fft.forward(re, im)
        for (k in 0 until n) {
            var sr = 0.0; var si = 0.0
            for (j in 0 until n) { sr += x[j] * cos(2 * PI * j * k / n); si -= x[j] * sin(2 * PI * j * k / n) }
            assertEquals(sr, re[k].toDouble(), 1e-3)
            assertEquals(si, im[k].toDouble(), 1e-3)
        }
        fft.inverse(re, im)
        for (j in 0 until n) assertEquals(x[j], re[j] / n, 1e-4f)
    }

    @Test fun stftRoundTripIsIdentity() {
        val seg = DemucsSpec.SEGMENT
        val mix = FloatArray(2 * seg) { i -> if (i < seg) sin(i * 0.05f) * 0.5f else cos(i * 0.013f) * 0.3f }
        val dsp = DemucsDsp()
        val mag = FloatArray(4 * DemucsSpec.FREQS * DemucsSpec.FRAMES)
        dsp.specIn(mix, mag)
        val spec = FloatArray(4 * mag.size)
        for (s in 0 until 4) System.arraycopy(mag, 0, spec, s * mag.size, mag.size)
        val out = FloatArray(4 * 2 * seg)
        dsp.specOut(spec, FloatArray(4 * 2 * seg), out)
        // HT-Demucs keeps only STFT frames 2..2+le, so the first/last ~1.1k
        // samples of a segment are not exactly invertible (the network and the
        // chunk overlap handle them; the PyTorch reference behaves identically).
        // Check the interior.
        var maxErr = 0f
        val edge = 2048
        for (s in 0 until 4) for (c in 0 until 2) for (i in edge until seg - edge) {
            maxErr = maxOf(maxErr, abs(out[(s * 2 + c) * seg + i] - mix[c * seg + i]))
        }
        println("STFT->iSTFT max error $maxErr")
        assertTrue("round trip error $maxErr", maxErr < 1e-4f)
    }

    @Test fun streamingOverlapAddReconstructsAnyLength() {
        // With a scaled-identity "model", stems must equal gain * input at
        // every sample — this validates chunking, padding, trimming, weights
        // and the streaming flush for lengths around segment boundaries.
        for (n in listOf(1000, DemucsSpec.SEGMENT - 7, DemucsSpec.SEGMENT * 2 + 12345)) {
            val l = FloatArray(n) { sin(it * 0.011f) * 0.4f + 0.05f }
            val r = FloatArray(n) { cos(it * 0.007f) * 0.3f }
            val gains = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
            val sink = CollectSink(n)
            StreamingDemucs(ScaledIdentityModel(gains)).separate(ArraySource(l, r), sink)
            var maxErr = 0f
            for (s in 0 until 4) {
                assertEquals(n, sink.pos[s])
                // Stems are gain*(x-mean)/std, denormalised with *std + mean.
                val mean = (l.sum() + r.sum()) / (2.0 * n)
                for (i in 0 until n) {
                    val expL = gains[s] * (l[i] - mean) + mean
                    maxErr = maxOf(maxErr, abs(sink.out[s][0][i] - expL.toFloat()))
                }
            }
            println("streaming n=$n max error $maxErr")
            assertTrue("n=$n err=$maxErr", maxErr < 2e-4f)
        }
    }

    @Test fun resamplerKeepsSinePure() {
        val src = 48000
        val n = src * 2
        val f = 1000.0
        val l = FloatArray(n) { (0.5 * sin(2 * PI * f * it / src)).toFloat() }
        val rs = ResampledSource(ArraySource(l, l), src, 44100)
        val count = 44100
        val ol = FloatArray(count)
        val or = FloatArray(count)
        rs.read(10000, count, ol, or)
        var err = 0.0
        var sig = 0.0
        for (i in 0 until count) {
            val e = 0.5 * sin(2 * PI * f * (10000 + i) / 44100)
            err += (ol[i] - e) * (ol[i] - e)
            sig += e * e
        }
        val snr = 10 * log10(sig / err)
        println("resampler SNR %.1f dB".format(snr))
        assertTrue(snr > 60)
    }

    /** Real model vs PyTorch Demucs (vectors from tools/model/reference_pipeline.py --dump). */
    @Test fun realModelMatchesPytorch() {
        val onnx = System.getenv("PHASE_DEMUCS_ONNX")
        val vec = System.getenv("PHASE_DEMUCS_VECTORS")
        assumeTrue("set PHASE_DEMUCS_ONNX and PHASE_DEMUCS_VECTORS to run", onnx != null && vec != null)
        val info = File(vec, "info.txt").readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        val n = info["frames"]!!.toInt()
        val inBuf = map(File(vec, "input_f32_interleaved.raw")).asFloatBuffer()
        val l = FloatArray(n) { inBuf.get(2 * it) }
        val r = FloatArray(n) { inBuf.get(2 * it + 1) }
        val expected = map(File(vec, "expected_SxCxN_f32.raw")).asFloatBuffer()

        val model = OrtSegmentModel(map(File(onnx)), Runtime.getRuntime().availableProcessors())
        val sink = CollectSink(n)
        val t0 = System.nanoTime()
        StreamingDemucs(model).separate(ArraySource(l, r), sink)
        val secs = (System.nanoTime() - t0) / 1e9
        model.close()
        println("separated %.1f s of audio in %.1f s".format(n / 44100.0, secs))
        java.io.File("/proc/self/status").takeIf { it.exists() }?.readLines()?.filter { it.startsWith("VmHWM") || it.startsWith("VmRSS") }?.let { println("memory: $it") }
        for (s in 0 until 4) {
            var e = 0.0
            var p = 0.0
            var maxD = 0.0
            var at = 0
            for (c in 0 until 2) for (i in 0 until n) {
                val want = expected.get((s * 2 + c) * n + i)
                val d = sink.out[s][c][i] - want
                e += d * d
                p += want * want
                if (abs(d) > maxD) { maxD = abs(d).toDouble(); at = i }
            }
            println("  max|diff| %.2e at sample %d (rms ref %.2e)".format(maxD, at, Math.sqrt(p / (2.0 * n))))
            val snr = 10 * log10(p / maxOf(e, 1e-20))
            println("${DemucsSpec.SOURCE_NAMES[s]}: SNR vs PyTorch %.1f dB".format(snr))
            assertTrue("${DemucsSpec.SOURCE_NAMES[s]} SNR $snr", snr > 40)
        }
    }

    private fun map(f: File) = RandomAccessFile(f, "r").use {
        it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()).order(ByteOrder.LITTLE_ENDIAN)
    }
}
