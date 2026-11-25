// Small DSP building blocks. All classes are allocation-free after prepare()
// so they are safe to use on the real-time audio thread.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace phase::dsp {

constexpr float kPi = 3.14159265358979323846f;

inline float dbToGain(float db) { return std::pow(10.f, db / 20.f); }

// One-pole parameter smoother (exponential approach to target).
class Smoother {
public:
    void prepare(float sampleRate, float timeMs) {
        coef_ = 1.f - std::exp(-1.f / (std::max(timeMs, 0.01f) * 0.001f * sampleRate));
    }
    void snap(float v) { y_ = target_ = v; }
    void setTarget(float t) { target_ = t; }
    float target() const { return target_; }
    float current() const { return y_; }
    float next() {
        y_ += (target_ - y_) * coef_;
        return y_;
    }
    // Advance n samples at once (used where per-sample precision is not needed).
    float advance(int n) {
        float k = 1.f - std::pow(1.f - coef_, (float)n);
        y_ += (target_ - y_) * k;
        return y_;
    }
    bool settled(float eps = 1e-5f) const { return std::fabs(target_ - y_) < eps; }

private:
    float coef_ = 1.f;
    float y_ = 0.f, target_ = 0.f;
};

// Topology-preserving-transform state variable filter (Cytomic / Zavalishin).
// Stable under fast cutoff modulation, which matters for swept filters.
class Svf {
public:
    enum Mode { LowPass, HighPass };
    void reset() { ic1_ = ic2_ = 0.f; }
    void setCoefs(float sampleRate, float freq, float q) {
        freq = std::clamp(freq, 10.f, sampleRate * 0.49f);
        g_ = std::tan(kPi * freq / sampleRate);
        k_ = 1.f / q;
        a1_ = 1.f / (1.f + g_ * (g_ + k_));
        a2_ = g_ * a1_;
        a3_ = g_ * a2_;
    }
    inline float process(float v0, Mode m) {
        float v3 = v0 - ic2_;
        float v1 = a1_ * ic1_ + a2_ * v3;
        float v2 = ic2_ + a2_ * ic1_ + a3_ * v3;
        ic1_ = 2.f * v1 - ic1_;
        ic2_ = 2.f * v2 - ic2_;
        if (m == LowPass) return v2;
        return v0 - k_ * v1 - v2;
    }

private:
    float g_ = 0, k_ = 1.4142f, a1_ = 0, a2_ = 0, a3_ = 0;
    float ic1_ = 0, ic2_ = 0;
};

// RBJ biquad (Direct Form II transposed), used by the 3-band EQ.
class Biquad {
public:
    void reset() { z1_ = z2_ = 0.f; }
    void setPeaking(float sr, float f, float q, float db) {
        float A = std::pow(10.f, db / 40.f), w = 2.f * kPi * f / sr;
        float alpha = std::sin(w) / (2.f * q), c = std::cos(w);
        set(1 + alpha * A, -2 * c, 1 - alpha * A, 1 + alpha / A, -2 * c, 1 - alpha / A);
    }
    void setLowShelf(float sr, float f, float db) {
        float A = std::pow(10.f, db / 40.f), w = 2.f * kPi * f / sr;
        float c = std::cos(w), alpha = std::sin(w) / 2.f * std::sqrt(2.f), sA = 2.f * std::sqrt(A) * alpha;
        set(A * ((A + 1) - (A - 1) * c + sA), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sA),
            (A + 1) + (A - 1) * c + sA, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sA);
    }
    void setHighShelf(float sr, float f, float db) {
        float A = std::pow(10.f, db / 40.f), w = 2.f * kPi * f / sr;
        float c = std::cos(w), alpha = std::sin(w) / 2.f * std::sqrt(2.f), sA = 2.f * std::sqrt(A) * alpha;
        set(A * ((A + 1) + (A - 1) * c + sA), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sA),
            (A + 1) - (A - 1) * c + sA, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sA);
    }
    inline float process(float x) {
        float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }

private:
    void set(float b0, float b1, float b2, float a0, float a1, float a2) {
        b0_ = b0 / a0; b1_ = b1 / a0; b2_ = b2 / a0; a1_ = a1 / a0; a2_ = a2 / a0;
    }
    float b0_ = 1, b1_ = 0, b2_ = 0, a1_ = 0, a2_ = 0, z1_ = 0, z2_ = 0;
};

// Circular delay line with fractional (linear-interpolated) reads.
class DelayLine {
public:
    void prepare(int maxSamples) {
        int n = 1;
        while (n < maxSamples + 4) n <<= 1;
        buf_.assign(n, 0.f);
        mask_ = n - 1;
        w_ = 0;
    }
    void reset() { std::fill(buf_.begin(), buf_.end(), 0.f); }
    inline void push(float x) {
        buf_[w_] = x;
        w_ = (w_ + 1) & mask_;
    }
    // delay in samples (>= 1), relative to the most recently pushed sample.
    inline float read(float delay) const {
        float rp = (float)w_ - delay - 1.f;
        while (rp < 0) rp += (float)(mask_ + 1);
        int i0 = (int)rp;
        float frac = rp - (float)i0;
        float a = buf_[i0 & mask_], b = buf_[(i0 + 1) & mask_];
        return a + (b - a) * frac;
    }
    inline float readInt(int delay) const { return buf_[(w_ - delay - 1) & mask_]; }
    int capacity() const { return mask_ + 1; }

private:
    std::vector<float> buf_;
    int mask_ = 0, w_ = 0;
};

}  // namespace phase::dsp
