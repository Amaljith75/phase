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

    std::printf("[export] offline render == live render; WAV + MP3 writers\n");
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

        o.path = tmpDir() + "/phase_export.mp3";
        o.format = "mp3";
        o.bitrateKbps = 256;
        ok = exportMix(live, o, prog, cancel, &err);
        std::ifstream m(o.path, std::ios::binary | std::ios::ate);
        long sz = (long)m.tellg();
        std::printf("    mp3 size %ld bytes for 4 s @256k\n", sz);
        CHECK(ok && sz > 100000 && sz < 160000, "MP3 export size consistent with bitrate");
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

    std::printf(failures ? "\n%d FAILURE(S)\n" : "\nALL ENGINE TESTS PASSED\n", failures);
    return failures ? 1 : 0;
}
