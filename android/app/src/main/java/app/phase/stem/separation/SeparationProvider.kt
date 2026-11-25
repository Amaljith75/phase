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

/**
 * A source-separation backend. Implementations produce one audio file per
 * stem (any format MediaCodec can decode). New engines — on-device ONNX
 * Demucs, BS-RoFormer servers, cloud APIs — plug in by implementing this.
 */
interface SeparationProvider {
    val id: String
    val displayName: String

    suspend fun status(): ProviderStatus

    /** Returns the downloaded stem files keyed by bus (vocals, drums, bass, other). */
    suspend fun separate(input: File, model: String, workDir: File, progress: SeparationProgress): Map<Bus, File>
}

class SeparationException(message: String, val retryable: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)
