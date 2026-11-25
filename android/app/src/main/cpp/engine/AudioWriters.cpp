#include "AudioWriters.h"

#include <cstring>

#include "layer3.h"

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

bool Mp3Writer::supportsSampleRate(int sr) { return shine_find_samplerate_index(sr) >= 0; }

bool Mp3Writer::open(const std::string& path, int sampleRate, std::string* err) {
    close();
    if (shine_check_config(sampleRate, bitrate_) < 0) {
        if (err) *err = "MP3: unsupported sample rate/bitrate " + std::to_string(sampleRate) + "/" + std::to_string(bitrate_);
        return false;
    }
    shine_config_t cfg;
    shine_set_config_mpeg_defaults(&cfg.mpeg);
    cfg.wave.channels = 2;
    cfg.wave.samplerate = sampleRate;
    cfg.mpeg.mode = SHINE_JOINT_STEREO;
    cfg.mpeg.bitrate = bitrate_;
    shine_t s = shine_initialise(&cfg);
    if (!s) {
        if (err) *err = "MP3 encoder init failed";
        return false;
    }
    f_ = fopen(path.c_str(), "wb");
    if (!f_) {
        shine_close(s);
        if (err) *err = "cannot create " + path;
        return false;
    }
    shine_ = s;
    samplesPerPass_ = shine_samples_per_pass(s);
    pending_.clear();
    pending_.reserve((size_t)samplesPerPass_ * 4);
    frames_ = 0;
    return true;
}

bool Mp3Writer::encodePending(bool flushPartial) {
    auto s = static_cast<shine_t>(shine_);
    const size_t passLen = (size_t)samplesPerPass_ * 2;
    size_t off = 0;
    while (pending_.size() - off >= passLen || (flushPartial && pending_.size() > off)) {
        if (pending_.size() - off < passLen) pending_.resize(off + passLen, 0);  // zero-pad last pass
        int written = 0;
        unsigned char* data = shine_encode_buffer_interleaved(s, pending_.data() + off, &written);
        if (written > 0 && fwrite(data, 1, (size_t)written, f_) != (size_t)written) return false;
        off += passLen;
    }
    pending_.erase(pending_.begin(), pending_.begin() + (long)off);
    return true;
}

bool Mp3Writer::write(const float* in, int frames) {
    if (!f_) return false;
    for (int i = 0; i < frames * 2; ++i) pending_.push_back(toInt16(in[i]));
    frames_ += frames;
    return encodePending(false);
}

bool Mp3Writer::close() {
    if (!f_) return true;
    bool ok = encodePending(true);
    int written = 0;
    unsigned char* data = shine_flush(static_cast<shine_t>(shine_), &written);
    if (written > 0) ok = ok && fwrite(data, 1, (size_t)written, f_) == (size_t)written;
    shine_close(static_cast<shine_t>(shine_));
    shine_ = nullptr;
    ok = (fclose(f_) == 0) && ok;
    f_ = nullptr;
    return ok;
}

std::unique_ptr<AudioFileWriter> makeWriter(const std::string& format, int bitrateKbps) {
    if (format == "mp3") return std::make_unique<Mp3Writer>(bitrateKbps);
    return std::make_unique<WavWriter>();
}

}  // namespace phase
