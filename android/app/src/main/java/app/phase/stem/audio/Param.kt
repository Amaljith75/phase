package app.phase.stem.audio

/** Mirrors engine/Params.h — ids must stay in sync with the C++ enum. */
enum class Param(val id: Int, val min: Float, val max: Float, val default: Float, val label: String, val unit: String = "") {
    GAIN(0, 0f, 1.5f, 1f, "Volume"),
    PAN(1, -1f, 1f, 0f, "Pan"),
    MUTE(2, 0f, 1f, 0f, "Mute"),
    SOLO(3, 0f, 1f, 0f, "Solo"),
    HPF_FREQ(4, 20f, 20000f, 20f, "High-pass", "Hz"),
    LPF_FREQ(5, 20f, 20000f, 20000f, "Low-pass", "Hz"),
    DRIVE(6, 0f, 1f, 0f, "Drive"),
    DIST_MIX(7, 0f, 1f, 0f, "Mix"),
    CHORUS_MIX(8, 0f, 1f, 0f, "Mix"),
    CHORUS_RATE(9, 0.05f, 5f, 0.8f, "Rate", "Hz"),
    CHORUS_DEPTH(10, 0f, 1f, 0.5f, "Depth"),
    DELAY_MIX(11, 0f, 1f, 0f, "Mix"),
    DELAY_TIME(12, 20f, 2000f, 375f, "Time", "ms"),
    DELAY_FEEDBACK(13, 0f, 0.95f, 0.35f, "Feedback"),
    ECHO_MIX(14, 0f, 1f, 0f, "Mix"),
    ECHO_TIME(15, 50f, 2000f, 500f, "Time", "ms"),
    ECHO_FEEDBACK(16, 0f, 0.95f, 0.5f, "Feedback"),
    REVERB_MIX(17, 0f, 1f, 0f, "Mix"),
    REVERB_SIZE(18, 0f, 1f, 0.6f, "Size"),
    REVERB_DAMP(19, 0f, 1f, 0.4f, "Damping"),
    PITCH(20, -12f, 12f, 0f, "Pitch", "st"),
    EQ_LOW(21, -12f, 12f, 0f, "Low", "dB"),
    EQ_MID(22, -12f, 12f, 0f, "Mid", "dB"),
    EQ_HIGH(23, -12f, 12f, 0f, "High", "dB");

    fun clamp(v: Float) = v.coerceIn(min, max)

    /** True for parameters whose natural scale is logarithmic (frequency, time). */
    val logarithmic: Boolean get() = this == HPF_FREQ || this == LPF_FREQ || this == DELAY_TIME || this == ECHO_TIME || this == CHORUS_RATE

    fun toNormalized(v: Float): Float = if (logarithmic) {
        ((kotlin.math.ln(clamp(v)) - kotlin.math.ln(min)) / (kotlin.math.ln(max) - kotlin.math.ln(min)))
    } else (clamp(v) - min) / (max - min)

    fun fromNormalized(n: Float): Float {
        val x = n.coerceIn(0f, 1f)
        return if (logarithmic) kotlin.math.exp(kotlin.math.ln(min) + x * (kotlin.math.ln(max) - kotlin.math.ln(min)))
        else min + x * (max - min)
    }

    fun format(v: Float): String = when (this) {
        HPF_FREQ, LPF_FREQ -> if (v >= 1000f) "%.1fk".format(v / 1000f) else "%.0f".format(v)
        DELAY_TIME, ECHO_TIME -> "%.0f".format(v)
        CHORUS_RATE -> "%.2f".format(v)
        PITCH -> (if (v > 0.05f) "+" else "") + "%.1f".format(v)
        EQ_LOW, EQ_MID, EQ_HIGH -> (if (v > 0.05f) "+" else "") + "%.1f".format(v)
        PAN -> when {
            v < -0.02f -> "L%.0f".format(-v * 100)
            v > 0.02f -> "R%.0f".format(v * 100)
            else -> "C"
        }
        GAIN -> "%.0f%%".format(v * 100)
        else -> "%.0f%%".format(v * 100)
    }

    companion object {
        val byId: Map<Int, Param> = entries.associateBy { it.id }
    }
}

/** Engine buses. Stems are in the order the separation backend returns them. */
enum class Bus(val index: Int, val label: String, val short: String) {
    VOCALS(0, "Vocals", "VOX"),
    DRUMS(1, "Drums", "DRM"),
    BASS(2, "Bass", "BAS"),
    OTHER(3, "Other", "OTH"),
    MASTER(4, "Master", "MST");

    val isStem get() = this != MASTER

    companion object {
        val stems = listOf(VOCALS, DRUMS, BASS, OTHER)
        fun of(index: Int) = entries.first { it.index == index }
    }
}
