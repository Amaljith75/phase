#include "AudioWriters.h"

#include <cstring>


namespace phase {

namespace {
void put32(uint8_t* p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
void put16(uint8_t* p, uint16_t v) { p[0] = v; p[1] = v >> 8; }

void writeWavHeader(FILE* f, int sampleRate, uint32_t dataBytes) {
    uint8_t h[44];
    std::memcpy(h, "RIFF", 4);
    put32(h + 4, 36 + dataBytes);
    std::memcpy(h + 8, "WAVEfmt ", 8);
    put32(h + 16, 16);
    put16(h + 20, 1);            // PCM
    put16(h + 22, 2);            // stereo
    put32(h + 24, sampleRate);
    put32(h + 28, sampleRate * 4);
    put16(h + 32, 4);
    put16(h + 34, 16);
    std::memcpy(h + 36, "data", 4);
    put32(h + 40, dataBytes);
    fwrite(h, 1, 44, f);
}
}  // namespace

bool WavWriter::open(const std::string& path, int sampleRate, std::string* err) {
    close();
    f_ = fopen(path.c_str(), "wb");
    if (!f_) {
        if (err) *err = "cannot create " + path;
        return false;
    }
    sampleRate_ = sampleRate;
    frames_ = 0;
    writeWavHeader(f_, sampleRate_, 0);
    return true;
}

bool WavWriter::write(const float* in, int frames) {
    if (!f_) return false;
    tmp_.resize((size_t)frames * 2);
    for (int i = 0; i < frames * 2; ++i) tmp_[i] = toInt16(in[i]);
    size_t w = fwrite(tmp_.data(), sizeof(int16_t), tmp_.size(), f_);
    frames_ += frames;
    return w == tmp_.size();
}

bool WavWriter::close() {
    if (!f_) return true;
    uint64_t bytes = (uint64_t)frames_ * 4;
    if (bytes > 0xFFFFFFF0ull) bytes = 0xFFFFFFF0ull;  // RIFF 4 GB limit
    fseek(f_, 0, SEEK_SET);
    writeWavHeader(f_, sampleRate_, (uint32_t)bytes);
    bool ok = fclose(f_) == 0;
    f_ = nullptr;
    return ok;
}

std::unique_ptr<AudioFileWriter> makeWriter(const std::string& format, int bitrateKbps) {
    (void)format;  // WAV only; compressed formats are encoded from WAV by the platform
    (void)bitrateKbps;
    return std::make_unique<WavWriter>();
}

}  // namespace phase
