// StemEngine — the real-time heart of Phase.
//
// Synchronisation model: every stem is read from ONE shared playhead (a
// double, in source frames) inside the audio callback. Stems are never
// separate players, so they cannot drift: stem k at output frame n is always
// read at exactly the same source position as every other stem.
//
// Thread model:
//   * UI/control threads write parameters (lock-free atomics) and post
//     transport requests (seek/play) through atomics + sequence numbers.
//   * The audio thread (Oboe callback, or the offline exporter) calls
//     render(); it never allocates, locks or blocks.
//   * Track (re)loading uses a Dekker-style handshake so the audio thread
//     outputs silence for the few microseconds a swap takes, instead of
//     taking a lock.
#pragma once

#include <atomic>
#include <memory>
#include <string>
#include <mutex>
#include <thread>
#include <vector>

#include "Params.h"
#include "PcmSource.h"
#include "Recorder.h"
#include "dsp/Effects.h"

namespace phase {

struct TransportSnapshot {
    float speed = 1.f;
    bool keepPitch = false;
    bool reverse = false;
    bool loop = false;
    double loopStart = 0, loopEnd = 0;  // seconds
};

class BusProcessor {
public:
    void prepare(float sr);
    void reset();
    // Gain/mute/solo smoothing is per-sample; `audible` is the solo/mute gate.
    void process(float* L, float* R, int n, const BusParams& p, bool audible, float extraPitch);
    void snapGain(const BusParams& p, bool audible);
    float rms() const { return rms_; }
    float peak() const { return peak_; }

private:
    dsp::PitchShifter pitch_;
    dsp::FilterPair filters_;
    dsp::ThreeBandEq eq_;
    dsp::Distortion dist_;
    dsp::Chorus chorus_;
    dsp::StereoDelay delay_;
    dsp::PingPongEcho echo_;
    dsp::Reverb reverb_;
    dsp::Smoother gain_, pan_;
    float sr_ = 48000, rmsCoef_ = 0.01f, rms2_ = 0, rms_ = 0, peak_ = 0, peakRel_ = 0.999f;
};

class StemEngine {
public:
    StemEngine();
    ~StemEngine();

    // Allocates all DSP state for an output rate. Must not run concurrently
    // with render() (call before the stream starts or while it is stopped).
    void prepare(int outputSampleRate);
    int outputSampleRate() const { return outRate_; }

    // ---- track management (control thread) ----
    // paths: 1 file (full mix, before separation) or 4 stems in bus order
    // (vocals, drums, bass, other). All files: int16 stereo at sourceRate.
    bool load(const std::vector<std::string>& paths, int sourceRate, std::string* err);
    void unload();
    bool hasTrack() const;
    int sourceCount() const;
    const std::vector<std::string>& loadedPaths() const { return paths_; }
    int sourceRate() const { return srcRate_; }

    // ---- transport (any thread) ----
    void setPlaying(bool play);
    bool isPlaying() const { return wantPlay_.load(); }
    void seek(double seconds);
    double position() const;  // seconds
    double duration() const;  // seconds
    bool consumeEnded() { return ended_.exchange(false); }

    void setSpeed(float s) { speed_.store(std::clamp(s, 0.25f, 3.f)); }
    void setKeepPitch(bool k) { keepPitch_.store(k); }
    void setReverse(bool r) { reverse_.store(r); }
    void setLoop(bool on, double startSec, double endSec);
    TransportSnapshot transport() const;
    void applyTransport(const TransportSnapshot& t);

    BusParams& bus(int i) { return params_[i]; }
    const BusParams& bus(int i) const { return params_[i]; }

    // out: kNumBuses * 2 floats (rms, peak) per bus.
    void meters(float* out) const;

    Recorder& recorder() { return recorder_; }

    // ---- audio thread ----
    void render(float* interleavedOut, int frames);

    // Offline helpers (exporter): skip ramps so the first sample is exact.
    void snapSmoothers();
    void setTransportImmediate(bool playing);

private:
    struct Track {
        std::vector<std::unique_ptr<PcmSource>> sources;
        int64_t frames = 0;
    };
    void renderChunk(float* out, int n, Track* t);
    void prefetchLoop();

    static constexpr int kChunk = 128;
    int outRate_ = 48000;
    int srcRate_ = 44100;
    std::vector<std::string> paths_;

    // Track swap handshake.
    std::atomic<Track*> track_{nullptr};
    std::atomic<bool> swapping_{false}, inRender_{false};

    // Transport state shared with control threads.
    std::atomic<bool> wantPlay_{false}, ended_{false};
    std::atomic<float> speed_{1.f};
    std::atomic<bool> keepPitch_{false}, reverse_{false};
    std::atomic<bool> loopOn_{false};
    std::atomic<double> loopStart_{0}, loopEnd_{0};
    std::atomic<double> seekTarget_{0};
    std::atomic<uint32_t> seekSeq_{0};
    std::atomic<double> publicPos_{0};

    // Audio-thread-only transport state.
    double playhead_ = 0;     // source frames
    double oldHead_ = 0;      // crossfade source position
    int xfRemain_ = 0, xfLen_ = 480;
    uint32_t lastSeekSeq_ = 0;
    bool running_ = false;
    dsp::Smoother tGain_;

    BusParams params_[kNumBuses];
    BusProcessor buses_[kNumBuses];
    dsp::Limiter limiter_;
    std::atomic<double> duration_{0};
    std::mutex trackMutex_;  // control-side ownership of Track (load/unload/prefetch)
    std::atomic<float> meter_[kNumBuses * 2];

    // Scratch (allocated in prepare()).
    std::vector<double> posNew_, posOld_;
    std::vector<float> wNew_, wOld_, tg_;
    std::vector<float> busL_, busR_, mixL_, mixR_, inter_;

    Recorder recorder_;

    std::thread prefetch_;
    std::atomic<bool> prefetchRun_{false};
};

}  // namespace phase
