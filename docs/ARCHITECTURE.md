# Phase — technical architecture

## 1. Stack decision

The brief preferred React Native, but asked for the best architecture to be
evaluated first. Phase is almost entirely a **real-time audio instrument**:
four synchronised streams, per-stem effects, gesture → gain at display rate,
and meters animating at 60–120 fps. I chose:

| Layer | Choice | Why |
|---|---|---|
| Audio engine | **C++17**, portable core (`android/app/src/main/cpp/engine`) | One sample clock for all stems; lock-free; no GC; reusable on iOS |
| Android audio I/O | **Oboe** (AAudio on 8.1+, OpenSL ES fallback) | Google's low-latency path; exclusive mode, burst-sized buffers, auto-reopen on route change |
| UI | **Kotlin + Jetpack Compose** | Gestures and Canvas drawing run in-process with the engine through JNI — no JS bridge between a swipe and the gain change |
| Persistence | **Room (SQLite)** + app-private files | Tracks, projects, presets, exports |
| Background work | **WorkManager** foreground worker | Separation survives backgrounding, shows progress, retries |
| AI separation | **Python FastAPI + Demucs** (`backend/`) | Real source separation (Hybrid Transformer Demucs) behind a pluggable interface |

**Why not React Native here:** RN would still need the same native C++ engine
and a native module; it would add a bridge hop for every meter frame and
gesture, a JS runtime, and a second build system, while buying only UI
portability. The parts that must be shared with iOS — the DSP engine and the
server — are already platform-neutral. On iOS the plan is a thin
CoreAudio/AVAudioEngine render-callback host around the same `StemEngine`
(mirroring `OboeHost.cpp`) and a SwiftUI (or Compose Multiplatform) UI.

## 2. Audio engine & stem synchronisation

```
 UI thread (Compose)          Audio thread (Oboe callback)              Worker threads
 ───────────────────          ──────────────────────────────           ──────────────
 gesture → setParam ──atomic──▶ BusParams[5]                            prefetch: madvise/touch
 play/seek ──atomic+seq──────▶ transport ──▶ ONE playhead (double)        pages around playhead
                                    │
                    ┌───────────────┼───────────────┬───────────────┐
                 PcmSource(vox)  PcmSource(drm)  PcmSource(bas)  PcmSource(oth)   (mmap int16)
                    │ cubic interp at the same fractional position │
                 BusProcessor×4: pitch → HPF/LPF → EQ → dist → chorus → delay → echo → reverb → gain/pan
                    └───────────────┴───────┬───────┴───────────────┘
                                   master BusProcessor (+keep-pitch shift) → limiter → out
                                            └──▶ Recorder ring buffer ──▶ writer thread (WAV/MP3)
```

* **No drift by construction.** Stems are not separate players. The callback
  advances a single `double` playhead and reads every stem at that exact
  position, so stems are sample-locked forever, across seeks, loops, speed
  changes and reverse play.
* **Varispeed + resampling in one step.** Step = `srcRate / deviceRate ×
  speed × direction`, read with Catmull-Rom interpolation. "Keep pitch"
  counter-shifts the master bus with the granular pitch shifter.
* **Click-free transport.** Seeks and loop wraps use a 10 ms equal-power
  crossfade between the old and new positions; play/pause ramps 6 ms;
  mute/solo/volume are smoothed per sample (15 ms) so solo/mute never gap.
* **Real-time safety.** The callback never allocates, locks or does I/O.
  Parameters are relaxed atomics. Track swaps use a Dekker handshake
  (`swapping_`/`inRender_`), so the audio thread outputs silence for one
  callback instead of blocking. Denormals are flushed to zero.
* **Large files.** Decoded stems are memory-mapped int16 PCM; a prefetch
  thread keeps ~7 s around the playhead resident. A 10-minute song never
  needs to be in RAM.
* **Export == playback.** `exportMix` builds a private `StemEngine`, copies
  every parameter, and renders through the same code faster than real time.
  The host test suite asserts offline output equals live output.

