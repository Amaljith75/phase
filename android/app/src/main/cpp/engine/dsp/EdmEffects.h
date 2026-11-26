// EDM / DJ effects: bitcrusher, phaser, flanger, beat-synced gate & pump,
// one-knob DJ filter and a channel vocoder ("robot voice"). All real-time
// safe, smoothed, and zero-cost when their amount is 0.
#pragma once

#include "Basics.h"

namespace phase::dsp {

// ------------------------------------------------------------ Bitcrusher ---
// Bit-depth reduction + sample-and-hold decimation (the classic lo-fi /
// French-house crunch). One knob drives both.
class Bitcrusher {
public:
    void prepare(float sr) { wet_.prepare(sr, 20.f); wet_.snap(0); }
    void process(float* L, float* R, int n, float amount) {
        wet_.setTarget(amount > 0.f ? 1.f : 0.f);
        if (amount <= 0.f && wet_.current() < 1e-4f) return;
        const float bits = 16.f - 12.5f * amount;
        const float q = std::pow(2.f, bits - 1.f);
        const int hold = 1 + (int)(amount * amount * 16.f);
        for (int i = 0; i < n; ++i) {
            const float w = wet_.next();
            if (counter_++ % hold == 0) {
                hl_ = std::round(L[i] * q) / q;
                hr_ = std::round(R[i] * q) / q;
            }
            L[i] += (hl_ - L[i]) * w;
            R[i] += (hr_ - R[i]) * w;
        }
    }

private:
    Smoother wet_;
    unsigned counter_ = 0;
    float hl_ = 0, hr_ = 0;
};

// ---------------------------------------------------------------- Phaser ---
class Phaser {
public:
    void prepare(float sr) { sr_ = sr; mix_.prepare(sr, 30.f); mix_.snap(0); reset(); }
    void reset() { for (auto& c : st_) for (auto& s : c) s = {0, 0}; fb_[0] = fb_[1] = 0; }
    void process(float* L, float* R, int n, float mix) {
        mix_.setTarget(mix);
        if (mix <= 0.f && mix_.current() < 1e-4f) return;
        float* io[2] = {L, R};
        for (int i = 0; i < n; ++i) {
            const float m = mix_.next();
            phase_ += 0.35f / sr_;
            if (phase_ >= 1.f) phase_ -= 1.f;
            for (int c = 0; c < 2; ++c) {
                const float lfo = 0.5f + 0.5f * std::sin(2 * kPi * (phase_ + 0.25f * c));
                const float f = 250.f * std::pow(10.f, 1.0f * lfo);  // 250 Hz .. 2.5 kHz
                const float t = std::tan(kPi * f / sr_);
                const float a = (t - 1.f) / (t + 1.f);
                float x = io[c][i] + fb_[c] * 0.55f;
                for (auto& s : st_[c]) {
                    const float y = a * x + s.x1 - a * s.y1;
                    s.x1 = x;
                    s.y1 = y;
                    x = y;
                }
                fb_[c] = x;
                io[c][i] = io[c][i] * (1.f - 0.5f * m) + x * 0.5f * m;
            }
        }
    }

private:
    struct Ap { float x1, y1; };
    float sr_ = 48000, phase_ = 0, fb_[2] = {0, 0};
    Ap st_[2][6] = {};
    Smoother mix_;
};

// --------------------------------------------------------------- Flanger ---
class Flanger {
public:
    void prepare(float sr) {
        sr_ = sr;
        for (auto& d : line_) d.prepare((int)(sr * 0.012f));
        mix_.prepare(sr, 30.f);
        mix_.snap(0);
    }
    void process(float* L, float* R, int n, float mix) {
        mix_.setTarget(mix);
        float* io[2] = {L, R};
        if (mix <= 0.f && mix_.current() < 1e-4f) {
            for (int i = 0; i < n; ++i) { line_[0].push(L[i]); line_[1].push(R[i]); }
            return;
        }
        for (int i = 0; i < n; ++i) {
            const float m = mix_.next();
            phase_ += 0.22f / sr_;
            if (phase_ >= 1.f) phase_ -= 1.f;
            for (int c = 0; c < 2; ++c) {
                const float lfo = 0.5f + 0.5f * std::sin(2 * kPi * (phase_ + 0.5f * c));
                const float d = (0.0008f + 0.0042f * lfo) * sr_;
                const float w = line_[c].read(d);
                line_[c].push(io[c][i] + w * 0.65f);
                io[c][i] = io[c][i] * (1.f - 0.5f * m) + w * 0.6f * m;
            }
        }
    }

private:
    float sr_ = 48000, phase_ = 0;
    DelayLine line_[2];
    Smoother mix_;
};

// ----------------------------------------------- Beat gate & sidechain pump ---
// Both read a per-sample beat phase (0..1 within each beat, from the
// timeline BPM). Gate chops 1/16 notes; pump ducks on every beat and swells
// back, the EDM "sidechain" sound.
class BeatDynamics {
public:
    void prepare(float sr) { g_.prepare(sr, 1.5f); g_.snap(1.f); }
    void process(float* L, float* R, int n, const float* beat, float gate, float pump) {
        if (!beat || (gate <= 0.f && pump <= 0.f)) {
            if (g_.current() < 0.9999f) {  // release smoothly
                g_.setTarget(1.f);
                for (int i = 0; i < n; ++i) { const float g = g_.next(); L[i] *= g; R[i] *= g; }
            }
            return;
        }
        for (int i = 0; i < n; ++i) {
            const float b = beat[i];
            float target = 1.f;
            if (gate > 0.f) {
                float s = b * 4.f;
                s -= std::floor(s);
                if (s >= 0.5f) target *= 1.f - gate;
            }
            if (pump > 0.f) {
                const float r = std::min(b / 0.55f, 1.f);
                target *= 1.f - pump * (1.f - r) * (1.f - r);
            }
            g_.setTarget(target);
            const float g = g_.next();
            L[i] *= g;
            R[i] *= g;
        }
    }

private:
    Smoother g_;
};

// ------------------------------------------------------------- DJ filter ---
// One knob: left = resonant low-pass sweep, right = high-pass sweep.
class DjFilter {
public:
    void prepare(float sr) { sr_ = sr; v_.prepare(sr / 16, 25.f); v_.snap(0); for (auto& f : f_) f.reset(); }
    void process(float* L, float* R, int n, float target) {
        v_.setTarget(target);
        for (int off = 0; off < n; off += 16) {
            const int m = std::min(16, n - off);
            const float v = v_.next();
            if (std::fabs(v) < 0.015f) { f_[0].reset(); f_[1].reset(); continue; }
            const bool lp = v < 0.f;
            const float a = std::fabs(v);
            const float fc = lp ? 20000.f * std::pow(120.f / 20000.f, a) : 20.f * std::pow(7000.f / 20.f, a);
            const float q = 0.75f + 2.2f * a;
            for (auto& f : f_) f.setCoefs(sr_, fc, q);
            const Svf::Mode md = lp ? Svf::LowPass : Svf::HighPass;
            for (int i = off; i < off + m; ++i) {
                L[i] = f_[0].process(L[i], md);
                R[i] = f_[1].process(R[i], md);
            }
        }
    }

private:
    float sr_ = 48000;
    Smoother v_;
    Svf f_[2];
};

// --------------------------------------------------------------- Vocoder ---
// 16-band channel vocoder. The input (normally the vocal stem) is the
// modulator; the carrier is a built-in detuned saw chord on a chosen key,
// plus a little noise so consonants stay intelligible. This is the
// classic robot-voice / talk-box style sound of French house.
class Vocoder {
public:
    static constexpr int kBands = 16;
    void prepare(float sr) {
        sr_ = sr;
        mix_.prepare(sr, 30.f);
        mix_.snap(0);
        for (int b = 0; b < kBands; ++b) {
            const float f = 110.f * std::pow(7500.f / 110.f, b / (float)(kBands - 1));
            mod_[b].setBandpass(sr, f, 6.f);
            car_[b].setBandpass(sr, f, 6.f);
            env_[b] = 0;
        }
        att_ = 1.f - std::exp(-1.f / (0.002f * sr));
        rel_ = 1.f - std::exp(-1.f / (0.035f * sr));
    }
    void process(float* L, float* R, int n, float mix, int rootMidi, bool minor) {
        mix_.setTarget(mix);
        if (mix <= 0.f && mix_.current() < 1e-4f) return;
        const int notes[4] = {rootMidi, rootMidi + (minor ? 3 : 4) + 12, rootMidi + 7, rootMidi + 12};
        float inc[4];
        for (int k = 0; k < 4; ++k) inc[k] = 440.f * std::pow(2.f, (notes[k] - 69) / 12.f) / sr_;
        for (int i = 0; i < n; ++i) {
            const float m = mix_.next();
            const float x = 0.5f * (L[i] + R[i]);
            float carrier = 0.f;
            for (int k = 0; k < 4; ++k) {
                ph_[k] += inc[k] * (k == 1 ? 1.003f : 1.f);  // slight detune for width
                if (ph_[k] >= 1.f) ph_[k] -= 1.f;
                carrier += 2.f * ph_[k] - 1.f;
            }
            noise_ = noise_ * 1664525u + 1013904223u;
            carrier = carrier * 0.25f + ((float)(noise_ >> 9) / 4194304.f - 1.f) * 0.04f;
            float y = 0.f;
            for (int b = 0; b < kBands; ++b) {
                const float a = std::fabs(mod_[b].process(x));
                env_[b] += (a - env_[b]) * (a > env_[b] ? att_ : rel_);
                y += car_[b].process(carrier) * env_[b];
            }
            y *= 9.f;
            L[i] += (y - L[i]) * m;
            R[i] += (y - R[i]) * m;
        }
    }

private:
    float sr_ = 48000, att_ = 0.1f, rel_ = 0.01f;
    Biquad mod_[kBands], car_[kBands];
    float env_[kBands] = {};
    float ph_[4] = {0, 0.25f, 0.5f, 0.75f};
    uint32_t noise_ = 12345;
    Smoother mix_;
};

}  // namespace phase::dsp
