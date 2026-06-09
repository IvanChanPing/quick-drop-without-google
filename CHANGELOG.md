# Changelog — Super Drop (fork of Bada)

All notable changes to this fork. Upstream Bada history is preserved in git;
entries here cover only fork-specific changes.

## [Unreleased]

### 2026-06-09 (b) — RadioHelperClient: wake a force-stopped helper + connect timeout + compile-verified
- **Wake after force-stop (user ask):** the client bind now adds `FLAG_INCLUDE_STOPPED_PACKAGES` to the
  explicit `bindService` intent, so it starts the helper even if it was force-stopped / never opened
  since install (Services aren't subject to the broadcast-receiver stopped-state exclusion). Aggressive
  OEM force-stop may still need one manual open — handled by the fallback below.
- **No-hang guard:** added a 5s connect timeout — if a bind returns true but never connects (sticky-OEM
  force-stop), `connect()` now returns `false` (+ unbinds) instead of leaving the callback hanging
  forever. `disconnect()` cancels it.
- **Compile-verified:** temporarily compiled `RadioHelperClient.kt` inside the helper module — caught a
  Kotlin recursive type-inference error (mutual `connection`↔`connectTimeout` refs) and fixed it with
  explicit types (`connection: ServiceConnection`, `connectTimeout: Runnable`). `compileDebugKotlin`
  BUILD SUCCESSFUL; temp copy removed (client stays a copy-paste file under `radio-helper/client/`, not
  in the APK).
- Helper module unchanged this entry → `radio-helper-debug.apk` not rebuilt. Client still compile-only
  until wired into a real app's share flow.

### 2026-06-09 — Universal helper: drop-in client + HELPER-OWNED share session (capture/restore) + direct API
- **Goal (user):** any of our file-sharing apps routes Wi-Fi/BT toggling THROUGH the one installed
  radio-helper; the HELPER (not the app) decides what was off, turns it on, and restores it — the app
  only says "prepare" and "finished". Also keep a DIRECT toggle path (no session). And the helper must
  wake on call.
- **New `RadioHelperClient.kt`** (`radio-helper/client/`, canonical copy-paste drop-in for each app):
  binds the helper RadioService via Messenger (async, no ANR). TWO modes:
  - SESSION: `prepareForShare(radios=RADIO_BOTH)` at transfer/NFC-tap start + `transferFinished()` at
    terminal. The app tracks NOTHING.
  - DIRECT: `setWifi(on,cb)` / `setBluetooth(on,cb)` / `queryState(cb)` — flip a radio immediately.
  - `connect`/`disconnect`; helper-missing or wrong-signing-key → callbacks return false/0 so the app
    can fall back.
- **New helper-side `ShareRadioSession`**: owns capture-original → enable-only-OFF → restore-only-ours,
  with the "what we turned on" flags **persisted in SharedPreferences** so a process kill between
  prepare and finish still restores correctly. `RadioService` gained MSG_PREPARE_SHARE=4 (arg1=radio
  bitmask, reply arg1=now-on bitmask) + MSG_TRANSFER_FINISHED=5 (existing MSG_SET_WIFI/BLUETOOTH/QUERY
  kept for the direct path); added `replyInt`.
- **Wake-on-call:** clients bind with `BIND_AUTO_CREATE`, so Android starts the helper process + creates
  RadioService on demand — a call wakes it (independent of the boot warm-up). Caveat: won't wake if the
  helper is force-stopped / never-opened-since-install (one-time setup opens it); bind from foreground.
- Integration contract (manifest `<uses-permission BIND_RADIO>` + `<queries>` + SAME signing key) is in
  the `/radio-helper-integration` skill + memory `universal-radio-helper-all-sharing-apps`.
- **Build:** `:radio-helper:assembleDebug` BUILD SUCCESSFUL. radio-helper-debug.apk refreshed.
- **Status:** helper compiles + (mechanism/persistence) emulator-validated; `RadioHelperClient` is
  compile-only until wired into a real app's share flow (next step, Super Drop first) — then e2e.

### 2026-06-08 (later 15) — libadb 1.0.1 → 3.1.1 + autoConnect (the real fix for "adbd unreachable")
- **Device evidence:** notification pairing SUCCEEDED, but the self-ADB CONNECT failed with the real
  error **`IOException: null`** (surfaced by "later 14"'s diagnostics) — TCP connected but the ADB/TLS
  handshake then failed, even trying the Wi-Fi IP. The Wi-Fi toggle "working" was the Shizuku rung.
- **Root cause:** we were on **libadb-android 1.0.1** (2022); the maintainer has a known **"Connect
  after pairing fails"** issue (#4) from that era, and the current version is **3.1.1**. The fix is the
  version bump PLUS using the library's own **`autoConnect(context, timeout)`** (added in 3.x), which
  does mDNS discovery + TLSv1.3 connect internally — instead of our hand-rolled discover + connect(host,
  port) that 1.0.1 couldn't complete on ColorOS.
- **Changes:**
  - `build.gradle.kts`: libadb `1.0.1 → 3.1.1`; BouncyCastle aligned to `jdk15to18:1.81` (matches the
    bcprov libadb 3.1.1 pulls in); kept `conscrypt-android` (libadb's SslUtils instantiates
    `org.conscrypt.OpenSSLProvider` itself for TLSv1.3, avoiding the hidden-API path).
  - `AdbWifiManager`: removed the manual `Conscrypt.newProvider()` global insertion (needed for 1.0.1,
    now redundant/interfering — libadb sets up TLS itself). `runShell(context, command)` now uses
    `mgr.autoConnect(context, 10s)` (no manual host/port); `setWifi(context,on)` /
    `selfGrantWriteSecureSettings(context)` updated. `pair(host,port,code)` unchanged (notification flow
    still resolves the pairing host/port via mDNS).
  - `AdbWifiRadio`: dropped the cachedHost/cachedPort/loopback logic; `ensureReady` now returns Boolean
    (probe via autoConnect) and `setWifi` delegates to the autoConnect path. `lastStatus` still surfaces
    `AdbWifiManager.lastError`.
  - Updated callers: `AdbWifiBootService` (Boolean), `PairingReplyReceiver` (Boolean), `SelfTestActivity`
    step 2 (self-grant via autoConnect, no manual port).
- **Build:** `:radio-helper:assembleDebug` BUILD SUCCESSFUL. radio-helper-debug.apk ≈ 15.5 MB (libadb
  3.1.1 + bundled BouncyCastle/spake2).
- **Status:** compile-only / device-UNVERIFIED. Next test: "3. Test self-ADB Wi-Fi" — autoConnect should
  now complete the handshake and run `svc wifi`; if not, the status shows the precise new error
  (e.g. `AdbPairingRequiredException` would mean the pairing didn't actually stick).

### 2026-06-08 (later 14) — self-ADB connect: use resolved Wi-Fi IP (not loopback) + real error in status
- **Device evidence:** notification-reply pairing SUCCEEDED (status showed "PAIRED" — the truthful
  marker), but the self-ADB CONNECT then failed ("adbd unreachable", port 40697). The Wi-Fi toggle that
  "started working" was the **Shizuku** rung of the ladder (status showed "Shizuku: available"), NOT
  self-ADB — and Shizuku doesn't self-start on boot, so it doesn't meet the no-per-restart rule.
- **Root-cause hypothesis (strong):** pairing connected to the device's resolved Wi-Fi IP (via
  `discoverHostPort`) and worked, but the connect path was hardcoded to `127.0.0.1`. On ColorOS adbd
  appears to bind to the Wi-Fi IP, not loopback → pair OK, connect refused.
- **Fix:** `AdbWifiRadio.ensureReady` now resolves BOTH host+port (`discoverHostPort`) and caches them;
  `setWifi` tries the resolved Wi-Fi IP FIRST, then falls back to loopback (`tryToggle`). The Wi-Fi IP is
  re-discovered every ensureReady (it can change across networks/reboots).
- **Observability:** `AdbWifiManager.lastError` now records the actual connect exception (class +
  message); `AdbWifiRadio.lastStatus` includes it, so a failure says e.g. "ConnectException: Connection
  refused" (wrong host) vs an SSL/handshake error (key not trusted) instead of a generic "unreachable".
- Goal: make the SELF-STARTING self-ADB path actually toggle Wi-Fi so Shizuku is unnecessary and nothing
  needs setup after a restart.
- **Build:** `:radio-helper:assembleDebug` BUILD SUCCESSFUL. radio-helper-debug.apk refreshed.
- **Status:** compile-only / device-UNVERIFIED. Next on-device test: tap "3. Test self-ADB Wi-Fi" — it
  should now connect via the Wi-Fi IP (or the status will show the precise remaining error).

### 2026-06-08 (later 13) — Boot persistence hardened: FOREGROUND warm-up + retry (adb-auto-enable model)
- **Persistence is the requirement**, and it is a SOLVED problem on this device family: the
  adb-auto-enable reference (libadb-android based, README explicitly covers OnePlus/ColorOS Android 14)
  does persistent wireless-ADB across reboot, no root, no per-boot manual step, with our exact mechanism
  (BootReceiver → re-enable `adb_wifi_enabled` via WSS → mDNS port → connect with the STORED paired key).
  The pairing key + device-side trust persist across reboot; we just re-enable + reconnect.
- Hardened `AdbWifiBootService` to match the reference's ColorOS boot pacing: now a FOREGROUND service
  (so ColorOS background limits don't kill the ~60-90s warm-up), waits 60s for the system to settle,
  then retries `ensureReady` up to 3× (15s apart) until adbd advertises a port. Brief low-importance
  "Radio helper / Preparing…" notification, removed when done.
- `AdbWifiBootReceiver` now `startForegroundService` on O+. Manifest: added `FOREGROUND_SERVICE`.
- **Defense in depth:** even if the boot warm-up is killed/skipped, the TAP path self-heals —
  `AdbWifiRadio.setWifi` calls `ensureReady` itself on the first NFC tap, so persistence does NOT depend
  on the boot service succeeding; worst case the first post-reboot tap is ~10-15s slower. No manual step
  either way.
- **Build:** `:radio-helper:assembleDebug` BUILD SUCCESSFUL. radio-helper-debug.apk refreshed.
- **Status:** compile-only / device-UNVERIFIED. The reboot test (pair once → reboot → toggle with no
  manual step) is the proof; the mechanism is proven on ColorOS by the reference.

### 2026-06-08 (later 12) — NOTIFICATION-reply pairing (the Brevent trick) — makes pairing POSSIBLE
- **Problem:** on the user's ColorOS the Wireless-debugging "Pair device with pairing code" dialog
  CLOSES the moment you switch apps, and Settings can't be put in split screen — so the
  type-the-code-into-our-app flow is impossible. (Confirmed: device showed "paired but adbd
  unreachable" = key never actually trusted.)
- **Solution (verified from Brevent's own strings):** pair via a NOTIFICATION with an inline reply.
  Replying to a notification does NOT switch the foreground app, so the pairing dialog stays alive.
  Brevent string: "reply Brevent's notification with the six digit code." mDNS finds the pairing port,
  so the user types ONLY the 6 digits.
- New `PairingNotifier` (posts the inline-reply notification, channel "ADB Wi-Fi pairing") +
  `PairingReplyReceiver` (RemoteInput → discover `_adb-tls-pairing._tcp` via mDNS →
  `AdbWifiManager.pair(host,port,code)` → `AdbWifiRadio.ensureReady`; result shown back in the
  notification). `AdbMdns` gained `discoverHostPort` (+ `SERVICE_PAIRING`) so pairing connects to the
  device's real Wi-Fi IP, not just loopback.
- `SelfTestActivity`: replaced the (dead) split-screen instructions + typed port/code fields with a
  "1. Start pairing (notification)" button and notification-flow instructions. Manifest: registered
  `PairingReplyReceiver` (not exported). POST_NOTIFICATIONS auto-granted at targetSdk 28.
- **Also fixed earlier this session:** `isPaired` was a false positive (it checked for the cert file,
  which is written even on a FAILED pair). Now a separate `adb_paired` marker is written ONLY on a
  genuinely successful pair, so the status is truthful.
- **Why this can beat Brevent on reboot:** Brevent says "won't work if the device reboots" because it
  lacks WRITE_SECURE_SETTINGS to re-enable wireless debugging. We HAVE WSS, so the boot service
  re-enables it and reconnects with the persisted paired key — no per-boot manual step.
- **Build:** `:radio-helper:assembleDebug` BUILD SUCCESSFUL (one deprecation warning on
  Notification.Action.Builder, harmless). radio-helper-debug.apk refreshed at root.
- **Status:** compile-only / device-UNVERIFIED. Make-or-break tests for the user: (1) does the
  notification-reply pairing complete on this ColorOS (status shows "Paired OK"); (2) does it still
  toggle Wi-Fi after a REBOOT with no manual step.

### 2026-06-08 (later 11) — Wi-Fi ladder: FIX self-ADB never tried in test UI + add full per-rung logging
- **Bug (user-reported):** with Shizuku disabled, tapping "Toggle Wi-Fi" on the "Super Drop Radio
  Helper" screen just opened the Wi-Fi settings — the self-ADB rung was NEVER attempted. Root cause:
  `SelfTestActivity`'s Toggle Wi-Fi ran only direct→Shizuku→panel; the self-ADB rung had only been
  added to `RadioToggler.setWifiSilent`/`setWifiSmart`, not to that button. And there was NO logging,
  so the fall-through to the panel was invisible.
- **Fix:** single source-of-truth ladder `RadioToggler.runWifiLadder(ctx,on,allowPanel)` →
  `WifiLadderResult(success, path, steps)`. Order: direct setWifiEnabled → self-ADB → Shizuku → panel
  (panel only if allowPanel). EVERY rung logs to logcat (tags `RadioToggler`, `AdbWifi/Radio`,
  `ShizukuRadio`) AND appends a human-readable step line. `setWifiSilent` (RadioService),
  `setWifiSmart`, and the new `setWifiWithDiagnostics` (test UI) all call it.
- `AdbWifiRadio` now has a `lastStatus` (mirrors ShizukuRadio): reports "NOT PAIRED", "no adbd port via
  mDNS (WSS granted?)", "svc wifi ran via ADB port N", or "paired but adbd unreachable" — so a skipped
  self-ADB rung says WHY instead of a silent false.
- `SelfTestActivity`: Toggle Wi-Fi now runs the FULL ladder (incl. self-ADB) on a bg thread and prints
  every rung's outcome; the status header shows self-ADB paired state ("PAIRED — last: …" / "NOT
  PAIRED"). `AdbWifiManager.pair` logs its result.
- **IMPORTANT (still required, by design):** the self-ADB rung only works AFTER the one-time pairing in
  the "Radio Helper: ADB-WiFi Setup" launcher (steps 1 Pair → 2 Self-grant WSS → 3 Toggle). Until then
  it correctly reports "NOT PAIRED" and the ladder falls to Shizuku/panel.
- **Build:** `:radio-helper:assembleDebug` BUILD SUCCESSFUL. radio-helper-debug.apk refreshed at root.
- **Status:** compile-only / device-UNVERIFIED. Whether self-ADB `svc wifi` actually flips Wi-Fi on the
  user's ColorOS is still the open on-device question — the new on-screen steps + logcat are exactly the
  instrument to answer it.

### 2026-06-08 (later 10) — self-ADB Wi-Fi: MIGRATED into :radio-helper + boot self-start (architecture fix)
- **Why:** the self-ADB Wi-Fi stack was wrongly prototyped inside Super Drop `:app`. It belongs in the
  universal `:radio-helper` so every sharing app (Super Drop, Bridge, O+ Connect, …) reaches ONE helper
  instead of each embedding an ADB client. Also corrected a design error: the *enable-wireless-debugging
  + mDNS-discover + connect* step was about to be done lazily inside `RadioService` on each tap — it must
  run on BOOT instead (the OS resets `adb_wifi_enabled` every reboot), so the first transfer after a
  reboot doesn't eat a ~10s cold-start.
- **Moved** `AdbWifiManager` + `AdbMdns` from `app/.../adbwifi/` into
  `radio-helper/.../adbwifi/` (repackaged `dev.superdrop.radiohelper.adbwifi`). Moved the libadb +
  Conscrypt + BouncyCastle deps from `:app` to `:radio-helper`. Removed the `:app` debug "SuperDrop
  ADB-WiFi Test" launcher.
- **New `AdbWifiRadio`** (shared engine) splits the two jobs explicitly: `ensureReady()` = BOOT job
  (re-enable wireless debugging + discover/cache adbd port); `setWifi(on)` = TAP job (`svc wifi` over the
  warm connection, re-warms once if the port is stale).
- **New `AdbWifiBootReceiver` (RECEIVE_BOOT_COMPLETED) → `AdbWifiBootService`** = self-start on boot,
  calls `ensureReady()` on a background thread. NO per-reboot manual step (the HARD RULE).
- **New helper launcher `AdbWifiTestActivity`** ("Radio Helper: ADB-WiFi Setup") — one-time pairing
  (steps 1 Pair → 2 Self-grant WSS → 3 Toggle Wi-Fi via `AdbWifiRadio`); its key/cert live in the
  helper's filesDir (same process as the boot service + RadioService).
- **Wired the Wi-Fi ladder** (`RadioToggler.setWifiSilent`/`setWifiSmart`): direct `setWifiEnabled` →
  **self-ADB** (preferred; self-starts on boot) → Shizuku (optional) → panel. `RadioService` already runs
  this on its background HandlerThread.
- **Manifest:** added INTERNET, WRITE_SECURE_SETTINGS (declared so the one-time `pm grant` self-grant is
  allowed; only re-enables wireless debugging, NOT for setWifiEnabled), RECEIVE_BOOT_COMPLETED; registered
  the activity, receiver, boot service.
- **Build:** `:radio-helper:assembleDebug` BUILD SUCCESSFUL (only expected legacy-API deprecation warnings
  for BluetoothAdapter.enable/disable). `radio-helper-debug.apk` ≈ 11.9 MB (now bundles libadb).
- **Status:** compile-only / device-UNVERIFIED. The make-or-break on-device test (pair → self-grant →
  toggle on ColorOS, then reboot-persistence) is run from the helper's "Radio Helper: ADB-WiFi Setup"
  launcher.

### 2026-06-08 (later 9) — self-ADB Wi-Fi: on-device TEST screen (debug)
- Step 5: `AdbMdns` (mDNS `_adb-tls-connect._tcp` port discovery) + debug-only
  `AdbWifiTestActivity` (launcher "SuperDrop ADB-WiFi Test"). Drives the full loop on-device:
  (1) Pair (enter Wireless-debugging pair port+code) → (2) Self-grant WRITE_SECURE_SETTINGS over ADB
  → (3) Toggle Wi-Fi (enable adb_wifi_enabled → mDNS discover port → `svc wifi`). All ADB ops on a
  background thread. `:app:assembleDebug` OK; super-drop-debug.apk refreshed (~24.8 MB, incl. libadb).
- This is the anti-Potemkin proof artifact: confirm the self-ADB silent Wi-Fi works on the user's
  ColorOS BEFORE building the BootReceiver/service + Wi-Fi-ladder wiring. NOT yet device-tested.

### 2026-06-08 (later 8) — self-ADB Wi-Fi: AdbWifiManager (libadb core)
- Step 2: `app/.../adbwifi/AdbWifiManager.kt` — libadb `AbsAdbConnectionManager` subclass mirroring
  the adb-auto-enable reference: persistent RSA key + self-signed cert in filesDir
  (adb_key/adb_key.pub/adb_cert) so a pairing survives reboots; `setApi`, the 3 protected overrides;
  Conscrypt+BouncyCastle providers. Facade: `isPaired`, `pair(host,port,code)`,
  `runShell(host,port,cmd)`. All blocking → callers must run off-main. `:app:compileDebugKotlin` OK
  (resolves against libadb API incl. openStream/openInputStream). NOT device-tested. Next: pairing UI
  -> self-grant WRITE_SECURE_SETTINGS -> BootReceiver+service (enable adb_wifi_enabled + mDNS) ->
  `svc wifi` toggle -> Wi-Fi ladder.

### 2026-06-08 (later 7) — self-ADB Wi-Fi (option 2): foundation
- Decided Wi-Fi silent path = **Android-11 wireless-debugging self-ADB** (not Tasker's legacy tcpip,
  not Brevent which needs ADB every boot). Reference: adb-auto-enable (proves no-PC-after-install:
  pair once on-device -> self-grant WRITE_SECURE_SETTINGS via `pm grant` -> BootReceiver re-enables
  `adb_wifi_enabled` + mDNS port discovery + self-connect with stored key).
- Step 1 (this commit): added `com.github.MuntashirAkon:libadb-android:1.0.1` (jitpack) + Conscrypt +
  BouncyCastle to `:app`; added jitpack repo to settings. `:app:assembleDebug` succeeds (deps resolve,
  no class conflicts). NOT device-tested. Next: AdbWifiManager (pair/connect/exec) -> pairing UI ->
  self-grant -> boot service -> `svc wifi` toggle -> slot into the Wi-Fi ladder.

### 2026-06-08 (later 6) — radio-helper: remove WSS dead-end, fix ANR at the source, harden Shizuku
- **Removed `WRITE_SECURE_SETTINGS`** from the helper: it does NOT affect `setWifiEnabled` (AOSP
  exemptions are DO/PO/system only), so granting it never helped and was misleading. Silent Wi-Fi on
  a clamping OEM (ColorOS) is **Shizuku-only**; otherwise the panel.
- **Fixed the crash (ANR) at the source — UI-thread blocking:** the Shizuku bind waits up to 8s.
  SelfTestActivity already moved to a background thread; `RadioService` now processes on a background
  `HandlerThread` too, so when the main app binds it a slow Shizuku call can't ANR. (Rule going
  forward: never call the helper/Shizuku on the main thread.)
- Hardened `ShizukuRadio` (sticky binder-received + dead listeners; precise `lastStatus`). Self-test
  drops the removed-WSS readout. `:radio-helper:assembleDebug` OK; root APK refreshed.
- Bluetooth = automatic (works). Wi-Fi silent path now hinges solely on whether Shizuku `svc wifi`
  works on the device; else the panel flow.

### 2026-06-08 (later 5) — radio-helper RadioService returns silent-only Wi-Fi result
- Per the finalized auto-toggle spec (BT automatic; Wi-Fi silent if WRITE_SECURE_SETTINGS/Shizuku
  else panel): the bound `RadioService` (called by the main app) now routes `MSG_SET_WIFI` through
  `RadioToggler.setWifiSilent` (direct `setWifiEnabled` → Shizuku, **no panel**) and replies success.
  So the main app learns whether the silent path worked and, if not, shows the Wi-Fi settings panel
  itself (foreground) — the panel must NOT be launched from the headless helper/background service
  (Android 14 background-activity-launch limits). Build OK; root APK refreshed.
- Spec recorded; remaining main-app integration (bind RadioService on the cold-tap wake → capture
  each radio's prior state → BT auto-enable + Wi-Fi silent-or-panel-only-if-off → restore what we
  turned on after the transfer terminal) is the next step. Wi-Fi branch (automatic vs two-tap panel)
  depends on the device-test of the silent path on the user's ColorOS phone.

### 2026-06-08 (later 4) — radio-helper Wi-Fi ladder: Shizuku fallback + panel pop-up
- Added the full Wi-Fi enable ladder to `:radio-helper` (`RadioToggler.setWifiSmart`):
  1. direct `setWifiEnabled` (silent; works after the one-time ADB WRITE_SECURE_SETTINGS grant),
  2. **Shizuku** (silent) — `ShizukuRadio` binds a Shizuku user service (`RadioShellService`, runs as
     shell UID) that execs `svc wifi enable/disable` (fallback `cmd -w wifi set-wifi-enabled`). Chosen
     over binding the hidden `IWifiManager` (version-fragile transaction codes); `svc wifi` is stable.
  3. **Wi-Fi settings panel** (`Settings.Panel.ACTION_WIFI`, API 29+) one-tap pop-up when neither
     silent path is available — there is no one-tap "turn on Wi-Fi" permission dialog (unlike BT's
     ACTION_REQUEST_ENABLE), the panel is the closest equivalent.
- Wiring: Shizuku deps (api+provider 13.1.5), `IRadioShell` AIDL, ShizukuProvider + API_V23 perm +
  uses-sdk overrideLibrary, buildFeatures aidl+buildConfig. SelfTestActivity now runs the ladder and
  shows WRITE_SECURE_SETTINGS-granted + Shizuku status + a Request-Shizuku-permission button.
- `:radio-helper:assembleDebug` OK; APK (~1.6 MB) refreshed at project root. NOT device-tested:
  whether ColorOS honors direct+WRITE_SECURE_SETTINGS or the Shizuku `svc wifi` path. BT stays
  zero-setup. Main-app integration (route Wi-Fi through setWifiSmart; capture/restore radio state
  around the cold-tap transfer) still TODO.

### 2026-06-08 (later 3) — radio-helper Wi-Fi: add WRITE_SECURE_SETTINGS (Tasker method)
- **Device-test (user's OnePlus/ColorOS):** the targetSdk-28 helper toggled **Bluetooth OK** but
  **Wi-Fi did not** flip. Read Tasker Settings' actual source (github.com/joaomgcd/TaskerSettings):
  its `ActionToggleBluetooth` = `BluetoothAdapter.enable()/disable()` (identical to ours → matches
  our working BT) and its `ActionToggleWifi` = `setWifiEnabled` (same as ours) — the only difference
  is it declares **`WRITE_SECURE_SETTINGS`** and targets a very low SDK (21/23). Per Tasker's docs,
  on Android 12+ Wi-Fi toggling needs that permission **granted via ADB**; the low-targetSdk trick
  alone no longer suffices. So fully zero-setup Wi-Fi enable is not achievable even for Tasker — it
  needs a one-time ADB grant; BT stays zero-setup.
- **Change:** `radio-helper` manifest now declares `WRITE_SECURE_SETTINGS` (with `tools:ignore`).
  Grant once on-device:
  `adb shell pm grant dev.superdrop.radiohelper.debug android.permission.WRITE_SECURE_SETTINGS`
  (survives reboot, lost on reinstall), then re-test Wi-Fi in SelfTestActivity. `:radio-helper:
  assembleDebug` OK; APK refreshed at project root. If Wi-Fi still fails on ColorOS after the grant,
  next steps are the Shizuku path (IWifiManager via ShizukuBinderWrapper) or lowering targetSdk to 23.

### 2026-06-08 (later 2) — radio-helper companion APK (targetSdk-28 Wi-Fi/BT toggle)
- **NEW MODULE `:radio-helper`** — standalone companion APK (`dev.superdrop.radiohelper`,
  **targetSdk 28**) that can silently toggle Wi-Fi and Bluetooth, for the NFC-tap "force radios on"
  feature. Rationale (verified verbatim from AOSP docs): the radio-enable APIs are
  **targetSdkVersion-gated, not permission-gated** — `WifiManager.setWifiEnabled()` works for
  targetSdk ≤ 28, `BluetoothAdapter.enable()/disable()` for targetSdk ≤ 32. The main app targets a
  modern SDK so it cannot hold this capability; a separate API-28 APK can (same trick as Tasker
  Settings / MacroDroid Helper). Corrects an earlier wrong assumption that silent toggle needs
  Shizuku/root.
  - `RadioToggler` (the framework calls), `RadioService` (signature-permission-guarded Messenger API:
    MSG_SET_WIFI / MSG_SET_BLUETOOTH / MSG_QUERY for the main app to bind), and `SelfTestActivity`
    (launch the helper alone, tap to toggle, see the call's return value).
  - `:radio-helper:assembleDebug` succeeds → `radio-helper-debug.apk` (~0.8 MB). Only deprecation
    warnings (expected — we deliberately use the legacy APIs).
  - **DEVICE-TEST FIRST (esp. OnePlus/ColorOS):** the AOSP gating is documented, but OEMs may clamp
    it. Install ONLY this APK and use SelfTestActivity to confirm both radios actually flip before the
    main-app integration (bind on cold NFC tap → capture prior state → enable off radio(s) → restore
    after transfer) is built on top. Fallback if the OEM blocks it: ACTION_REQUEST_ENABLE +
    Settings.Panel.ACTION_WIFI, or Shizuku.

### 2026-06-08 (later) — cold NFC tap-to-receive wake
- **FEAT: tapping our idle HCE now wakes the receiver into a discoverable
  window, so a cold "just browsing" phone starts receiving on tap** (matches
  stock Quick Share's behaviour). Root-cause of why native works on a cold
  phone, verified from GMS smali: an idle/visible receiver advertises BLE-only
  (per-mode medium flags `ifkw`: FOREGROUND incl. NFC, BACKGROUND = BLE only)
  and registers NO NFC tag — instead the long-lived `NearbySharingChimeraService`
  registers a wake `PendingIntent` (`djvf.i`), and when the idle HCE is tapped it
  has no tag (`djvf.g`==null) so it calls `djvf.f` → `PendingIntent.send()`,
  launching the receive flow. Android starts a registered `HostApduService` on
  tap even if the process was dead → the one cold-wake path a non-privileged app
  has. Mirrored here:
  - `SuperDropTapHceService.handleAdvertisement`: when idle (`NfcTapLinkHolder`
    null) it now calls `fireReceiveWake()` (startForegroundService with
    `ACTION_NFC_WAKE`) before returning the empty response.
  - `ReceiverForegroundService`: new `ACTION_NFC_WAKE` →
    `armNfcWakeWindow()` forces `MdnsVisibilityOverrideHolder` visible for 60 s
    (so the gate advertises + the sender can connect), then auto-restores —
    preserving a user's pre-existing always-visible state, and not interrupting an
    in-flight transfer. The existing inbound path then posts the **Accept consent
    notification** (no app-UI takeover), as requested.
  - `:app:assembleDebug` succeeds. **NOT device-tested.** Device-test items:
    (1) background foreground-service start from the HCE on the target OEM/API
    (may be refused → would need a full-screen-intent fallback); (2) single-tap
    timing (the first cold tap answers empty + wakes; the sender connects once we
    advertise, or a second tap returns the real tag); (3) AID `F00000FE2C`
    collides with GMS on a phone that has stock Quick Share, so this cold-wake is
    for the non-GMS target / Super Drop↔Super Drop.

### 2026-06-08
- **FIX (NFC tap interop): deym NfcTag PCP corrected 2 → 3 so a stock Google Quick
  Share tap actually registers Super Drop as a peer.** Root cause (verified from GMS
  26.18.33 smali, classes.dex/classes8 disasm): Quick Share's file-transfer session
  uses Strategy `P2P_POINT_TO_POINT`, and the stock receiver's post-tap handler
  `dfdo.run` DISCARDS the tapped tag unless `deym.pcp == dfet.x(localStrategy)`.
  `dfet.x` maps P2P_STAR=1, P2P_CLUSTER=2, P2P_POINT_TO_POINT=3, so the required
  header byte is `(1<<5)|3 = 0x23`. Super Drop's `QuickShareNfcCodec` emitted PCP 2
  (`0x22`, actually P2P_CLUSTER and mislabeled "P2P_STAR") → every native-QS read of
  our HCE failed the Pcp check silently. Changed `PCP_P2P_STAR=2` →
  `PCP_P2P_POINT_TO_POINT=3` (`QuickShareNfcCodec.kt`), updated the `encodeNfcTag`
  default + KDoc, and updated the codec unit tests' golden bytes (`0x22` → `0x23`).
  All 8 `QuickShareNfcCodecTest` cases pass; `:app:assembleDebug` succeeds.
  Also corrected `docs/NFC_INTEROP_BYTEMAP.md` (PCP now VERIFIED = 3, not flagged).
  Mechanism note: native QS NFC tap is UI-driven — the sender's share sheet arms
  `NfcAdapter.enableReaderMode` and rebroadcasts the read tag into GMS as
  `ACTION_TAG_DISCOVERED`; the receiver answers on HCE AID `F00000FE2C`. NFC
  advertising/discovery is gated by a server-pushed phenotype flag (`ifif.aC()`),
  enabled on real devices. NOT device-tested here (no NFC hardware) — byte/Strategy
  facts verified from smali, build + unit tests verified locally.

### 2026-06-06 (later)
- **FIX: "Bridge card style" (consent notification option 2) rendered NOTHING on
  device — root cause found + fixed, verified on a real notification surface.** The
  `notification_consent_bridge.xml` layout used two bare `<View>` elements as
  dividers. RemoteViews (which backs every custom notification layout) rejects the
  base `android.view.View` class — device logcat showed
  `InflateException: Class not allowed to be inflated android.view.View` at line #75,
  which aborts the WHOLE custom layout, so the system silently dropped the
  notification (blank). Option 1 ("Recolored") was unaffected because it only uses
  `LinearLayout`/`TextView`/`Button`. Fix: both `<View>` dividers replaced with
  zero-text `TextView` bars (a RemoteViews-allowed view). Re-tested on redroid A16
  (`:5573`): no inflate error, the dark bridge card now renders (thumbnail + title +
  subtitle + Decline | Accept). Reproduced + verified via a new debug-only harness.
- **DEBUG-only harness `ConsentPreviewActivity`** (`app/src/debug`, action
  `dev.superdrop.debug.CONSENT_PREVIEW`, `--es style bridge|recolored`) posts the
  consent notification using the real layout resources + the same
  `DecoratedCustomViewStyle` + RemoteViews path as production `ConsentNotification`,
  so the custom-layout inflation can be exercised on an emulator (no live transfer
  needed). Not in release builds.

### 2026-06-06
- **NFC tap-to-share (Google Quick Share interop), both directions — compile-only,
  on-device UNVERIFIED.** Implements the Nearby Connections NFC tap path (AID
  `F00000FE2C`) so a tap hands off identity + Wi-Fi-LAN IP:port and the transfer
  runs over the existing mDNS/TCP stack.
  - `core-protocol/.../nfc/QuickShareNfcCodec.kt` — hand-rolled encoders/decoders for
    the `hhww`/`hhwv` protobuf messages, the `deym` NfcTag blob, and the Wi-Fi-LAN
    `rxInstantConnectionAdv` Nearby Data-Element TLV. Byte layout verified from GMS
    26.18.33 smali (`docs/NFC_INTEROP_BYTEMAP.md` §1/§3/§4 — incl. `denp.g()` for the
    IP:port encoding and the `dfdo.run` NfcTag deserializer). 8 round-trip + golden-byte
    tests (`QuickShareNfcCodecTest`) PASS.
  - `core-protocol/.../nfc/NfcTapLinkHolder.kt` — process-global bridge carrying the
    live receiver's {endpointId, serviceIdHash, endpointInfo, ip, port} from the
    receiver service to the HCE.
  - RECEIVER (HCE): `app/.../nfc/SuperDropTapHceService.kt` on AID `F00000FE2C`
    (+ `superdrop_tap_apduservice.xml`, manifest `<service>`). Answers SELECT → 9000
    and ADVERTISEMENT → `hhwv{deym NfcTag + Wi-Fi-LAN rxAdv}`. Liveness gated on
    `NfcTapLinkHolder`; the existing iPhone-link NDEF HCE (`D2760000850101`) is untouched.
  - `service-android` `ReceiverForegroundService` publishes/clears `NfcTapLinkHolder`
    in lock-step with the receiver's mDNS advertise state (same foreground/sheet/
    visibility gating as `MdnsAdvertisementGate`), reading the Wi-Fi LAN IPv4 via
    `ConnectivityManager` and the bound TCP port.
  - SENDER (reader-mode): `app/.../nfc/SuperDropTapReader.kt` + `SendActivity` wiring.
    Reader-mode is enabled while the send sheet is up and the QR panel is closed
    (mutually exclusive with the NDEF HCE on one radio); on tap it transceives
    SELECT + ADVERTISEMENT, parses the peer's tag, and injects a `NearbyPeer`
    (Wi-Fi-LAN route) through the same `onPeerSelected` auto-connect path a tapped
    peer-icon uses.
  - Uses only standard `android.nfc` (HostApduService / NfcAdapter reader-mode /
    IsoDep) at minSdk 24; no toolchain/version bump. `:app:assembleDebug` SUCCESSFUL.
  - FLAGGED best-effort (need on-device validation): rxAdv random NC encryption key
    (parser only checks size/type); PCP↔Strategy int (= 2/P2P_STAR). NO NFC/transfer
    tested — no hardware in the build environment.

### 2026-06-05
- Forked `kyujin-cho/Bada` at upstream HEAD `62d60f3` (release `20260604.02`).
- Renamed git remote `origin` → `upstream`; created working branch `fork/superdrop-ui`.
- Added `FORK_PLAN.md` documenting the two UI redesigns (send = bottom sheet from
  share sheet; receive = Quick Settings tile → bottom-sheet foreground activity),
  the exact tile↔visibility behavior, the planned app identity
  (`dev.peskoff.superdrop`, name "Super Drop"), and the interop-critical wire
  identifiers that must not be renamed.
- Renamed package `dev.bluehouse.bada` → `dev.superdrop` (all 5 modules: source dirs,
  package/import, 3 Gradle namespaces + applicationId + `.debug` suffix, 3 custom-View
  FQCNs in XML, Robolectric shadow ref), `rootProject.name`→`SuperDrop`, `app_name`→"Super Drop".
  Wire identifiers (`_FC9F5ED42C8A._tcp.`, `NearbySharing`, BLE UUIDs) left untouched.
  Verified: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 17), app-debug.apk produced.
- Protocol/transport stack untouched.
- **Phase 1 — SEND share-sheet redesigned into an OShare-style bottom sheet.**
  - Ported 3 OShare UI primitives from shareit-bridge (Java) to Kotlin under
    `dev.superdrop.ui.sheet`: `DraggableSheetLayout` (slide-up OvershootInterpolator
    entrance, drag-down-to-dismiss, `animateGrow`, `applyBottomInset`),
    `RingProgressView` (blue disc + glyph + progress arc + green-check complete),
    `DeviceIconView` (circular icon + name + status + tap bounce). Programmatic
    custom Views, no XML.
  - New translucent theme `Theme.SuperDrop.SendSheet` (parent `Theme.Bada`):
    `windowIsTranslucent`, transparent `windowBackground`, `windowContentOverlay=@null`,
    `windowNoTitle`, `backgroundDimEnabled` + `backgroundDimAmount=0.2`, slide-up/down
    window animation (`WindowAnimation.SuperDrop.SendSheet` → new `@anim/slide_up_in`
    / `@anim/slide_down_out`). Applied to `.send.SendActivity` in the manifest.
  - `activity_send.xml` + `layout-land/activity_send.xml` restructured: root is now a
    transparent `FrameLayout` scrim (`send_sheet_scrim` → tap dismisses) hosting a
    bottom-anchored `DraggableSheetLayout` (`send_sheet`, rounded 28dp `send_sheet_background`
    surface). Old toolbar removed; the device-name pill (`send_device_pill` /
    `send_device_pill_text`) relocated into the sheet header. Every binding id
    preserved (QR panel, PIN, status panel, progress, cancel/done, show-qr).
  - Peers now render as a HORIZONTAL row of circular `DeviceIconView` icons inside a
    `HorizontalScrollView` (was vertical `item_peer_row` rows), deduped by display name;
    tap routes through the unchanged `onPeerSelected` path. Discovery /
    OutboundConnection / QR logic untouched — view layer + presentation only.
  - `SendActivity` gained `wireBottomSheet()` (scrim tap → finish, sheet dismiss →
    finish, bottom-inset, entrance) and a `DraggableSheetLayout` import; new dimen
    `send_sheet_base_bottom_padding`.
  - Verified: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 17). UI not yet exercised on a
    device — compile-verified only.
- **Phase 2 — RECEIVE consent surface redesigned into an OShare-style bottom sheet, opened
  by the Quick Settings tile with a temporary visibility bump.**
  - Ported `RoundedProgressBar` (shareit-bridge Java → Kotlin) to
    `dev.superdrop.ui.sheet.RoundedProgressBar`: pill track + animated blue fill
    (`ValueAnimator`, 180 ms), `setProgress(percent)`, ≥1-dot minimum so 0% reads as
    "started". `AttributeSet` ctor so it can be inflated from XML.
  - New translucent theme `Theme.SuperDrop.ReceiveSheet` (parent `Theme.Bada`,
    mirrors `SendSheet`): `windowIsTranslucent`, transparent `windowBackground`,
    `windowContentOverlay=@null`, `windowNoTitle`, `backgroundDimEnabled` +
    `backgroundDimAmount=0.2`, reuses `WindowAnimation.SuperDrop.SendSheet` slide-up/down.
    Applied to `.consent.ConsentTrampolineActivity` in the manifest, replacing the centered
    `Theme.Bada.ConsentDialog`.
  - `activity_consent_trampoline.xml` rebuilt: root is a transparent `FrameLayout`
    (`consent_sheet_root`) + tap scrim (`consent_sheet_scrim` → dismiss) hosting a
    bottom-anchored `DraggableSheetLayout` (`consent_sheet`, rounded `send_sheet_background`
    surface) wrapping the fixed-480dp inner content frame (`consent_root`, the transition
    scene root). Every consent panel id is preserved verbatim (consent/receiving/completed/
    failed) so the activity's `findViewById` state machine, `submitUserConsent` broadcast
    path, `InboundConnection.state` observation, `onNewIntent`, `setShowWhenLocked`/
    `setTurnScreenOn`, and `ConsentModalRegistry` dismiss hook are untouched. Receiving
    progress switched from `CircularProgressIndicator` → `RoundedProgressBar`.
  - `ConsentTrampolineActivity`: added `wireBottomSheet()` (scrim tap → finish, drag-down
    dismiss, bottom inset, overshoot entrance); dropped the old popup fade transitions (the
    theme's window slide handles enter/exit now). Added a **WAITING** mode — when launched
    by the tile with `ConsentIntents.ACTION_OPEN_RECEIVE_SHEET` and no pending consent entry,
    it shows a "Ready to receive / waiting for sender" panel (`consent_waiting_panel`,
    new strings `consent_state_waiting_title` / `_body`) instead of finishing. `renderEntry`
    swaps waiting → consent in place. `renderProgress` updated for `RoundedProgressBar`.
  - Foreground incoming-consent routing into the open waiting sheet needed NO coordinator
    change: the open sheet is itself a foregrounded Bada activity, so
    `AppForegroundState.isForeground` is true and the existing
    `ConsentCoordinator.raiseConsentSurface` → `Sink.launchModal` →
    `launchConsentTrampolineAsModal` path re-launches the `singleTop` activity with the new
    connection id, delivered via `onNewIntent`.
  - **QS tile (`BadaQuickShareTileService`) replaced the persistent on/off toggle** with a
    momentary capture→bump→open→restore flow: (a) capture `MdnsVisibilityOverrideHolder.isActive`;
    (b) if below visible, set override on + `ReceiverForegroundService.start` (recorded in the
    new `TileVisibilityElevationHolder`); (c) `startActivityAndCollapse` into the receive sheet
    in waiting mode (`ACTION_OPEN_RECEIVE_SHEET` + `EXTRA_TILE_ELEVATED`); (d) the activity's
    `finish()` calls `TileVisibilityElevationHolder.restoreIfArmed` to set the override back and
    stop the service it started. If visibility was ALREADY active, nothing is bumped or restored.
    FGS-start / activity-launch failures roll the bump back. Permission gate unchanged.
  - New `dev.superdrop.service.receiver.TileVisibilityElevationHolder` (in-memory one-shot
    record of the prior visibility/service state; `arm`/`restoreIfArmed`/`disarm`). Cleared on
    `ReceiverForegroundService.stopReceiverAndExit` so a stale armed flag can't later stop a
    service the user controls by other means. Best-effort across process death (in-memory only,
    matching the override holder).
  - New intent constants `ConsentIntents.ACTION_OPEN_RECEIVE_SHEET` / `EXTRA_TILE_ELEVATED`.
  - Protocol/transport, `ConsentNotification.kt` (incoming heads-up), `:core-protocol`, wire
    constants, and discovery networking all untouched.
  - Verified: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 17); `:service-android` consent unit
    tests pass. UI is compile-only — NOT click-tested on a device.
- **Phase 3 — RECEIVE completion notification with an "Open" action (the missing third
  notification in the consent / progress / completion trio).**
  - New `dev.superdrop.service.receiver.progress.TransferCompleteNotification` (object),
    mirroring `TransferProgressNotification` / `ConsentNotification`: own channel
    `transfer_complete` (IMPORTANCE_HIGH, sound + vibration — the user wants a heads-up on
    completion, unlike the quiet progress channel); disjoint stable notification-id base
    `0x436F_5663` ("CoVc"); `setAutoCancel(true)` + `CATEGORY_STATUS`. Title
    "Received N file(s) from <sender>" (sender name reused from the same consent/progress
    metadata). `ensureChannel` / `post` / `dismiss` / `build` surface.
  - **Open action + body tap**: for a single received FILE whose `content://` Uri resolves
    from `MediaStore.Downloads` by display name → `ACTION_VIEW` on that Uri with
    `FLAG_GRANT_READ_URI_PERMISSION` (mirrors `ConsentTrampolineActivity.findReceivedImageUri`
    but against the Downloads collection, where received files land via
    `MediaStoreDownloadsEnvironment`); for multiple files, an unresolvable single Uri
    (collision-suffixed commit name, un-indexed row, user-chosen SAF tree), or pre-API-29 →
    falls back to the system Downloads view (`DownloadManager.ACTION_VIEW_DOWNLOADS`). Uri
    resolution is best-effort (a query miss / `SecurityException` → Downloads view), so the
    action is always tappable.
  - Wired into the existing terminal path: `TransferProgressCoordinator.Sink` gained a
    `postComplete(connectionId, sourceDeviceName, items)` method, called from the `Completed`
    branch of `observeOneConnection` (which already dismisses the progress card on the same
    transition — so the completion heads-up effectively *replaces* the progress card; the two
    use disjoint ids). Production `Sink` in `ReceiverForegroundService.startProgressCoordinator`
    routes it to `TransferCompleteNotification.post`; the `transfer_complete` channel is
    ensured in `onCreate` alongside the other channels. Not posted on Failed/Cancelled/Rejected.
  - Foreground case unchanged: `ConsentTrampolineActivity` still shows the inline completion +
    View-image panel; the notification posts in addition (acceptable per the design).
  - `ConsentNotification.kt`, `:core-protocol`, the protocol/wire code, and the MediaStore
    factory were NOT changed (the factory's committed-row Uri is re-resolved by display-name
    query rather than threading a Uri back through `ReceivedItem`).
  - New strings in `:service-android` `strings.xml`: `transfer_complete_channel_name` /
    `_description`, `transfer_complete_title_with_name` / `_unknown_sender`,
    `transfer_complete_action_open`.
  - Tests: `RecordingSink` extended with `postComplete`; two new
    `TransferProgressCoordinatorTest` cases (posts completion with sender name on `Completed`;
    does NOT post on `Failed`). All `TransferProgressCoordinatorTest` cases pass.
  - Verified: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 17); `:service-android`
    `compileDebugUnitTestKotlin` + `TransferProgressCoordinatorTest` pass. Compile-only — the
    notification + Open-action click path is NOT device click-tested.

### 2026-06-05 — All 4 UI phases complete + on-device UI check
- Phase 1 send bottom sheet (cad8d21), Phase 2 receive sheet + tile (ee2fc13), Phase 4 NFC link
  broadcast (merged d3f1bea), Phase 3 completion notification (14cfd3b). Final assembleDebug green.
- On-device (redroid A16 x86_64, :5573) UI-verified by screenshot: app renders as "Super Drop"; SEND
  opens as a bottom sheet over the dimmed home with the picker + QR button; in-sheet QR shows the live
  pairing link; RECEIVE waiting sheet ("Ready to receive") renders via the tile action.
- UNVERIFIED (need 2 physical phones / NFC hardware): populated peer icons, real consent/PIN/progress/
  completion+Open flow, completion notification, tile visibility bump/restore, NFC tap→Safari, and any
  real Quick Share interop transfer (redroid lacks real Wi-Fi/BLE).

### 2026-06-06 — Battery: one-tap exemption popup
- Settings "Background activity" button now fires the system ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS one-tap "Allow" popup (MainActivity.requestIgnoreBatteryOptimizations), with auto-fallback to the settings list on OEMs that strip/no-op it. Button relabeled "Turn off battery optimization". REQUEST_IGNORE_BATTERY_OPTIMIZATIONS perm was already declared.

### 2026-06-06 — Real-device bug fixes (4)
- #4/#5 tile: ConsentTrampolineActivity taskAffinity="" (own floating task, not the app) + restore on onDestroy + onUserLeaveHint (leaving restores the visibility bump).
- #2 battery: escalate to App Info when the one-tap popup no-ops on OEM ROMs (OnePlus/vivo).
- #3 device name: never show the raw IP/stableId; hidden peers show a generic label.

### 2026-06-06 — Custom consent notification (#1)
- Recolored Accept/Decline on OPPOSITE sides via custom RemoteViews (DecoratedCustomViewStyle). Decline left (light/red), Accept right (blue). Replaces the side-by-side action row. Render device-unverified (needs a real incoming transfer).

### 2026-06-06 — NFC tap-to-share setting (dedicated, separate)
- New Settings card "NFC tap to share" with a 3-way selector (Only while a receive sheet is open [default] / While the app is open / Always in background), backed by NfcTapSharePreferences. Separate from the visible toggle, per request. Renders verified on redroid; the receiver HCE wires to it when tap-to-share lands.

### 2026-06-06 — Live Updates groundwork (blocked on toolchain)
- Added POST_PROMOTED_NOTIFICATIONS manifest permission; the progress notification already meets the promotion shape. Actual Live Update (status-bar chip / OEM island via setRequestPromotedOngoing) is BLOCKED: the compat API needs androidx.core >= 1.17.0 which requires AGP >= 8.9.1 (project on 8.7.3), and android-36 rev2 lacks the platform method. TODO left in TransferProgressNotification.kt. Per-OEM: Pixel Live Updates + Xiaomi HyperOS 3.1 Hyper Island consume the standard API; OnePlus Live Alerts is currently allowlist-limited.
