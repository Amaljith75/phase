// Live-mix recorder. The audio thread pushes the exact master output into a
// single-producer/single-consumer ring buffer (wait-free, no allocation);
// a writer thread drains it to disk.
#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "AudioWriters.h"

namespace phase {

class Recorder {
public:
    ~Recorder() { stop(); }
    bool start(const std::string& path, const std::string& format, int bitrateKbps, int sampleRate, std::string* err);
    // Returns recorded duration in seconds (or -1 if not recording).
    double stop();
    bool isRecording() const { return active_.load(std::memory_order_acquire); }
    double seconds() const { return sampleRate_ > 0 ? (double)pushed_.load() / sampleRate_ : 0.0; }
    int64_t droppedFrames() const { return dropped_.load(); }

    // Real-time safe. Interleaved stereo.
    void push(const float* interleaved, int frames);

private:
    void writerLoop();
    std::vector<float> ring_;
    size_t capacityFrames_ = 0;
    std::atomic<size_t> writeIdx_{0}, readIdx_{0};
    std::atomic<bool> active_{false}, stopReq_{false};
    std::atomic<int64_t> pushed_{0}, dropped_{0};
    std::atomic<int> inPush_{0};
    std::unique_ptr<AudioFileWriter> writer_;
    std::thread thread_;
    int sampleRate_ = 0;
};

}  // namespace phase
