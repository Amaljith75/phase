# Phase — a tactile stem instrument for Android

Import any song on your phone, let **on-device AI** split it into **vocals · drums · bass ·
other** (offline, nothing uploaded), then remix it live with swipes: isolate the vocal, drop the drums,
push the bass, throw reverb on the voice, follow synced lyrics and record
the take.

```
 ┌──────────┬──────────┐
 │  VOCALS  │  DRUMS   │   swipe ↑↓  volume (haptic detent per LED)
 │  ▮▮▮▮▮▯  │  ▮▮▮▯▯▯  │   swipe →   solo        swipe ←  mute
 ├────── ( ▶ ) ────────┤   double-tap mute       long-press  effects
 │  BASS    │  OTHER   │   all gestures remappable in Settings
 │  ▮▮▮▮▮▮  │  ▮▮▮▮▯▯  │
 └──────────┴──────────┘
```

## What's in the box

| Path | What |
|---|---|
| `android/` | The app: Kotlin + Jetpack Compose UI, native C++ audio engine on Oboe/AAudio |
| `android/app/src/main/cpp/engine/` | Portable real-time engine: sample-locked stems, effects, recorder, offline exporter |
| `android/engine-tests/` | Host-side engine test suite (sync, clicks, FX stability, export == playback) |
| `android/separation/` | On-device AI pipeline (STFT, chunking, ONNX Runtime), JVM-tested against PyTorch |
| `tools/model/` | Builds/verifies the bundled HT-Demucs ONNX model |
| `backend/` | Optional self-hosted separation server (FastAPI + Demucs) |
| `docs/PLAY_STORE.md` | Publishing checklist; `PRIVACY.md` privacy policy |
| `docs/ARCHITECTURE.md` | Architecture, sync model, platform limits, roadmap |

## Features

* **Import** MP3 · WAV · FLAC · M4A · AAC · OGG via the system picker (multi-select); title/artist/artwork from tags. Files are copied into app-private storage.
* **On-device AI stem separation** with Hybrid Transformer Demucs on ONNX Runtime — no server, no account, works offline; progress notification, survives backgrounding, cached forever. (Optional: offload to your own server.)
* **Native engine**: one shared playhead for all stems (no drift), click-free seek/loop/mute/solo, varispeed 0.5–2× with optional keep-pitch, reverse playback, A/B loop.
* **Effects per stem and on master**: reverb, delay, ping-pong echo, low/high-pass, distortion, chorus, pitch shift, 3-band EQ, volume, pan — all real-time, with animated knobs.
* **One-tap modes**: all · no vocals (instrumental/karaoke) · acapella · rhythm.
* **Lyrics**: synced LRC view with highlighted, auto-scrolling lines, tap-to-seek, LRC import, raw editor, tap-to-sync timestamps, offset nudging, online lookup (LRCLIB).
* **Record** the live master output (WAV/MP3) and **export** the mix offline (WAV/MP3) through the identical DSP path; share or save to Music.
* **Projects & presets**: every mix autosaves per track; save named projects and reusable presets.
* **Library**: recent, imported, separated, favourites, search, delete, stem-cache management.
* BPM detection, tap tempo, waveform scrubber, stem colour customisation, haptics, background playback with lock-screen controls.

## Install the app

Download the APK for your phone from the [Releases](../../releases) page
(`arm64-v8a` for almost every phone made since 2017) and open it, or publish
the `.aab` on Google Play (see `docs/PLAY_STORE.md`). Android 8.0+, 4 GB RAM
or more for AI separation. Import a song and separation starts automatically.

## Build from source

Requirements: JDK 17+, Android SDK 36, NDK 27.2.12479018, CMake 3.22.1,
and Python 3.10+ with ffmpeg to build the AI model once:

```bash
pip install -r tools/model/requirements.txt onnxscript
tools/model/build_model.sh          # → android/app/src/main/assets/models/htdemucs.onnx
cd android
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # JVM unit tests
cmake -S engine-tests -B ../build/et && cmake --build ../build/et && ../build/et/engine_test
```

### Signing

Release builds use `PHASE_KEYSTORE_FILE` / `PHASE_KEYSTORE_PASSWORD` /
`PHASE_KEY_ALIAS` / `PHASE_KEY_PASSWORD` when set (CI reads them from the
`PHASE_KEYSTORE_BASE64` etc. repository secrets). Without them the build
falls back to `android/keystore/phase-sideload.jks`, a **public** key whose
password is in the build script. It exists so that sideloaded releases can
upgrade each other; because anyone can sign with it, configure your own
secrets before distributing the app widely.

## Releases

Pushing a tag `vX.Y.Z` (or running the workflow manually with a `release_tag` input) runs `.github/workflows/build.yml`: engine tests,
server tests, unit tests, release APK, and a GitHub Release with the APK and
SHA-256 checksums.

## Licences

No licence has been chosen for the Phase code yet — add a `LICENSE` file
before distributing. Third-party components keep their own licences: shine
MP3 encoder (LGPL-2, full source in `android/app/src/main/cpp/third_party/shine`),
Oboe (Apache-2.0, via Maven), Demucs (MIT, used by the server; model weights
are downloaded from Meta).
