## Phase — on-device AI

**The AI now runs on your phone.** Import a song and it is split into
vocals · drums · bass · other right on the device — no computer, no server,
no account, nothing uploaded, works offline.

**Which file?**
- `Phase-*-arm64-v8a.apk` — almost every Android phone (install this one)
- `Phase-*-armeabi-v7a.apk` — older 32-bit phones
- `Phase-*.aab` — for publishing on Google Play

Android 8.0+. AI separation needs 4 GB RAM or more (6 GB+ recommended) and takes a few minutes
per song (it keeps running in the background with a progress notification).

### New in this release
- On-device Hybrid Transformer Demucs (ONNX Runtime), verified to produce the
  same stems as the original PyTorch model
- Separation runs one song at a time, survives backgrounding, cancellable
- Play Store ready: targets Android 16 (API 36), 16 KB page-size compatible,
  App Bundle, privacy policy
- Landscape/tablet layout for the player
- The self-hosted server is now an optional advanced setting

### Known limitations
- Separation speed depends on the phone (roughly 1–3× the song length on recent phones).
- Keep-pitch at extreme speeds uses a granular shifter (audible artefacts).
