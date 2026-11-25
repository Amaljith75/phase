#include "OboeHost.h"

#include <android/log.h>

#include "Denormals.h"

#define LOG_TAG "PhaseAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace phase {

bool OboeHost::start(bool lowLatency) {
    std::lock_guard<std::mutex> lk(mutex_);
    if (stream_ && lowLatency == lowLatency_) return true;
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    lowLatency_ = lowLatency;
    wanted_ = true;
    return openLocked();
}

bool OboeHost::openLocked() {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(lowLatency_ ? oboe::PerformanceMode::LowLatency : oboe::PerformanceMode::PowerSaving)
        ->setSharingMode(lowLatency_ ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setFormatConversionAllowed(true)
        ->setChannelConversionAllowed(true)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(r));
        stream_.reset();
        return false;
    }
    const int sr = stream_->getSampleRate();
    if (sr != engine_.outputSampleRate()) engine_.prepare(sr);  // stream not started yet: safe
    if (lowLatency_) {
        stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
        tuner_ = std::make_unique<oboe::LatencyTuner>(*stream_);
    } else {
        tuner_.reset();
    }
    r = stream_->requestStart();
    if (r != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(r));
        stream_->close();
        stream_.reset();
        return false;
    }
    LOGI("stream open: %d Hz, burst %d, buffer %d, api %s, sharing %s", sr, stream_->getFramesPerBurst(),
         stream_->getBufferSizeInFrames(), stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL",
         stream_->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared");
    return true;
}

void OboeHost::stop() {
    std::lock_guard<std::mutex> lk(mutex_);
    wanted_ = false;
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    tuner_.reset();
}

bool OboeHost::isRunning() const { return stream_ != nullptr; }

StreamInfo OboeHost::info() {
    std::lock_guard<std::mutex> lk(mutex_);
    StreamInfo i;
    if (!stream_) return i;
    i.sampleRate = stream_->getSampleRate();
    i.framesPerBurst = stream_->getFramesPerBurst();
    i.bufferSize = stream_->getBufferSizeInFrames();
    i.bufferCapacity = stream_->getBufferCapacityInFrames();
    i.aaudio = stream_->getAudioApi() == oboe::AudioApi::AAudio;
    i.exclusive = stream_->getSharingMode() == oboe::SharingMode::Exclusive;
    i.lowLatency = stream_->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
    auto lat = stream_->calculateLatencyMillis();
    if (lat) i.latencyMs = lat.value();
    auto x = stream_->getXRunCount();
    if (x) i.xruns = x.value();
    return i;
}

oboe::DataCallbackResult OboeHost::onAudioReady(oboe::AudioStream*, void* data, int32_t frames) {
    enableFlushToZero();
    if (tuner_) tuner_->tune();  // grows the buffer only if underruns happen
    engine_.render(static_cast<float*>(data), frames);
    return oboe::DataCallbackResult::Continue;
}

void OboeHost::onErrorAfterClose(oboe::AudioStream*, oboe::Result err) {
    // Typically ErrorDisconnected: headphones unplugged / BT route change.
    // Oboe invokes this on its own thread, where reopening is permitted.
    LOGI("stream closed (%s), reopening", oboe::convertToText(err));
    std::lock_guard<std::mutex> lk(mutex_);
    stream_.reset();
    tuner_.reset();
    if (wanted_) openLocked();
}

}  // namespace phase
