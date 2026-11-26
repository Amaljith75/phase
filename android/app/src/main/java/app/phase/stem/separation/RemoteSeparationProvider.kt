package app.phase.stem.separation

import app.phase.stem.audio.Bus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the Phase separation server (backend/ in this repo: FastAPI +
 * Demucs). Protocol:
 *   GET  /v1/health                    -> device + available models
 *   POST /v1/jobs  (multipart file, model) -> job
 *   GET  /v1/jobs/{id}                 -> status / progress / stage
 *   GET  /v1/jobs/{id}/stems/{stem}    -> FLAC
 */
class RemoteSeparationProvider(
    baseUrl: String,
    private val token: String?,
    client: OkHttpClient,
) : SeparationProvider {
    override val id = "remote-demucs"
    override val displayName = "Phase server (Demucs)"
    override val needsNetwork = true

    private val base = baseUrl.trim().trimEnd('/')
    private val http = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun req(path: String) = Request.Builder().url("$base$path").apply {
        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
    }

    override suspend fun status(): ProviderStatus = withContext(Dispatchers.IO) {
        if (base.isBlank()) return@withContext ProviderStatus(false, "No server URL configured")
        try {
            http.newCall(req("/v1/health").build()).execute().use { r ->
                if (r.code == 401) return@withContext ProviderStatus(false, "Server rejected the access token")
                if (!r.isSuccessful) return@withContext ProviderStatus(false, "Server error ${r.code}")
                val o = JSONObject(r.body!!.string())
                val models = o.optJSONArray("models")?.let { a ->
                    (0 until a.length()).map { i ->
                        val m = a.getJSONObject(i)
                        val stems = m.optJSONArray("stems")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList()
                        SeparationModel(m.getString("id"), m.optString("name", m.getString("id")), stems)
                    }
                } ?: emptyList()
                ProviderStatus(true, "Connected", o.optString("device"), models, o.optString("default_model").ifBlank { null })
            }
        } catch (e: IOException) {
            ProviderStatus(false, "Unreachable: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            ProviderStatus(false, "Invalid response: ${e.message}")
        }
    }

    override suspend fun separate(job: SeparationJob, progress: SeparationProgress): SeparationResult =
        SeparationResult.EncodedFiles(upload(job.sourceFile, job.model, job.workDir, progress))

    private suspend fun upload(input: File, model: String, workDir: File, progress: SeparationProgress): Map<Bus, File> =
        withContext(Dispatchers.IO) {
            if (base.isBlank()) throw SeparationException("No separation server configured. Add one in Settings → AI separation.")
            workDir.mkdirs()

            // 1) Upload (0..10%)
            progress(0f, "Uploading")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("file", input.name, ProgressBody(input, "application/octet-stream".toMediaType()) { f ->
                    runBlocking { progress(0.10f * f, "Uploading") }
                })
                .build()
            val jobId = call(req("/v1/jobs").post(body).build()) { JSONObject(it).getString("id") }

            // 2) Server-side separation (10..85%)
            var lastStage = "Queued"
            var failures = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val o = try {
                    call(req("/v1/jobs/$jobId").build()) { JSONObject(it) }.also { failures = 0 }
                } catch (e: IOException) {
                    if (++failures > 10) throw SeparationException("Lost connection to server: ${e.message}", retryable = true, cause = e)
                    delay(2000)
                    continue
                }
                val st = o.optString("status")
                val p = o.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
                lastStage = o.optString("stage", lastStage).ifBlank { lastStage }
                when (st) {
                    "done" -> break
                    "error" -> throw SeparationException("Server failed: ${o.optString("error", "unknown error")}")
                    else -> progress(0.10f + 0.75f * p, lastStage)
                }
                delay(1000)
            }

            // 3) Download stems (85..100%)
            val stemNames = listOf(Bus.VOCALS to "vocals", Bus.DRUMS to "drums", Bus.BASS to "bass", Bus.OTHER to "other")
            val out = LinkedHashMap<Bus, File>()
            stemNames.forEachIndexed { i, (bus, name) ->
                currentCoroutineContext().ensureActive()
                progress(0.85f + 0.15f * i / stemNames.size, "Downloading $name")
                val f = File(workDir, "$name.flac")
                http.newCall(req("/v1/jobs/$jobId/stems/$name").build()).execute().use { r ->
                    if (!r.isSuccessful) throw SeparationException("Download of $name failed (${r.code})", retryable = true)
                    f.outputStream().use { os -> r.body!!.byteStream().copyTo(os, 1 shl 16) }
                }
                if (f.length() == 0L) throw SeparationException("Server returned an empty $name stem")
                out[bus] = f
            }
            // Free server disk; failures here are harmless.
            runCatching { http.newCall(req("/v1/jobs/$jobId").delete().build()).execute().close() }
            progress(1f, "Downloaded")
            out
        }

    private inline fun <T> call(r: Request, parse: (String) -> T): T {
        http.newCall(r).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401) throw SeparationException("Server rejected the access token (Settings → Processing)")
            if (resp.code == 413) throw SeparationException("File too large for the server")
            if (!resp.isSuccessful) {
                val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                throw IOException("HTTP ${resp.code}${if (!detail.isNullOrBlank()) ": $detail" else ""}")
            }
            return parse(text)
        }
    }

    private class ProgressBody(val file: File, val type: MediaType, val onProgress: (Float) -> Unit) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = file.length()
        override fun writeTo(sink: BufferedSink) {
            val total = file.length().coerceAtLeast(1)
            var sent = 0L
            var lastPct = -1
            file.source().buffer().use { src ->
                while (true) {
                    val n = src.read(sink.buffer, 1 shl 16)
                    if (n == -1L) break
                    sink.flush()
                    sent += n
                    val pct = (sent * 100 / total).toInt()
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(sent.toFloat() / total)
                    }
                }
            }
        }
    }
}
