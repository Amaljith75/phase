#include "Recorder.h"

#include <chrono>

namespace phase {

bool Recorder::start(const std::string& path, const std::string& format, int bitrateKbps, int sampleRate,
                     std::string* err) {
    stop();
    auto w = makeWriter(format, bitrateKbps);
    if (!w->open(path, sampleRate, err)) return false;
    writer_ = std::move(w);
    sampleRate_ = sampleRate;
    capacityFrames_ = (size_t)sampleRate * 8;  // 8 s of headroom for slow storage
    ring_.assign(capacityFrames_ * 2, 0.f);
    writeIdx_.store(0);
    readIdx_.store(0);
    pushed_.store(0);
    dropped_.store(0);
    stopReq_.store(false);
    active_.store(true, std::memory_order_release);
    thread_ = std::thread(&Recorder::writerLoop, this);
    return true;
}

void Recorder::push(const float* in, int frames) {
    // inPush_/active_ form a Dekker pair (seq_cst) so stop() never frees the
    // ring while the audio thread is inside push().
    struct Guard { std::atomic<int>& c; ~Guard() { c.fetch_sub(1); } };
    inPush_.fetch_add(1);
    Guard g{inPush_};
    if (!active_.load()) return;
    const size_t w = writeIdx_.load(std::memory_order_relaxed);
    const size_t r = readIdx_.load(std::memory_order_acquire);
    const size_t free = capacityFrames_ - (w - r);
    if ((size_t)frames > free) {
        dropped_.fetch_add(frames, std::memory_order_relaxed);
        return;
    }
    for (int i = 0; i < frames; ++i) {
        size_t idx = ((w + i) % capacityFrames_) * 2;
        ring_[idx] = in[2 * i];
        ring_[idx + 1] = in[2 * i + 1];
    }
    writeIdx_.store(w + frames, std::memory_order_release);
    pushed_.fetch_add(frames, std::memory_order_relaxed);
}

void Recorder::writerLoop() {
    std::vector<float> chunk;
    for (;;) {
        const bool stopping = stopReq_.load(std::memory_order_acquire);
        const size_t r = readIdx_.load(std::memory_order_relaxed);
        const size_t w = writeIdx_.load(std::memory_order_acquire);
        size_t avail = w - r;
        if (avail > 0) {
            chunk.resize(avail * 2);
            for (size_t i = 0; i < avail; ++i) {
                size_t idx = ((r + i) % capacityFrames_) * 2;
                chunk[2 * i] = ring_[idx];
                chunk[2 * i + 1] = ring_[idx + 1];
            }
            readIdx_.store(r + avail, std::memory_order_release);
            writer_->write(chunk.data(), (int)avail);
        } else if (stopping) {
            break;
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(40));
        }
    }
}

double Recorder::stop() {
    if (!thread_.joinable()) return -1;
    active_.store(false);  // audio thread stops pushing
    while (inPush_.load() > 0) std::this_thread::yield();
    stopReq_.store(true, std::memory_order_release);
    thread_.join();
    double secs = writer_ ? (double)writer_->framesWritten() / sampleRate_ : 0;
    if (writer_) writer_->close();
    writer_.reset();
    ring_.clear();
    ring_.shrink_to_fit();
    return secs;
}

}  // namespace phase
