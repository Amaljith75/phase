package app.phase.stem.audio

/**
 * Thin JNI facade over the C++ StemEngine + Oboe host (libphaseaudio.so).
 * All methods are thread-safe; heavy ones (load, export) should run off the
 * main thread.
 */
object NativeEngine {
    init {
        System.loadLibrary("phaseaudio")
    }

    data class StreamInfo(
        val sampleRate: Int,
        val framesPerBurst: Int,
        val bufferSize: Int,
        val bufferCapacity: Int,
        val aaudio: Boolean,
        val exclusive: Boolean,
        val lowLatency: Boolean,
        val latencyMs: Double,
        val xruns: Int,
    )

    fun streamInfo(): StreamInfo {
        val v = nStreamInfo()
        return StreamInfo(v[0], v[1], v[2], v[3], v[4] != 0, v[5] != 0, v[6] != 0, v[7] / 100.0, v[8])
    }

    fun setParam(bus: Bus, p: Param, value: Float) = nSetParam(bus.index, p.id, p.clamp(value))
    fun getParam(bus: Bus, p: Param): Float = nGetParam(bus.index, p.id)

    external fun nStartOutput(lowLatency: Boolean): Boolean
    external fun nStopOutput()
    external fun nIsOutputRunning(): Boolean
    external fun nStreamInfo(): IntArray

    external fun nLoad(paths: Array<String>, sourceRate: Int): Boolean
    external fun nUnload()
    external fun nSourceCount(): Int
    external fun nLastError(): String

    external fun nSetPlaying(playing: Boolean)
    external fun nIsPlaying(): Boolean
    external fun nConsumeEnded(): Boolean
    external fun nSeek(seconds: Double)
    external fun nPosition(): Double
    external fun nDuration(): Double
    external fun nSetSpeed(speed: Float)
    external fun nSetKeepPitch(keep: Boolean)
    external fun nSetReverse(reverse: Boolean)
    external fun nSetLoop(enabled: Boolean, startSec: Double, endSec: Double)

    external fun nSetParam(bus: Int, id: Int, value: Float)
    external fun nGetParam(bus: Int, id: Int): Float
    /** Fills 10 floats: (rms, peak) for vocals, drums, bass, other, master. */
    external fun nMeters(out: FloatArray)

    external fun nStartRecording(path: String, format: String, kbps: Int): Boolean
    external fun nStopRecording(): Double
    external fun nIsRecording(): Boolean
    external fun nRecordingSeconds(): Double

    // ---- mashup decks & performance ----
    /** [paths] deck by deck; [counts] files per deck (1 or 4); [rates] sample rate per deck. */
    external fun nLoadDecks(paths: Array<String>, counts: IntArray, rates: IntArray): Boolean
    external fun nDeckCount(): Int
    external fun nSetDeckParam(deck: Int, id: Int, value: Float)
    external fun nSetGlobal(id: Int, value: Float)
    external fun nTriggerSfx(pad: Int)
    /** DJ tape stop: brakes to a halt, then pauses. */
    external fun nBrake()

    /** Blocking offline render of the current mix. Call from a worker thread. */
    external fun nExport(path: String, format: String, kbps: Int, startSec: Double, endSec: Double, tail: Boolean): Boolean
    external fun nExportProgress(): Float
    external fun nCancelExport()
}
