package app.phase.stem

import app.phase.stem.analysis.BpmDetector
import app.phase.stem.analysis.TapTempo
import app.phase.stem.audio.Bus
import app.phase.stem.audio.MixState
import app.phase.stem.audio.Param
import app.phase.stem.audio.QuickMode
import app.phase.stem.lyrics.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class LrcTest {
    @Test fun parsesTimestampsMetaAndOffset() {
        val l = Lyrics.parse(
            """
            [ti:Song]
            [ar:Someone]
            [offset:250]
            [00:12.30]First line
            [00:15.8]Second
            [01:02.345][01:30.00]Chorus
            """.trimIndent(),
        )
        assertTrue(l.isSynced)
        assertEquals(250L, l.offsetMs)
        assertEquals("Song", l.meta["ti"])
        assertEquals(listOf(12_300L, 15_800L, 62_345L, 90_000L), l.lines.map { it.timeMs })
        assertEquals("Chorus", l.lines[3].text)
    }

    @Test fun activeLineHonoursOffset() {
        val l = Lyrics.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c")
        assertEquals(-1, l.activeIndex(500))
        assertEquals(0, l.activeIndex(1500))
        assertEquals(2, l.activeIndex(9000))
        assertEquals(1, l.copy(offsetMs = 600).activeIndex(1500))
    }

    @Test fun plainTextIsUnsynced() {
        val l = Lyrics.parse("hello\nworld")
        assertFalse(l.isSynced)
        assertEquals(2, l.lines.size)
    }

    @Test fun roundTrip() {
        val src = Lyrics.parse("[00:01.50]a\n[00:20.05]b")
        val again = Lyrics.parse(src.toLrc())
        assertEquals(src.lines, again.lines)
    }
}

class MixStateTest {
    @Test fun jsonRoundTripKeepsEveryParam() {
        var m = MixState()
        m = m.with(Bus.VOCALS, Param.REVERB_MIX, 0.4f).with(Bus.DRUMS, Param.GAIN, 0.3f).with(Bus.MASTER, Param.EQ_LOW, 4f)
        m = m.copy(speed = 1.25f, keepPitch = true, loopOn = true, loopA = 10.0, loopB = 20.0)
        val back = MixState.fromJson(m.toJson())!!
        assertEquals(m, back)
    }

    @Test fun valuesAreClamped() {
        val m = MixState().with(Bus.BASS, Param.GAIN, 9f)
        assertEquals(1.5f, m[Bus.BASS, Param.GAIN])
    }

    @Test fun quickModes() {
        val aca = QuickMode.ACAPELLA.apply(MixState())
        assertTrue(aca.isAudible(Bus.VOCALS))
        assertFalse(aca.isAudible(Bus.DRUMS))
        val inst = QuickMode.INSTRUMENTAL.apply(aca)
        assertFalse(inst.isAudible(Bus.VOCALS))
        assertTrue(inst.isAudible(Bus.BASS))
        assertEquals(QuickMode.INSTRUMENTAL, QuickMode.detect(inst))
    }

    @Test fun logParamMapping() {
        val p = Param.LPF_FREQ
        for (v in listOf(20f, 200f, 2000f, 20000f)) assertEquals(v, p.fromNormalized(p.toNormalized(v)), v * 1e-3f)
        assertEquals(0.5f, p.toNormalized(632.45f), 1e-3f)
    }
}

class TempoTest {
    private fun clickTrack(bpm: Double, sr: Int = 44100, secs: Int = 30): FloatArray {
        val out = FloatArray(sr * secs)
        val period = sr * 60.0 / bpm
        var t = 0.0
        while (t < out.size) {
            val s = t.toInt()
            for (i in 0 until 2000) if (s + i < out.size) out[s + i] += (0.8 * exp(-i / 300.0) * sin(2 * PI * 120 * i / sr)).toFloat()
            t += period
        }
        return out
    }

    @Test fun detectsCommonTempos() {
        for (bpm in listOf(90.0, 120.0, 128.0, 140.0)) {
            val got = BpmDetector.detect(clickTrack(bpm), 44100)
            assertNotNull(got)
            assertTrue("expected $bpm got $got", abs(got!! - bpm) < 1.5)
        }
    }

    @Test fun tapTempo() {
        val t = TapTempo()
        var bpm: Float? = null
        for (i in 0 until 6) bpm = t.tap(1000L + i * 500L)
        assertEquals(120f, bpm!!, 0.01f)
    }
}

class MashupTest {
    private fun deck(bpm: Float?, off: Float = 0f) = app.phase.stem.audio.DeckState("t$bpm", "song", bpm, off)

    @Test fun tempoRatioPrefersNaturalHalfOrDoubleTime() {
        val m = app.phase.stem.audio.MashupState("m", "x", listOf(deck(128f), deck(70f), deck(126f)))
        assertEquals(1f, m.ratio(m.decks[0]), 1e-6f)
        // 70 -> 128 would be 1.83x; half-time 64 needs only 0.914x
        assertEquals(128f / 140f, m.ratio(m.decks[1]), 1e-4f)
        assertEquals(128f / 126f, m.ratio(m.decks[2]), 1e-4f)
    }

    @Test fun beatsAlignToTheMasterGrid() {
        // Deck 0 first beat at 0.25 s (120 BPM); deck 1 first beat at 0.40 s.
        val m = app.phase.stem.audio.MashupState("m", "x", listOf(deck(120f, 0.25f), deck(120f, 0.40f)))
        val off = m.offsetSeconds(1)
        val deck1FirstBeatOnTimeline = off + 0.40f
        val phase = ((deck1FirstBeatOnTimeline - 0.25f) % 0.5f + 0.5f) % 0.5f
        assertTrue("phase $phase", phase < 1e-4f || phase > 0.5f - 1e-4f)
        val later = m.copy(decks = listOf(m.decks[0], m.decks[1].copy(startBeats = 4)))
        assertEquals(off + 2f, later.offsetSeconds(1), 1e-4f)
    }

    @Test fun jsonRoundTrip() {
        val m = app.phase.stem.audio.MashupState("m", "My mash", listOf(deck(128f).copy(stemGains = listOf(0f, 1f, 1.2f, 0.5f), pitch = -2f, startBeats = 8)), 124f)
        assertEquals(m, app.phase.stem.audio.MashupState.fromJson("m", m.toJson()))
    }

    @Test fun slowedReverbMode() {
        val s = app.phase.stem.audio.SoundMode.SLOWED.apply(MixState())
        assertEquals(0.85f, s.speed)
        assertTrue(s[Bus.MASTER, Param.REVERB_MIX] > 0.3f)
        assertEquals(app.phase.stem.audio.SoundMode.SLOWED, app.phase.stem.audio.SoundMode.detect(s))
        assertEquals(app.phase.stem.audio.SoundMode.ORIGINAL, app.phase.stem.audio.SoundMode.detect(app.phase.stem.audio.SoundMode.ORIGINAL.apply(s)))
    }
}
