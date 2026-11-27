// DJ one-shot sound effects, synthesised at startup (no sample files, no
// licensing): riser, drop impact, laser, air horn, siren, downlifter.
// Triggering is lock-free: the UI bumps a per-pad counter, the audio
// thread starts a voice when it sees the counter change.
#pragma once

#include <atomic>

#include "Basics.h"

namespace phase::dsp {

class SfxBank {
public:
    static constexpr int kPads = 6;
    enum { RISER, DROP, LASER, HORN, SIREN, DOWNLIFTER };

    void prepare(float sr) {
        sr_ = sr;
        for (int p = 0; p < kPads; ++p) render(p);
        for (auto& v : voices_) v.pad = -1;
    }

    // Any thread.
    void trigger(int pad) {
        if (pad >= 0 && pad < kPads) seq_[pad].fetch_add(1, std::memory_order_release);
    }

    // Any thread: silences every playing pad.
    void stopAll() { stopSeq_.fetch_add(1, std::memory_order_release); }

    // Audio thread: adds active voices into the stereo block.
    void mix(float* L, float* R, int n) {
        const uint32_t st = stopSeq_.load(std::memory_order_acquire);
        if (st != stopSeen_) {
            stopSeen_ = st;
            for (auto& v : voices_) v.pad = -1;
            for (int p = 0; p < kPads; ++p) seen_[p] = seq_[p].load();
        }
        for (int p = 0; p < kPads; ++p) {
            const uint32_t s = seq_[p].load(std::memory_order_acquire);
            if (s != seen_[p]) {
                seen_[p] = s;
                Voice* v = &voices_[0];
                for (auto& c : voices_) if (c.pad < 0) { v = &c; break; }
                v->pad = p;
                v->pos = 0;
            }
        }
        for (auto& v : voices_) {
            if (v.pad < 0) continue;
            const auto& b = buf_[v.pad];
            const int len = (int)b.size() / 2;
            const int m = std::min(n, len - v.pos);
            for (int i = 0; i < m; ++i) {
                L[i] += b[2 * (v.pos + i)];
                R[i] += b[2 * (v.pos + i) + 1];
            }
            v.pos += m;
            if (v.pos >= len) v.pad = -1;
        }
    }

    float lengthSeconds(int pad) const { return buf_[pad].size() / 2 / sr_; }

private:
    struct Voice { int pad = -1; int pos = 0; };

    uint32_t rnd() { rng_ = rng_ * 1664525u + 1013904223u; return rng_; }
    float noise() { return (float)(rnd() >> 9) / 4194304.f - 1.f; }

    void render(int pad) {
        const float dur[kPads] = {4.0f, 1.6f, 0.55f, 1.7f, 2.4f, 2.6f};
        const int n = (int)(dur[pad] * sr_);
        auto& b = buf_[pad];
        b.assign((size_t)n * 2, 0.f);
        Svf fL, fR;
        double ph = 0, ph2 = 0, ph3 = 0;
        for (int i = 0; i < n; ++i) {
            const float t = i / sr_, x = t / dur[pad];
            float l = 0, r = 0;
            switch (pad) {
                case RISER: {  // noise + saw sweeping up, building to the drop
                    const float fc = 300.f * std::pow(9000.f / 300.f, x);
                    if (i % 32 == 0) { fL.setCoefs(sr_, fc, 3.f); fR.setCoefs(sr_, fc * 1.03f, 3.f); }
                    ph += (90.f * std::pow(10.f, x)) / sr_;
                    const float saw = 2.f * (float)(ph - std::floor(ph)) - 1.f;
                    l = fL.process(noise(), Svf::LowPass) * 0.7f + saw * 0.18f;
                    r = fR.process(noise(), Svf::LowPass) * 0.7f + saw * 0.18f;
                    const float env = x * x;
                    l *= env; r *= env;
                    break;
                }
                case DROP: {  // sub boom with pitch dive and a click
                    const float f = 35.f + 90.f * std::exp(-t * 9.f);
                    ph += f / sr_;
                    float s = std::sin(2 * kPi * (float)ph) * std::exp(-t * 2.2f);
                    s += noise() * std::exp(-t * 60.f) * 0.5f;
                    l = r = std::tanh(s * 1.6f);
                    break;
                }
                case LASER: {  // fast downward zap with a slapback
                    const float f = 3200.f * std::exp(-t * 9.f) + 120.f;
                    ph += f / sr_;
                    const float sq = std::sin(2 * kPi * (float)ph) > 0 ? 0.6f : -0.6f;
                    const float s = sq * std::exp(-t * 7.f);
                    l = s;
                    r = s * 0.85f;
                    break;
                }
                case HORN: {  // "da-da-daaa" detuned saw stack
                    const float on = (t < 0.16f) || (t > 0.22f && t < 0.38f) || (t > 0.44f);
                    const float rel = t > 1.4f ? std::max(0.f, 1.f - (t - 1.4f) / 0.3f) : 1.f;
                    ph += 466.f / sr_; ph2 += 587.f / sr_; ph3 += 698.5f / sr_;
                    float s = 0;
                    for (double p : {ph, ph2, ph3, ph * 1.006, ph2 * 0.994}) s += 2.f * (float)(p - std::floor(p)) - 1.f;
                    if (i % 32 == 0) { fL.setCoefs(sr_, 2600.f, 1.2f); }
                    s = fL.process(s * 0.2f, Svf::LowPass);
                    l = r = std::tanh(s * 2.f) * on * rel * 0.8f;
                    break;
                }
                case SIREN: {  // rave siren wobble
                    const float f = 650.f + 350.f * std::sin(2 * kPi * 2.2f * t);
                    ph += f / sr_;
                    const float s = (std::sin(2 * kPi * (float)ph) + 0.3f * std::sin(6 * kPi * (float)ph)) * 0.45f;
                    const float env = std::min(1.f, t * 8.f) * std::min(1.f, (dur[pad] - t) * 4.f);
                    l = s * env;
                    r = s * env * 0.9f;
                    break;
                }
                case DOWNLIFTER: {  // falling filtered noise sweep
                    const float fc = 9000.f * std::pow(150.f / 9000.f, x);
                    if (i % 32 == 0) { fL.setCoefs(sr_, fc, 2.f); fR.setCoefs(sr_, fc * 0.97f, 2.f); }
                    const float env = (1.f - x) * std::min(1.f, t * 20.f);
                    l = fL.process(noise(), Svf::LowPass) * env * 0.8f;
                    r = fR.process(noise(), Svf::LowPass) * env * 0.8f;
                    break;
                }
            }
            b[2 * i] = l;
            b[2 * i + 1] = r;
        }
        float peak = 1e-6f;
        for (float v : b) peak = std::max(peak, std::fabs(v));
        const float g = 0.6f / peak;
        for (float& v : b) v *= g;
        // De-click the ends.
        const int fade = std::min(n / 4, (int)(0.004f * sr_));
        for (int i = 0; i < fade; ++i) {
            const float k = i / (float)fade;
            b[2 * i] *= k; b[2 * i + 1] *= k;
            b[2 * (n - 1 - i)] *= k; b[2 * (n - 1 - i) + 1] *= k;
        }
    }

    float sr_ = 48000;
    uint32_t rng_ = 987654321u;
    std::vector<float> buf_[kPads];
    Voice voices_[8];
    std::atomic<uint32_t> seq_[kPads] = {};
    uint32_t seen_[kPads] = {};
    std::atomic<uint32_t> stopSeq_{0};
    uint32_t stopSeen_ = 0;
};

}  // namespace phase::dsp
