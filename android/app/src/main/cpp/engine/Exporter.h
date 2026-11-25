// Offline mix export. Builds a private StemEngine with the live engine's
// exact parameter set and renders it faster than real time through the same
// DSP code path used for playback — what you hear is what you export.
#pragma once

#include <atomic>
#include <string>

#include "StemEngine.h"

namespace phase {

struct ExportOptions {
    std::string path;
    std::string format = "wav";  // "wav" | "mp3"
    int bitrateKbps = 256;
    double startSec = 0;         // range in track time
    double endSec = -1;          // <0 = to the end
    bool renderTail = true;      // let reverb/delay ring out
};

bool exportMix(const StemEngine& live, const ExportOptions& opt, std::atomic<float>& progress,
               const std::atomic<bool>& cancel, std::string* err);

}  // namespace phase
