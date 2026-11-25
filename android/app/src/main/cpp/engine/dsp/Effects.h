// Real-time effects. Each effect processes a stereo block in place and is
// allocation-free after prepare(). Wet/dry amounts are smoothed per-sample so
// knob moves never click.
#pragma once

#include "Basics.h"

namespace phase::dsp {

// ---------------------------------------------------------------- Reverb ---
// Schroeder/Moorer reverb in the Freeverb topology: 8 damped feedback combs +
// 4 series allpasses per channel, decorrelated by a fixed stereo spread.
class Reverb {
public:
    void prepare(float sr) {
        static const int combT[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int apT[4] = {556, 441, 341, 225};
        const float scale = sr / 44100.f;
        for (int c = 0; c < 2; ++c) {
            int spread = c ? 23 : 0;
            for (int i = 0; i < 8; ++i) comb_[c][i].prepare((int)((combT[i] + spread) * scale));
            for (int i = 0; i < 4; ++i) ap_[c][i].prepare((int)((apT[i] + spread) * scale));
        }
        mix_.prepare(sr, 30.f);
        mix_.snap(0.f);
    }
    void reset() {
        for (auto& ch : comb_) for (auto& c : ch) c.reset();
        for (auto& ch : ap_) for (auto& a : ch) a.reset();
    }
    bool idle() const { return mix_.current() < 1e-4f && mix_.target() == 0.f; }
    void process(float* L, float* R, int n, float mix, float size, float damp) {
        mix_.setTarget(mix);
        if (idle()) return;
        const float fb = 0.7f + 0.28f * size;
        const float d1 = damp * 0.4f, d2 = 1.f - d1;
        for (int i = 0; i < n; ++i) {
            const float m = mix_.next();
            const float in = (L[i] + R[i]) * 0.015f;
            float outL = 0, outR = 0;
            for (int k = 0; k < 8; ++k) {
                outL += comb_[0][k].process(in, fb, d1, d2);
                outR += comb_[1][k].process(in, fb, d1, d2);
            }
            for (int k = 0; k < 4; ++k) {
                outL = ap_[0][k].process(outL);
                outR = ap_[1][k].process(outR);
            }
            // Wet/dry: keep dry near unity for small amounts, as on hardware.
            const float dry = 1.f - 0.5f * m;
            L[i] = L[i] * dry + outL * 3.f * m;
            R[i] = R[i] * dry + outR * 3.f * m;
        }
    }

private:
    struct Comb {
        std::vector<float> b; int idx = 0; float store = 0;
        void prepare(int len) { b.assign(std::max(len, 1), 0.f); idx = 0; store = 0; }
        void reset() { std::fill(b.begin(), b.end(), 0.f); store = 0; }
        inline float process(float in, float fb, float d1, float d2) {
            float out = b[idx];
            store = out * d2 + store * d1;
            b[idx] = in + store * fb;
            if (++idx >= (int)b.size()) idx = 0;
            return out;
        }
    };
    struct Allpass {
        std::vector<float> b; int idx = 0;
        void prepare(int len) { b.assign(std::max(len, 1), 0.f); idx = 0; }
        void reset() { std::fill(b.begin(), b.end(), 0.f); }
        inline float process(float in) {
            float bo = b[idx];
            float out = -in + bo;
            b[idx] = in + bo * 0.5f;
            if (++idx >= (int)b.size()) idx = 0;
            return out;
        }
    };
    Comb comb_[2][8];
    Allpass ap_[2][4];
    Smoother mix_;
};

// --------------------------------------------------------- Stereo delay ---
// Clean digital delay with mild high-cut in the feedback path.
class StereoDelay {
public:
    void prepare(float sr) {
        sr_ = sr;
        for (auto& d : line_) d.prepare((int)(sr * 2.1f));
        time_.prepare(sr, 120.f);
        mix_.prepare(sr, 30.f);
        mix_.snap(0);
        damp_[0].setCoefs(sr, 9000.f, 0.707f);
        damp_[1].setCoefs(sr, 9000.f, 0.707f);
        primed_ = false;
    }
    void reset() { for (auto& d : line_) d.reset(); damp_[0].reset(); damp_[1].reset(); }
    void process(float* L, float* R, int n, float mix, float timeMs, float feedback) {
        mix_.setTarget(mix);
        float target = std::clamp(timeMs * 0.001f * sr_, 1.f, (float)line_[0].capacity() - 8.f);
        if (!primed_) { time_.snap(target); primed_ = true; }
        time_.setTarget(target);
        if (mix_.current() < 1e-4f && mix == 0.f) {
            // Keep the line fed with silence so re-enabling does not replay stale audio.
            for (int i = 0; i < n; ++i) { line_[0].push(0); line_[1].push(0); }
            return;
        }
        for (int i = 0; i < n; ++i) {
            const float m = mix_.next(), t = time_.next();
            float wl = line_[0].read(t), wr = line_[1].read(t);
            line_[0].push(L[i] + damp_[0].process(wl, Svf::LowPass) * feedback);
            line_[1].push(R[i] + damp_[1].process(wr, Svf::LowPass) * feedback);
            const float dry = 1.f - 0.4f * m;
            L[i] = L[i] * dry + wl * m;
            R[i] = R[i] * dry + wr * m;
        }
    }

private:
    float sr_ = 48000;
    DelayLine line_[2];
    Svf damp_[2];
    Smoother time_, mix_;
    bool primed_ = false;
};

// ------------------------------------------------------- Ping-pong echo ---
// Tape-flavoured ping-pong echo: band-limited, softly saturated repeats that
// bounce between channels. Distinct in character from StereoDelay.
class PingPongEcho {
public:
    void prepare(float sr) {
        sr_ = sr;
        for (auto& d : line_) d.prepare((int)(sr * 2.1f));
        time_.prepare(sr, 150.f);
        mix_.prepare(sr, 30.f);
        mix_.snap(0);
        for (int c = 0; c < 2; ++c) {
            lp_[c].setCoefs(sr, 3200.f, 0.707f);
            hp_[c].setCoefs(sr, 180.f, 0.707f);
        }
        primed_ = false;
    }
    void reset() { for (auto& d : line_) d.reset(); for (int c = 0; c < 2; ++c) { lp_[c].reset(); hp_[c].reset(); } }
    void process(float* L, float* R, int n, float mix, float timeMs, float feedback) {
        mix_.setTarget(mix);
        float target = std::clamp(timeMs * 0.001f * sr_, 1.f, (float)line_[0].capacity() - 8.f);
        if (!primed_) { time_.snap(target); primed_ = true; }
        time_.setTarget(target);
        if (mix_.current() < 1e-4f && mix == 0.f) {
            for (int i = 0; i < n; ++i) { line_[0].push(0); line_[1].push(0); }
            return;
        }
        for (int i = 0; i < n; ++i) {
            const float m = mix_.next(), t = time_.next();
            float el = line_[0].read(t), er = line_[1].read(t);
            float fl = std::tanh(hp_[0].process(lp_[0].process(el, Svf::LowPass), Svf::HighPass) * feedback * 1.1f);
            float fr = std::tanh(hp_[1].process(lp_[1].process(er, Svf::LowPass), Svf::HighPass) * feedback * 1.1f);
            // Mono input enters the left line; repeats cross over each pass.
            line_[0].push((L[i] + R[i]) * 0.5f + fr);
            line_[1].push(fl);
            const float dry = 1.f - 0.4f * m;
            L[i] = L[i] * dry + el * m;
            R[i] = R[i] * dry + er * m;
        }
    }

private:
    float sr_ = 48000;
    DelayLine line_[2];
    Svf lp_[2], hp_[2];
    Smoother time_, mix_;
    bool primed_ = false;
};

// ---------------------------------------------------------------- Chorus ---
class Chorus {
public:
    void prepare(float sr) {
        sr_ = sr;
        for (auto& d : line_) d.prepare((int)(sr * 0.06f));
        mix_.prepare(sr, 30.f);
        mix_.snap(0);
        phase_ = 0;
    }
    void reset() { for (auto& d : line_) d.reset(); }
    void process(float* L, float* R, int n, float mix, float rateHz, float depth) {
        mix_.setTarget(mix);
        if (mix_.current() < 1e-4f && mix == 0.f) {
            for (int i = 0; i < n; ++i) { line_[0].push(L[i]); line_[1].push(R[i]); }
            return;
        }
        const float inc = rateHz / sr_;
        const float base = 0.014f * sr_, dep = 0.008f * sr_ * depth;
        for (int i = 0; i < n; ++i) {
            const float m = mix_.next();
            line_[0].push(L[i]);
            line_[1].push(R[i]);
            float dl = base + dep * 0.5f * (1.f + std::sin(2.f * kPi * phase_));
            float dr = base + dep * 0.5f * (1.f + std::sin(2.f * kPi * (phase_ + 0.25f)));
            phase_ += inc;
            if (phase_ >= 1.f) phase_ -= 1.f;
            float wl = line_[0].read(dl), wr = line_[1].read(dr);
            const float norm = 1.f / (1.f + 0.5f * m);
            L[i] = (L[i] + wl * m) * norm;
            R[i] = (R[i] + wr * m) * norm;
        }
    }

private:
    float sr_ = 48000, phase_ = 0;
    DelayLine line_[2];
    Smoother mix_;
};

// ------------------------------------------------------------ Distortion ---
// tanh waveshaper with level compensation and a post high-cut to tame fizz.
class Distortion {
public:
    void prepare(float sr) {
        mix_.prepare(sr, 30.f);
        mix_.snap(0);
        drive_.prepare(sr, 30.f);
        drive_.snap(0);
        for (auto& f : tone_) f.setCoefs(sr, 7500.f, 0.707f);
    }
    void reset() { for (auto& f : tone_) f.reset(); }
    void process(float* L, float* R, int n, float mix, float drive) {
        mix_.setTarget(mix);
        drive_.setTarget(drive);
        if (mix_.current() < 1e-4f && mix == 0.f) return;
        for (int i = 0; i < n; ++i) {
            const float m = mix_.next();
            const float k = 1.f + 40.f * drive_.next() * drive_.current();
            const float comp = 0.35f / std::tanh(k * 0.35f);
            float wl = tone_[0].process(std::tanh(k * L[i]) * comp, Svf::LowPass);
            float wr = tone_[1].process(std::tanh(k * R[i]) * comp, Svf::LowPass);
            L[i] += (wl - L[i]) * m;
            R[i] += (wr - R[i]) * m;
        }
    }

private:
    Smoother mix_, drive_;
    Svf tone_[2];
};

// ------------------------------------------------------- Pitch shifter ---
// Time-domain granular pitch shifter: two read taps sweep through a delay
// line half a window apart with complementary (equal-power) windows.
// Latency ~ window/2. Used both as a creative effect and to keep pitch
// constant when playback speed changes.
class PitchShifter {
public:
    void prepare(float sr) {
        sr_ = sr;
        win_ = 0.060f * sr;
        for (auto& d : line_) d.prepare((int)(win_ * 2 + 64));
        wet_.prepare(sr, 25.f);
        wet_.snap(0);
        semis_.prepare(sr, 40.f);
        semis_.snap(0);
        phase_ = 0;
    }
    void reset() { for (auto& d : line_) d.reset(); phase_ = 0; }
    void process(float* L, float* R, int n, float semitones) {
        const bool active = std::fabs(semitones) > 0.01f;
        wet_.setTarget(active ? 1.f : 0.f);
        if (active) semis_.setTarget(semitones);
        if (!active && wet_.current() < 1e-4f) {
            for (int i = 0; i < n; ++i) { line_[0].push(L[i]); line_[1].push(R[i]); }
            semis_.snap(0);
            return;
        }
        for (int i = 0; i < n; ++i) {
            const float w = wet_.next();
            const float ratio = std::exp2(semis_.next() / 12.f);
            line_[0].push(L[i]);
            line_[1].push(R[i]);
            phase_ += (1.f - ratio) / win_;
            phase_ -= std::floor(phase_);
            float p2 = phase_ + 0.5f;
            if (p2 >= 1.f) p2 -= 1.f;
            const float g1 = std::sin(kPi * phase_), g2 = std::sin(kPi * p2);
            const float d1 = 2.f + phase_ * win_, d2 = 2.f + p2 * win_;
            float sl = line_[0].read(d1) * g1 + line_[0].read(d2) * g2;
            float sr = line_[1].read(d1) * g1 + line_[1].read(d2) * g2;
            L[i] += (sl - L[i]) * w;
            R[i] += (sr - R[i]) * w;
        }
    }

private:
    float sr_ = 48000, win_ = 2880, phase_ = 0;
    DelayLine line_[2];
    Smoother wet_, semis_;
};

// ------------------------------------------------------------- Filters ---
// 12 dB/oct HPF and LPF with smoothed cutoffs; bypassed at range ends.
class FilterPair {
public:
    void prepare(float sr) {
        sr_ = sr;
        hpF_.prepare(sr / kBlock, 20.f);
        lpF_.prepare(sr / kBlock, 20.f);
        hpF_.snap(std::log(20.f));
        lpF_.snap(std::log(20000.f));
        for (auto& f : hp_) f.reset();
        for (auto& f : lp_) f.reset();
    }
    void reset() { for (auto& f : hp_) f.reset(); for (auto& f : lp_) f.reset(); }
    void process(float* L, float* R, int n, float hpHz, float lpHz) {
        hpF_.setTarget(std::log(std::max(hpHz, 20.f)));
        lpF_.setTarget(std::log(std::min(lpHz, 20000.f)));
        for (int off = 0; off < n; off += kBlock) {
            const int m = std::min(kBlock, n - off);
            const float hf = std::exp(hpF_.next()), lf = std::exp(lpF_.next());
            const bool hpOn = hf > 21.f, lpOn = lf < 19900.f;
            if (hpOn) {
                for (auto& f : hp_) f.setCoefs(sr_, hf, 0.707f);
                for (int i = off; i < off + m; ++i) {
                    L[i] = hp_[0].process(L[i], Svf::HighPass);
                    R[i] = hp_[1].process(R[i], Svf::HighPass);
                }
            } else {
                hp_[0].reset(); hp_[1].reset();
            }
            if (lpOn) {
                for (auto& f : lp_) f.setCoefs(sr_, lf, 0.707f);
                for (int i = off; i < off + m; ++i) {
                    L[i] = lp_[0].process(L[i], Svf::LowPass);
                    R[i] = lp_[1].process(R[i], Svf::LowPass);
                }
            } else {
                lp_[0].reset(); lp_[1].reset();
            }
        }
    }

private:
    static constexpr int kBlock = 16;
    float sr_ = 48000;
    Smoother hpF_, lpF_;
    Svf hp_[2], lp_[2];
};

// ---------------------------------------------------------- 3-band EQ ---
class ThreeBandEq {
public:
    void prepare(float sr) { sr_ = sr; for (auto& b : bands_) for (auto& f : b) f.reset(); cur_[0] = cur_[1] = cur_[2] = 0; update(); }
    void reset() { for (auto& b : bands_) for (auto& f : b) f.reset(); }
    void process(float* L, float* R, int n, float lowDb, float midDb, float highDb) {
        float t[3] = {lowDb, midDb, highDb};
        bool changed = false, active = false;
        for (int b = 0; b < 3; ++b) {
            // Glide toward target at most 0.5 dB per block to avoid zipper noise.
            float d = std::clamp(t[b] - cur_[b], -0.5f, 0.5f);
            if (d != 0.f) { cur_[b] += d; changed = true; }
            if (std::fabs(cur_[b]) > 0.01f) active = true;
        }
        if (changed) update();
        if (!active) return;
        for (int b = 0; b < 3; ++b) {
            if (std::fabs(cur_[b]) <= 0.01f) continue;
            for (int i = 0; i < n; ++i) {
                L[i] = bands_[b][0].process(L[i]);
                R[i] = bands_[b][1].process(R[i]);
            }
        }
    }

private:
    void update() {
        for (int c = 0; c < 2; ++c) {
            bands_[0][c].setLowShelf(sr_, 140.f, cur_[0]);
            bands_[1][c].setPeaking(sr_, 1000.f, 0.8f, cur_[1]);
            bands_[2][c].setHighShelf(sr_, 7000.f, cur_[2]);
        }
    }
    float sr_ = 48000, cur_[3] = {0, 0, 0};
    Biquad bands_[3][2];
};

// ------------------------------------------------------------ Limiter ---
// Peak limiter for the master bus: instant attack, smooth release, followed
// by a soft clipper as a final safety net. Prevents summed stems clipping.
class Limiter {
public:
    void prepare(float sr) {
        rel_ = std::exp(-1.f / (0.12f * sr));
        env_ = 0;
    }
    void process(float* L, float* R, int n) {
        const float th = 0.97f;
        for (int i = 0; i < n; ++i) {
            float p = std::max(std::fabs(L[i]), std::fabs(R[i]));
            env_ = p > env_ ? p : env_ * rel_ + p * (1.f - rel_);
            float g = env_ > th ? th / env_ : 1.f;
            L[i] = softClip(L[i] * g);
            R[i] = softClip(R[i] * g);
        }
    }

private:
    static inline float softClip(float x) {
        if (x > 0.98f) return 0.98f + 0.02f * std::tanh((x - 0.98f) * 50.f);
        if (x < -0.98f) return -0.98f + 0.02f * std::tanh((x + 0.98f) * 50.f);
        return x;
    }
    float rel_ = 0.999f, env_ = 0;
};

}  // namespace phase::dsp
