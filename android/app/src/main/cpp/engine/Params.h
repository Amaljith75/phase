// Phase stem engine — parameter model shared by the live engine, the offline
// renderer and the JNI/Kotlin layer. Keep the numeric ids in sync with
// app/src/main/java/app/phase/stem/audio/Param.kt.
#pragma once

#include <atomic>
#include <cstdint>

namespace phase {

// Bus layout: four stems + master. In "mix only" mode (track not yet
// separated) a single source is routed to bus 0.
constexpr int kNumStems = 4;
constexpr int kMasterBus = 4;
constexpr int kNumBuses = 5;

enum ParamId : int {
    P_GAIN = 0,        // linear 0..1.5
    P_PAN,             // -1..1
    P_MUTE,            // 0/1
    P_SOLO,            // 0/1
    P_HPF_FREQ,        // Hz, <= 20 == bypass
    P_LPF_FREQ,        // Hz, >= 20000 == bypass
    P_DRIVE,           // 0..1
    P_DIST_MIX,        // 0..1
    P_CHORUS_MIX,      // 0..1
    P_CHORUS_RATE,     // Hz 0.05..5
    P_CHORUS_DEPTH,    // 0..1
    P_DELAY_MIX,       // 0..1
    P_DELAY_TIME,      // ms 20..2000
    P_DELAY_FEEDBACK,  // 0..0.95
    P_ECHO_MIX,        // 0..1
    P_ECHO_TIME,       // ms 50..2000
    P_ECHO_FEEDBACK,   // 0..0.95
    P_REVERB_MIX,      // 0..1
    P_REVERB_SIZE,     // 0..1
    P_REVERB_DAMP,     // 0..1
    P_PITCH,           // semitones -12..12
    P_EQ_LOW,          // dB -12..12
    P_EQ_MID,          // dB -12..12
    P_EQ_HIGH,         // dB -12..12
    P_CRUSH,           // 0..1 bitcrusher (bit depth + sample-rate reduction)
    P_PHASER,          // 0..1 mix
    P_FLANGER,         // 0..1 mix
    P_GATE,            // 0..1 depth, 1/16-note trance gate (beat synced)
    P_PUMP,            // 0..1 depth, sidechain-style pump on every beat
    P_VOCODER,         // 0..1 mix, robot voice (channel vocoder, synth carrier)
    P_DJ_FILTER,       // -1..1  (<0 low-pass sweep, >0 high-pass sweep)
    P_COUNT
};

// Mashup decks: up to four songs, each with four stems.
constexpr int kMaxDecks = 4;
enum DeckParam : int {
    D_GAIN_VOCALS = 0,  // per-stem level inside this deck (bus order)
    D_GAIN_DRUMS,
    D_GAIN_BASS,
    D_GAIN_OTHER,
    D_VOLUME,           // deck fader 0..1.5
    D_OFFSET,           // seconds: where this song starts on the shared timeline
    D_RATIO,            // tempo ratio (beat-sync): 0.5..2
    D_PITCH,            // semitones -12..12
    D_KEYLOCK,          // 1 = keep original key when the tempo ratio != 1
    D_TRIM_IN,          // seconds into the song where it starts playing
    D_TRIM_OUT,         // seconds into the song where it stops (<= 0: to the end)
    D_COUNT
};

// Engine-wide performance controls.
enum GlobalParam : int {
    G_BPM = 0,          // tempo of the timeline (beat effects, rolls); <=0 = unknown
    G_BEAT_OFFSET,      // seconds of the first downbeat
    G_VOCODER_ROOT,     // MIDI note of the vocoder carrier chord
    G_VOCODER_MINOR,    // 1 = minor chord
    G_ROLL,             // beat-repeat length in beats; 0 = off
    G_COUNT
};

struct ParamSpec {
    float min, max, def;
};

inline const ParamSpec& paramSpec(int id) {
    static const ParamSpec specs[P_COUNT] = {
        {0.f, 1.5f, 1.f},        // GAIN
        {-1.f, 1.f, 0.f},        // PAN
        {0.f, 1.f, 0.f},         // MUTE
        {0.f, 1.f, 0.f},         // SOLO
        {20.f, 20000.f, 20.f},   // HPF
        {20.f, 20000.f, 20000.f},// LPF
        {0.f, 1.f, 0.f},         // DRIVE
        {0.f, 1.f, 0.f},         // DIST_MIX
        {0.f, 1.f, 0.f},         // CHORUS_MIX
        {0.05f, 5.f, 0.8f},      // CHORUS_RATE
        {0.f, 1.f, 0.5f},        // CHORUS_DEPTH
        {0.f, 1.f, 0.f},         // DELAY_MIX
        {20.f, 2000.f, 375.f},   // DELAY_TIME
        {0.f, 0.95f, 0.35f},     // DELAY_FEEDBACK
        {0.f, 1.f, 0.f},         // ECHO_MIX
        {50.f, 2000.f, 500.f},   // ECHO_TIME
        {0.f, 0.95f, 0.5f},      // ECHO_FEEDBACK
        {0.f, 1.f, 0.f},         // REVERB_MIX
        {0.f, 1.f, 0.6f},        // REVERB_SIZE
        {0.f, 1.f, 0.4f},        // REVERB_DAMP
        {-12.f, 12.f, 0.f},      // PITCH
        {-12.f, 12.f, 0.f},      // EQ_LOW
        {-12.f, 12.f, 0.f},      // EQ_MID
        {-12.f, 12.f, 0.f},      // EQ_HIGH
        {0.f, 1.f, 0.f},         // CRUSH
        {0.f, 1.f, 0.f},         // PHASER
        {0.f, 1.f, 0.f},         // FLANGER
        {0.f, 1.f, 0.f},         // GATE
        {0.f, 1.f, 0.f},         // PUMP
        {0.f, 1.f, 0.f},         // VOCODER
        {-1.f, 1.f, 0.f},        // DJ_FILTER
    };
    return specs[(id < 0 || id >= P_COUNT) ? 0 : id];
}

inline float clampParam(int id, float v) {
    const ParamSpec& s = paramSpec(id);
    return v < s.min ? s.min : (v > s.max ? s.max : v);
}

// Lock-free parameter store. Written by the UI thread, read by the audio
// thread once per processing block. Plain relaxed atomics: every parameter
// is independent and smoothed on the audio side, so no ordering is needed.
struct BusParams {
    std::atomic<float> v[P_COUNT];
    BusParams() { reset(); }
    void reset() {
        for (int i = 0; i < P_COUNT; ++i) v[i].store(paramSpec(i).def, std::memory_order_relaxed);
    }
    float get(int id) const { return v[id].load(std::memory_order_relaxed); }
    void set(int id, float x) { v[id].store(clampParam(id, x), std::memory_order_relaxed); }
    void copyFrom(const BusParams& o) {
        for (int i = 0; i < P_COUNT; ++i) v[i].store(o.get(i), std::memory_order_relaxed);
    }
};

}  // namespace phase
