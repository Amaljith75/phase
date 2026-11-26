package app.phase.stem.audio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Complete, serialisable mixer configuration: every bus parameter plus the
 * transport settings. Used for autosave, projects and (bus-only) presets.
 */
data class MixState(
    val buses: List<List<Float>> = List(Bus.entries.size) { Param.entries.map { it.default } },
    val speed: Float = 1f,
    val keepPitch: Boolean = false,
    val reverse: Boolean = false,
    val loopOn: Boolean = false,
    val loopA: Double = 0.0,
    val loopB: Double = 0.0,
) {
    operator fun get(bus: Bus, p: Param): Float = buses[bus.index][p.id]

    fun with(bus: Bus, p: Param, v: Float): MixState {
        val nb = buses.toMutableList()
        nb[bus.index] = buses[bus.index].toMutableList().also { it[p.id] = p.clamp(v) }
        return copy(buses = nb)
    }

    fun resetBus(bus: Bus): MixState {
        val nb = buses.toMutableList()
        nb[bus.index] = Param.entries.map { it.default }
        return copy(buses = nb)
    }

    fun isMuted(bus: Bus) = this[bus, Param.MUTE] > 0.5f
    fun isSoloed(bus: Bus) = this[bus, Param.SOLO] > 0.5f
    val anySolo get() = Bus.stems.any { isSoloed(it) }
    fun isAudible(bus: Bus) = !isMuted(bus) && (!anySolo || isSoloed(bus))

    /** Effects that are currently doing something on a bus (for indicators). */
    fun activeEffects(bus: Bus): List<String> = buildList {
        if (this@MixState[bus, Param.REVERB_MIX] > 0.001f) add("REV")
        if (this@MixState[bus, Param.DELAY_MIX] > 0.001f) add("DLY")
        if (this@MixState[bus, Param.ECHO_MIX] > 0.001f) add("ECH")
        if (this@MixState[bus, Param.CHORUS_MIX] > 0.001f) add("CHO")
        if (this@MixState[bus, Param.DIST_MIX] > 0.001f) add("DST")
        if (this@MixState[bus, Param.HPF_FREQ] > 21f) add("HPF")
        if (this@MixState[bus, Param.LPF_FREQ] < 19900f) add("LPF")
        if (kotlin.math.abs(this@MixState[bus, Param.PITCH]) > 0.01f) add("PCH")
        if (listOf(Param.EQ_LOW, Param.EQ_MID, Param.EQ_HIGH).any { p -> kotlin.math.abs(this@MixState[bus, p]) > 0.05f }) add("EQ")
        if (this@MixState[bus, Param.VOCODER] > 0f) add("ROBOT")
        if (this@MixState[bus, Param.CRUSH] > 0f) add("CRSH")
        if (this@MixState[bus, Param.PHASER] > 0f) add("PHSR")
        if (this@MixState[bus, Param.FLANGER] > 0f) add("FLNG")
        if (this@MixState[bus, Param.GATE] > 0f) add("GATE")
        if (this@MixState[bus, Param.PUMP] > 0f) add("PUMP")
        if (this@MixState[bus, Param.DJ_FILTER] != 0f) add("FILT")
    }

    fun toJson(includeTransport: Boolean = true): String {
        val o = JSONObject()
        val arr = JSONArray()
        Bus.entries.forEach { b ->
            val bo = JSONObject()
            Param.entries.forEach { p -> bo.put(p.name, this[b, p].toDouble()) }
            arr.put(bo)
        }
        o.put("version", 1)
        o.put("buses", arr)
        if (includeTransport) {
            o.put("speed", speed.toDouble())
            o.put("keepPitch", keepPitch)
            o.put("reverse", reverse)
            o.put("loopOn", loopOn)
            o.put("loopA", loopA)
            o.put("loopB", loopB)
        }
        return o.toString()
    }

    /** Copies only bus parameters from [other] (presets don't carry transport). */
    fun withBusesFrom(other: MixState) = copy(buses = other.buses)

    companion object {
        fun fromJson(json: String?): MixState? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                val arr = o.getJSONArray("buses")
                val buses = Bus.entries.map { b ->
                    val bo = arr.optJSONObject(b.index) ?: JSONObject()
                    // Keyed by name so the format survives parameter additions.
                    Param.entries.map { p -> p.clamp(bo.optDouble(p.name, p.default.toDouble()).toFloat()) }
                }
                MixState(
                    buses = buses,
                    speed = o.optDouble("speed", 1.0).toFloat(),
                    keepPitch = o.optBoolean("keepPitch", false),
                    reverse = o.optBoolean("reverse", false),
                    loopOn = o.optBoolean("loopOn", false),
                    loopA = o.optDouble("loopA", 0.0),
                    loopB = o.optDouble("loopB", 0.0),
                )
            }.getOrNull()
        }
    }
}

/** One-tap stem modes. */
enum class QuickMode(val label: String) {
    ALL("ALL"),
    INSTRUMENTAL("NO VOX"),
    ACAPELLA("ACAPELLA"),
    RHYTHM("RHYTHM");

    fun apply(m: MixState): MixState {
        var s = m
        Bus.stems.forEach { b -> s = s.with(b, Param.SOLO, 0f).with(b, Param.MUTE, 0f) }
        return when (this) {
            ALL -> s
            INSTRUMENTAL -> s.with(Bus.VOCALS, Param.MUTE, 1f)
            ACAPELLA -> s.with(Bus.VOCALS, Param.SOLO, 1f)
            RHYTHM -> s.with(Bus.VOCALS, Param.MUTE, 1f).with(Bus.OTHER, Param.MUTE, 1f)
        }
    }

    companion object {
        fun detect(m: MixState): QuickMode? = entries.firstOrNull { mode ->
            val t = mode.apply(m)
            Bus.stems.all { b -> t.isMuted(b) == m.isMuted(b) && t.isSoloed(b) == m.isSoloed(b) }
        }
    }
}
