package app.phase.stem.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class LyricsQuery(val title: String, val artist: String?, val album: String?, val durationSec: Int)

data class LyricsResult(val providerName: String, val lrc: String, val synced: Boolean, val matchedTitle: String, val matchedArtist: String?)

/** Pluggable online lyrics source. Implementations must not assume a hit. */
interface LyricsProvider {
    val name: String
    suspend fun find(q: LyricsQuery): LyricsResult?
}

/** LRCLIB (lrclib.net): free, keyless, community time-synced lyrics. */
class LrcLibProvider(private val client: OkHttpClient) : LyricsProvider {
    override val name = "LRCLIB"
    private val base = "https://lrclib.net/api"

    override suspend fun find(q: LyricsQuery): LyricsResult? = withContext(Dispatchers.IO) {
        exactMatch(q) ?: search(q)
    }

    private fun exactMatch(q: LyricsQuery): LyricsResult? {
        if (q.artist.isNullOrBlank()) return null
        val url = "$base/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", q.title)
            .addQueryParameter("artist_name", q.artist)
            .apply { q.album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album_name", it) } }
            .addQueryParameter("duration", q.durationSec.toString())
            .build()
        val body = get(url.toString()) ?: return null
        return toResult(JSONObject(body))
    }

    private fun search(q: LyricsQuery): LyricsResult? {
        val url = "$base/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", listOfNotNull(q.title, q.artist).joinToString(" "))
            .build()
        val body = get(url.toString()) ?: return null
        val arr = JSONArray(body)
        val candidates = (0 until arr.length()).map { arr.getJSONObject(it) }
        // Prefer synced lyrics whose duration is close to ours.
        val best = candidates.sortedWith(
            compareBy<JSONObject>({ it.optString("syncedLyrics").isBlank() },
                { kotlin.math.abs(it.optDouble("duration", 0.0) - q.durationSec) }),
        ).firstOrNull() ?: return null
        return toResult(best)
    }

    private fun toResult(o: JSONObject): LyricsResult? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        val text = synced ?: plain ?: return null
        return LyricsResult(name, text, synced != null, o.optString("trackName"), o.optString("artistName").ifBlank { null })
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "Phase-Android/0.1").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("Lyrics service error ${r.code}")
            return r.body?.string()
        }
    }
}
