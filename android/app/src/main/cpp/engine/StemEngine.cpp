#include "StemEngine.h"

#include <chrono>
#include <cmath>
#include <cstring>

namespace phase {

namespace {
float deckDefault(int id) {
    switch (id) {
        case D_OFFSET: case D_PITCH: case D_TRIM_IN: case D_TRIM_OUT: return 0.f;
        default: return 1.f;  // gains, volume, ratio, keylock
    }
}
float clampDeck(int id, float v) {
    switch (id) {
        case D_GAIN_VOCALS: case D_GAIN_DRUMS: case D_GAIN_BASS: case D_GAIN_OTHER: case D_VOLUME:
            return std::clamp(v, 0.f, 1.5f);
        case D_OFFSET: return std::clamp(v, -3600.f, 3600.f);
        case D_RATIO: return std::clamp(v, 0.5f, 2.f);
        case D_PITCH: return std::clamp(v, -12.f, 12.f);
        case D_TRIM_IN: case D_TRIM_OUT: return std::clamp(v, 0.f, 36000.f);
        default: return v > 0.5f ? 1.f : 0.f;
    }
}
}  // namespace

// ============================================================ BusProcessor ==

void BusProcessor::prepare(float sr) {
    sr_ = sr;
    pitch_.prepare(sr);
    filters_.prepare(sr);
    djFilter_.prepare(sr);
    eq_.prepare(sr);
    crush_.prepare(sr);
    dist_.prepare(sr);
    vocoder_.prepare(sr);
    phaser_.prepare(sr);
    flanger_.prepare(sr);
    chorus_.prepare(sr);
    delay_.prepare(sr);
    echo_.prepare(sr);
    reverb_.prepare(sr);
    beatDyn_.prepare(sr);
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
    phaser_.reset();
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

void BusProcessor::process(float* L, float* R, int n, const BusParams& p, bool audible, float extraPitch,
                           const BeatContext& bc) {
    pitch_.process(L, R, n, p.get(P_PITCH) + extraPitch);
    filters_.process(L, R, n, p.get(P_HPF_FREQ), p.get(P_LPF_FREQ));
    djFilter_.process(L, R, n, p.get(P_DJ_FILTER));
    eq_.process(L, R, n, p.get(P_EQ_LOW), p.get(P_EQ_MID), p.get(P_EQ_HIGH));
    crush_.process(L, R, n, p.get(P_CRUSH));
    dist_.process(L, R, n, p.get(P_DIST_MIX), p.get(P_DRIVE));
    vocoder_.process(L, R, n, p.get(P_VOCODER), bc.vocoderRoot, bc.vocoderMinor);
    phaser_.process(L, R, n, p.get(P_PHASER));
    flanger_.process(L, R, n, p.get(P_FLANGER));
    chorus_.process(L, R, n, p.get(P_CHORUS_MIX), p.get(P_CHORUS_RATE), p.get(P_CHORUS_DEPTH));
    delay_.process(L, R, n, p.get(P_DELAY_MIX), p.get(P_DELAY_TIME), p.get(P_DELAY_FEEDBACK));
    echo_.process(L, R, n, p.get(P_ECHO_MIX), p.get(P_ECHO_TIME), p.get(P_ECHO_FEEDBACK));
    reverb_.process(L, R, n, p.get(P_REVERB_MIX), p.get(P_REVERB_SIZE), p.get(P_REVERB_DAMP));
    beatDyn_.process(L, R, n, bc.beat, p.get(P_GATE), p.get(P_PUMP));

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
    for (auto& d : deck_) for (int i = 0; i < D_COUNT; ++i) d[i].store(deckDefault(i));
    for (auto& g : globals_) g.store(0.f);
    globals_[G_VOCODER_ROOT].store(48.f);
    globals_[G_VOCODER_MINOR].store(1.f);
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
    beat_.assign(kChunk, 0);
    for (int s = 0; s < kNumStems; ++s) {
        busL_[s].assign(kChunk, 0);
        busR_[s].assign(kChunk, 0);
    }
    srcL_.assign(kChunk, 0);
    srcR_.assign(kChunk, 0);
    mixL_.assign(kChunk, 0);
    mixR_.assign(kChunk, 0);
    for (auto& b : buses_) b.prepare((float)outRate_);
    for (int i = 0; i < kNumBuses; ++i) buses_[i].snapGain(params_[i], true);
    for (auto& d : deckDsp_) {
        for (int s = 0; s < kNumStems; ++s) {
            d.gain[s].prepare((float)outRate_, 15.f);
            d.gain[s].snap(1.f);
            d.pitch[s].prepare((float)outRate_);
        }
    }
    limiter_.prepare((float)outRate_);
    sfx_.prepare((float)outRate_);
    tGain_.prepare((float)outRate_, 6.f);
    tGain_.snap(running_ ? 1.f : 0.f);
    xfRemain_ = 0;
}

bool StemEngine::loadDecks(const std::vector<DeckSpec>& specs, std::string* err) {
    if (specs.empty() || (int)specs.size() > kMaxDecks) {
        if (err) *err = "need 1.." + std::to_string(kMaxDecks) + " decks";
        return false;
    }
    auto nt = std::make_unique<Track>();
    for (const auto& spec : specs) {
        if (spec.paths.empty() || spec.sampleRate <= 0) {
            if (err) *err = "deck has no sources";
            return false;
        }
        Deck d;
        d.rate = spec.sampleRate;
        d.spec = spec;
        for (const auto& p : spec.paths) {
            auto src = std::make_unique<PcmSource>();
            if (!src->open(p, err)) return false;
            d.frames = std::max(d.frames, src->frames());
            d.sources.push_back(std::move(src));
        }
        nt->decks.push_back(std::move(d));
    }
    std::lock_guard<std::mutex> lk(trackMutex_);
    swapping_.store(true);
    while (inRender_.load()) std::this_thread::yield();  // at most one callback
    Track* old = track_.exchange(nt.release());
    // Audio thread is parked: safe to reset its private state.
    srcRate_ = specs[0].sampleRate;
    for (auto& d : deck_) for (int i = 0; i < D_COUNT; ++i) d[i].store(deckDefault(i));
    playhead_ = oldHead_ = slipHead_ = 0;
    xfRemain_ = 0;
    running_ = false;
    braking_ = false;
    brakeMul_ = 1.f;
    rollActive_ = false;
    wantPlay_.store(false);
    ended_.store(false);
    tGain_.snap(0.f);
    lastSeekSeq_ = seekSeq_.load();
    lastBrakeSeq_ = brakeSeq_.load();
    publicPos_.store(0);
    for (int i = 0; i < kNumBuses; ++i) {
        buses_[i].reset();
        buses_[i].snapGain(params_[i], true);
    }
    for (auto& d : deckDsp_) for (int s = 0; s < kNumStems; ++s) { d.gain[s].snap(1.f); d.pitch[s].reset(); }
    swapping_.store(false);
    recomputeDuration();
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
    delete old;
}

bool StemEngine::hasTrack() const { return track_.load() != nullptr; }

int StemEngine::sourceCount() const {
    Track* t = track_.load();
    return t && !t->decks.empty() ? (int)t->decks[0].sources.size() : 0;
}

int StemEngine::deckCount() const {
    Track* t = track_.load();
    return t ? (int)t->decks.size() : 0;
}

std::vector<DeckSpec> StemEngine::loadedDecks() const {
    std::vector<DeckSpec> out;
    Track* t = track_.load();
    if (t) for (const auto& d : t->decks) out.push_back(d.spec);
    return out;
}

void StemEngine::recomputeDuration() {
    Track* t = track_.load();
    double dur = 0;
    if (t) {
        for (size_t i = 0; i < t->decks.size(); ++i) {
            const auto& d = t->decks[i];
            const double ratio = deck_[i][D_RATIO].load();
            const double len = (double)d.frames / d.rate;
            const double in = std::min<double>(deck_[i][D_TRIM_IN].load(), len);
            const double outRaw = deck_[i][D_TRIM_OUT].load();
            const double out = outRaw > in ? std::min(outRaw, len) : len;
            dur = std::max(dur, deck_[i][D_OFFSET].load() + (out - in) / ratio);
        }
    }
    duration_.store(std::max(0.0, dur));
}

void StemEngine::setDeckParam(int deck, int id, float v) {
    if (deck < 0 || deck >= kMaxDecks || id < 0 || id >= D_COUNT) return;
    deck_[deck][id].store(clampDeck(id, v));
    if (id == D_OFFSET || id == D_RATIO || id == D_TRIM_IN || id == D_TRIM_OUT) recomputeDuration();
}

float StemEngine::deckParam(int deck, int id) const {
    if (deck < 0 || deck >= kMaxDecks || id < 0 || id >= D_COUNT) return 0;
    return deck_[deck][id].load();
}

void StemEngine::copyPerformanceFrom(const StemEngine& o) {
    for (int b = 0; b < kNumBuses; ++b) params_[b].copyFrom(o.params_[b]);
    for (int d = 0; d < kMaxDecks; ++d) for (int i = 0; i < D_COUNT; ++i) deck_[d][i].store(o.deck_[d][i].load());
    for (int g = 0; g < G_COUNT; ++g) globals_[g].store(o.globals_[g].load());
    globals_[G_ROLL].store(0.f);  // momentary
    recomputeDuration();
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
    for (int d = 0; d < kMaxDecks; ++d)
        for (int s = 0; s < kNumStems; ++s)
            deckDsp_[d].gain[s].snap(deck_[d][D_GAIN_VOCALS + s].load() * deck_[d][D_VOLUME].load());
}

void StemEngine::setTransportImmediate(bool playing) {
    // Only valid when no audio thread is rendering this instance (offline use).
    lastSeekSeq_ = seekSeq_.load();
    playhead_ = slipHead_ = seekTarget_.load();
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
        const double target = seekTarget_.load();
        if (running_ && tGain_.current() > 1e-3f) {
            oldHead_ = playhead_;  // crossfade from where we are to the target
            xfRemain_ = xfLen_;
        }
        playhead_ = target;
        slipHead_ = target;
        if (rollActive_) rollStart_ = target;
    }
    const bool want = wantPlay_.load() && t != nullptr;
    if (want && !running_) running_ = true;
    tGain_.setTarget(want ? 1.f : 0.f);

    const uint32_t bseq = brakeSeq_.load();
    if (bseq != lastBrakeSeq_) {
        lastBrakeSeq_ = bseq;
        if (running_) { braking_ = true; brakeMul_ = 1.f; }
    }

    const float speed = speed_.load();
    const bool reverse = reverse_.load();
    const double baseStep = speed * (reverse ? -1.0 : 1.0) / outRate_;
    const double total = duration_.load();
    double ls = loopStart_.load(), le = std::min(loopEnd_.load(), total);
    const bool loopActive = loopOn_.load() && (le - ls) > 0.05;
    const float bpmRaw = globals_[G_BPM].load();
    const bool beatKnown = bpmRaw > 1.f;
    const double bps = (beatKnown ? bpmRaw : 120.f) / 60.0;
    const double beatOff = globals_[G_BEAT_OFFSET].load();

    // Beat repeat (roll) with slip: the loop plays while the timeline keeps
    // moving underneath; releasing jumps (crossfaded) to where we'd be.
    const float rollBeats = globals_[G_ROLL].load();
    if (rollBeats > 0.f && running_) {
        const double len = rollBeats / bps;
        if (!rollActive_) {
            rollActive_ = true;
            rollStart_ = playhead_;
            slipHead_ = playhead_;
        }
        rollLen_ = len;
    } else if (rollActive_) {
        rollActive_ = false;
        oldHead_ = playhead_;
        xfRemain_ = xfLen_;
        playhead_ = slipHead_;
    }

    // ---- per-frame timeline positions & weights ----------------------------
    for (int i = 0; i < n; ++i) {
        if (!running_) {
            tg_[i] = 0.f;
            wNew_[i] = wOld_[i] = 0.f;
            beat_[i] = 0.f;
            continue;
        }
        tg_[i] = tGain_.next();
        double stepMul = 1.0;
        if (braking_) {
            brakeMul_ -= 1.f / (0.8f * outRate_);
            if (brakeMul_ <= 0.f) {
                braking_ = false;
                brakeMul_ = 1.f;
                running_ = false;
                wantPlay_.store(false);
                tGain_.snap(0.f);
                xfRemain_ = 0;
                tg_[i] = 0.f;
                wNew_[i] = wOld_[i] = 0.f;
                continue;
            }
            stepMul = brakeMul_ * brakeMul_;
        }
        const double step = baseStep * stepMul;
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
        double b = (playhead_ - beatOff) * bps;
        beat_[i] = (float)(b - std::floor(b));
        playhead_ += step;
        slipHead_ += step;

        double rs = ls, re = le;
        bool region = loopActive;
        if (rollActive_) { rs = rollStart_; re = rollStart_ + rollLen_; region = true; }
        if (region) {
            if (step > 0 && playhead_ >= re) {
                oldHead_ = playhead_;
                xfRemain_ = xfLen_;
                playhead_ = rs + std::fmod(playhead_ - re, re - rs);
            } else if (step < 0 && playhead_ <= rs) {
                oldHead_ = playhead_;
                xfRemain_ = xfLen_;
                playhead_ = re - std::fmod(rs - playhead_, re - rs);
            }
            if (!rollActive_) slipHead_ = playhead_;
        } else if ((step > 0 && playhead_ >= total) || (step < 0 && playhead_ <= 0)) {
            playhead_ = slipHead_ = step > 0 ? total : 0;
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

    // ---- decks -> stem buses -> master -------------------------------------
    std::fill(mixL_.begin(), mixL_.begin() + n, 0.f);
    std::fill(mixR_.begin(), mixR_.begin() + n, 0.f);
    for (int s = 0; s < kNumStems; ++s) {
        std::fill(busL_[s].begin(), busL_[s].begin() + n, 0.f);
        std::fill(busR_[s].begin(), busR_[s].begin() + n, 0.f);
    }
    bool stemMode = false;
    const int nd = t ? (int)t->decks.size() : 0;
    for (int d = 0; d < nd; ++d) {
        const Deck& deck = t->decks[d];
        const double off = deck_[d][D_OFFSET].load();
        const double ratio = deck_[d][D_RATIO].load();
        const double fps = deck.rate * ratio;  // deck frames per timeline second
        // Trim: the song plays its [in, out) region, starting at `off` on the timeline.
        const double inF = (double)deck_[d][D_TRIM_IN].load() * deck.rate;
        const double outRaw = (double)deck_[d][D_TRIM_OUT].load() * deck.rate;
        const double outF = outRaw > inF ? std::min<double>(outRaw, (double)deck.frames) : (double)deck.frames;
        const float vol = deck_[d][D_VOLUME].load();
        const float keyComp = deck_[d][D_KEYLOCK].load() > 0.5f ? (float)(-12.0 * std::log2(ratio)) : 0.f;
        const float semis = deck_[d][D_PITCH].load() + keyComp;
        const int ns = (int)deck.sources.size();
        const bool stems = ns == kNumStems;
        stemMode |= stems;
        for (int s = 0; s < ns; ++s) {
            const PcmSource& src = *deck.sources[s];
            float* L = srcL_.data();
            float* R = srcR_.data();
            for (int i = 0; i < n; ++i) {
                if (wNew_[i] == 0.f && wOld_[i] == 0.f) { L[i] = R[i] = 0.f; continue; }
                float l = 0, r = 0;
                const double pn = (posNew_[i] - off) * fps + inF;
                if (pn >= inF && pn < outF) {
                    src.sample(pn, l, r);
                    l *= wNew_[i];
                    r *= wNew_[i];
                }
                if (wOld_[i] > 0.f) {
                    const double po = (posOld_[i] - off) * fps + inF;
                    if (po >= inF && po < outF) {
                        float ol, orr;
                        src.sample(po, ol, orr);
                        l += ol * wOld_[i];
                        r += orr * wOld_[i];
                    }
                }
                L[i] = l * tg_[i];
                R[i] = r * tg_[i];
            }
            // Per-deck stem level and key (only meaningful for stem decks).
            const int slot = stems ? s : kNumStems - 1;
            auto& g = deckDsp_[d].gain[slot];
            g.setTarget((stems ? deck_[d][D_GAIN_VOCALS + s].load() : 1.f) * vol);
            for (int i = 0; i < n; ++i) {
                const float k = g.next();
                L[i] *= k;
                R[i] *= k;
            }
            deckDsp_[d].pitch[slot].process(L, R, n, semis);
            float* dl = stems ? busL_[s].data() : mixL_.data();
            float* dr = stems ? busR_[s].data() : mixR_.data();
            for (int i = 0; i < n; ++i) {
                dl[i] += L[i];
                dr[i] += R[i];
            }
        }
    }

    BeatContext bc;
    bc.beat = (running_ || tGain_.current() > 0.f) && beatKnown ? beat_.data() : nullptr;
    bc.vocoderRoot = (int)globals_[G_VOCODER_ROOT].load();
    bc.vocoderMinor = globals_[G_VOCODER_MINOR].load() > 0.5f;

    if (stemMode) {
        bool anySolo = false;
        for (int s = 0; s < kNumStems; ++s) anySolo |= params_[s].get(P_SOLO) > 0.5f;
        for (int s = 0; s < kNumStems; ++s) {
            const BusParams& p = params_[s];
            const bool audible = p.get(P_MUTE) < 0.5f && (!anySolo || p.get(P_SOLO) > 0.5f);
            buses_[s].process(busL_[s].data(), busR_[s].data(), n, p, audible, 0.f, bc);
            for (int i = 0; i < n; ++i) {
                mixL_[i] += busL_[s][i];
                mixR_[i] += busR_[s][i];
            }
        }
    }

    // Master bus. "Keep pitch" counter-shifts the varispeed pitch change.
    float extraPitch = 0.f;
    if (keepPitch_.load() && std::fabs(speed - 1.f) > 1e-3f) extraPitch = -12.f * std::log2(speed);
    buses_[kMasterBus].process(mixL_.data(), mixR_.data(), n, params_[kMasterBus], true, extraPitch, bc);
    sfx_.mix(mixL_.data(), mixR_.data(), n);
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
    publicPos_.store(rollActive_ ? slipHead_ : playhead_, std::memory_order_relaxed);
}

void StemEngine::prefetchLoop() {
    using namespace std::chrono_literals;
    while (prefetchRun_.load()) {
        {
            std::lock_guard<std::mutex> lk(trackMutex_);
            Track* t = track_.load();
            if (t) {
                const double pos = publicPos_.load();
                const bool rev = reverse_.load();
                for (size_t d = 0; d < t->decks.size(); ++d) {
                    const auto& deck = t->decks[d];
                    const double fps = deck.rate * deck_[d][D_RATIO].load();
                    const int64_t p = (int64_t)((pos - deck_[d][D_OFFSET].load()) * fps + deck_[d][D_TRIM_IN].load() * deck.rate);
                    const int64_t sec = (int64_t)fps;
                    const int64_t behind = sec * (rev ? 6 : 1);
                    const int64_t ahead = sec * (rev ? 1 : 6);
                    for (auto& s : deck.sources) {
                        s->prefetch(p - behind, behind + ahead);
                        if (loopOn_.load()) {
                            const int64_t ls = (int64_t)((loopStart_.load() - deck_[d][D_OFFSET].load()) * fps);
                            s->prefetch(ls - sec / 2, sec * 2);
                        }
                    }
                }
            }
        }
        std::this_thread::sleep_for(60ms);
    }
}

}  // namespace phase
