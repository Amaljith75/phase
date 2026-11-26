#include "Exporter.h"

#include "Denormals.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace phase {

bool exportMix(const StemEngine& live, const ExportOptions& opt, std::atomic<float>& progress,
               const std::atomic<bool>& cancel, std::string* err) {
    enableFlushToZero();  // runs on a worker thread
    progress.store(0.f);
    const auto decks = live.loadedDecks();
    const int rate = live.sourceRate();
    if (decks.empty()) {
        if (err) *err = "no track loaded";
        return false;
    }
    if (opt.format == "mp3" && !Mp3Writer::supportsSampleRate(rate)) {
        if (err) *err = "MP3 export does not support " + std::to_string(rate) + " Hz";
        return false;
    }

    auto engine = std::make_unique<StemEngine>();
    engine->prepare(rate);  // render at the source rate: no resampling at speed 1
    if (!engine->loadDecks(decks, err)) return false;
    engine->copyPerformanceFrom(live);  // stems, decks, FX, beat settings

    TransportSnapshot t = live.transport();
    t.loop = false;
    engine->applyTransport(t);

    const double dur = engine->duration();
    double a = std::clamp(opt.startSec, 0.0, dur);
    double b = opt.endSec < 0 ? dur : std::clamp(opt.endSec, 0.0, dur);
    if (b - a < 0.05) { a = 0; b = dur; }

    engine->seek(t.reverse ? b : a);
    engine->snapSmoothers();
    engine->setTransportImmediate(true);

    auto writer = makeWriter(opt.format, opt.bitrateKbps);
    if (!writer->open(opt.path, rate, err)) return false;

    const int64_t mainFrames = (int64_t)std::llround((b - a) * rate / std::max(0.25f, t.speed));
    bool tails = false;
    if (opt.renderTail) {
        for (int i = 0; i < kNumBuses; ++i) {
            const auto& p = live.bus(i);
            tails |= p.get(P_REVERB_MIX) > 0 || p.get(P_DELAY_MIX) > 0 || p.get(P_ECHO_MIX) > 0;
        }
    }
    // Always render a short tail so the transport's 6 ms fade-out completes.
    const int64_t tailFrames = (int64_t)(rate * (tails ? 3.0 : 0.02));
    const int64_t totalFrames = mainFrames + tailFrames;

    constexpr int kBlock = 4096;
    std::vector<float> buf(kBlock * 2);
    int64_t done = 0;
    bool ok = true;
    while (done < totalFrames) {
        if (cancel.load()) {
            if (err) *err = "cancelled";
            ok = false;
            break;
        }
        if (done < mainFrames && done + kBlock >= mainFrames) {
            // Render exactly up to the range end, then let the transport stop.
            const int n = (int)(mainFrames - done);
            if (n > 0) {
                engine->render(buf.data(), n);
                if (!writer->write(buf.data(), n)) { ok = false; break; }
                done += n;
            }
            engine->setPlaying(false);  // short de-click ramp, effect tails keep ringing
            continue;
        }
        const int n = (int)std::min<int64_t>(kBlock, totalFrames - done);
        engine->render(buf.data(), n);
        if (!writer->write(buf.data(), n)) {
            if (err) *err = "write failed (disk full?)";
            ok = false;
            break;
        }
        done += n;
        progress.store((float)done / (float)totalFrames);
    }
    if (!writer->close()) {
        if (err && ok) *err = "failed to finalise file";
        ok = false;
    }
    if (ok) progress.store(1.f);
    else std::remove(opt.path.c_str());
    return ok;
}

}  // namespace phase
