package app.phase.stem.lyrics

/** A lyric line. [timeMs] < 0 means the line is not time-synced yet. */
data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val meta: Map<String, String> = emptyMap(),
    val offsetMs: Long = 0,
) {
    val isSynced: Boolean get() = lines.isNotEmpty() && lines.all { it.timeMs >= 0 }

    /** Index of the line active at [positionMs] (respecting offset), or -1. */
    fun activeIndex(positionMs: Long): Int {
        if (!isSynced) return -1
        val t = positionMs + offsetMs
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= t) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }

    fun toLrc(): String = buildString {
        meta.forEach { (k, v) -> append("[").append(k).append(":").append(v).append("]\n") }
        if (offsetMs != 0L) append("[offset:").append(offsetMs).append("]\n")
        lines.forEach { l ->
            if (l.timeMs >= 0) append(formatTimestamp(l.timeMs))
            append(l.text).append('\n')
        }
    }

    companion object {
        private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val META_TAG = Regex("""^\[([a-zA-Z]+):(.*)]\s*$""")

        /**
         * Parses LRC (including multiple timestamps per line, [offset:] and
         * metadata tags). Text without any timestamps becomes unsynced lines.
         */
        fun parse(text: String): Lyrics {
            val lines = mutableListOf<LyricLine>()
            val meta = linkedMapOf<String, String>()
            var offset = 0L
            text.lineSequence().forEach { raw ->
                val line = raw.trimEnd('\r')
                if (line.isBlank()) return@forEach
                val stamps = TIME_TAG.findAll(line).toList()
                if (stamps.isEmpty()) {
                    val m = META_TAG.matchEntire(line.trim())
                    if (m != null) {
                        val key = m.groupValues[1].lowercase()
                        val value = m.groupValues[2].trim()
                        if (key == "offset") offset = value.toLongOrNull() ?: 0L else meta[key] = value
                    } else {
                        lines += LyricLine(-1, line.trim())
                    }
                    return@forEach
                }
                // Text is whatever follows the last leading timestamp.
                val lyric = line.substring(stamps.last().range.last + 1).trim()
                stamps.forEach { s ->
                    val min = s.groupValues[1].toLong()
                    val sec = s.groupValues[2].toLong()
                    val fracStr = s.groupValues[3]
                    val frac = when (fracStr.length) {
                        0 -> 0L
                        1 -> fracStr.toLong() * 100
                        2 -> fracStr.toLong() * 10
                        else -> fracStr.take(3).toLong()
                    }
                    lines += LyricLine(min * 60_000 + sec * 1000 + frac, lyric)
                }
            }
            // Mostly-synced files: drop stray untimed lines (credits etc.).
            // Partially-synced work in progress (e.g. from tap-sync) keeps every
            // line in written order so no text is lost.
            val syncedCount = lines.count { it.timeMs >= 0 }
            val ordered = when {
                syncedCount == lines.size -> lines.sortedBy { it.timeMs }
                syncedCount * 2 > lines.size -> lines.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }
                else -> lines
            }
            return Lyrics(ordered, meta, offset)
        }

        fun formatTimestamp(ms: Long): String {
            val m = ms / 60_000
            val s = (ms / 1000) % 60
            val cs = (ms % 1000) / 10
            return "[%02d:%02d.%02d]".format(m, s, cs)
        }
    }
}
