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

## 5. Which phones can install it

* **Android 8.0 (API 26) or newer** — about 97% of active Android devices.
* All CPU types that matter: arm64-v8a (practically every phone since
  2017), armeabi-v7a (old 32-bit phones), x86_64 (Chromebooks/emulators).
* Phones and tablets (the player adapts to landscape tablets).
* **AI separation needs 4 GB RAM or more** (6 GB+ recommended). Phones with
  less can install and play songs, but the app refuses to separate. Because
  this is a paid app, exclude low-memory devices so nobody buys something
  that can't separate: Play Console → **Release → Device catalog → Exclude
  devices by rule → RAM < 4 GB**.
* Speed depends on the phone's CPU; a 4-minute song takes a few minutes on a
  recent phone.

## 6. Protecting your code, your app and your income

**Your GitHub repository is the biggest risk.** If it is public, anyone can
download the full source. Make it private: GitHub → repository → **Settings
→ General → Danger Zone → Change visibility → Make private**. Releases of a
private repository are private too. Also:

* Turn on **two-factor authentication** for your GitHub account.
* Delete old public releases you don't want downloadable (Releases → each
  release → Delete). Anything that was public may already have been copied.
* Never commit your upload keystore or its passwords; keep them only in
  GitHub **Secrets** and in a safe offline backup (password manager +
  USB stick). If you lose the upload key, Google can reset it, but it takes
  days.

**What a determined person can and can't get from the APK.** Every Android
app can be downloaded and unpacked; no app is impossible to reverse.
Phase makes it as hard as is practical:

* Kotlin code is minified and obfuscated (R8): class/method names are
  scrambled, source-file names and line numbers removed, debug logging
  stripped.
* The audio engine is compiled C++ with all symbols stripped — the hardest
  part of the app to reverse.
* Your source code, comments, build scripts, tests and Git history are
  **not** in the APK. Only the repository holds them.
* The AI model file is extractable from any APK; it is Meta's openly
  published Demucs model, not your secret.

**Piracy.** For a paid app without login, turn on Play Console → **Release
→ App integrity → Automatic protection**. Google then adds anti-tamper
checks and blocks copies not installed from Play (sideloaded APKs) from
running. Keep it on for every release.

## 7. Shipping updates

1. Make changes, push, then run **Actions → Build & release → Run workflow**
   with a new `release_tag` (e.g. `v1.0.1`).
2. Download `Phase-<version>.aab` from the release (or the workflow
   artifacts) and upload it in Play Console → **Production → Create new
   release**. Users get the update automatically.

`versionCode` increases automatically with every CI run, so Play always
accepts the new bundle. Updates must be signed with the **same upload key**
every time — that is why the key lives in GitHub Secrets.

## 8. Licences and legal

* All bundled components are under permissive licences (MIT, Apache-2.0)
  that allow paid, closed-source apps; their notices are shown in the app
  (Settings → About → Open-source licences). The LGPL MP3 encoder used in
  early versions has been removed; exports are WAV or M4A (AAC).
* Demucs' code and weights are MIT-licensed, but the model was trained on
  research datasets (MUSDB18-HQ) that carry their own terms. If you plan
  significant commercial sales, get a quick opinion from a lawyer on using
  the pretrained weights commercially.
* Users process their own music files; mention in the listing that users
  are responsible for rights to the music they remix.
