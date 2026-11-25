## Phase — first release

A tactile stem-remixing instrument for Android (8.0+).

**Install:** download `Phase-*.apk` below and open it on your phone (allow
"install unknown apps" for your browser/files app when prompted).

**AI separation** needs the Phase server from this repository
(`backend/` — one `docker compose up`). Enter its address in
Settings → AI separation. Without it, songs play as full mixes with master
effects.

### Included
- Import MP3/WAV/FLAC/M4A/AAC/OGG, library with search, favourites, recents
- Real AI stem separation (Demucs) with progress notification and caching
- Native low-latency engine (Oboe/AAudio): sample-locked stems, click-free seek/loop/solo/mute
- Swipe gestures with haptics (customisable), one-tap acapella / instrumental / rhythm
- Per-stem + master effects: reverb, delay, echo, filters, distortion, chorus, pitch, EQ, pan
- Speed 0.5–2× (keep-pitch option), reverse, A/B loop, BPM detection, tap tempo
- Synced lyrics: LRC import, editor, tap-to-sync, online lookup (LRCLIB)
- Live recording + offline WAV/MP3 export, share / save to Music, projects & presets

### Known limitations
- Separation runs on a server you host; on-device separation is on the roadmap.
- Keep-pitch at extreme speeds uses a granular shifter (audible artefacts).
- Signed with the public sideload key unless the maintainer configures signing secrets.
