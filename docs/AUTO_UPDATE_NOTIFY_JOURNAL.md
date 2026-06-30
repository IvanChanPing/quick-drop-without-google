# Auto-Update Notification — Task Journal

## CURRENT STATE / NEXT STEP
**Goal:** Add an AUTOMATIC periodic background check that polls GitHub Releases
(IvanChanPing/Bada) for a newer Super Drop version and posts a NOTIFICATION offering
to (a) open the GitHub release page, and/or (b) download + drop-in install the APK
directly. Extends the existing MANUAL "Check for updates" flow.

**USER DECISIONS (2026-06-30):** poll frequency = **every 6 hours**. Notification actions =
**adaptive**: if the GitHub release has an `.apk` asset attached → offer "Download & install";
else → "View on GitHub" only. (User: "depends on where — if GitHub built the APK it's pullable,
otherwise it just takes you to GitHub.")

**DONE (this session, 2026-06-30):** ALL code written. Build kicked off (gradlew
`:app:assembleDebug`, log `/tmp/bada_update_build.log`).

**IN PROGRESS:** Build compiling. NEXT STEP (exact): read build log → if SUCCESS, confirm
APK at repo root (place-apk hook), commit + CHANGELOG; if FAIL, read errors and fix.
After build: feature is COMPILE-status only — on-device UNVERIFIED (worker trigger,
notification render, download+install confirm dialog, unknown-sources grant). Hand user the
on-device test script (below) since there's no device in this env.

### Files written (all new unless noted)
- `gradle/libs.versions.toml` (edit): + `androidxWork=2.9.1` + `androidx-work-runtime-ktx`.
- `app/build.gradle.kts` (edit): + `implementation(libs.androidx.work.runtime.ktx)`.
- `update/UpdateChecker.kt` (edit): parse `assets[]` → `LatestRelease.apkAssetUrl` (nullable).
- `update/UpdatePreferences.kt` (edit): + `lastNotifiedVersion`/`saveNotifiedVersion` (dedup
  the alert), + `autoCheckEnabled`/`setAutoCheckEnabled` (default true, future toggle).
- `update/UpdateNotifier.kt` (NEW): channel `app_update` + adaptive notification (body→
  CheckForUpdatesActivity; action "View on GitHub" always; "Download & install" only if apk asset).
- `update/UpdateDownloadInstaller.kt` (NEW): streams APK URL → PackageInstaller session
  (mirrors HelperInstaller); "Downloading…"/"failed" notifications.
- `update/UpdateInstallActivity.kt` (NEW): transparent trampoline; gates `canRequestPackageInstalls`
  (reuses HelperInstaller helpers) then calls installer; manifest noHistory/excludeFromRecents.
- `update/UpdateInstallReceiver.kt` (NEW): PackageInstaller status sink (mirror HelperInstallReceiver).
- `update/UpdateCheckWorker.kt` (NEW): CoroutineWorker; fetch→compare→dedup→notify; failure=success.
- `BadaApplication.kt` (edit): schedule unique 6h PeriodicWork (NetworkType.CONNECTED, policy UPDATE).
- `AndroidManifest.xml` (edit): register UpdateInstallActivity (translucent) + UpdateInstallReceiver.
- `res/values/strings.xml` (edit): 15 new `update_*` strings.

### ON-DEVICE TEST SCRIPT (user — no device in build env)
1. Install the new debug APK. Confirm app launches (WorkManager schedules silently in onCreate).
2. Force a check NOW without waiting 6h: `adb shell cmd jobscheduler run -f dev.superdrop.debug <jobid>`
   OR temporarily lower the interval / use the existing overflow "Check for updates" to prove the
   network path. (The 6h PeriodicWork itself can't be hand-triggered easily; verify the worker via a
   one-off if needed.)
3. Publish (or already have) a GitHub Release on IvanChanPing/Bada with a tag NEWER than the installed
   versionName (e.g. v20260701.01) AND an attached `super-drop-*.apk` → within a poll, expect the
   "Super Drop update available" notification with BOTH actions.
4. Tap "View on GitHub" → release page opens in browser.
5. Tap "Download & install" → (first time) unknown-sources settings prompt; grant once; tap again →
   "Downloading update…" → system install confirm dialog → app updates in place (same pkg+key).
6. Publish a release with NO apk asset → expect notification with ONLY "View on GitHub".
7. Verify no duplicate notification on the next poll for the same version (dedup via lastNotifiedVersion).

---

## VERIFIED FACTS (read this session 2026-06-30)
Repo: `/root/agent-work/projects/bada-fork` (real app, `dev.superdrop`; installed build =
`dev.superdrop.debug`). origin = github.com/IvanChanPing/Bada.

### Existing update stack (MANUAL only — no auto/periodic, no notification)
- `app/.../update/UpdateChecker.kt` — `object`, `fetchLatestRelease(): Result<LatestRelease>`.
  Single-shot `GET /repos/IvanChanPing/Bada/releases/latest` via HttpURLConnection (no auth,
  60/h quota). Skips draft/prerelease. Returns `LatestRelease(version=stripV(tag_name),
  releaseUrl=html_url)`. **Does NOT capture the APK asset download URL** → must extend for
  direct download (parse `assets[].browser_download_url`, name `super-drop-<tag>.apk`).
- `UpdateRepository.kt` — process `object`. `seedFromCache(ctx)`, `refresh(ctx)` (Mutex-coalesced),
  `hasPendingUpdate()`. `isNewer(a,b)= a>b` lexicographic on `YYYYMMDD.NN` versionName.
  Persists via `UpdatePreferences`. State = `UpdateState` sealed (Idle/Checking/UpToDate/
  UpdateAvailable/Error).
- `UpdatePreferences.kt` — SharedPreferences `bada.update`: latest_release_version + _url.
- `CheckForUpdatesActivity.kt` — overflow-menu screen; renders state; button opens release
  page. `MainActivity` seeds + one-shot refresh at start; paints a red-dot badge.

### Release / distribution infra (purpose-built for "offer + install")
- `.github/workflows/release.yml`: on tag `v*`, builds `:app:assembleDebug` signed with the
  SHARED family keystore (secret KEYSTORE_B64, creds androiddebugkey/android), attaches
  `super-drop-<tag>.apk` to the matching GitHub Release. **VERIFIED: shipped variant =
  `dev.superdrop.debug` with the same key as installed → a direct download is a true
  DROP-IN in-place update** (resolves the variant/signature concern). Release tag scheme
  `vYYYYMMDD.NN`; versionName source of truth = `app/build.gradle.kts` (currently
  `20260614.01`, versionCode 2026061401).

### Reusable building blocks
- `BadaApplication.onCreate()` — process bootstrap; correct place to schedule the worker +
  create the notification channel.
- `helper/HelperInstaller.kt` — installs an APK via `PackageInstaller` session on a worker
  thread; gates on `canRequestPackageInstalls()`; one-time unknown-sources grant intent
  (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`). EXACT pattern to reuse for "download directly +
  install". Status delivered to a BroadcastReceiver.
- Manifest already declares: POST_NOTIFICATIONS (33+), REQUEST_INSTALL_PACKAGES, INTERNET,
  ACCESS_NETWORK_STATE. minSdk 24 / target+compile 36.
- Notification infra precedent in `:service-android` (channels + NotificationCompat).
- No `androidx.work` dependency yet (libs.versions.toml has none).

## DECISIONS / OPEN QUESTIONS
- Q1 poll frequency (default: daily). Q2 notification actions (default: both "View on GitHub"
  + "Download & install"). WorkManager chosen over AlarmManager+BootReceiver because it
  survives reboot with zero per-boot user action (satisfies no-manual-setup rule) and is the
  standard periodic scheduler; min interval 15 min (we use ~daily).

## PRE-BUILD RISK PASS
- A1 (VERIFIED): GitHub release ships matching debug variant+key → drop-in install works.
- A2 (UNVERIFIED until device): unknown-sources grant + PackageInstaller confirm dialog flow
  on-device — same gap as HelperInstaller (compile-only there too). Will reuse its gating.
- A3: WorkManager PeriodicWork min interval = 15 min (OS-enforced); daily is fine.
- A4: POST_NOTIFICATIONS runtime grant needed on API 33+ — MainActivity should request it
  (check if already requested for transfer notifications; reuse).
- A5: GitHub unauth rate limit 60/h per IP — daily poll is negligible.
- A6: Observability — Worker logs to DiagnosticLog; notification itself is the visible signal.
- A7: Avoid re-notifying for the same version every poll — store "notified version" in
  UpdatePreferences, only notify when latest > both installed AND last-notified.
