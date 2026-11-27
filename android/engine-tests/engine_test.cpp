// Host-side tests for the portable C++ engine core. Build & run:
//   cmake -S android/engine-tests -B build/engine-tests && cmake --build build/engine-tests && build/engine-tests/engine_test
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

#include "Exporter.h"
#include "StemEngine.h"

using namespace phase;

static int failures = 0;
#define CHECK(cond, msg)                                                      \
    do {                                                                      \
        if (!(cond)) { std::printf("  FAIL: %s (%s:%d)\n", msg, __FILE__, __LINE__); ++failures; } \
        else std::printf("  ok:   %s\n", msg);                                \
    } while (0)

static std::string tmpDir() {
    const char* d = std::getenv("TMPDIR");
    return d ? d : "/tmp";
}

// Writes an int16 stereo raw PCM file from a generator.
template <typename F>
static std::string writePcm(const std::string& name, int frames, F gen) {
    std::string path = tmpDir() + "/" + name;
    std::vector<int16_t> buf(frames * 2);
    for (int i = 0; i < frames; ++i) {
        float l, r;
        gen(i, l, r);
        buf[2 * i] = (int16_t)std::lrint(std::clamp(l, -1.f, 1.f) * 32767.f);
        buf[2 * i + 1] = (int16_t)std::lrint(std::clamp(r, -1.f, 1.f) * 32767.f);
    }
    std::ofstream(path, std::ios::binary).write((const char*)buf.data(), buf.size() * 2);
    return path;
}

static std::vector<float> renderSeconds(StemEngine& e, double secs, int block = 192) {
    const int total = (int)(secs * e.outputSampleRate());
    std::vector<float> out(total * 2);
    for (int off = 0; off < total; off += block) e.render(out.data() + off * 2, std::min(block, total - off));
    return out;
}

static float maxJump(const std::vector<float>& v, int from = 0) {
    float m = 0;
    for (size_t i = from + 2; i < v.size(); i += 2) m = std::max(m, std::fabs(v[i] - v[i - 2]));
    return m;
}

static bool allFinite(const std::vector<float>& v) {
    for (float x : v) if (!std::isfinite(x) || std::fabs(x) > 1.0f) return false;
    return true;
}

