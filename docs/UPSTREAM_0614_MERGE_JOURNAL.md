# Upstream sync: Bada v20260604.02 → v20260614.01 into Super Drop fork

## CURRENT STATE / NEXT STEP
- **Goal:** Merge upstream `kyujin-cho/Bada` changes from our fork base **v20260604.02** up to upstream's
  latest **v20260614.01** into the Super Drop fork (`dev.superdrop`), without destroying fork features.
  Push to our GitHub (`origin` = IvanChanPing/Bada).
- **Scope decision (user, 2026-06-22):** FULL catch-up incl. the "gap" (0604→0610), not just the linked
  0610→0614 compare. User: "we're just merging the updates that they added in their latest version" +
  "Make sure that adding the gap files doesn't destroy any of the features we added." The gap contains
  PRs #215/#218 (off-LAN BT RFCOMM + BLE offer-wait) which SUPERDROP-CHANGES.txt D1 flagged as the fork's
  top missing fix — so the gap is wanted.
- **DONE this session:** All 60 changed files reconciled on branch `superdrop-pr/upstream-0614-merge`
  (off `fork/superdrop-ui`). 18 new files created (translated `dev.bluehouse.bada`→`dev.superdrop`),
  34 clean 3-way merges, 7 conflicts resolved, version bumped to 20260614.01.
- **IN PROGRESS:** `./gradlew :app:compileDebugKotlin` (log /tmp/bada_build.log) to catch merge artifacts.
  Already found+fixed one: duplicate `override fun onResume()` in SendActivity.
- **NEXT:** (1) Confirm compile clean; fix any remaining artifacts. (2) Run unit tests
  (EmptyPeerRadioHintTest, core-protocol OutboundConnectionTest). (3) Decide update-check repo target
  (see RISK). (4) Commit, update SUPERDROP-CHANGES.txt + CHANGELOG. (5) Push to origin via residential
  proxy (GitHub-only egress rule).

## METHOD (verified)
- Fork = upstream **@v20260604.02** (`62d60f3`, true merge-base) + 149 Super Drop commits. Package
  uniformly renamed `dev.bluehouse.bada`→`dev.superdrop` (two segments collapse to one).
- Per-file 3-way merge: BASE = `v20260604.02:<path>` (translated), THEIRS = `v20260614.01:<path>`
  (translated), OURS = fork current. New files: `git show v20260614.01:<path> | sed rename`.
- `merge-file` "CLEAN" only means no overlapping lines — it can still produce duplicate symbols (the
  onResume bug). Compile is the definitive check.

## CONFLICTS RESOLVED (7)
1. `values/strings.xml` — keep both (fork NFC-diag strings + upstream transfer-expert strings).
2. `SendActivity.kt` — keep both (fork `isNfcTapPeer`/`runTapConnectWithGrace` + upstream
   `attemptRouteOutcome`/`retryLanAfterReresolve` #203). Verified #203 is CALLED at the normal route loop.
   FIXED duplicate `onResume()` (merged the #209 onRadioStateChanged call into the fork's existing onResume).
3. `ConsentTrampolineActivity.kt` — keep both ×3 (imports, imports, onDestroy: TileVisibility restore +
   setTransferKeepScreenOn). KEEP-OURS for the progress widget line: fork uses OShare `RoundedProgressBar`
   (only `setProgress(pct)`; upstream's `setProgressCompat`/`isIndeterminate` would NOT compile).
4. `SendPeerPickerController.kt` — conflict#1 keep both (`resolvedPeers()` + `reresolveLan()` helpers);
   conflict#2 take upstream #209 radio-hint logic but rewrite `binding.sendEmptyState`→`emptyState`
   (the controller scope has the `emptyState` field, not `binding`).
5. `SettingsFragment.kt` — surgical: restored fork original, added 2 imports + keep-screen-on/expert
   switches after bugReport block + `refreshTransferSwitches()` parity in onStart.
6. `activity_consent_trampoline.xml` — surgical insert of `consent_expert_details` TextView after
   `consent_receiving_progress_text` (kept fork structure, not upstream's restructure).
7. `activity_send.xml` — surgical insert of `send_expert_details` TextView after the status-circle
   FrameLayout (kept fork structure).

## FEATURES MERGED IN
- **Gap (0604→0610):** off-LAN BT RFCOMM bootstrap (#214/#215), BLE offer-wait shorten (#216/#218),
  LAN re-resolve-on-failure retry (#203/#204), GitHub-releases update check (#211), QS-tile long-press
  + 1.2× glyph (#207/#210). New: `send/LanReresolvePolicy.kt`, `send/SendBootstrapPlan.kt` (already
  existed? see report), `update/` package, drawables, menu, colors.
- **Tail (0610→0614):** keep-screen-on transfer setting (#219/#221), expert transfer view (speed/ETA/
  medium/Wi-Fi band) + Wi-Fi-frequency plumbing, contextual empty-peer radio hint (#209/#224).
  New: `transfer/KeepScreenOnPreferences.kt`, `transfer/TransferExpertDetailsFormatter.kt`,
  `transfer/TransferExpertViewPreferences.kt`, `send/EmptyPeerRadioHint.kt`, `send/RadioStateReader.kt`,
  `protocol/connection/UpgradePathCredentialMetadata.kt`, + tests.

## RISKS / OPEN ITEMS
- **Update-check repo target (DECISION NEEDED):** `update/UpdateChecker.kt` `RELEASES_LATEST_URL` =
  `api.github.com/repos/kyujin-cho/Bada/releases/latest`. For the dev.superdrop fork this checks
  UPSTREAM releases and would offer the upstream (dev.bluehouse.bada) APK. Options: point at
  IvanChanPing/Bada, leave as-is, or drop the feature. Left AS-IS (faithful merge) pending user choice.
- **Device-UNVERIFIED:** compile-only here (no radios/NFC in build env). All new transfer UI + the
  off-LAN/LAN-reresolve fixes need on-device confirmation.
- **MainActivity / AndroidManifest auto-merged CLEAN** — reviewed for duplicate symbols (none beyond
  onResume). Compile confirms wiring.
