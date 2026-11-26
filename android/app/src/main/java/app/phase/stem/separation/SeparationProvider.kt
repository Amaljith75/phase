package app.phase.stem.separation

import app.phase.stem.audio.Bus
import java.io.File

data class SeparationModel(val id: String, val name: String, val stems: List<String>)

data class ProviderStatus(
    val reachable: Boolean,
    val message: String,
    val device: String? = null,
    val models: List<SeparationModel> = emptyList(),
    val defaultModel: String? = null,
)

/** Progress callback: overall fraction 0..1 and a short human-readable stage. */
typealias SeparationProgress = suspend (fraction: Float, stage: String) -> Unit

/** Everything a provider may need about the track being separated. */
data class SeparationJob(
    val trackId: String,
    /** The imported original file (for providers that upload it). */
    val sourceFile: File,
    /** Decoded full mix in engine format (int16 stereo) at [mixRate]. */
    val mixPcm: File,
    val mixRate: Int,
    val model: String,
    val workDir: File,
    /** Where finished engine-format stems must be written (PCM providers). */
    val stemPcm: (Bus) -> File,
    val stemPeaks: (Bus) -> File,
)

sealed interface SeparationResult {
    /** Encoded audio files (e.g. FLAC from a server) that still need decoding. */
    data class EncodedFiles(val stems: Map<Bus, File>) : SeparationResult

    /** Stems already written to [SeparationJob.stemPcm] at [sampleRate]. */
    data class Pcm(val sampleRate: Int) : SeparationResult
}

/**
 * A source-separation backend. Phase ships an on-device HT-Demucs provider
 * (default, offline) and an optional remote one; others (e.g. a quantised
 * or GPU-delegated model) plug in by implementing this.
 */
interface SeparationProvider {
    val id: String
    val displayName: String
    /** True if the provider needs network access. */
    val needsNetwork: Boolean

    suspend fun status(): ProviderStatus

    suspend fun separate(job: SeparationJob, progress: SeparationProgress): SeparationResult
}

class SeparationException(message: String, val retryable: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)