int main() {
    const int sr = 44100, frames = sr * 20;
    // Four distinct stems with deliberately different content.
    std::vector<std::string> stems = {
        writePcm("vox.pcm", frames, [](int i, float& l, float& r) { l = r = 0.1f * std::sin(2 * M_PI * 440 * i / 44100.0); }),
        writePcm("drm.pcm", frames, [](int i, float& l, float& r) { l = r = (i % 11025 < 200) ? 0.2f : 0.f; }),
        writePcm("bas.pcm", frames, [](int i, float& l, float& r) { l = r = 0.1f * std::sin(2 * M_PI * 55 * i / 44100.0); }),
        writePcm("oth.pcm", frames, [](int i, float& l, float& r) { l = 0.05f * std::sin(2 * M_PI * 880 * i / 44100.0); r = -l; }),
    };

    std::printf("[sync] four stems share one sample-accurate playhead\n");
    {
        StemEngine e;
        e.prepare(sr);
        std::string err;
        CHECK(e.load(stems, sr, &err), "load 4 stems");
        CHECK(std::fabs(e.duration() - 20.0) < 1e-6, "duration is 20 s");
        e.setPlaying(true);
        auto out = renderSeconds(e, 5.0);
        // After the 6 ms start ramp, output must equal the exact sum of stems.
        double maxErr = 0;
        for (int i = sr / 10; i < sr * 5; ++i) {
            float vl = 0.1f * std::sin(2 * M_PI * 440 * i / 44100.0) + ((i % 11025 < 200) ? 0.2f : 0.f) +
                       0.1f * std::sin(2 * M_PI * 55 * i / 44100.0) + 0.05f * std::sin(2 * M_PI * 880 * i / 44100.0);
            maxErr = std::max(maxErr, (double)std::fabs(out[2 * i] - vl));
        }
        std::printf("    max deviation from ideal sum over 5 s: %.6f\n", maxErr);
        CHECK(maxErr < 2e-4, "mix == sum of stems (no drift, no offset)");
        CHECK(std::fabs(e.position() - 5.0) < 0.01, "position advanced exactly 5 s");
    }

    std::printf("[mute/solo] gain changes are smoothed, no gaps\n");
    {
        StemEngine e;
        e.prepare(sr);
        std::string err;
        e.load({stems[0], stems[2]}, sr, &err);  // not stem mode -> full-mix path
        e.unload();
        e.load(stems, sr, &err);
        e.setPlaying(true);
        renderSeconds(e, 0.5);
        e.bus(0).set(P_SOLO, 1);  // solo vocals
        auto out = renderSeconds(e, 1.0);
        double resid = 0;  // energy that is not the 440 Hz vocal after 100 ms
        for (int i = sr / 10; i < sr; ++i) {
            int gi = i + sr / 2;
            float v = 0.1f * std::sin(2 * M_PI * 440 * gi / 44100.0);
            resid = std::max(resid, (double)std::fabs(out[2 * i] - v));
        }
        CHECK(resid < 1e-3, "solo isolates the vocal stem exactly");
        float jump = 0;  // ignore the synthetic drum pulse's own edges
        for (int i = 1; i < sr; ++i) {
            int gi = (i + sr / 2) % 11025;
            if (gi == 0 || gi == 200) continue;
            jump = std::max(jump, std::fabs(out[2 * i] - out[2 * i - 2]));
        }
        CHECK(jump < 0.02f, "solo transition has no click (smoothed)");
        e.bus(0).set(P_SOLO, 0);
        e.bus(0).set(P_MUTE, 1);
        out = renderSeconds(e, 1.0);
        CHECK(allFinite(out), "mute output finite");
    }

    std::printf("[seek/loop] crossfaded, continuous\n");
    {
        StemEngine e;
        e.prepare(48000);  // exercise resampling 44.1k -> 48k
        std::string err;
        e.load({stems[0]}, sr, &err);  // pure sine: any discontinuity shows up
        e.setPlaying(true);
        renderSeconds(e, 0.3);
        float base = maxJump(renderSeconds(e, 0.3));
        e.seek(7.123);
        auto out = renderSeconds(e, 0.3);
        std::printf("    sine max step %.4f, after seek %.4f\n", base, maxJump(out));
        CHECK(maxJump(out) < base * 3.f, "seek is click-free");
        CHECK(std::fabs(e.position() - 7.423) < 0.02, "seek lands on target");
        e.setLoop(true, 2.0, 2.5013);
        e.seek(2.4);
        out = renderSeconds(e, 2.0);
        CHECK(e.position() >= 2.0 && e.position() <= 2.5013, "loop keeps playhead inside A/B");
        CHECK(maxJump(out) < base * 3.f, "loop wrap is click-free");
        e.setLoop(false, 0, 0);
        e.setReverse(true);
        double p0 = e.position();
        renderSeconds(e, 1.0);
        CHECK(e.position() < p0 - 0.9, "reverse playback runs backwards");
        e.setReverse(false);
        e.setSpeed(1.5f);
        e.setKeepPitch(true);
        p0 = e.position();
        out = renderSeconds(e, 1.0);
        CHECK(std::fabs((e.position() - p0) - 1.5) < 0.02, "speed 1.5x advances 1.5 s per second");
        CHECK(allFinite(out), "keep-pitch output finite");
        e.setPlaying(false);
        renderSeconds(e, 0.1);
        p0 = e.position();
        renderSeconds(e, 0.5);
        CHECK(e.position() == p0, "pause holds position");
    }

    std::printf("[fx] every effect at extreme settings stays stable\n");
    {
        StemEngine e;
        e.prepare(48000);
        std::string err;
        e.load(stems, sr, &err);
        for (int b = 0; b < kNumBuses; ++b) {
            auto& p = e.bus(b);
            p.set(P_HPF_FREQ, 300); p.set(P_LPF_FREQ, 3000);
            p.set(P_DRIVE, 1); p.set(P_DIST_MIX, 1);
            p.set(P_CHORUS_MIX, 1); p.set(P_CHORUS_DEPTH, 1); p.set(P_CHORUS_RATE, 5);
            p.set(P_DELAY_MIX, 1); p.set(P_DELAY_FEEDBACK, 0.95f); p.set(P_DELAY_TIME, 120);
            p.set(P_ECHO_MIX, 1); p.set(P_ECHO_FEEDBACK, 0.95f); p.set(P_ECHO_TIME, 300);
            p.set(P_REVERB_MIX, 1); p.set(P_REVERB_SIZE, 1); p.set(P_REVERB_DAMP, 0);
            p.set(P_PITCH, b % 2 ? 7 : -5);
            p.set(P_EQ_LOW, 12); p.set(P_EQ_MID, -12); p.set(P_EQ_HIGH, 12);
            p.set(P_PAN, b % 2 ? 1 : -1);
            p.set(P_GAIN, 1.5f);
        }
        e.setPlaying(true);
        auto t0 = std::chrono::steady_clock::now();
        auto out = renderSeconds(e, 15.0, 96);
        double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        CHECK(allFinite(out), "worst-case FX: finite and limited to [-1, 1]");
        std::printf("    15 s of 4 stems + master, all FX on: %.3f s CPU (%.1fx real time)\n", secs, 15.0 / secs);
        CHECK(secs < 15.0 / 4, "at least 4x faster than real time on host");
        float m[kNumBuses * 2];
        e.meters(m);
        CHECK(m[8] > 0.f, "master meter reports signal");
    }

    std::printf("[export] offline render == live render\n");
    {
        StemEngine live;
        live.prepare(sr);
        std::string err;
        live.load(stems, sr, &err);
        live.bus(1).set(P_GAIN, 0.4f);
        live.bus(0).set(P_REVERB_MIX, 0.5f);
        live.bus(2).set(P_LPF_FREQ, 400);
        live.bus(kMasterBus).set(P_EQ_HIGH, 3);
        std::atomic<float> prog{0};
        std::atomic<bool> cancel{false};
        ExportOptions o;
        o.path = tmpDir() + "/phase_export.wav";
        o.endSec = 4.0;
        o.renderTail = false;
        bool ok = exportMix(live, o, prog, cancel, &err);
        CHECK(ok, ("WAV export: " + err).c_str());
        // Compare against a fresh live render with identical params.
        live.seek(0);
        live.snapSmoothers();
        live.setTransportImmediate(true);
        auto ref = renderSeconds(live, 4.0, 4096);
        std::ifstream f(o.path, std::ios::binary);
        std::vector<char> bytes((std::istreambuf_iterator<char>(f)), {});
        const int16_t* pcm = (const int16_t*)(bytes.data() + 44);
        size_t n = std::min((bytes.size() - 44) / 2, ref.size());
        double maxErr = 0;
        for (size_t i = 0; i < n; ++i) maxErr = std::max(maxErr, std::fabs(pcm[i] / 32767.0 - ref[i]));
        std::printf("    export vs live max diff: %.6f over %zu samples\n", maxErr, n);
        CHECK(maxErr < 3e-4, "export matches playback (same DSP path)");
        CHECK(prog.load() == 1.f, "progress reaches 100%");

    }

    std::printf("[recorder] live output captured to disk\n");
    {
        StemEngine e;
        e.prepare(48000);
        std::string err;
        e.load(stems, sr, &err);
        e.setPlaying(true);
        std::string path = tmpDir() + "/phase_rec.wav";
        CHECK(e.recorder().start(path, "wav", 0, 48000, &err), "recorder start");
        renderSeconds(e, 2.0);
        double secs = e.recorder().stop();
        CHECK(std::fabs(secs - 2.0) < 0.01, "recorded 2.0 s");
        std::ifstream f(path, std::ios::binary | std::ios::ate);
        CHECK((long)f.tellg() == 44 + 2 * 48000 * 4, "WAV size exact");
    }


    std::printf("[mashup] multiple decks share one timeline\n");
    {
        StemEngine e;
        e.prepare(sr);
        std::string err;
        // Deck 0: the four stems; deck 1: the same stems starting 1 s later,
        // with its vocals muted at deck level.
        CHECK(e.loadDecks({DeckSpec{stems, sr}, DeckSpec{stems, sr}}, &err), "load 2 decks");
        CHECK(e.deckCount() == 2, "deck count");
        e.setDeckParam(1, D_OFFSET, 1.0f);
        e.setDeckParam(1, D_GAIN_VOCALS, 0.f);
        CHECK(std::fabs(e.duration() - 21.0) < 1e-6, "duration covers offset deck");
        e.snapSmoothers();
        e.setPlaying(true);
        auto out = renderSeconds(e, 3.0);
        auto sig = [](int i) -> double {
            if (i < 0) return 0;
            return 0.1 * std::sin(2 * M_PI * 440 * i / 44100.0) + ((i % 11025 < 200) ? 0.2 : 0.0) +
                   0.1 * std::sin(2 * M_PI * 55 * i / 44100.0) + 0.05 * std::sin(2 * M_PI * 880 * i / 44100.0);
        };
        auto vox = [](int i) -> double { return i < 0 ? 0 : 0.1 * std::sin(2 * M_PI * 440 * i / 44100.0); };
        double maxErr = 0;
        for (int i = sr / 10; i < sr * 3; ++i) {
            const double want = sig(i) + sig(i - sr) - vox(i - sr);
            maxErr = std::max(maxErr, std::fabs(out[2 * i] - want));
        }
        std::printf("    two-deck mix max deviation %.6f\n", maxErr);
        CHECK(maxErr < 3e-4, "deck 1 lands exactly 1 s later, deck stem gain applies");

        // Tempo sync: deck 1 at 2x reads its source twice as fast.
        e.setDeckParam(1, D_OFFSET, 0.f);
        e.setDeckParam(1, D_RATIO, 2.f);
        e.setDeckParam(1, D_KEYLOCK, 0.f);
        e.setDeckParam(1, D_GAIN_VOCALS, 1.f);
        e.setDeckParam(0, D_VOLUME, 0.f);
        e.seek(2.0);
        renderSeconds(e, 0.5);
        out = renderSeconds(e, 0.5);
        maxErr = 0;
        for (int i = sr / 10; i < sr / 2; ++i) {
            const double t = 2.5 + (double)i / sr;
            const double want = sig((int)std::lround(t * 2 * sr));
            maxErr = std::max(maxErr, std::fabs(out[2 * i] - want));
        }
        std::printf("    2x tempo deck max deviation %.5f\n", maxErr);
        CHECK(maxErr < 0.03, "tempo ratio resamples the deck on the shared clock");
        CHECK(std::fabs(e.duration() - 20.0) < 1e-6, "duration follows deck ratio");

        // Trim: deck 1 plays song seconds [5, 7) starting at timeline 0.
        e.setDeckParam(1, D_RATIO, 1.f);
        e.setDeckParam(1, D_TRIM_IN, 5.f);
        e.setDeckParam(1, D_TRIM_OUT, 7.f);
        CHECK(std::fabs(e.duration() - 20.0) < 1e-6, "deck 0 still defines the length");
        e.seek(0.0);
        renderSeconds(e, 0.5);
        out = renderSeconds(e, 2.0);
        maxErr = 0;
        double tail = 0;
        for (int i = sr / 10; i < 2 * sr; ++i) {
            const double t = 0.5 + (double)i / sr;
            const double want = t < 2.0 ? sig((int)std::lround((5.0 + t) * sr)) : 0.0;
            if (std::fabs(t - 2.0) < 0.02) continue;  // edge sample
            maxErr = std::max(maxErr, std::fabs(out[2 * i] - want));
            if (t > 2.05) tail = std::max(tail, (double)std::fabs(out[2 * i]));
        }
        std::printf("    trimmed deck max deviation %.5f, after out-point %.5f\n", maxErr, tail);
        CHECK(maxErr < 3e-4 && tail < 1e-6, "trim in/out plays exactly the chosen region");
    }

    std::printf("[dj] roll, tape stop, sound pads\n");
    {
        StemEngine e;
        e.prepare(48000);
        std::string err;
        e.load(stems, sr, &err);
        e.setGlobal(G_BPM, 120);
        e.setPlaying(true);
        renderSeconds(e, 1.0);
        e.setGlobal(G_ROLL, 0.5f);  // 1/2 beat = 0.25 s repeat
        auto out = renderSeconds(e, 2.0);
        CHECK(std::fabs(e.position() - 3.0) < 0.02, "slip: timeline keeps moving during a roll");
        CHECK(allFinite(out), "roll output finite");
        e.setGlobal(G_ROLL, 0.f);
        renderSeconds(e, 0.5);
        CHECK(std::fabs(e.position() - 3.5) < 0.02, "release resumes where the track would be");
        e.brake();
        renderSeconds(e, 1.2);
        CHECK(!e.isPlaying(), "tape stop halts playback");
        const double stopped = e.position();
        CHECK(stopped > 3.6 && stopped < 4.1, "tape stop decelerates (doesn't jump)");
        std::vector<float> pad(48000 * 2, 0.f);
        e.triggerSfx(1);  // drop
        e.render(pad.data(), 48000);
        float pk = 0;
        for (float v : pad) pk = std::max(pk, std::fabs(v));
        CHECK(pk > 0.2f && pk <= 1.f, "sound pad plays even while paused");
        e.triggerSfx(0);
        e.render(pad.data(), 4800);
        e.stopSfx();
        e.render(pad.data(), 4800);
        pk = 0;
        for (int i = 2 * 2400; i < 2 * 4800; ++i) pk = std::max(pk, std::fabs(pad[i]));
        CHECK(pk < 1e-6f, "stop silences all pads");
    }

    std::printf("[edm] new effects at extreme settings stay stable\n");
    {
        StemEngine e;
        e.prepare(48000);
        std::string err;
        e.load(stems, sr, &err);
        e.setGlobal(G_BPM, 128);
        for (int b = 0; b < kNumBuses; ++b) {
            auto& p = e.bus(b);
            p.set(P_CRUSH, 1); p.set(P_PHASER, 1); p.set(P_FLANGER, 1);
            p.set(P_GATE, 1); p.set(P_PUMP, 1); p.set(P_VOCODER, 1);
            p.set(P_DJ_FILTER, b % 2 ? 0.9f : -0.9f);
        }
        e.setPlaying(true);
        auto t0 = std::chrono::steady_clock::now();
        auto out = renderSeconds(e, 10.0, 96);
        double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        CHECK(allFinite(out), "EDM FX: finite and limited");
        std::printf("    10 s with every EDM effect on every bus: %.2f s CPU (%.1fx real time)\n", secs, 10.0 / secs);
        double energy = 0;
        for (float v : out) energy += v * v;
        CHECK(energy > 1.0, "EDM chain produces sound");
    }

    std::printf("[export] mashup render == live\n");
    {
        StemEngine live;
        live.prepare(sr);
        std::string err;
        live.loadDecks({DeckSpec{stems, sr}, DeckSpec{stems, sr}}, &err);
        live.setDeckParam(1, D_OFFSET, 0.5f);
        live.setDeckParam(1, D_PITCH, 3.f);
        live.setDeckParam(0, D_GAIN_DRUMS, 0.3f);
        live.bus(kMasterBus).set(P_PHASER, 0.5f);
        std::atomic<float> prog{0};
        std::atomic<bool> cancel{false};
        ExportOptions o;
        o.path = tmpDir() + "/phase_mashup.wav";
        o.endSec = 3.0;
        o.renderTail = false;
        CHECK(exportMix(live, o, prog, cancel, &err), "mashup export");
        live.seek(0);
        live.snapSmoothers();
        live.setTransportImmediate(true);
        auto ref = renderSeconds(live, 3.0, 4096);
        std::ifstream f(o.path, std::ios::binary);
        std::vector<char> bytes((std::istreambuf_iterator<char>(f)), {});
        const int16_t* pcm = (const int16_t*)(bytes.data() + 44);
        size_t n = std::min((bytes.size() - 44) / 2, ref.size());
        double maxErr = 0;
        for (size_t i = 0; i < n; ++i) maxErr = std::max(maxErr, std::fabs(pcm[i] / 32767.0 - ref[i]));
        std::printf("    mashup export vs live max diff %.6f\n", maxErr);
        CHECK(maxErr < 3e-4, "mashup export matches playback");
    }

    std::printf(failures ? "\n%d FAILURE(S)\n" : "\nALL ENGINE TESTS PASSED\n", failures);
    return failures ? 1 : 0;
}
