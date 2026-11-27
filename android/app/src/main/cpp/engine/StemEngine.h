// StemEngine — the real-time heart of Phase.
//
// Synchronisation model: there is ONE timeline position (seconds, a double)
// advanced inside the audio callback. Every deck (song) reads all of its
// stems at   frame = (t - deckOffset) * deckRate * deckTempoRatio,   so the
// four stems of a song are sample-locked, and songs in a mashup stay locked
// to each other: nothing can drift, across seeks, loops, rolls, speed
// changes or reverse play. A normal track is simply a mashup of one deck.
//
// Thread model:
//   * UI/control threads write parameters (lock-free atomics) and post
//     transport requests (seek/play/brake) through atomics + sequence numbers.
//   * The audio thread (Oboe callback, or the offline exporter) calls
//     render(); it never allocates, locks or blocks.
//   * Track (re)loading uses a Dekker-style handshake so the audio thread
//     outputs silence for the few microseconds a swap takes.
#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "Params.h"
#include "PcmSource.h"
#include "Recorder.h"
#include "dsp/EdmEffects.h"
#include "dsp/Effects.h"
#include "dsp/SfxBank.h"

namespace phase {

struct TransportSnapshot {
    float speed = 1.f;
    bool keepPitch = false;
    bool reverse = false;
    bool loop = false;
    double loopStart = 0, loopEnd = 0;  // seconds
};

/** One song: 1 file (full mix) or 4 stems in bus order (vocals, drums, bass, other). */
struct DeckSpec {
    std::vector<std::string> paths;
    int sampleRate = 44100;
};

struct BeatContext {
    const float* beat = nullptr;  // per-sample beat phase 0..1, or null if tempo unknown
    int vocoderRoot = 48;
    bool vocoderMinor = true;
};

class BusProcessor {
public:
    void prepare(float sr);
    void reset();
    // Gain/mute/solo smoothing is per-sample; `audible` is the solo/mute gate.
    void process(float* L, float* R, int n, const BusParams& p, bool audible, float extraPitch, const BeatContext& bc);
    void snapGain(const BusParams& p, bool audible);
    float rms() const { return rms_; }
    float peak() const { return peak_; }

private:
    dsp::PitchShifter pitch_;
    dsp::FilterPair filters_;
    dsp::DjFilter djFilter_;
    dsp::ThreeBandEq eq_;
    dsp::Bitcrusher crush_;
    dsp::Distortion dist_;
    dsp::Vocoder vocoder_;
    dsp::Phaser phaser_;
    dsp::Flanger flanger_;
    dsp::Chorus chorus_;
    dsp::StereoDelay delay_;
    dsp::PingPongEcho echo_;
    dsp::Reverb reverb_;
    dsp::BeatDynamics beatDyn_;
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
    bool load(const std::vector<std::string>& paths, int sourceRate, std::string* err) {
        return loadDecks({DeckSpec{paths, sourceRate}}, err);
    }
    bool loadDecks(const std::vector<DeckSpec>& decks, std::string* err);
    void unload();
    bool hasTrack() const;
    int sourceCount() const;  // sources of deck 0
    int deckCount() const;
    std::vector<DeckSpec> loadedDecks() const;
    int sourceRate() const { return srcRate_; }

    // ---- transport (any thread) ----
    void setPlaying(bool play);
    bool isPlaying() const { return wantPlay_.load(); }
    void seek(double seconds);
    double position() const;  // seconds on the timeline
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

    // ---- decks & performance ----
    void setDeckParam(int deck, int id, float v);
    float deckParam(int deck, int id) const;
    /** Extra timeline delay a deck has gathered while paused (seconds). */
    double deckHold(int deck) const { return deck >= 0 && deck < kMaxDecks ? deckHoldPub_[deck].load() : 0.0; }
    void setGlobal(int id, float v) { if (id >= 0 && id < G_COUNT) globals_[id].store(v); }
    float global(int id) const { return globals_[id].load(); }
    void triggerSfx(int pad) { sfx_.trigger(pad); }
    void stopSfx() { sfx_.stopAll(); }
    /** DJ tape stop: playback brakes to a halt over ~0.8 s, then pauses. */
    void brake() { brakeSeq_.fetch_add(1); }
    void copyPerformanceFrom(const StemEngine& o);

    // out: kNumBuses * 2 floats (rms, peak) per bus.
    void meters(float* out) const;

    Recorder& recorder() { return recorder_; }

    // ---- audio thread ----
    void render(float* interleavedOut, int frames);

    // Offline helpers (exporter): skip ramps so the first sample is exact.
    void snapSmoothers();
    void setTransportImmediate(bool playing);

private:
    struct Deck {
        std::vector<std::unique_ptr<PcmSource>> sources;
        int64_t frames = 0;
        int rate = 44100;
        DeckSpec spec;
    };
    struct Track {
        std::vector<Deck> decks;
    };
    struct DeckDsp {
        dsp::Smoother gain[kNumStems];
        dsp::PitchShifter pitch[kNumStems];
        dsp::Smoother fade;      // deck play/pause de-click
        double hold = 0;         // seconds the deck has been held while paused
        bool on = true;          // audible & advancing
        bool pending = false;    // waiting for the next beat to start
    };
    void renderChunk(float* out, int n, Track* t);
    void prefetchLoop();
    void recomputeDuration();

    static constexpr int kChunk = 128;
    int outRate_ = 48000;
    int srcRate_ = 44100;

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
    std::atomic<uint32_t> seekSeq_{0}, brakeSeq_{0};
    std::atomic<double> publicPos_{0};
    std::atomic<double> duration_{0};

    std::atomic<float> deck_[kMaxDecks][D_COUNT];
    std::atomic<float> globals_[G_COUNT];

    // Audio-thread-only transport state (timeline seconds).
    double playhead_ = 0;
    double oldHead_ = 0;
    int xfRemain_ = 0, xfLen_ = 480;
    uint32_t lastSeekSeq_ = 0, lastBrakeSeq_ = 0;
    bool running_ = false;
    bool braking_ = false;
    float brakeMul_ = 1.f;
    bool rollActive_ = false;
    double rollStart_ = 0, rollLen_ = 0, slipHead_ = 0;
    dsp::Smoother tGain_;

    BusParams params_[kNumBuses];
    BusProcessor buses_[kNumBuses];
    DeckDsp deckDsp_[kMaxDecks];
    dsp::Limiter limiter_;
    dsp::SfxBank sfx_;
    std::mutex trackMutex_;  // control-side ownership of Track (load/unload/prefetch)
    std::atomic<float> meter_[kNumBuses * 2];

    // Scratch (allocated in prepare()).
    std::vector<double> posNew_, posOld_;
    std::vector<float> wNew_, wOld_, tg_, beat_;
    std::vector<double> step_;
    float lastBeat_ = 0.f;
    std::atomic<double> deckHoldPub_[kMaxDecks];
    std::vector<float> busL_[kNumStems], busR_[kNumStems];
    std::vector<float> srcL_, srcR_, mixL_, mixR_;

    Recorder recorder_;

    std::thread prefetch_;
    std::atomic<bool> prefetchRun_{false};
};

}  // namespace phase
