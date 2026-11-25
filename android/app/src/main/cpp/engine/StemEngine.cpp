#include "StemEngine.h"

#include <chrono>
#include <cmath>
#include <cstring>

namespace phase {

// ============================================================ BusProcessor ==

void BusProcessor::prepare(float sr) {
    sr_ = sr;
    pitch_.prepare(sr);
    filters_.prepare(sr);
    eq_.prepare(sr);
    dist_.prepare(sr);
    chorus_.prepare(sr);
    delay_.prepare(sr);
    echo_.prepare(sr);
    reverb_.prepare(sr);
    gain_.prepare(sr, 15.f);
    pan_.prepare(sr, 30.f);
    gain_.snap(1.f);
    pan_.snap(0.f);
    rmsCoef_ = 1.f - std::exp(-1.f / (0.06f * sr));
    peakRel_ = std::exp(-1.f / (0.3f * sr));
    rms2_ = rms_ = peak_ = 0;
}

void BusProcessor::reset() {
    pitch_.reset();
    filters_.reset();
    eq_.reset();
    dist_.reset();
    chorus_.reset();
    delay_.reset();
    echo_.reset();
    reverb_.reset();
    rms2_ = rms_ = peak_ = 0;
}

void BusProcessor::snapGain(const BusParams& p, bool audible) {
    gain_.snap(audible ? p.get(P_GAIN) : 0.f);
    pan_.snap(p.get(P_PAN));
}

void BusProcessor::process(float* L, float* R, int n, const BusParams& p, bool audible, float extraPitch) {
    pitch_.process(L, R, n, p.get(P_PITCH) + extraPitch);
    filters_.process(L, R, n, p.get(P_HPF_FREQ), p.get(P_LPF_FREQ));
    eq_.process(L, R, n, p.get(P_EQ_LOW), p.get(P_EQ_MID), p.get(P_EQ_HIGH));
    dist_.process(L, R, n, p.get(P_DIST_MIX), p.get(P_DRIVE));
    chorus_.process(L, R, n, p.get(P_CHORUS_MIX), p.get(P_CHORUS_RATE), p.get(P_CHORUS_DEPTH));
    delay_.process(L, R, n, p.get(P_DELAY_MIX), p.get(P_DELAY_TIME), p.get(P_DELAY_FEEDBACK));
    echo_.process(L, R, n, p.get(P_ECHO_MIX), p.get(P_ECHO_TIME), p.get(P_ECHO_FEEDBACK));
    reverb_.process(L, R, n, p.get(P_REVERB_MIX), p.get(P_REVERB_SIZE), p.get(P_REVERB_DAMP));

    gain_.setTarget(audible ? p.get(P_GAIN) : 0.f);
    pan_.setTarget(p.get(P_PAN));
    float pk = peak_;
    for (int i = 0; i < n; ++i) {
        const float g = gain_.next(), pn = pan_.next();
        const float gl = pn > 0.f ? 1.f - pn : 1.f;
        const float gr = pn < 0.f ? 1.f + pn : 1.f;
        L[i] *= g * gl;
        R[i] *= g * gr;
        const float e = 0.5f * (L[i] * L[i] + R[i] * R[i]);
        rms2_ += (e - rms2_) * rmsCoef_;
        const float a = std::max(std::fabs(L[i]), std::fabs(R[i]));
        pk = a > pk ? a : pk * peakRel_;
    }
    peak_ = pk;
    rms_ = std::sqrt(std::max(rms2_, 0.f));
}

// ============================================================== StemEngine ==

StemEngine::StemEngine() {
    for (auto& m : meter_) m.store(0.f);
    prepare(48000);
    prefetchRun_.store(true);
    prefetch_ = std::thread(&StemEngine::prefetchLoop, this);
}

StemEngine::~StemEngine() {
    prefetchRun_.store(false);
    if (prefetch_.joinable()) prefetch_.join();
    recorder_.stop();
    unload();
}

void StemEngine::prepare(int sr) {
    outRate_ = sr > 0 ? sr : 48000;
    xfLen_ = std::max(64, (int)(0.010 * outRate_));  // 10 ms equal-power crossfade
    posNew_.assign(kChunk, 0);
    posOld_.assign(kChunk, 0);
    wNew_.assign(kChunk, 0);
    wOld_.assign(kChunk, 0);
    tg_.assign(kChunk, 0);
    busL_.assign(kChunk, 0);
    busR_.assign(kChunk, 0);
    mixL_.assign(kChunk, 0);
    mixR_.assign(kChunk, 0);
    inter_.assign(kChunk * 2, 0);
    for (auto& b : buses_) b.prepare((float)outRate_);
    for (int i = 0; i < kNumBuses; ++i) buses_[i].snapGain(params_[i], true);
    limiter_.prepare((float)outRate_);
    tGain_.prepare((float)outRate_, 6.f);
    tGain_.snap(running_ ? 1.f : 0.f);
    xfRemain_ = 0;
}

bool StemEngine::load(const std::vector<std::string>& paths, int sourceRate, std::string* err) {
    if (paths.empty() || sourceRate <= 0) {
        if (err) *err = "no sources";
        return false;
    }
    auto nt = std::make_unique<Track>();
    for (const auto& p : paths) {
        auto src = std::make_unique<PcmSource>();
        if (!src->open(p, err)) return false;
        nt->frames = std::max(nt->frames, src->frames());
        nt->sources.push_back(std::move(src));
    }
    std::lock_guard<std::mutex> lk(trackMutex_);
    swapping_.store(true);
    while (inRender_.load()) std::this_thread::yield();  // at most one callback
    Track* old = track_.exchange(nt.release());
    // Audio thread is parked: safe to reset its private state.
    srcRate_ = sourceRate;
    playhead_ = oldHead_ = 0;
    xfRemain_ = 0;
    running_ = false;
    wantPlay_.store(false);
    ended_.store(false);
    tGain_.snap(0.f);
    lastSeekSeq_ = seekSeq_.load();
    publicPos_.store(0);
    duration_.store((double)track_.load()->frames / sourceRate);
    for (int i = 0; i < kNumBuses; ++i) {
        buses_[i].reset();
        buses_[i].snapGain(params_[i], true);
    }
    swapping_.store(false);
    paths_ = paths;
    delete old;
    return true;
}

void StemEngine::unload() {
    std::lock_guard<std::mutex> lk(trackMutex_);
    swapping_.store(true);
    while (inRender_.load()) std::this_thread::yield();
    Track* old = track_.exchange(nullptr);
    running_ = false;
    wantPlay_.store(false);
    tGain_.snap(0.f);
    duration_.store(0);
    publicPos_.store(0);
    swapping_.store(false);
    paths_.clear();
    delete old;
}

bool StemEngine::hasTrack() const { return track_.load() != nullptr; }

int StemEngine::sourceCount() const {
    Track* t = track_.load();
    return t ? (int)t->sources.size() : 0;
}

void StemEngine::setPlaying(bool play) {
    if (play) {
        const double pos = position(), dur = duration();
        if (!reverse_.load() && pos >= dur - 0.01) seek(0);
        else if (reverse_.load() && pos <= 0.01) seek(dur);
    }
    wantPlay_.store(play);
}

void StemEngine::seek(double seconds) {
    seconds = std::clamp(seconds, 0.0, std::max(0.0, duration()));
    seekTarget_.store(seconds);
    publicPos_.store(seconds);
    seekSeq_.fetch_add(1, std::memory_order_acq_rel);
}

double StemEngine::position() const { return publicPos_.load(); }
double StemEngine::duration() const { return duration_.load(); }

void StemEngine::setLoop(bool on, double a, double b) {
    if (b < a) std::swap(a, b);
    loopStart_.store(std::max(0.0, a));
    loopEnd_.store(std::max(0.0, b));
    loopOn_.store(on);
}

TransportSnapshot StemEngine::transport() const {
    TransportSnapshot t;
    t.speed = speed_.load();
    t.keepPitch = keepPitch_.load();
    t.reverse = reverse_.load();
    t.loop = loopOn_.load();
    t.loopStart = loopStart_.load();
    t.loopEnd = loopEnd_.load();
    return t;
}

void StemEngine::applyTransport(const TransportSnapshot& t) {
    setSpeed(t.speed);
    setKeepPitch(t.keepPitch);
    setReverse(t.reverse);
    setLoop(t.loop, t.loopStart, t.loopEnd);
}

void StemEngine::meters(float* out) const {
    for (int i = 0; i < kNumBuses * 2; ++i) out[i] = meter_[i].load(std::memory_order_relaxed);
}

void StemEngine::snapSmoothers() {
    const bool anySolo = [&] {
        for (int s = 0; s < kNumStems; ++s) if (params_[s].get(P_SOLO) > 0.5f) return true;
        return false;
    }();
    for (int s = 0; s < kNumStems; ++s) {
        const bool audible = params_[s].get(P_MUTE) < 0.5f && (!anySolo || params_[s].get(P_SOLO) > 0.5f);
        buses_[s].snapGain(params_[s], audible);
    }
    buses_[kMasterBus].snapGain(params_[kMasterBus], true);
}

void StemEngine::setTransportImmediate(bool playing) {
    // Only valid when no audio thread is rendering this instance (offline use).
    lastSeekSeq_ = seekSeq_.load();
    playhead_ = seekTarget_.load() * srcRate_;
    xfRemain_ = 0;
    running_ = playing;
    wantPlay_.store(playing);
    tGain_.snap(playing ? 1.f : 0.f);
}

void StemEngine::render(float* out, int frames) {
    inRender_.store(true);
    if (swapping_.load()) {
        std::memset(out, 0, sizeof(float) * frames * 2);
        inRender_.store(false);
        return;
    }
    Track* t = track_.load();
    for (int off = 0; off < frames; off += kChunk) {
        const int n = std::min(kChunk, frames - off);
        renderChunk(out + off * 2, n, t);
    }
    inRender_.store(false);
}

void StemEngine::renderChunk(float* out, int n, Track* t) {
    // ---- transport requests ------------------------------------------------
    const uint32_t seq = seekSeq_.load(std::memory_order_acquire);
    if (seq != lastSeekSeq_) {
        lastSeekSeq_ = seq;
        const double target = seekTarget_.load() * srcRate_;
        if (running_ && tGain_.current() > 1e-3f) {
            oldHead_ = playhead_;  // crossfade from where we are to the target
            xfRemain_ = xfLen_;
        }
        playhead_ = target;
    }
    const bool want = wantPlay_.load() && t != nullptr;
    if (want && !running_) running_ = true;
    tGain_.setTarget(want ? 1.f : 0.f);

    const float speed = speed_.load();
    const bool reverse = reverse_.load();
    const double step = (double)srcRate_ / outRate_ * speed * (reverse ? -1.0 : 1.0);
    const double total = t ? (double)t->frames : 0.0;
    double ls = loopStart_.load() * srcRate_, le = loopEnd_.load() * srcRate_;
    le = std::min(le, total);
    const bool loopActive = loopOn_.load() && (le - ls) > 0.05 * srcRate_;

    // ---- per-frame source positions & weights ------------------------------
    for (int i = 0; i < n; ++i) {
        if (!running_) {
            tg_[i] = 0.f;
            wNew_[i] = wOld_[i] = 0.f;
            continue;
        }
        tg_[i] = tGain_.next();
        posNew_[i] = playhead_;
        if (xfRemain_ > 0) {
            const float x = 1.f - (float)xfRemain_ / (float)xfLen_;
            wNew_[i] = std::sin(x * dsp::kPi * 0.5f);
            wOld_[i] = std::cos(x * dsp::kPi * 0.5f);
            posOld_[i] = oldHead_;
            oldHead_ += step;
            --xfRemain_;
        } else {
            wNew_[i] = 1.f;
            wOld_[i] = 0.f;
        }
        playhead_ += step;

        if (loopActive) {
            if (step > 0 && playhead_ >= le) {
                oldHead_ = playhead_;
                xfRemain_ = xfLen_;
                playhead_ = ls + std::fmod(playhead_ - le, le - ls);
            } else if (step < 0 && playhead_ <= ls) {
                oldHead_ = playhead_;
                xfRemain_ = xfLen_;
                playhead_ = le - std::fmod(ls - playhead_, le - ls);
            }
        } else if ((step > 0 && playhead_ >= total) || (step < 0 && playhead_ <= 0)) {
            playhead_ = step > 0 ? total : 0;
            running_ = false;
            wantPlay_.store(false);
            ended_.store(true);
            tGain_.snap(0.f);
            xfRemain_ = 0;
        }
        if (!want && tGain_.current() < 1e-4f) {  // pause ramp finished
            running_ = false;
            tGain_.snap(0.f);
            xfRemain_ = 0;
        }
    }

    // ---- sources -> stem buses -> master -----------------------------------
    std::fill(mixL_.begin(), mixL_.begin() + n, 0.f);
    std::fill(mixR_.begin(), mixR_.begin() + n, 0.f);
    const int ns = t ? (int)t->sources.size() : 0;
    const bool stemMode = ns == kNumStems;
    bool anySolo = false;
    if (stemMode)
        for (int s = 0; s < kNumStems; ++s) anySolo |= params_[s].get(P_SOLO) > 0.5f;

    for (int s = 0; s < ns; ++s) {
        const PcmSource& src = *t->sources[s];
        float* L = busL_.data();
        float* R = busR_.data();
        for (int i = 0; i < n; ++i) {
            if (wNew_[i] == 0.f && wOld_[i] == 0.f) { L[i] = R[i] = 0.f; continue; }
            float l, r;
            src.sample(posNew_[i], l, r);
            l *= wNew_[i];
            r *= wNew_[i];
            if (wOld_[i] > 0.f) {
                float ol, orr;
                src.sample(posOld_[i], ol, orr);
                l += ol * wOld_[i];
                r += orr * wOld_[i];
            }
            L[i] = l * tg_[i];
            R[i] = r * tg_[i];
        }
        if (stemMode) {
            const BusParams& p = params_[s];
            const bool audible = p.get(P_MUTE) < 0.5f && (!anySolo || p.get(P_SOLO) > 0.5f);
            buses_[s].process(L, R, n, p, audible, 0.f);
        }
        for (int i = 0; i < n; ++i) {
            mixL_[i] += L[i];
            mixR_[i] += R[i];
        }
    }

    // Master bus. "Keep pitch" counter-shifts the varispeed pitch change.
    float extraPitch = 0.f;
    if (keepPitch_.load() && std::fabs(speed - 1.f) > 1e-3f) extraPitch = -12.f * std::log2(speed);
    buses_[kMasterBus].process(mixL_.data(), mixR_.data(), n, params_[kMasterBus], true, extraPitch);
    limiter_.process(mixL_.data(), mixR_.data(), n);

    for (int i = 0; i < n; ++i) {
        out[2 * i] = mixL_[i];
        out[2 * i + 1] = mixR_[i];
    }
    recorder_.push(out, n);

    for (int b = 0; b < kNumBuses; ++b) {
        meter_[2 * b].store(buses_[b].rms(), std::memory_order_relaxed);
        meter_[2 * b + 1].store(buses_[b].peak(), std::memory_order_relaxed);
    }
    if (!stemMode) {
        // In full-mix mode bus 0..3 carry nothing; mirror master so the UI
        // activity animation still reflects the music.
        for (int b = 0; b < kNumStems; ++b) {
            meter_[2 * b].store(buses_[kMasterBus].rms(), std::memory_order_relaxed);
            meter_[2 * b + 1].store(buses_[kMasterBus].peak(), std::memory_order_relaxed);
        }
    }
    publicPos_.store(playhead_ / srcRate_, std::memory_order_relaxed);
}

void StemEngine::prefetchLoop() {
    using namespace std::chrono_literals;
    while (prefetchRun_.load()) {
        {
            std::lock_guard<std::mutex> lk(trackMutex_);
            Track* t = track_.load();
            if (t) {
                const int64_t pos = (int64_t)(publicPos_.load() * srcRate_);
                const bool rev = reverse_.load();
                const int64_t behind = (int64_t)srcRate_ * (rev ? 6 : 1);
                const int64_t ahead = (int64_t)srcRate_ * (rev ? 1 : 6);
                for (auto& s : t->sources) {
                    s->prefetch(pos - behind, behind + ahead);
                    if (loopOn_.load()) {
                        const int64_t ls = (int64_t)(loopStart_.load() * srcRate_);
                        s->prefetch(ls - srcRate_ / 2, (int64_t)srcRate_ * 2);
                    }
                }
            }
        }
        std::this_thread::sleep_for(60ms);
    }
}

}  // namespace phase
