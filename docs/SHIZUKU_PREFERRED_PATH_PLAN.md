# Shizuku-Preferred Radio Path — Plan / Journal

## CURRENT STATE / NEXT STEP
- **Goal (clarified by user 2026-07-03):** Shizuku and the helper do the SAME job (privileged
  radio toggling with no per-app ADB). So when Shizuku is present the helper is redundant —
  **PORT THE HELPER'S ENTIRE MECHANISM INTO THE MAIN APP, with Shizuku swapping in for the
  self-ADB privileged rung.** "Everything done exactly as it was set up, just Shizuku replacing
  ADB." One app to install. The **radio-helper APK stays only as the fallback** for users
  without Shizuku (or who prefer the helper). Choice made **ONCE per share session**, cached.
- **State:** PHASE 1 COMPILE-GREEN (`:service-android:assembleDebug` = BUILD SUCCESSFUL, 2026-07-03).
  Files below. Runtime/on-device UNVERIFIED (nothing binds it yet). Helper route untouched.
- **PHASE 1 FILES (2026-07-03):** NEW `service-android/src/main/aidl/dev/superdrop/service/radio/
  IRadioShell.aidl` (Wi-Fi + Bluetooth) + NEW `service-android/.../kotlin/dev/superdrop/service/
  radio/RadioShellService.kt` (shell-UID user service, copy of helper's + BT with observable
  `cmd bluetooth_manager` → `svc bluetooth` chain). EDITED `service-android/build.gradle.kts`
  (buildFeatures aidl+buildConfig; Shizuku api+provider 13.1.5) + `service-android/src/main/
  AndroidManifest.xml` (uses-sdk overrideLibrary, API_V23 perm, ShizukuProvider). No runtime
  wiring yet; nothing calls these — proven only once it builds green.
- **NEXT STEP:** confirm `:service-android:assembleDebug` is GREEN (build in progress). Then Phase 1
  is compile-done → Phase 2 (in-app Shizuku wrapper `ShizukuRadio` + copied toggler/session/
  service + AIDL with Bluetooth) → Phase 2 (copied in-app session: toggler + session + watchdog +
  boot recovery) → Phase 3 (single cached `useShizuku` trigger + branch in ShareRadioController;
  helper path untouched) → Phase 4 (settings toggle + manifest/gradle). ONE honest residual to accept: boot
  recovery via Shizuku can only run once Shizuku is up post-boot (see Residual below).

## VERIFIED FACTS (read this session, 2026-07-03)
- **Single funnel:** every app radio call goes through `ShareRadioController`
  (`service-android/src/main/kotlin/dev/superdrop/service/radio/ShareRadioController.kt`).
  Call sites: `SendActivity`, `SendActivityInApp`, `ReceiverForegroundService`,
  `NameCardExchangeService`, `NameCardTransferActivity`. → the natural once-per-session
  decision point is `ShareRadioController.requestRadiosOn()`.
- `ShareRadioController` creates a `RadioHelperClient`, calls `connect → prepareForShare →`
  (heartbeat ticker every 5s) `→ transferFinished → disconnect`.
- **Helper already has Shizuku** as **rung 3** of its Wi-Fi ladder
  (`RadioToggler.runWifiLadder`: direct `setWifiEnabled` → self-ADB → **Shizuku** → panel).
  Shizuku impl = `ShizukuRadio.kt` + user-service `RadioShellService` (shell UID) + AIDL
  `IRadioShell.aidl`, all in the **radio-helper module only**.
- **Shizuku user service does Wi-Fi ONLY** (`svc wifi enable` / `cmd -w wifi set-wifi-enabled`).
  No Bluetooth method exists in `IRadioShell`/`RadioShellService`.
- **Helper session logic** (capture original / enable-only-off / restore-only-ours /
  SharedPreferences persistence / AlarmManager watchdog / boot recovery) lives in
  `ShareRadioSession.kt` (helper-side, by design — "app is dumb").
- **Bluetooth toggle:** helper uses `BluetoothAdapter.enable()` which works only because the
  helper targets **API 28** (BT enable allowed targetSdk≤32). The **main app targets SDK 36**
  → `BluetoothAdapter.enable()` NO-OPS in-app. ∴ a direct in-app Shizuku BT toggle MUST go
  through a shell command, not the adapter.
- **Deps:** `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5` are in the **helper module
  only**; app/service-android have NO Shizuku dep. SDK: minSdk 24, app targetSdk/compileSdk 36.
- **Signing:** app + helper both signed by the same release signingConfig (shared key) — the
  `BIND_RADIO` signature permission depends on this; unchanged by this work.

