// File writers for recorded/exported audio: 16-bit PCM WAV and MP3 (via the
// bundled shine encoder). Not real-time safe — used from worker threads.
#pragma once

#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <vector>

namespace phase {

class AudioFileWriter {
public:
    virtual ~AudioFileWriter() = default;
    virtual bool open(const std::string& path, int sampleRate, std::string* err) = 0;
    // Interleaved stereo float frames.
    virtual bool write(const float* interleaved, int frames) = 0;
    virtual bool close() = 0;
    int64_t framesWritten() const { return frames_; }

protected:
    int64_t frames_ = 0;
    uint32_t dither_ = 22222;
    // TPDF dither to 16 bit.
    inline int16_t toInt16(float x) {
        dither_ = dither_ * 1664525u + 1013904223u;
        float r1 = (float)(dither_ >> 8) / 16777216.f;
        dither_ = dither_ * 1664525u + 1013904223u;
        float r2 = (float)(dither_ >> 8) / 16777216.f;
        float v = x * 32767.f + (r1 - r2);
        if (v > 32767.f) v = 32767.f;
        if (v < -32768.f) v = -32768.f;
        return (int16_t)(v < 0 ? v - 0.5f : v + 0.5f);
    }
};

class WavWriter : public AudioFileWriter {
public:
    ~WavWriter() override { close(); }
    bool open(const std::string& path, int sampleRate, std::string* err) override;
    bool write(const float* interleaved, int frames) override;
    bool close() override;

private:
    FILE* f_ = nullptr;
    int sampleRate_ = 44100;
    std::vector<int16_t> tmp_;
};

class Mp3Writer : public AudioFileWriter {
public:
    explicit Mp3Writer(int bitrateKbps) : bitrate_(bitrateKbps) {}
    ~Mp3Writer() override { close(); }
    bool open(const std::string& path, int sampleRate, std::string* err) override;
    bool write(const float* interleaved, int frames) override;
    bool close() override;
    static bool supportsSampleRate(int sampleRate);

private:
    bool encodePending(bool flushPartial);
    FILE* f_ = nullptr;
    void* shine_ = nullptr;
    int bitrate_ = 256;
    int samplesPerPass_ = 1152;
    std::vector<int16_t> pending_;  // interleaved
};

std::unique_ptr<AudioFileWriter> makeWriter(const std::string& format, int bitrateKbps);

}  // namespace phase