Verified by `android/engine-tests` (runs on the host and in CI): stem sum
error 6e-5, click-free seek/loop/solo, exact speed/reverse, worst-case FX
stability, export==live, exact recorder length, ≥4× real-time worst case.

## 3. Stem separation pipeline

```
SAF picker → copy to files/tracks/<id>/source.ext → MediaCodec decode → mix.pcm (playable now)
                                   │
                   WorkManager SeparationWorker (foreground, progress notification)
                                   │  SeparationProvider (interface)
                                   ▼
            RemoteSeparationProvider ── HTTP ──▶ Phase server: POST /v1/jobs
                                                  Job queue → Separator registry → Demucs
                                                  cache by (model, sha256)
            ◀── GET /v1/jobs/{id} progress ──────
            ◀── GET /v1/jobs/{id}/stems/{vocals|drums|bass|other} (FLAC)
                                   │
              decode each FLAC → stems/<stem>.pcm (+ peaks) → status SEPARATED
                                   │
          PlayerController hot-swaps mix → 4 stems at the same position, no stop
```

* Separation is **real** (Demucs `htdemucs`, `htdemucs_ft`, `hdemucs_mmi`,
  `mdx_extra`), never EQ tricks. Progress is measured from Demucs's own chunk
  loop.
* Results are cached on the phone (stems are never re-separated unless the
  user asks) and on the server (same file + model returns instantly).
* New engines plug in at two points: `SeparationProvider` (app side, e.g. an
  on-device ONNX Demucs) and `Separator` (server side, e.g. BS-RoFormer via
  `audio-separator`).

**Why not on-device by default:** HT-Demucs is ~80 MB of weights and needs
several GFLOPs per second of audio; on current phones that means minutes per
song with heavy heat/battery cost, and its STFT/iSTFT layers do not export
cleanly to ONNX/NNAPI. The interface is ready for it when that changes.

## 4. Platform limitations

**Android**
* Output latency depends on the device: AAudio exclusive MMAP gives ~10–20 ms
  on Pixels; many devices fall back to shared mode (~20–40 ms). Bluetooth adds
  100–250 ms that no app can remove. Settings shows the live stream info.
* Background playback requires a foreground service (implemented); Android
  12+ can refuse to re-promote a service from the background — playback
  continues, only the notification may lag.
* No storage permission is needed: imports use the Storage Access Framework,
  exports go through MediaStore (Android 10+) or the share sheet.
* Codec support comes from the device's MediaCodec: MP3, AAC/M4A, FLAC, WAV,
  OGG Vorbis/Opus are guaranteed on API 26+; ALAC/WMA are device-dependent.

**iOS (architecture kept compatible)**
* The C++ engine core has no Android dependencies; an iOS host needs a
  CoreAudio render callback and `AVAudioSession` (`.playback`).
* iOS has no system file picker restrictions issue (UIDocumentPicker), but
  background audio requires the `audio` background mode.
* MP3 export uses the bundled shine encoder on both platforms.

## 5. What is native vs. Kotlin

| Native C++ | Kotlin |
|---|---|
| Mixing, sync, resampling, all effects, limiter, recorder, offline export, MP3/WAV writing, Oboe stream | Decoding (MediaCodec), library/DB, import, separation client & worker, lyrics (LRC + LRCLIB), BPM detection, UI/gestures/haptics, MediaSession |

## 6. Roadmap

Done in 0.1: engine + sync, import/library, real separation pipeline,
gestures, per-stem & master FX, lyrics (sync/edit/import/online), projects,
presets, live recording, WAV/MP3 export & share, reverse, BPM detection,
tap tempo, A/B loop, waveform, stem colours, master EQ, one-tap modes.

Next:
1. On-device separation provider (ONNX Runtime, quantised HT-Demucs) for
   offline use on flagship phones.
2. Beat-grid snapping for A/B loops (BPM + downbeat phase), quantised loop
   lengths, metronome.
3. Per-stem waveforms in the player; spectral activity meters.
4. iOS host (CoreAudio) + SwiftUI shell over the shared engine.
5. BS-RoFormer server plugin for higher-quality vocals.
6. Time-stretch upgrade (phase vocoder) for higher-quality keep-pitch.