## RULE TENSIONS TO SURFACE (must tell the user)
1. **No-manual-setup-per-restart rule names Shizuku as the banned example** (open app → enable
   wireless ADB → tap Start after every reboot). Reconciliation: here Shizuku is **opportunistic
   / preferred only**, and the **self-starting radio-helper remains the guaranteed baseline**, so
   the feature NEVER requires a per-boot manual step to *function* (Shizuku down → helper path).
   Compliant **only as long as the helper stays the always-available fallback** — Shizuku must
   never be the sole route.
2. **Crash/boot restore backstop:** the helper's robustness came from helper-side persistence +
   AlarmManager watchdog + boot recovery + **self-start on boot**. Shizuku **cannot self-start
   after a reboot**. So if the app is killed mid-transfer and the radios need restoring after a
   reboot with Shizuku down, an in-app Shizuku watchdog can't toggle. → recommend routing the
   crash/boot RESTORE backstop through the self-starting helper even when the live toggle path
   was Shizuku (a deliberate small seam between the two "separate" paths). [DECISION FORK #2]

## DESIGN (revised per user 2026-07-03 — TWO COPIED PATHS, one trigger; NOT an interface)
Implementation strategy chosen by the user: **copy, don't abstract.** Rather than a `RadioProvider`
interface with two implementations, keep the proven helper path byte-for-byte UNTOUCHED and add a
second, fully separate copied in-app path. The two share NO code except the single trigger — so a
change to one can never regress the other, and the device-proven helper path carries zero refactor
risk. Tradeoff: duplication can drift → both paths are clearly labeled and cross-referenced in
comments so a future edit to one flags the other.

The single trigger lives INSIDE `ShareRadioController.requestRadiosOn()`, so none of the 5 call
sites change. The check happens once ("beginning of the task/transfer"), cached until `restoreRadios`:

    useShizuku = prefsWantShizuku && ShizukuGate.isAvailable(ctx)   // the ONE check, cached
    if (useShizuku)  shizukuPath.prepare(...)         // NEW copied in-app path (Path B)
    else             <existing helper logic UNCHANGED> // Path A
    // restoreRadios() and the heartbeat ticker branch on the SAME cached useShizuku

### SINGLE-GATE RULE (user-emphatic 2026-07-03) — no per-flip probing
- The **only** Shizuku availability check + the **only** Shizuku→helper fallback decision happen
  ONCE, at session start (`requestRadiosOn`). The result is cached on the controller instance for
  the whole session and re-evaluated only on the NEXT session's `requestRadiosOn`.
- **Every switch-flip inside a session** (Wi-Fi on, BT on, heartbeat, Wi-Fi off, BT off, restore)
  goes to the ALREADY-chosen provider. There is NO "try Shizuku, catch, retry via helper" on each
  flip. If Shizuku was chosen and one `svc wifi`/`svc bluetooth` call fails, we do NOT bounce to
  the helper mid-session — it fails/logs and at most drops to the in-app ladder's own last rung
  (the settings panel), identical to how the helper handles an internal rung failure.
- Rationale: the two paths stay entirely separate; the decision is made once and honoured for the
  session, so the failure mode is predictable and there is no per-flip cross-path thrash.
- (No separate app-open probe for the DECISION — evaluating at session start is strictly fresher,
  since Shizuku could be started between app-open and the transfer. An app-open ping is used ONLY
  to show status in the settings UI, never to bind the session.)

- **HelperRadioProvider** — thin wrapper over the EXISTING `RadioHelperClient` → radio-helper
  APK. Behavior 100% unchanged; this is the fallback when Shizuku is absent / user prefers it.
- **InAppRadioProvider** — the PORT: does everything the helper did, IN the main app, with
  Shizuku as the privileged executor. When this path is chosen the helper APK is not touched
  and need not even be installed.

### The in-app port (only active when Shizuku present) — faithful copy of the helper's parts
1. **App-owned Shizuku user service** — Shizuku binds a UserService in the CALLER's package, so
   the app needs its OWN `IRadioShell.aidl` + `RadioShellService` (in `service-android`, merged
   into the app APK). EXTENDED beyond the helper's Wi-Fi-only version with
   `setBluetoothEnabled` / `getBluetoothState` (BT via shell — see item 2).
2. **Bluetooth via shell** [VERIFY on-device — make-or-break for "both radios"]: the app's own
   `BluetoothAdapter.enable()` no-ops at targetSdk 36, so BT MUST go through the Shizuku shell.
   Candidate commands: `svc bluetooth enable|disable` (older AOSP) vs `cmd bluetooth_manager
   enable` (newer). Build it observable (log which worked); exact command is device-verified only.
3. **In-app toggle ladder** = port of `RadioToggler`, privileged rung = **Shizuku** (replacing
   the helper's self-ADB rung): `direct setWifiEnabled → Shizuku(shell) → panel` for Wi-Fi;
   `Shizuku(shell)` for BT.
4. **In-app session** = port of `ShareRadioSession`: capture original → enable only what's off →
   restore only what we turned on, SharedPreferences persistence (survives process kill),
   AlarmManager watchdog (restore if `finish` never arrives), + **BootReceiver** boot recovery.
   FULLY SELF-CONTAINED: when this path runs, the helper APK is never bound and need not be
   installed (user's point: Super Drop + Shizuku = 2 apps, not + helper = 3).
   - **HEARTBEAT ported CORRECTLY, not cargo-culted:** the helper heartbeat existed only because
     the helper was a SEPARATE process that couldn't see the app's liveness (app→helper ping →
     helper re-arms its restore). In-app, app and "helper" are the SAME process, so there is no
     IPC to ping. The port keeps the SAME crash-safety semantics — the existing 5s ticker in
     `ShareRadioController` re-arms the in-app AlarmManager restore (`scheduleRestoreIn(~20s)`)
     each beat, so if the process is killed mid-transfer the alarm restores ~20s after the last
     beat; a live transfer keeps re-arming so it's never cut. Just the app→itself IPC hop is
     dropped (it would be a no-op ping to the same process).
5. **Single cached trigger** in `ShareRadioController` (`useShizuku` decided once in
   `requestRadiosOn`, read again in `restoreRadios` + the heartbeat ticker). Path A = existing
   helper body UNCHANGED; Path B = the copied in-app session object. No shared interface — just
   the branch. The 5 call sites are untouched.
   - **HELPER ROUTE = ZERO LOGIC CHANGES (user-emphatic 2026-07-03):** the only edit to
     `ShareRadioController` is a 2-line early-return guard PREPENDED to `requestRadiosOn()` and
     `restoreRadios()` — `if (useShizuku) { shizukuPath.…; return }`. Every existing helper
     statement below stays byte-for-byte identical; when Shizuku isn't chosen, control falls
     straight through to today's code. Literal 0-file change would require editing all 5 call
     sites instead (worse) — one tiny guard in one file is the minimal-risk seam.

### Gradle + manifest (app / service-android)
Add `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5`, the `ShizukuProvider` `<provider>`, the
`moe.shizuku.manager.permission.API_V23` permission, AIDL sourceset. First-run: request Shizuku
permission via `Shizuku.requestPermission()` (one-time grant — allowed under the no-per-boot rule).

### User preference (from "mainly if the user wanted Shizuku, or if they didn't have Shizuku")
A real setting in the app's settings UI (not hidden), e.g. **"Use Shizuku (no helper app
needed)"**, default ON when Shizuku is detected. OFF → always use the helper path. Added via the
settings UI, not by hand-editing prefs (UI-first rule).

## RESIDUAL / HONEST GAP (the ONE thing that can't be "exactly as ADB")
The helper's self-ADB path **self-starts on boot** (libadb autoConnect, no user action), so its
boot recovery can silently restore radios after a reboot. **Shizuku canNOT self-start after a
reboot** — its service is down until the user (or an auto-start allowance) brings it back. So the
in-app BootReceiver can only complete a silent restore once Shizuku is up again. Mitigation:
attempt on boot; if Shizuku is down, re-attempt the stale-session restore on next app launch
(and when Shizuku's binder becomes available). This is inherent to Shizuku, not a design choice.

## DECISION FORKS — RESOLVED (user 2026-07-03)
1. "Helper harness" → **port the helper wholesale into the main app**, Shizuku replacing ADB;
   helper APK becomes the no-Shizuku fallback. (NOT a separate test app.)
2. Restore backstop → **replicate the helper's mechanism in-app exactly (persistence + watchdog
   + boot recovery), Shizuku as executor.** Accept the boot-recovery residual above.

## LOG
- 2026-07-03: Research pass. Read ShareRadioController, RadioHelperClient(call sites), RadioToggler,
  ShizukuRadio, RadioShellService, IRadioShell.aidl, ShareRadioSession, gradle/manifest. Facts above.
- 2026-07-03: User clarified intent → full in-app port (Shizuku replaces ADB), helper = fallback,
  add user preference toggle. Forks resolved. Design section rewritten accordingly.
