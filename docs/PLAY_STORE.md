# Publishing Phase on Google Play

Everything technical is already done in the build: target API 36, 16 KB
page-aligned native libraries, an Android App Bundle (AAB), the AI model
bundled in the app (≈94 MB download per device, under Play's 200 MB limit),
correct foreground-service types, and no dangerous permissions. What is left
needs **your** Google Play developer account.

## 1. Create your upload key (once)

Run this on your computer (it ships with Android Studio / any JDK). Keep the
file and passwords safe — you need them for every update.

```bash
keytool -genkeypair -v -keystore phase-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

## 2. Give the key to the release workflow

In GitHub → the repository → **Settings → Secrets and variables → Actions →
New repository secret**, add:

| Secret | Value |
|---|---|
| `PHASE_KEYSTORE_BASE64` | the keystore as base64 (`base64 -w0 phase-upload.jks` on Linux/macOS, `[Convert]::ToBase64String([IO.File]::ReadAllBytes("phase-upload.jks"))` in PowerShell) |
| `PHASE_KEYSTORE_PASSWORD` | keystore password |
| `PHASE_KEY_ALIAS` | `upload` |
| `PHASE_KEY_PASSWORD` | key password |

Then run **Actions → Build & release → Run workflow** with a new
`release_tag` (e.g. `v0.2.1`). The release contains `Phase-<version>.aab`.
Without these secrets the AAB is signed with the public sideload key —
**don't upload that one to Play.**

## 3. Play Console

1. **Create app** → name *Phase – Stem Remixer* (or your choice), App, Free.
2. **Store listing** — paste from `docs/play-store/listing.md`; upload
   `docs/play-store/icon-512.png` and `feature-graphic-1024x500.png`; add at
   least 2 phone screenshots (take them on your phone: Home, Player,
   FX Studio, Lyrics).
3. **Privacy policy URL**:
   `https://github.com/Amaljith75/phase/blob/claude/gifted-feynman-z1kb6u/PRIVACY.md`
4. **App content**
   - *Ads*: No ads.
   - *App access*: All functionality available without special access.
   - *Content rating*: questionnaire → category "Music/Audio, Utility"; answer
     No to violence, sexuality, gambling, user interaction/sharing (sharing
     only uses the Android share sheet). Result is typically "Everyone / 3+".
   - *Target audience*: 13+ (or 18+); not designed for children.
   - *Data safety*: see the answers in `docs/play-store/listing.md`.
   - *Foreground service permissions*: declare **Media processing**
     ("on-device AI stem separation of a song the user selected, with a
     progress notification"), **Data sync** (same task on Android 14 and older,
     and optional upload to the user's own server) and **Media playback**
     (background music playback).
5. **Testing → Internal testing** → create release → upload the `.aab` →
   add your own email as a tester → install from the opt-in link and test on
   your phone. New personal developer accounts must run a **closed test with
   at least 12 testers for 14 days** before production is unlocked.
6. **Production** → promote the tested release.

Enrol in **Play App Signing** when asked (default). Google keeps the app
signing key; your `phase-upload.jks` is only the upload key.

## 4. Before each release

- Bump nothing by hand: `versionCode` comes from the CI run number,
  `versionName` from the tag.
- Test the internal-testing build on a real phone: import, wait for AI
  separation, swipe the pads, effects, lyrics, record, export.
