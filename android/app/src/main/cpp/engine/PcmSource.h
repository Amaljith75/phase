// Memory-mapped raw PCM source: interleaved int16 stereo (the format the
// Kotlin decoder writes). Memory mapping lets four stems of a long track be
// played without holding them in RAM; a prefetch thread keeps the pages
// around the playhead resident so the audio thread rarely faults.
#pragma once

#include <cstdint>
#include <string>

namespace phase {

class PcmSource {
public:
    PcmSource() = default;
    ~PcmSource() { close(); }
    PcmSource(const PcmSource&) = delete;
    PcmSource& operator=(const PcmSource&) = delete;

    bool open(const std::string& path, std::string* err);
    void close();
    bool isOpen() const { return data_ != nullptr; }
    int64_t frames() const { return frames_; }

    // Returns the sample pair at an integer frame; silence outside the file.
    inline void frame(int64_t i, float& l, float& r) const {
        if (i < 0 || i >= frames_) { l = r = 0.f; return; }
        constexpr float k = 1.f / 32768.f;
        l = data_[2 * i] * k;
        r = data_[2 * i + 1] * k;
    }

    // Catmull-Rom cubic interpolation at a fractional frame position.
    inline void sample(double pos, float& l, float& r) const {
        const int64_t i = (int64_t)(pos >= 0 ? pos : pos - 1.0);
        const float t = (float)(pos - (double)i);
        float l0, r0, l1, r1, l2, r2, l3, r3;
        frame(i - 1, l0, r0);
        frame(i, l1, r1);
        frame(i + 1, l2, r2);
        frame(i + 2, l3, r3);
        l = hermite(l0, l1, l2, l3, t);
        r = hermite(r0, r1, r2, r3, t);
    }

    // Hint the kernel to page in [startFrame, startFrame + count).
    void prefetch(int64_t startFrame, int64_t count) const;

private:
    static inline float hermite(float xm1, float x0, float x1, float x2, float t) {
        const float c = (x1 - xm1) * 0.5f;
        const float v = x0 - x1;
        const float w = c + v;
        const float a = w + v + (x2 - x0) * 0.5f;
        const float b = w + a;
        return (((a * t) - b) * t + c) * t + x0;
    }

    int fd_ = -1;
    void* map_ = nullptr;
    size_t mapSize_ = 0;
    const int16_t* data_ = nullptr;
    int64_t frames_ = 0;
};

}  // namespace phase
