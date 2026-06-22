# CI APK build + first-run Radio-Helper install

## CURRENT STATE / NEXT STEP
- **Goal (user, 2026-06-22):** (A) Set up GitHub Actions so the repo compiles a downloadable, installable
  APK. (B) On first launch, if the Radio Helper app isn't installed, show a popup that installs it in-app
  (bundled, no browser).
- **VERIFIED shared signing key:** the whole app family (Super Drop, :radio-helper, oconnect-bridge,
  in-app-split build) is signed with the box's `~/.android/debug.keystore` — cert SHA-256
  `eeb79952331b990758e4345fe3413406059468286132d30701ac32ad2cf31cf7`, creds android/androiddebugkey/android.
  Reuse THIS key (user: a new key breaks the family). ⇒ stay on the **debug variant** (app
  `dev.superdrop.debug`, helper `dev.superdrop.radiohelper.debug`) so CI APKs drop-in-update what's
  installed and the `BIND_RADIO` signature-permission keeps working.
- **VERIFIED repo state:** IvanChanPing/Bada is PUBLIC, default branch `main`, work branch
  `fork/superdrop-ui`. Existing `.github/workflows/ci.yml` (runs on main only, no artifact upload) +
  `release.yml` (tag `v*`, needs 4 signing secrets that are NOT set; no releases exist). `:radio-helper`
  has NO release signing config.
- **2026-06-22 STATUS: BUILT + verified locally; committing/pushing; CI trigger pending.**
  - DONE: Gradle helper-bundling task (`bundleRadioHelperDebug`) + deterministic debug signing when
    KEYSTORE_FILE injected; manifest `REQUEST_INSTALL_PACKAGES` + `HelperInstallReceiver`; new
    `helper/HelperInstaller.kt` + `helper/HelperInstallReceiver.kt`; MainActivity first-run dialog +
    onResume resume + prefs; strings; `.github/workflows/build-apk.yml` (new) + rewrote `release.yml`
    (debug variant + shared key); repo secret `KEYSTORE_B64` SET on IvanChanPing/Bada.
  - VERIFIED: `:app:assembleDebug` clean; `assets/radio-helper.apk` embedded (pkg
    `dev.superdrop.radiohelper.debug`, signer `eeb79952…` = app signer); `HelperInstaller`/`Receiver`
    dexed into the APK; `:app:testDebugUnitTest` green.
  - NEXT: commit + push fork/superdrop-ui; trigger `build-apk.yml` via gh workflow_dispatch and read the
    run log to confirm CI produces the artifact; then on-device test (device-UNVERIFIED): first-run dialog
    → Install → grant unknown-sources → helper installs → Super Drop binds it.

## PLAN
A. **Gradle bundling** — `:app` gets a task that runs `:radio-helper:assembleDebug`, copies the helper APK
   to `app/src/main/assets/radio-helper.apk`, wired before `mergeDebugAssets`. Deterministic debug signing:
   when `KEYSTORE_FILE` env is set (CI), point BOTH debug+release signingConfigs at it; locally debug uses
   the box `~/.android/debug.keystore` (= same cert).
B. **Manifest** — add `REQUEST_INSTALL_PACKAGES`; the helper package is already in `<queries>`.
C. **First-run install code** — `HelperInstaller` (check installed via PackageManager; if missing, copy
   bundled asset → PackageInstaller Session off main thread → status via PendingIntent) + a first-run
   AlertDialog in `MainActivity.onCreate` ("Install Radio Helper?" Install / Not now). One-time pref flag.
   Derive helper pkg from app id suffix (`.debug` ⇒ helper `.debug`). Route to ACTION_MANAGE_UNKNOWN_APP_
   SOURCES if install-unknown-apps not granted (one-time, not per-boot).
D. **CI** — new/updated workflow: trigger on `workflow_dispatch` + push to `fork/superdrop-ui`; inject the
   shared keystore from secret `DEBUG_KEYSTORE_B64`; build `:app:assembleDebug` (helper auto-bundled);
   upload APK artifact. Keep release.yml (tags) but feed it the shared key + attach the app APK so the
   in-app updater (points at IvanChanPing/Bada releases) works.
E. **Secrets** — `gh secret set DEBUG_KEYSTORE_B64` = base64 of the box debug.keystore (+ password/alias
   are the well-known debug creds, can be literals or secrets).
F. **Verify** — local `:app:assembleDebug`; confirm `assets/radio-helper.apk` present in APK + signer cert
   matches; push; trigger CI via gh; read run logs. On-device install click-path = DEVICE-UNVERIFIED → test
   script for the user.

## RISKS
- REQUEST_INSTALL_PACKAGES: user grants "allow this source" once (system UI) — one-time, compliant with the
  no-per-boot rule. Without it, route to settings; never silently fail.
- ANR: stream the ~15 MB helper asft to the PackageInstaller session on a worker thread.
- Public repo: do NOT commit the keystore; inject via secret.
- CI uses JDK 17 (existing workflows); local build used 21 — project compileOptions = Java 17, builds on 17.
- Device-UNVERIFIED: no device/radios here; the install + bind click-path needs on-device confirmation.
