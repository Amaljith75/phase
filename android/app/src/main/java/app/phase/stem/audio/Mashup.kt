package app.phase.stem.audio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln

/** One song in a mashup. Stem levels are in bus order (vocals, drums, bass, other). */
data class DeckState(
    val trackId: String,
    val title: String,
    val sourceBpm: Float?,
    val sourceBeatOffset: Float = 0f,
    val stemGains: List<Float> = listOf(1f, 1f, 1f, 1f),
    val volume: Float = 1f,
    val pitch: Float = 0f,
    val keyLock: Boolean = true,
    val sync: Boolean = true,
    /** Start position on the timeline, in beats of the master tempo. */
    val startBeats: Int = 0,
    /** Fine alignment in milliseconds. */
    val nudgeMs: Float = 0f,
    /** Per-stem mute (bus order). */
    val stemMuted: List<Boolean> = listOf(false, false, false, false),
    /** Part of the song that plays: [trimIn, trimOut) seconds; trimOut 0 = to the end. */
    val trimIn: Float = 0f,
    val trimOut: Float = 0f,
) {
    /** Level the engine should use for a stem. */
    fun level(bus: Int) = if (stemMuted[bus]) 0f else stemGains[bus]
}

data class MashupState(
    val id: String,
    val name: String,
    val decks: List<DeckState>,
    /** Timeline tempo; null = follow the first deck. */
    val masterBpm: Float? = null,
) {
    val bpm: Float? get() = masterBpm ?: decks.firstOrNull()?.sourceBpm

    /** Tempo ratio for a deck: matches the master BPM, choosing half/double time to stay natural. */
    fun ratio(d: DeckState): Float {
        val target = bpm ?: return 1f
        val src = d.sourceBpm ?: return 1f
        if (!d.sync || src <= 0f) return 1f
        val r = target / src
        return listOf(r, r * 2f, r / 2f).filter { it in 0.5f..2f }.minByOrNull { abs(ln(it)) } ?: r.coerceIn(0.5f, 2f)
    }

    /** Seconds on the timeline where the master beat grid starts. */
    val gridOffset: Float get() {
        val d0 = decks.firstOrNull() ?: return 0f
        val period = 60f / (bpm ?: 120f)
        return (((d0.sourceBeatOffset - d0.trimIn) / ratio(d0)) % period + period) % period
    }

    /** Where a deck starts so its beats land on the master grid. */
    fun offsetSeconds(index: Int): Float {
        val d = decks[index]
        val period = 60f / (bpm ?: 120f)
        val align = if (index == 0 || d.sourceBpm == null) 0f else gridOffset - (d.sourceBeatOffset - d.trimIn) / ratio(d)
        // Keep the aligned phase inside one beat so start-beat 0 means "together".
        val base = if (index == 0) 0f else ((align % period) + period) % period
        return base + d.startBeats * period + d.nudgeMs / 1000f
    }

    fun toJson(): String {
        val o = JSONObject()
        o.put("name", name)
        masterBpm?.let { o.put("masterBpm", it.toDouble()) }
        val arr = JSONArray()
        decks.forEach { d ->
            arr.put(
                JSONObject()
                    .put("trackId", d.trackId).put("title", d.title)
                    .put("sourceBpm", d.sourceBpm?.toDouble() ?: JSONObject.NULL)
                    .put("sourceBeatOffset", d.sourceBeatOffset.toDouble())
                    .put("stemGains", JSONArray(d.stemGains.map { it.toDouble() }))
                    .put("volume", d.volume.toDouble()).put("pitch", d.pitch.toDouble())
                    .put("keyLock", d.keyLock).put("sync", d.sync)
                    .put("startBeats", d.startBeats).put("nudgeMs", d.nudgeMs.toDouble())
                    .put("stemMuted", JSONArray(d.stemMuted))
                    .put("trimIn", d.trimIn.toDouble()).put("trimOut", d.trimOut.toDouble()),
            )
        }
        o.put("decks", arr)
        return o.toString()
    }

    companion object {
        const val MAX_DECKS = 4

        fun fromJson(id: String, json: String): MashupState? = runCatching {
            val o = JSONObject(json)
            val arr = o.getJSONArray("decks")
            val decks = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                val g = d.optJSONArray("stemGains")
                DeckState(
                    trackId = d.getString("trackId"),
                    title = d.optString("title"),
                    sourceBpm = if (d.isNull("sourceBpm")) null else d.optDouble("sourceBpm").toFloat(),
                    sourceBeatOffset = d.optDouble("sourceBeatOffset", 0.0).toFloat(),
                    stemGains = if (g != null && g.length() == 4) (0 until 4).map { g.getDouble(it).toFloat() } else listOf(1f, 1f, 1f, 1f),
                    volume = d.optDouble("volume", 1.0).toFloat(),
                    pitch = d.optDouble("pitch", 0.0).toFloat(),
                    keyLock = d.optBoolean("keyLock", true),
                    sync = d.optBoolean("sync", true),
                    startBeats = d.optInt("startBeats", 0),
                    nudgeMs = d.optDouble("nudgeMs", 0.0).toFloat(),
                    stemMuted = d.optJSONArray("stemMuted")?.takeIf { it.length() == 4 }?.let { a -> (0 until 4).map { a.getBoolean(it) } }
                        ?: listOf(false, false, false, false),
                    trimIn = d.optDouble("trimIn", 0.0).toFloat(),
                    trimOut = d.optDouble("trimOut", 0.0).toFloat(),
                )
            }
            MashupState(id, o.optString("name", "Mashup"), decks, if (o.has("masterBpm")) o.getDouble("masterBpm").toFloat() else null)
        }.getOrNull()
    }
}

