package app.phase.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ONNX Runtime implementation of [SegmentModel]. The model is passed as a
 * (memory-mapped, direct) ByteBuffer, so on Android it is read straight out
 * of the APK without a copy.
 *
 * Input and output tensors are allocated once over direct buffers and
 * reused ("pinned") for every chunk, so a whole song creates no per-chunk
 * native garbage (each output copy would otherwise be ~55 MB off-heap).
 */
class OrtSegmentModel(model: ByteBuffer, threads: Int) : SegmentModel {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { o ->
        o.setIntraOpNumThreads(threads)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Memory matters more than the last few % of speed on phones: the
        // default CPU arena + memory patterns keep every intermediate buffer
        // reserved (~4.9 GB peak); without them the peak is ~2 GB.
        o.setCPUArenaAllocator(false)
        o.setMemoryPatternOptimization(false)
        env.createSession(model, o)
    }

    private val seg = DemucsSpec.SEGMENT.toLong()
    private val fq = DemucsSpec.FREQS.toLong()
    private val fr = DemucsSpec.FRAMES.toLong()
    private val src = DemucsSpec.SOURCES.toLong()

    private fun direct(floats: Long): FloatBuffer =
        ByteBuffer.allocateDirect((floats * 4).toInt()).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val mixBuf = direct(2 * seg)
    private val magBuf = direct(4 * fq * fr)
    private val specBuf = direct(src * 4 * fq * fr)
    private val waveBuf = direct(src * 2 * seg)
    private val tMix = OnnxTensor.createTensor(env, mixBuf, longArrayOf(1, 2, seg))
    private val tMag = OnnxTensor.createTensor(env, magBuf, longArrayOf(1, 4, fq, fr))
    private val tSpec = OnnxTensor.createTensor(env, specBuf, longArrayOf(1, src, 4, fq, fr))
    private val tWave = OnnxTensor.createTensor(env, waveBuf, longArrayOf(1, src, 2, seg))
    private val inputs = mapOf("mix" to tMix, "mag" to tMag)
    private val outputs = mapOf("spec" to tSpec, "wave" to tWave)

    override fun run(mix: FloatArray, mag: FloatArray, spec: FloatArray, wave: FloatArray) {
        mixBuf.clear(); mixBuf.put(mix)
        magBuf.clear(); magBuf.put(mag)
        session.run(inputs, outputs).close() // results land in the pinned buffers
        specBuf.clear(); specBuf.get(spec)
        waveBuf.clear(); waveBuf.get(wave)
    }

    override fun close() {
        listOf(tMix, tMag, tSpec, tWave).forEach { it.close() }
        session.close()
    }
}
