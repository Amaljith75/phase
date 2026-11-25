// Android audio output host: owns the Oboe stream (AAudio on 8.1+, OpenSL ES
// below) and drives StemEngine::render() from the real-time callback.
#pragma once

#include <oboe/Oboe.h>

#include <memory>
#include <mutex>

#include "StemEngine.h"

namespace phase {

struct StreamInfo {
    int sampleRate = 0;
    int framesPerBurst = 0;
    int bufferSize = 0;
    int bufferCapacity = 0;
    bool aaudio = false;
    bool exclusive = false;
    bool lowLatency = false;
    double latencyMs = -1;
    int xruns = 0;
};

class OboeHost : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit OboeHost(StemEngine& engine) : engine_(engine) {}
    ~OboeHost() override { stop(); }

    bool start(bool lowLatency);
    void stop();
    bool isRunning() const;
    StreamInfo info();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* s, void* data, int32_t frames) override;
    void onErrorAfterClose(oboe::AudioStream* s, oboe::Result err) override;

private:
    bool openLocked();
    StemEngine& engine_;
    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<oboe::LatencyTuner> tuner_;
    bool lowLatency_ = true;
    bool wanted_ = false;
};

}  // namespace phase