/** One-tap sound modes applied to the master bus + transport. */
enum class SoundMode(val label: String) {
    ORIGINAL("ORIGINAL"),
    SLOWED("SLOWED + REVERB"),
    NIGHTCORE("SPED UP"),
    LOFI("LO-FI"),
    CLUB("CLUB");

    fun apply(m: MixState): MixState {
        var s = m
        val reset = listOf(Param.REVERB_MIX, Param.REVERB_SIZE, Param.REVERB_DAMP, Param.LPF_FREQ, Param.HPF_FREQ, Param.CRUSH,
            Param.EQ_LOW, Param.EQ_HIGH, Param.PUMP)
        reset.forEach { p -> s = s.with(Bus.MASTER, p, p.default) }
        s = s.copy(speed = 1f, keepPitch = false)
        return when (this) {
            ORIGINAL -> s
            // Classic "slowed + reverb": varispeed down (pitch drops with it), big dark room.
            SLOWED -> s.copy(speed = 0.85f)
                .with(Bus.MASTER, Param.REVERB_MIX, 0.38f).with(Bus.MASTER, Param.REVERB_SIZE, 0.92f)
                .with(Bus.MASTER, Param.REVERB_DAMP, 0.55f).with(Bus.MASTER, Param.LPF_FREQ, 11000f)
                .with(Bus.MASTER, Param.EQ_LOW, 2f)
            NIGHTCORE -> s.copy(speed = 1.25f).with(Bus.MASTER, Param.EQ_HIGH, 1.5f)
            LOFI -> s.copy(speed = 0.95f)
                .with(Bus.MASTER, Param.CRUSH, 0.32f).with(Bus.MASTER, Param.LPF_FREQ, 4200f)
                .with(Bus.MASTER, Param.HPF_FREQ, 90f).with(Bus.MASTER, Param.REVERB_MIX, 0.15f)
            // Festival energy: weighty low end, crisp top, gentle sidechain pump.
            CLUB -> s.with(Bus.MASTER, Param.EQ_LOW, 4f).with(Bus.MASTER, Param.EQ_HIGH, 2.5f)
                .with(Bus.MASTER, Param.PUMP, 0.35f)
        }
    }

    companion object {
        fun detect(m: MixState): SoundMode? = entries.firstOrNull { mode ->
            val t = mode.apply(m)
            t.speed == m.speed && Param.entries.all { p -> t[Bus.MASTER, p] == m[Bus.MASTER, p] }
        }
    }
}
