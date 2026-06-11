# Diagnosis & Fix Plan — Super Drop SENDER → tap → NATIVE Google Quick Share RECEIVER

> **THIS FILE IS THE TASK JOURNAL** (per the task-journal rule). Update the block below after every
> turn; append a dated entry on every discovery. A fresh session should resume from here + the memory index.

## CURRENT STATE / NEXT STEP   (updated 2026-06-11)
- **⛔ SCOPE (unchanged, user, emphatic):** the Super Drop app ALREADY worked perfectly with Quick Share —
  DO NOT rewrite it or change the working transfer. The ONLY thing in scope was **the NFC tap initiating a
  share**; on a tap the NFC layer feeds the target INTO the existing working send flow, never changes it.
- **✅ SOLVED + USER-VERIFIED ON DEVICE:** the NFC tap now initiates the Quick Share transfer. Fix shipped =
  the tap is a **one-shot WAKE** (not a 2.5s blocking re-poll): SELECT + one ADVERTISEMENT; an empty `0000`
  answer is treated as "the idle receiver was woken", which opens a short window and hands off to the
  already-running Wi-Fi discovery → auto-connect into the normal transfer (`SuperDropTapReader.TapResult`
  → `SendActivity.onNfcTapWake`/`onNfcTapWakePeersResolved`). Re-arms each tap (fixed "second tap did
  nothing"). Commits `175ca2e` (fix) … `fc224f8`; replicates stock QS, whose reader `djkb.c` sends ADV once
  and relies on discovery, and whose wake `djvf.f` only opens the receiver.
- **VERDICT — receiver Accept dialog is a stock-QS ceiling (user chose to leave it):** QS-to-QS starts with
  NO prompt only via **self-share auto-accept** = both devices on the SAME signed-in Google account
  (account-bound trust check; not forgeable cross-account). For a different account the receiver taps Accept
  once. Not a bug in our tap.
- **Diagnostics (shipped this session):** auto-upload made RELIABLE — the share Wi-Fi has no internet, so the
  POST is QUEUED and flushed when a validated-internet network returns / over cellular
  (`DiagnosticUploader` queue + `registerNetworkCallback`). Settings toggle "Show NFC tap diagnostics" (default
  on) gates the on-screen Toasts. `SendActivity.onPause` ships `reason=send-leave` so a tap that never read
  still uploads its ring.
- **Bluetooth pair request during a tap — NOT from our connect (verified):** every socket our connection opens
  is insecure (no pairing); BluetoothClassic route is unused. Source is outside our path (OEM NFC→BT handover /
  the other phone's bootstrap / a QS phone). UNPINNED — to pin, need: which phone shows it + the device name.
- **PR breakdown updated (2026-06-11, commits `b14103c` → `6d2bbc8`):** `SUPERDROP-CHANGES.txt` item #3
  "Tap to share" rewritten to describe ONLY the finished working feature (user: don't show the
  non-working version + a fix on top). Correct mechanisms folded into Technical as plain design; the
  broken→fixed findings + the diagnostics-collector + Bluetooth-pair-request bullets were DROPPED from
  the PR copy (they live here + in BUGS.md). This file IS the "text file of pull requests" the user keeps
  PR copy in (one PR per item). CHANGELOG entries added.
- **PR breakdown #4 reframed (2026-06-11, commit `ba070c2`):** item #4 now centers on "the QR code button
  also turns your phone into an NFC tag for iPhones" (per user, its own separate PR). Grounded:
  `SendActivity.onShowQrClicked()` line 1739 sets `NfcLinkHolder.currentUrl`; `SuperDropNdefApduService`
  (AID D2760000850101) serves it; opening the QR drops tap reader-mode (one radio). User confirmed #5
  stays as just the recolored style (other 2 styles not working). OPEN (await user): whether to fold the
  peer-name-not-IP fix (096673f — nameless/stock peers show "Quick Share device" not a raw IP) into #1,
  and whether to add a debug-only developer-diagnostics note (auto-upload + toggle currently excluded as
  debug scaffolding).
- **NEXT STEP (open, low-priority):** (1) the tap fix worked but was reported intermittent once — if it
  recurs, read the auto-uploaded trace (`reason=nfc-send-tap`/`send-leave`) to see which step stalls (SELECT /
  ADV / discovery / connect). (2) Pin the BT-pair-request source when the user can say which phone + name.
  (3) Outward-facing: there is NO GitHub PR/fork yet (gh=IvanChanPing, 0 repos; only remote `upstream`=
  kyujin-cho/Bada). Awaiting the user's chosen push target before any push.
- **KEY PATHS:** journal=this file · trace=`/root/nfc-diag/collector.log` · GMS smali=`/root/nfc-diag/gms-smali`
  · djvf=`/root/nfc-diag/gms-smali/classes/djvf.smali` · reader=`app/.../nfc/SuperDropTapReader.kt`
  · collector=`/root/nfc-diag/collector.py` (127.0.0.1:7911) · app=`/root/agent-work/projects/bada-fork`.

## DECISIVE 2026-06-10 — THE NFC TAG IS THE WRONG MEDIUM FOR THIS GOAL; real path = BLE FastInitiation + Wi-Fi-LAN
- VERIFIED (gms-smali): the wake re-sends the FastInit HUN PendingIntent (`SafePendingIntentChimeraBroadcastReceiver.onHandleIntent`) — it does NOT call startDiscovery. The NFC tag (`djvf.h`) is registered ONLY from the Nearby `StartDiscoveryParams` path (`dfad`/`depr`/`dfet.G`). ⇒ an idle / receiving / merely-visible QS phone NEVER exposes a readable NFC tag and the tap's wake cannot make it. So **tapping a native-QS phone you want to RECEIVE → `0000` forever. The NFC-tag route (our `SuperDropTapReader`/`SuperDropTapHceService` F00000FE2C path) cannot deliver to a stock-QS receiver.** This is why "it wasn't receiving / wasn't going through."
- VERIFIED (gms-smali): stock QS's proximity "tap" = **BLE FastInitiation**, not NFC. The receiver runs FastInitiation SCANNING (gated: screen unlocked, FastInit enabled, location ON, Bluetooth ON, battery not low — `NearbySharingChimeraService` L28149/28207/28270/28291/28389) and, on detecting a sender's FastInit BLE signal while `isVisibleToSomeSender`, shows the "Device nearby is sharing" HUN (`is_from_fast_init` L26936, "Displaying FastInit HUN" L27566) → transfer over Wi-Fi-LAN.
- VERIFIED (our code): we do NOT emit any BLE advertisement — `BluetoothLeAdvertiser`/`startAdvertising` appear NOWHERE in `app/src/main/kotlin/dev/superdrop/`; the "FastInitiation pulse" is only a comment (`SendActivity:231`, `SendPeerPickerController:44`). So as a sender we scan/discover but never emit the proximity pulse that actually triggers QS.
- **CONCLUSION / NEW DIRECTION:** stop trying to fix the NFC-tag exchange for send-to-QS-receiver (dead end, verified). To make our "tap"/proximity send to a native QS receiver work we must: (1) **emit the QS-compatible BLE FastInitiation pulse** from our sender (frame bytes NOT yet mapped — service data under 0xFE2C/FastInit; map from GMS next, no-piecemeal); (2) connect over **Wi-Fi-LAN** to the QS endpoint (our picker already discovers + can dial `192.168.1.139:53601`); (3) receiver preconditions (visible + screen-on + BT-on + location-on) must hold.
- **OPEN UNKNOWNS:** exact FastInit BLE frame bytes (map from gms-smali); whether stock QS accepts our Wi-Fi-LAN Nearby handshake after FastInit detection (device test). 
- **NEXT STEP:** the FastInitiation frame is now fully mapped (see §FASTINIT FRAME below). Decide: build a
  minimal BLE FastInitiation emitter in Super Drop (BluetoothLeAdvertiser, no GMS) + on-device test whether a
  visible QS phone shows "Device nearby is sharing"; THEN tackle the post-accept Nearby Connections transfer.

## FASTINIT FRAME — COMPLETE VERIFIED MAP (GMS 26.18.33, jadx of `dnmj`/`dnlw`/`dekv`/`deku` + scan-filter smali)
Source: emitter `dnmj.l(int type, dekv, deku)` (`classes/dnmj.smali` / `jadx-fastinit/.../dnmj.java`),
frame holder `dnlw` (`classes8/dnlw.smali`). Advertise = `degc.e(AdvertisingSetParameters, AdvertiseData, …)`.
- **Transport:** BLE **legacy** advertising — `AdvertisingSetParameters`: `setLegacyMode(true)`,
  `setConnectable(false)`, `setScannable(!flag)`, `setTxPowerLevel(1)`, `setInterval(160)` (≈INTERVAL_LOW/fast).
  (A legacy `AdvertiseSettings{mode=LOW_LATENCY(2), tx=HIGH(3), connectable=false}` is built but discarded.)
- **AdvertiseData:** `setIncludeDeviceName(false)`, `setIncludeTxPowerLevel(false)`, ONE service-data entry:
  - **UUID = `0xFE2C`** (`dnlw.a = dejv.a("FE2C")` → `0000FE2C-0000-1000-8000-00805F9B34FB`).
  - **data = 24 bytes** (`Arrays.copyOf(…, dnlw.e + 19)`, `dnlw.e = len(FC128E)+2 = 5`):
    - `[0..2]` = **`FC 12 8E`** — fixed prefix `dnlw.b = bihg.d("FC128E")`.
    - `[3]` = **byte0** = `((version<<5)&0xE0) | ((type<<2)&0x1C) | (hasUWB?2:0) | (hasExtra?1:0)`. Construction
      uses `version(dnlw.f)=0`; `type(dnlw.g)=` the `l()` arg. **type 0=NOTIFY, 1=SILENT, -1=NONE** (`dnmj.b(I)`).
      ⇒ standard NOTIFY, no metadata → **byte0 = `0x00`**.
    - `[4]` = **byte1** = `(byte)(-dnlw.a())` = TX/RSSI **calibration** from Phenotype `ifkq.a.mj().h()` (negated abs);
      a small constant; receiver uses it for distance, it does NOT gate detection.
    - `[5..]` = optional sections, else zero-pad: UWB `dekv` (UwbComplexChannel) byte `((deku.b==1?0:32)|dekv.a())`
      + `deku.e()` (8 random bytes) when `d()`; then `e`/`f` extra arrays when `c()`. All NULL for a plain BLE
      share → zero-padded.
    - `[23]` = `0x80` if "require BT" (`dnlw.j`) else `0x00`.
- **Receiver match (scan filter, `dnmj` smali L1261-1269):** `ScanFilter.setServiceData(FE2C, {FC 12 8E})` with
  NO mask ⇒ matches ANY advert with service-data UUID FE2C whose data **starts with `FC 12 8E`**. (2nd filter =
  UUID-only, gated by `ifif.aJ()`.) On match, byte0 type=0 ⇒ NOTIFY ⇒ "Device nearby is sharing" HUN — IF the
  receiver is visible + screen-unlocked + Bluetooth-on + location-on + battery-ok (`NearbySharingChimeraService.X`).
- **MINIMAL EMITTABLE FRAME (to trigger the HUN):** serviceData(`FE2C`, `FC 12 8E 00 <cal> 00*18 00`) — 24 bytes,
  byte0=`0x00` (NOTIFY), byte1=calibration (any plausible small value, e.g. the config default), rest zero. Emit
  via `BluetoothLeAdvertiser.startAdvertisingSet(legacy, non-connectable)` — pure AOSP, needs `BLUETOOTH_ADVERTISE`.
- **⚠️ SCOPE (proactive-foresight):** this frame only makes the receiver **show the HUN / become primed** — it is
  NOT the file transfer. After the user accepts, stock QS expects the full **Nearby Connections** handshake +
  Quick Share payload (over BLE/Wi-Fi-LAN). FastInit is necessary but NOT sufficient; the transfer protocol is the
  next, larger map. UNKNOWNS: exact `cal` default; whether our non-Google sender can complete the Nearby handshake.


**Goal (user, 2026-06-09):** make our Super Drop app, as the SENDER (NFC reader-mode), tap a
phone running stock Google Quick Share (the RECEIVER / HCE on AID `F00000FE2C`) and actually send
the file over Quick Share's native path. Super Drop↔Super Drop is explicitly out of scope.

**Observed symptom (user):** first tap "pulls me into Quick Share" but never initializes sending;
afterward something isn't torn down, so it doesn't work again.

**Round-1 device trace (RAW, verified — `/root/nfc-diag/collector.log`, sender = CPH2515 OnePlus
Nord N30 5G / Android 14, receiver = real Quick Share device "L6DT"):**
- Our reader DID run and reached ADVERTISEMENT. The real QS HCE answered our ADVERTISEMENT APDU with
  only **2 bytes** (status word, NO `hhwv` tag) on both attempts → `tap exchange failed: Tag was lost.`
- In parallel, our mDNS browse `_FC9F5ED42C8A._tcp` FOUND the QS receiver (`endpoint:L6DT`,
  `192.168.1.139:53601`, `WIFI_LAN`) then lost it ~3 s later with NO connect attempt logged.
- GAP: `readAdvertisement` logs only the response SIZE, not the 2 bytes.

## GROUND RULES (user-mandated, non-negotiable)
- Raw data only. No guessing/estimating. Verify every claim by reading actual code/data BEFORE
  saying or doing anything. Read EVERY file associated with a thing before changing it.
- Hardest correct/official route a professional dev would take; never the easy route over the right one.
- After completing EACH step: STOP, think about what it means for later steps, and WRITE the findings
  into this file (the `### FINDINGS — Step N` sections). Complete ALL steps even if it "looks done."
- New questions/info → add new steps on the fly (≥10 bullets each). Each step distinct; no doubling up.
- Sources: `/root/agent-work/projects/quickshare-nfc` (decompiled GMS / Google Play services, smali2/7/8/9),
  its `base/manifest.txt` + `allstrings.txt`, our repo, and the device trace.

---

## STEP 1 — Pin our SENDER reader APDUs to the exact bytes + map the trace line-by-line
- Re-read `app/.../nfc/SuperDropTapReader.kt` in full; extract `buildSelectApdu()` exact bytes.
- Extract `buildAdvertisementApdu()` exact bytes (CLA/INS/P1/P2/Lc/Le) and the `hhww` payload it wraps.
- Read `QuickShareNfcCodec.encodeHhwwRequest` + `HhwwRequest(serviceId = NearbyServiceId.VALUE)`;
  resolve `NearbyServiceId.VALUE` literal and confirm it is exactly `"NearbySharing"`.
- Compute the precise SELECT and ADVERTISEMENT byte strings we transmit.
- Map every line of the Round-1 trace to the code path that emitted it (which method, which branch).
- Confirm from code what "ADVERTISEMENT empty/not-OK (…, 2B)" means: `!endsWithOk || size<=2`.
- Enumerate exactly which response shapes produce that log (e.g. `90 00`, `6A 82`, `6D 00`, `67 00`).
- Identify the instrumentation gaps: SELECT response not logged; ADV response bytes not logged; the
  exact `hhww`/SELECT bytes we send not logged.
- List the EORs (end-of-record) we cannot yet decide between with current data.
- Note `enableReaderMode` flags used (`NFC_A|NFC_B|SKIP_NDEF_CHECK`, no presence-check delay) and the
  2500 ms / 250 ms re-poll window — relevant to "Tag was lost."
- WRITE FINDINGS.

## STEP 2 — Decompile-map the real QS HCE ADVERTISEMENT handling (the authoritative receiver)
- Read `…/nearfieldcommunication/NfcAdvertisingChimeraService.smali` `processCommandApdu` IN FULL.
- Trace the APDU length/format checks at the top (the `if-ge v2,0x4` / `0x5` / `0x7` branches) and what
  each rejection returns (the 2-byte SW path) — this likely explains the "2B" answer.
- Identify how it distinguishes SELECT (`00 A4…`) from ADVERTISEMENT (`80 01…`).
- Find the exact expected ADVERTISEMENT layout (CLA/INS/P1/P2/Lc/trailer) it accepts vs rejects.
- Locate the branch that builds and returns the `hhwv` tag (the success path) and its preconditions.
- Read the private `a()Z` method and `onDeactivated(I)` for state/gating that affects the response.
- Find the "not advertising / cold" branch and what it returns (empty tag vs SW) + any PendingIntent
  that launches the Quick Share UI ("pulled me into Quick Share").
- Resolve the `djje`/`dedi` logger strings to understand each failure reason it logs.
- Determine whether a STOCK QS receiver answers a tag only when it is actively in receive/visible mode.
- Compare its accepted ADVERTISEMENT byte layout to ours from Step 1 (byte-by-byte).
- WRITE FINDINGS (esp. the precise reason a 2-byte SW is returned to our APDU).

## STEP 3 — Decompile-map the real QS SENDER (reader-mode) APDU it sends to the HCE
- Locate the GMS reader-mode class that drives `IsoDep.transceive` against `F00000FE2C` (search
  `allstrings.txt` + smali for `enableReaderMode`, `F00000FE2C`, `IsoDep`, ADVERTISEMENT builders).
- Extract the exact SELECT APDU GMS sends (compare to ours).
- Extract the exact ADVERTISEMENT APDU GMS sends: CLA/INS/P1/P2, Lc, the `hhww` content, and any
  trailer byte(s) (our code appends `00` Le; GMS doc-comment showed `… 00 FF`).
- Determine the `hhww` fields GMS populates (serviceId, localEndpointId, field3) and whether any are
  REQUIRED for the HCE to return a tag.
- Check whether GMS sends additional commands before/after ADVERTISEMENT.
- Check the reader-mode flags GMS uses (presence-check delay, NDEF skip, tech mask).
- Identify any retry/timing behavior GMS uses on the reader side.
- Note differences that could cause the HCE to reject ours (e.g. missing `localEndpointId`, wrong Le/Lc,
  missing `FF` trailer).
- Cross-check against our `buildAdvertisementApdu` and `encodeHhwwRequest`.
- WRITE FINDINGS (the precise APDU/`hhww` divergence, if any).

## STEP 4 — Verify the `hhww` request proto + `deym`/`hhwv` shapes against the decompile (current names)
- Resolve the current obfuscated class names for `hhww`/`hhwv` (the doc's old names not found; map via
  `NfcAdvertisingChimeraService` field types + `processCommandApdu` parse calls).
- Read the `hhww` parser the HCE uses; confirm field numbers/types and whether unknown/missing fields
  cause a reject.
- Read `deym.smali` serializer/deserializer; re-verify the NfcTag framing our codec mirrors.
- Read `dfga.smali` (rxAdv parser) and `denp.smali` (rxAdv encoder) to re-confirm the Wi-Fi-LAN DE TLV.
- Confirm `serviceIdHash` = SHA-256("NearbySharing")[:3] = `FC9F5E` and reconcile with the mDNS
  service `_FC9F5ED42C8A._tcp` seen in the trace (6-byte vs 3-byte).
- Verify the `NearbyServiceId.VALUE` + `hashPrefix` our app uses match.
- Confirm our `encodeHhwwRequest` byte output equals what GMS' HCE expects to parse.
- Check endianness/length-prefix details against the smali.
- Identify any version drift between this GMS build and the byte map in `docs/NFC_INTEROP_BYTEMAP.md`.
- WRITE FINDINGS.

## STEP 5 — Investigate the parallel mDNS/Wi-Fi-LAN peer that was found but never connected
- Read our discovery→connect wiring for the NFC-tap-send flow (SendActivity discovery + onPeerSelected).
- Determine why the discovered `L6DT` peer (WIFI_LAN, IP:port) was NOT auto-connected.
- Check whether the NFC-tap-send path deliberately waits for the NFC tag (ignoring mDNS peers).
- Read how an icon-tap (manual) peer selection connects vs the NFC path — what differs.
- Check the timing: peer found at +0.4 s, lost at +3.6 s; our connect gating in that window.
- Determine whether a stock QS receiver advertising over mDNS is itself connectable by us without NFC.
- Inspect `serviceLost` causes (QS receiver stopped advertising vs our browse teardown).
- Check whether selecting the discovered QS peer would even pass our UKEY2/QR gating (QR vs non-QR).
- Decide whether mDNS-direct is a viable/again-correct route or a distraction vs the NFC tag route.
- WRITE FINDINGS.

## STEP 6 — Add minimal, correct instrumentation to capture the missing bytes (no behavior change)
- In `SuperDropTapReader`, log the SELECT response bytes (hex) + status.
- Log the exact SELECT + ADVERTISEMENT APDUs we transmit (hex).
- Log the full ADVERTISEMENT response bytes (hex), not just size, per attempt.
- Log the `hhww` payload bytes we send.
- Keep all logging off the main thread (already on binder thread) — confirm no ANR risk.
- Ensure logs flow through `DiagnosticLog` so they reach the collector.
- Guard hex dumps for size (bounded) to avoid huge uploads.
- Do not change any APDU/byte/logic in this step — instrumentation ONLY.
- Self-review the diff (callers, threading, regressions).
- WRITE FINDINGS (what the instrumentation will reveal + why it's safe).

## STEP 7 — Sender-side NFC routing / "pulled into Quick Share" mechanism
- Read `base/manifest.txt` for GMS NFC services (HCE services, AIDs, reader/observe registrations).
- Determine if GMS registers anything that competes for the field while our reader-mode is foreground.
- Verify from the trace whether our reader actually owned the field (it did fire) — reconcile with the
  user's "pulled into Quick Share" (which phone's UI; sender vs receiver).
- Read Android reader-mode precedence vs HCE/observe (from our code usage + GMS usage only, not web).
- Check `NfcPreferredService.kt` and whether we call `setPreferredService`/reader-mode correctly.
- Identify what component shows the Quick Share UI on a tap (PendingIntent from Step 2 cold branch?).
- Determine if the receiver QS launching its own UI is expected/benign vs the blocker.
- Check whether our SELECT even reached the HCE (it must have, to get to ADVERTISEMENT) — confirm.
- Decide whether routing is a real factor or a red herring given the trace.
- WRITE FINDINGS.

## STEP 8 — Our connect + teardown lifecycle (the "stuck / didn't work again" half)
- Re-read SendActivity reader lifecycle: `onResume` enable, `onPause` disable, `onNfcPeerTapped` disable.
- Confirm there is NO reader re-enable on the failure terminal path (verified candidate) and trace it.
- Read `renderTerminal` + the failed-tap UI state; determine if it blocks subsequent taps.
- Check whether a failed exchange (`Tag was lost`) leaves reader-mode enabled or disabled.
- Inspect `SuperDropTapReader.disable()`/`enable()` idempotency + platform reader-mode reset.
- Read radio/teardown (`shareRadios.restoreRadios`) for state left after a failed tap.
- Determine the exact user-recoverable path (leave+return re-enables via onResume).
- Propose the minimal correct re-enable/teardown fix (do NOT apply yet).
- Cross-check against the trace (was reader still active when the tag was lost?).
- WRITE FINDINGS.

## STEP 9 — Build + deliver the instrumented build; capture Round-2 trace
- Build `:app:assembleDebug`; copy APK into repo root per the copy-APK-first rule.
- Confirm the collector tunnel is still live (re-bake URL if it rotated) before handing off.
- Give the user a 1-phone test script (tap a QS receiver, reopen app to upload).
- Watch the collector for the Round-2 `nfc-send-tap` upload.
- Read the actual SELECT response + ADVERTISEMENT response bytes.
- Decode the 2-byte SW (90 00 vs 6A82/6D00/67 00) → exact HCE verdict.
- Confirm our transmitted ADVERTISEMENT bytes vs the Step-3 GMS reference.
- Determine whether the HCE rejected (error SW) or accepted-but-empty (90 00, not in receive mode).
- Correlate with whether the receiver was actually in QS receive/visible mode during the test.
- WRITE FINDINGS.

## STEP 10 — Root-cause synthesis (only from verified Step 1–9 data)
- Assemble the verified facts into one ordered causal chain for the ADVERTISEMENT failure.
- State precisely why the HCE returned 2 bytes (rejected vs not-advertising) with citations.
- State whether the fix is on our APDU/`hhww` (format) or on receiver state (must be in receive mode).
- Decide if the mDNS/Wi-Fi-LAN direct route is the correct official path when NFC only bootstraps.
- Resolve the "pulled into Quick Share" + "stuck" sub-issues with cited evidence.
- Cross-check the chain against the decompiled HCE accept conditions (no contradictions).
- List anything still unverified and the data needed to close it.
- Reconcile with `docs/NFC_INTEROP_BYTEMAP.md` and flag any corrections to it.
- Produce the definitive statement of the bug.
- WRITE FINDINGS.

## STEP 11 — Design the official correct fix (hardest-correct route)
- Specify the exact code changes (APDU/`hhww` correction and/or receiver-state handling and/or
  mDNS-direct connect and/or teardown re-enable), each tied to a verified finding.
- Ensure the fix matches real Quick Share behavior byte-for-byte (per Steps 2–4).
- Cover all call sites (reader, codec, SendActivity, discovery) that must change together.
- Address cross-cutting: threading, lifecycle, permissions/manifest, API levels, OEM (OnePlus) quirks.
- Keep iPhone NDEF path (Track 1) untouched; ensure no regression to the working A15→A14 direction.
- Add the observability needed to prove the fix on-device.
- Define the acceptance criterion (a real QS receiver receives the file on one tap).
- Plan rollback/guarding if the receiver isn't in receive mode.
- Note the no-per-boot-manual-step constraint holds.
- WRITE FINDINGS / final design.

## STEP 12 — Verification plan + commit/changelog
- Define the precise on-device test (sender = Super Drop, receiver = stock QS in receive mode).
- Build, copy APK, hand off the ordered script; capture the trace proving one-tap send.
- Confirm the working A15→A14 direction still works (no regression).
- Confirm teardown: a failed tap leaves the app able to retry without leave+return.
- Read the post-fix trace and verify each acceptance criterion against raw data.
- Only then state the result, separating proven (drove it / saw the trace) vs inferred.
- Update `CHANGELOG.md` + in-file KDoc + memory per the document-every-change rule.
- Commit on a branch with a descriptive message.
- Re-bake/retire the diagnostics collector URL when done.
- WRITE FINDINGS / closeout.

---

# FINDINGS LOG (appended after each step — raw data only)

### FINDINGS — Step 9 / ROUND-2 DEVICE TRACE (RAW, /root/nfc-diag/collector.log lines 55+) — OVERTURNS the timing theory
Sender = CPH2515 OnePlus Nord N30 5G/A14, instrumented build. Receiver = real Quick Share device. Verbatim:
- `SELECT apdu=00A4040005F00000FE2C00 resp=9000` — SELECT accepted (90 00 = `djvb.b()` OK).
- `ADV apdu=800100000F0A0D4E656172627953686172696E6700` — our ADVERTISEMENT is byte-perfect (parses to serviceId="NearbySharing").
- `ADVERTISEMENT empty/not-OK attempt=1..11 resp=0000` over the FULL 2500ms window, then "yielded no usable tag after 11 attempts".
  **The empty response is `00 00`** (= `djvb.a().e()`, i.e. `djvc.b` error trailer = `00 00`; vs `djvc.a` OK = `90 00`).
- Repeated across 3+ taps over ~25s: ALWAYS `0000`. On one later tap: attempt 1-2 `0000` then "Tag was lost +735ms" (phones moved).
- THROUGHOUT, our mDNS browse `_FC9F5ED42C8A._tcp` finds the SAME receiver advertising over Wi-Fi-LAN at `192.168.1.139:53601`,
  with RAPIDLY ROTATING hidden endpoint IDs (143F→21UX→MGJ6→46ZU, new one ~every 1s) — classic QS hidden-visibility rotation.
- **CONCLUSION (verified): the stock QS receiver returns `0000` "not currently advertising for NearbySharing" to our ADVERTISEMENT
  for the ENTIRE poll and never registers an NFC advertisement (`djvf.g` stays null) — so there is NO `hhwv` tag to return.**
  This is NOT a timing/re-poll problem (full 2500ms ran, 11 polls) and NOT a byte problem (APDU perfect). My earlier
  "extend/robustify the re-poll" hypothesis is REFUTED by this data.
- IMPLICATION: against an idle stock QS receiver, the NFC-tag bootstrap does not yield a tag. BUT the receiver IS reachable over
  mDNS/Wi-Fi-LAN (we already discover its IP:port). The open question (Step 7, via jadx-full): WHO/WHAT makes a QS receiver
  register its NFC advertisement (`djvf.h`) + wake PI (`djvf.i`) — i.e. is NFC-advertising ever available to an external reader,
  or does QS only advertise over NFC inside its own session, with the real transfer going over the mDNS/BLE channel (the NFC tap
  only WAKING + scoping the peer)? The fix axis moves from "reader re-poll" to "reach the receiver over the channel it actually
  exposes (mDNS/Wi-Fi-LAN) + satisfy its accept/auth", with the tap used to wake. Pending djvf.h/i caller verification.

### FINDINGS — Step 6 (instrumentation added to SuperDropTapReader.kt — instrumentation-ONLY, no behavior change)
- Added (all via `DiagnosticLog.w`, on the binder thread — no ANR): SELECT apdu+resp hex; ADVERTISEMENT apdu hex;
  per-attempt ADVERTISEMENT FULL response hex + `+Nms` elapsed; exact tag-loss attempt + `+Nms`. Added a bounded
  `hex()` helper (max 80B, masks sign). Fixed a sign-extension bug (`bytes[i].toInt() and 0xFF`).
- WHY: Round-1 logged only response SIZE ("2B"), which cannot distinguish `djvb.a()` (error trailer, wake fired) from
  `90 00` (accepted-but-empty). Round-2 will show the exact trailer + the wake timing (how long after attempt 1 until
  the receiver re-advertises or the link drops).
- Re-poll/loop logic UNCHANGED (still 2500ms/250ms, same-connection). This is deliberate: measure first, fix after.
- Open question Round-2 must answer: (a) exact 2-byte trailer of the empty response; (b) does the link survive past
  the wake (more attempts) or drop at ~1s every time; (c) on a SECOND tap (after the wake), does ADVERTISEMENT now
  return a tag (would confirm the wake works + ours just needs to span two taps / longer window).

### FINDINGS — Step 3 (VERIFIED from jadx: djvh, dnzn; reader-side primitives — GMS 26.18.33)
- `djvh` = low-level NFC device wrapper over `IsoDep` (`dego.a`): `b(djva)` builds APDU `[CLA][INS][P1][P2][e][f][g][h][i]`
  and `transceive`s ONCE, parsing the response into `djvb(dataMinus2, last2=trailer)`. `d()` = SELECT-bind: sends
  `djva(0x00,0xA4,0x04,0x00, AID bytes of "F00000FE2C", 0)` and throws IOException if `.d()` (error trailer). So GMS's
  SELECT == ours. `a()` computes max transceive length (extended-APDU aware). `setTimeout(ifia…cB())` per transceive.
- `dnzn` = the reader-mode entry: `nfcAdapter.enableReaderMode(activity, readerCallback, flags, bundle)`, and
  `onTagDiscovered(tag) → ilen.invoke(tag)` — i.e. the per-tag exchange + re-poll + wake-handling lives in an
  obfuscated Kotlin-coroutine lambda (`ilen`) in the Nearby NFC medium, not in `djvh`/`dnzn`. The advertisement
  djva (INS=0x01) construction + the loop that calls `djvh.b()`, checks `djvb.d()` (error) / empty, and re-polls is
  deeper in that coroutine graph (heavily obfuscated; steep diminishing returns to hand-trace vs measuring on-device).
- VERIFIED so far on the reader side: GMS sends ONE APDU per `djvh.b()` and reads `djvb`; it does NOT batch. The
  re-poll/keepalive/timeout policy (how it survives the receiver's QS launching after the wake, whether it tolerates a
  fresh tag) is the remaining unknown — to be measured by instrumenting our reader (actual response bytes, per-attempt
  timing, tag-loss timing) rather than guessed. This is Step 6/9.
- IMPLICATION: our `SuperDropTapReader` re-polls on ONE IsoDep connection and aborts on the first IOException
  ("Tag was lost"); it does NOT log the response bytes nor re-arm/continue across a wake-induced tag loss. The fix axis
  is reader robustness across the wake; the exact target timing comes from the Round-2 instrumented trace.

### FINDINGS — Step 2 (VERIFIED from jadx decompile: NfcAdvertisingChimeraService.processCommandApdu, djvf, djvb — GMS 26.18.33)
- The real QS HCE parses each APDU into `djva(CLA, INS, P1, P2, data, le)` and handles FOUR commands:
  - SELECT: CLA=`0x00`, INS=`0xA4` → returns `djvb.b()` (OK). (Our SELECT reaches ADVERTISEMENT, so this passes.)
  - ADVERTISEMENT: CLA=`0x80`, INS=`0x01` → parse `data` as protobuf `hhww`; `str=hhww.c` (serviceId),
    `str2=hhww.d`, `N=hhww.e`. Then:
      - `str` empty → log "No service ID" → `djvb.a()` (empty).
      - `byte[] g = djvf.b().g(str)`:
          - `g==null` → log "**We are not currently advertising for service NearbySharing.**" → **`djvf.b().f(str)`** → `djvb.a()` (empty).
          - `g!=null` → `djvf.b().d(str,str2,N)` (push sender info into Nearby) → `djvb.c(g)` (return tag).
  - CONNECT: CLA=`0x80`, INS=`0x02` → open an NFC socket (`djve`).
  - DATA: CLA=`0x80`, INS=`0x03` → write to / read from the open socket.
- `djvb.a()` = `new djvb([], djvc.b)`, `djvb.b()` = `c([])`, `djvb.c(g)` = `new djvb(g, djvc.a)`. `e()` = `data ++ trailer(2B)`.
  So `djvb.a().e()` = a 2-byte trailer only = OUR observed "2B" empty response. `djvc.a` vs `djvc.b` are the 2-byte
  OK vs error trailers (to read exactly in a follow-up; `endsWithOk` saw SELECT's `djvb.b()` end in 90 00).
- **`djvf` (singleton registry) — THE mechanism:** maps `b`=serviceId→**PendingIntent (wake)**, `c`=serviceId→**advertisement bytes** (`hhwv.r()`), `d`=serviceId→server-socket handler, `e`=serviceId→endpoint ctx.
  - `g(str)` = `c.get(str)` (null when not advertising).
  - **`f(str)` = `b.get(str).send()` — fires the wake PendingIntent → launches Quick Share into advertising.**
  - `i(pi)` = `b.put("NearbySharing", pi)` — registers that wake PI (so idle QS phones ARE wakeable; consistent with user's QS→QS idle-receiver test).
  - `h(str, dfdr, hhwv, djjv)` = `c.put(str, hhwv.r())` — registers the live advertisement (the tag returned by `g`).
- **CORRECTION (user refuted my earlier read, correctly):** the receiver does NOT need to be pre-in-receive-mode.
  An idle receiver returns empty BUT `f()` WAKES Quick Share, which then calls `h()` to register its advertisement;
  a RE-POLLED ADVERTISEMENT then returns the `hhwv` tag. The tag IS an `hhwv` (matches our codec).
- **REFRAMED ROOT-CAUSE HYPOTHESIS (verify in Step 3 vs the real QS sender):** our reader DID trigger the wake
  ("pulled into Quick Share") but lost the ISO-DEP link ("Tag was lost") after ~2 empty re-polls (~1s) — before the
  woken QS re-advertised. Likely our re-poll/field-keepalive differs from how the real QS SENDER sustains the tap and
  recovers across the wake. Need: the real QS sender's reader-mode loop (timing, re-establish-on-loss, how many polls,
  whether it re-SELECTs). Also confirm the wake PI launching QS may itself reset the receiver NFC field (causing our loss).
- Our ADVERTISEMENT format is ACCEPTED (serviceId parsed; trailing `00` gives `i=1 ≤ 2` slack) — NOT a byte bug.
  Note: we send empty `hhww.d`/`hhww.e`; the real sender likely fills `localEndpointId`(d)+endpointInfo(e) — relevant
  once we get the tag, because `djvf.d(str,str2,N)` feeds those into the receiver's Nearby connect-back.

### FINDINGS — Step 1 (verified from SuperDropTapReader.kt, QuickShareNfcCodec.kt, NearbyServiceId.kt)
- `NearbyServiceId.VALUE` = `"NearbySharing"` (13 ASCII bytes `4E 65 61 72 62 79 53 68 61 72 69 6E 67`). hashPrefix = SHA-256(VALUE)[:3] (= FC9F5E per codec/byte-map; to re-verify in Step 4).
- Our SELECT APDU (`buildSelectApdu`) = `00 A4 04 00 05 F0 00 00 FE 2C 00` (P1=04 select-by-name, Lc=05 AID, Le=00).
- Our `hhww` (`encodeHhwwRequest(serviceId="NearbySharing")`, field2/field3 NULL) = proto field1 string =
  `0A 0D 4E 65 61 72 62 79 53 68 61 72 69 6E 67` (15 bytes). We send ONLY serviceId — NO `localEndpointId` (field2), NO field3.
- Our ADVERTISEMENT APDU (`buildAdvertisementApdu`) = `80 01 00 00 0F` + the 15 `hhww` bytes + `00` (Le).
  Full: `80 01 00 00 0F 0A 0D 4E 65 61 72 62 79 53 68 61 72 69 6E 67 00`.
- DIVERGENCE CANDIDATE (must verify vs decompile in Step 3): our trailer is a single `00` (Le). Our own
  `docs/NFC_INTEROP_BYTEMAP.md` / `SuperDropTapHceService` KDoc describe the real reader's ADVERTISEMENT as
  `80 01 00 00 <Lc> <hhww> 00 FF` or `… FF` — i.e. a trailing `FF` we do NOT send. NOT yet confirmed from raw smali.
- Trace mapping: "ADVERTISEMENT empty/not-OK (…, 2B)" is emitted by `readAdvertisement` when
  `!endsWithOk(advResp) || advResp.size <= 2`. A 2-byte response = SW only, no body. CANNOT distinguish
  from the log whether it is `90 00` (OK-but-empty body) or an error SW (`6A 82` file-not-found / `6D 00`
  ins-not-supported / `67 00` wrong-length) — our reader logs SIZE only, not the bytes. THIS IS THE KEY GAP.
- SELECT must have SUCCEEDED on the real HCE (we reached the ADVERTISEMENT branch; `exchange` returns early
  on `!endsWithOk(selectResp)`). So `F0 00 00 FE 2C` IS routed/answered `90 00` by the real QS HCE. Good.
- `enableReaderMode` flags = `FLAG_READER_NFC_A | FLAG_READER_NFC_B | FLAG_READER_SKIP_NDEF_CHECK`, presence
  arg = null (default presence check). Re-poll window 2500 ms / 250 ms. "Tag was lost" = IsoDep IOException
  on the 3rd transceive (phones separated before the receiver produced a tag).
- IMPLICATION for next steps: the failure is at ADVERTISEMENT (HCE returns no tag). Two live hypotheses to
  decide ONLY with raw data: (H-A) our ADVERTISEMENT/`hhww` format is rejected by the real HCE (→ error SW),
  (H-B) the HCE accepts but returns an empty tag because the receiver is not in active receive/advertising
  mode (→ `90 00`, no body). Step 2 (HCE accept conditions) + Step 9 (actual 2 bytes) resolve this. Note the
  earlier user report "A15 Super Drop → A14 Quick Share worked" — IF that receiver was truly stock QS, it
  argues against H-A (format) and toward H-B (state); but that's unverified and must not be assumed.

---

## ROUND-2 RESULT + VERIFIED REGISTRATION MAP (2026-06-10, resumed after session limit)

> **RESUME AUDIT (2026-06-10, this session):** confirmed this file is THE canonical journal for the task
> (related: `docs/NFC_INTEROP_BYTEMAP.md` byte map + memory `project_superdrop_nfc_url_opens_browser_2026_06_09`).
> Already done by the prior session (NOT redone): Steps 1/2/3/6/9 findings below — APDU bytes, HCE
> ADVERTISEMENT handler + `djvf` g/f/d/h/i semantics, reader primitives, the SuperDropTapReader
> instrumentation (already in `super-drop-debug.apk`), and the Round-2 trace capture itself (already in
> `collector.log`). This session's NEW work = the registration-CALLER map below (resolves the open question
> the prior session stalled on: jadx-full was only ~16% when the limit hit). Honest note: reviving the
> collector + re-baking `COLLECTOR_URL` + rebuilding the APK was NOT needed for the pending step (Round-2 was
> already captured) — it only serves a future follow-up trace.

**Round-2 device trace (collector.log, sender CPH2515/Android 14, receiver = real Quick Share):**
- `SELECT … resp=9000` ✓ — AID `F00000FE2C` selected/accepted by the real HCE.
- `ADVERTISEMENT … resp=0000` on **ALL 11 re-poll attempts** (+22ms → +2754ms) and again on later taps.
  The 2 bytes are `00 00` (the not-advertising trailer), returned EVERY time; the receiver never woke
  (user: "wouldn't let me tap again until I turned real Quick Share off and on, then it did the same").
- **H-B confirmed, H-A refuted, re-poll hypothesis OVERTURNED:** our APDU is byte-accepted; longer
  re-polling cannot help. The receiver simply never registers an NFC advertisement → `djvf.g==null`.

**VERIFIED REGISTRATION CHAIN (baksmali of all 15 GMS 26.18.33 dexes → `/root/nfc-diag/gms-smali`):**
- HCE `NfcAdvertisingChimeraService.processCommandApdu` ADVERTISEMENT(80 01) branch (classes8, ~L1197):
  `g = djvf.g(serviceId)`; if `g==null` → log *"…not currently advertising for service %s"* → `djvf.f(serviceId)`
  (the WAKE) → return `00 00`. If `g!=null` → `djvf.d(svc, hhww.d, hhww.e)` (push sender endpoint) → return tag.
- `djvf` (classes/djvf.smali, confirmed): `b`=svc→PendingIntent(wake); `f(svc)`=`b.get(svc).send()` **but
  NO-OP if b has no PI**; `c`=svc→advertisement bytes; `g(svc)`=`c.get`; `h(...)`=register advertisement; `i(pi)`=register wake PI for "NearbySharing".
- **Wake PI registration `djvf.i()`** ← ONLY `NearbySharingChimeraService.X()` = "start FastInitiation
  scanning". X() **bails without registering if the screen is locked** ("Stopping FastInitiation scanning
  because the screen is locked") or FastInit disabled (aP()/aQ()). Triggered by screen-on AND by
  `SetFastInitNotificationEnabled(true)` (via `dmfk`). Torn down (`i(null)`) when QS stops.
- **Advertisement registration `djvf.h()`** ← `djkb.q()` ← `deyl.i()` = **startNfcAdvertising** (log:
  *"became NFC discoverable"*) ← **the startDiscovery path ONLY** (`dfad.call()` gated by
  `deyl.f(DiscoveryOptions)` deciding NFC participates; and `dfet.G(…DiscoveryOptions…)`).

**THE DECISIVE CONSTRAINT (verified):** A Quick Share phone exposes a *readable NFC tag* (answers our
ADVERTISEMENT with a real `hhwv` instead of `0000`) **ONLY while it is itself running NFC-enabled
DISCOVERY — i.e. the Quick Share SEND sheet open, scanning for recipients.** A phone that is idle, or on
the *receive* screen, is NOT NFC-discoverable → returns `0000`. The HCE's wake (`djvf.f`) can only fire if
FastInit scanning registered a wake PI (screen unlocked + QS enabled); even then the wake launches the QS
UI, which does not auto-enter NFC discovery without user action — so our 2.5s window saw `0000` throughout.

**WHAT THIS MEANS FOR THE GOAL (Super Drop sender → tap → native Google Quick Share *receiver*):** the
intended target (a phone the user wants to RECEIVE) is exactly the state in which stock QS is NOT
NFC-discoverable. So the NFC-*tag* path cannot, by itself, make an idle/receiving QS phone hand us a tag.
**However** — our app already resolves that same QS phone over mDNS/Wi-Fi-LAN the entire time
(`endpoint:L6DT/143F/21UX/… 192.168.1.139:53601 mediums=[WIFI_LAN]`, displayName "Quick Share device").
So the live OPEN QUESTION / decision fork (for the user): (A) treat the tap purely as a trigger and connect
to the mDNS-resolved QS endpoint over Wi-Fi-LAN (no tag needed) — needs verifying stock QS accepts an
inbound Nearby connection while idle/visible; or (B) require the receiver to be on the QS *send* sheet for
the tag path; or (C) accept that tap-to-send-to-a-QS-receiver is a QS design limitation. NEXT: verify (A) —
can we dial `192.168.1.139:53601` and complete a Nearby handshake to a visible-but-idle QS receiver?
</content>

## QUICK-SHARE TAP-INITIATION MECHANISM (2026-06-10, Quick Share code ONLY — per user scope)
Verified from GMS 26.18.33 (`classes8/dnzf,dnzh,dnzp`):
- QS sender arms NFC reader mode: `dnzf` → `NfcAdapter.enableReaderMode` while the share screen is up.
- On tap, `dnzh.onTagDiscovered(Tag)` → `dnzp.a(tag)` builds Intent(`android.nfc.action.TAG_DISCOVERED`,
  `setPackage("com.google.android.gms")`, extra `android.nfc.extra.TAG`=tag) → `sendBroadcast`. It hands the
  raw tapped Tag to GMS; GMS internally runs the `F00000FE2C` ISO-DEP exchange + starts the connection.
- ⇒ QS "tap initiates a share" = sender reads the tapped phone's NFC advertisement tag and connects to the
  endpoint in it. Precondition (verified earlier): the tapped phone must be PRESENTING that tag (registered
  only via NFC-enabled startDiscovery). No tag presented → nothing to initiate.
- STILL TO LOCK (from user): the exact tap scenario (who taps whom; which device runs what) — determines
  which QS state must present the tag, hence the exact fix. Then map that remaining piece (Quick Share only).

### ROLE ASSIGNMENT VERIFIED (2026-06-10, Quick Share only) — scenario: Super Drop(send) → tap → native QS(receive)
- QS SENDER surface = `dpst` (handles `ShareTarget`/attachments/onSelectFilesRequest) — this is the share sheet.
  It ARMS NFC reader mode (`dpst` → `dnzp.c` → `dnzn`/`dnzf` enableReaderMode). So **the QS sender is the
  reader/initiator** — exactly the role Super Drop plays. ✓ (Our reader side matches QS's design.)
- On tap, the sender does NOT do the exchange itself; `dnzh.onTagDiscovered` → `dnzp.a(tag)` builds
  Intent(TAG_DISCOVERED, setPackage gms, extra TAG) → sendBroadcast → GMS runs the F00000FE2C exchange.
- ⇒ The tap reads the TAPPED phone's NFC advertisement tag. That tag is registered only while the tapped phone
  runs NFC-enabled discovery (`djvf.h ← startNfcAdvertising ← startDiscovery`, NFC in DiscoveryOptions.o + flag
  `ifif.aC()`). The QS receive surface (`ReceiveSurfaceChimeraService`) is about DeviceVisibility/advertising +
  Wi-Fi connect; NOT yet proven to register an NFC advertisement.
- **LINCHPIN (unresolved): which QS receiver state actually PRESENTS the NFC tag** (idle vs on the "Receive"/
  visible screen vs on QS's own send screen). The Round-2 trace got `0000` because the receiver was not
  presenting a tag (user: "wasn't receiving"). Cleanest resolution = on-device observation with our EXISTING
  instrumented reader (logs SELECT/ADV bytes): tap the QS phone in each state, see which returns a real tag.
- NOTE: our reader's ADVERTISEMENT is accepted by the HCE (it reached the g==null lookup branch), so no APDU
  bug is evident — the gap is the receiver not advertising, i.e. its STATE, not our bytes.

## ✅ RESOLUTION (2026-06-10) — why the tap auto-opens an idle QS phone, and why ours didn't (Quick Share only)
User's working behavior: two QS phones, neither in the app; one enters QS SEND, taps the other while it's
just browsing → the browsing phone AUTO-OPENS Quick Share and receives. That auto-open = the **`djvf.f()` wake**.

VERIFIED CHAIN (all from GMS 26.18.33, no app analysis):
1. `hhww` proto: serviceId = `c` = **proto field #1** (info string `ဈ…` → field1=c, field2=d, field3=e).
   Our reader sends serviceId as field 1 (`0A 0D "NearbySharing"`). ⇒ **our ADVERTISEMENT bytes are CORRECT.**
2. HCE ADVERTISEMENT branch (`NfcAdvertisingChimeraService` ~L1197-1245): `g = djvf.g("NearbySharing")` → null
   → log "not currently advertising" → **`djvf.f("NearbySharing")`** (the WAKE) → return `djvb.a()` = `0000`.
   ⇒ our `0000` trace is THIS branch — meaning **our tap DID reach `djvf.f()` (the wake call).**
3. `djvf.f(str)` = `b.get(str).send()` — fires the wake PendingIntent IF registered; **NO-OP if not registered.**
4. The wake PI is registered ONLY by `djvf.i()` ← `NearbySharingChimeraService.X()` = "start FastInitiation
   scanning", which BAILS (no registration) unless: screen UNLOCKED, FastInit/"nearby scanning" ENABLED,
   Location ON, Bluetooth ON, battery not low ("Stopping FastInitiation scanning because …" L28149/28207/28270/28291/28389).

**CONCLUSION:** our NFC tap is protocol-correct and already reaches Quick Share's wake. The idle phone opens
Quick Share IFF it had the wake handler registered — i.e. its Quick Share was FastInitiation-scanning at tap
time (screen unlocked + Bluetooth ON + Location ON + nearby-scanning enabled + battery ok). In the user's
working QS→QS test the browsing phone met those. When our tap got `0000`-with-no-open, the target was missing
one — most commonly **Bluetooth or Location OFF**. ⇒ **likely NOTHING to fix in the app's tap bytes**; the
receiving phone needs BT + Location ON + unlocked + nearby-scanning enabled.

**CONFIRMATION TEST (no build):** on the receiving phone turn ON Bluetooth + Location, unlock it, leave it
browsing; from the sender's Super Drop send screen, tap it → it should auto-open Quick Share. If it STILL
doesn't open with all those ON, there is a deeper difference (next: extract the exact GMS reader ADVERTISEMENT
bytes + verify djvf is the same process for HCE and FastInit) — but the protocol says our tap reaches the wake.

## ⚠️ SYMPTOM REFINED BY USER (2026-06-10) — wake WORKS; post-wake receive does NOT initiate; teardown broken
User: our tap "would wake once but not initiate the receiving, and then didn't tear down so it wouldn't do it
again." ⇒ RETRACT the "Bluetooth/Location was off" conclusion — the wake PI WAS registered and OUR TAP FIRED IT
(Quick Share opened on the receiver). The real gap is the SECOND half of the flow:
  (1) after `djvf.f()` opens Quick Share on the idle receiver, the receive does NOT initiate (no transfer);
  (2) a stuck state isn't torn down, so a second tap won't re-trigger.
So this is the post-wake handshake + teardown — NOT the wake, NOT our ADVERTISEMENT bytes (proven correct).
NEXT: map from Quick Share ONLY — after the wake PI opens QS on the receiver, what makes it actually start
receiving (does it auto-register its NFC advertisement so the sender's re-poll gets the tag, or does it need
the sender presented via another channel?), and how QS tears the tap down so it can be retried.

## ✅✅ WHY IT WAKES BUT DOESN'T RECEIVE / WON'T RETRY (2026-06-10, Quick Share code only — answers the user)
Verified from GMS 26.18.33:
- The NFC tap's ENTIRE job is the WAKE: `djvf.f()` fires a PendingIntent whose inner intent is
  `Intent.setClassName(ctx, "com.google.android.gms.nearby.sharing.main.MainActivity")` (X() L29197). So the tap
  OPENS Quick Share (MainActivity) on the receiver. It carries NO file and establishes NO connection itself.
- After MainActivity opens, the receiver registers a **ReceiveSurface** (`RegisterReceiveSurfaceParams`) → it
  becomes a visible/advertising Quick Share receiver. The actual transfer then completes over the NORMAL Nearby
  Wi-Fi/BLE rendezvous — the SENDER discovers the now-visible receiver and connects (the path that already works).
- The NFC ADVERTISEMENT exchange returns empty (`djvb.a()` = the `0000` we saw) on the wake; the phones separate
  right after the tap (ISO-DEP "Tag was lost"), so re-polling the NFC tag post-tap cannot complete the rendezvous —
  it MUST finish over Wi-Fi/BLE.

**So the symptom maps exactly:**
- "wakes once" = the wake PI fired, MainActivity opened. ✓
- "doesn't initiate the receiving" = after the wake, the share is NOT carried through the normal Wi-Fi/BLE path
  to the now-awake receiver; the flow stopped at the empty NFC exchange.
- "doesn't tear down / won't do it again" = the half-open state isn't reset (receiver left on MainActivity as a
  registered receive surface; sender tap/reader state stuck), so a second tap can't cleanly re-wake.

**FIX DIRECTION (fixing the tap, derived from Quick Share — NOT a rewrite):** treat the tap as WAKE-ONLY: after
the tap fires Quick Share's wake, complete the share over the normal channel (discover the now-visible receiver
and connect — the working path), and reset both ends after each attempt so it can be retried. The NFC exchange
itself is not the data path and should not be depended on to "initiate the receiving."
OPEN/UNVERIFIED: whether the woken MainActivity auto-receives vs needs a user confirm (user's QS→QS obs = auto);
the exact teardown QS does on a failed tap (next, if needed).

## NO-CONFIRM AUTO-RECEIVE = SELF-SHARE (same account) — 2026-06-10, Quick Share only
User: QS→QS had NO confirm tap (auto-received). Quick Share has a **self-share** concept: `TransferMetadata.isSelfShare`,
and a "self-share with no account" fallback-visibility check (`NearbySharingChimeraService` L64976). Self-share =
sharing between your OWN devices (same Google account), which is the case that auto-accepts WITHOUT a confirmation.
- STRONG INFERENCE (not a fully-isolated gate, obfuscated): the QS→QS no-confirm the user saw = self-share, i.e.
  the two phones were on the same account. A non-Google sender (Super Drop) is NOT a same-account device, so a
  woken QS receiver would NOT classify it as self-share → would show a confirmation (or only accept if visibility =
  Everyone/Contacts). So the seamless "no confirm" QS→QS experience is likely NOT replicable for Super Drop → QS;
  a confirm is the expected stock behavior for a 3rd-party sender.
- This is SEPARATE from the core break: even WITH a confirm, ours currently wakes but never initiates the transfer.

## CONSOLIDATED ANSWER (what the tap must do)
1. Tap = WAKE only → opens QS MainActivity on the receiver (ours already does this — "wakes once"). ✓
2. After wake, the SHARE must be carried over the normal Nearby Wi-Fi/BLE channel (the path Super Drop already does)
   to the now-visible receiver — this is the missing "initiate the receiving" step. NFC does not carry it.
3. Both ends must RESET after each attempt (sender tap/reader state; receiver surface) so a re-tap works.
4. The "no confirm" auto-accept is self-share (same account) — likely a confirm will appear for Super Drop → QS;
   that's a stock-QS limitation, not our bug.

## ★ COMPLETE ORDERED MAP — WHAT ORIGINAL QUICK SHARE DOES FOR THE NFC TAP (GMS 26.18.33, verified by 4 parallel decompile readers, file:line evidence in journal history)

### A. SENDER, when the share sheet is open (class dpst.i — ShareTarget + attachments)
1. Arms NFC reader mode: `enableReaderMode(flags=0x181 = NFC-A|SKIP_NDEF|NO_PLATFORM_SOUNDS, presence=100ms)` (dnzf/dnzn/dnzj; dnzp.c gates on nfc feature+permission+adapter-enabled).
2. On tap → `dnzh.onTagDiscovered` → rebroadcasts `TAG_DISCOVERED`(setPackage gms, extra TAG) to GMS; the reader callback is NON-BLOCKING. The actual IsoDep exchange is `djkb.c(Tag)`.
3. Starts **FastInit BLE SCAN** (looking for receivers; "Starting scanning for Fast Initiation") — the send sheet SCANS; it does NOT emit FastInit advertising (advertising is tied to the receive surface).
4. Starts Nearby **startDiscovery** per-medium (NFC/BLE/BT/WIFI_LAN/WIFI_AWARE), gated by the DiscoveryOptions mediums array (`dfad.call`). So the sender is concurrently DISCOVERING over Wi-Fi/BLE.

### B. The NFC tap exchange (sender = reader, `djkb.c`)
- SELECT: `00 A4 04 00 05 F0 00 00 FE 2C` (no Le byte).
- ADVERTISEMENT: `80 01 00 00 <Lc> <hhww> FF` (trailing **Le=0xFF**). The sender's `hhww` populates **all three fields**: serviceId="NearbySharing"(#1) + **localEndpointId(#2)** + **endpointInfo(#3)** — it advertises ITSELF to the receiver.
- Receiver HCE responds `hhwv`: c=`deym`(version+PCP+endpointId+serviceIdHash+endpointInfo+**6-byte BT-Classic MAC**), d=rxAdv connectivity caps (incl WIFI_LAN), e=extra. **If the receiver is idle (not advertising) → HCE fires the WAKE (djvf.f → opens MainActivity) and returns 0000.**
- Reader parses hhwv (`dfdo.run`): registers the WIFI_LAN/NFC instant-connection endpoint FIRST, then checks the BT MAC; **if the tag has NO BT-Classic MAC, dfdo.run ABORTS the BT medium (return-void)** — but the WIFI_LAN/NFC endpoint is already registered.
- NFC can carry data (CONNECT `80 02` + DATA `80 03` stream) but is just one medium; the connection layer (`dfck.a`) picks among NFC / WIFI_LAN / BLUETOOTH by a server-priority sort — typically upgrading to WIFI_LAN.

### C. Idle receiver → WAKE → becomes connectable
- Wake `djvf.f` fires PI → opens `…nearby.sharing.main.MainActivity` (extra is_from_fast_init=true).
- MainActivity registers a FOREGROUND **ReceiveSurface** → the receiver starts **ADVERTISING** over Nearby (BLE + BT-Classic→Wi-Fi upgrade). (No NFC advert re-registration observed on this path; the FastInit HUN/BLE drives it.)
- The phones have separated by now (NFC dropped) → the rendezvous completes over the NORMAL Nearby channel: the SENDER's concurrent discovery (A.4) finds the now-advertising receiver and connects (medium priority; WIFI_LAN possible). NFC tag is NOT needed post-wake.

### D. Auto-accept (NO confirm) — three paths (skipLocal=true), set in `drbk`
1. **self-share** = sender+receiver on the SAME Google account (certificate match: `dqne.c`/`dqtt.e`/`TransferMetadata.isSelfShare`). 
2. `isIncomingConnection` (a Nearby-Connections-engine flag).
3. QR-code / out-of-band handshake authenticator.
The TAP itself does NOT grant no-confirm. ⇒ a non-Google-account sender (Super Drop) generally CANNOT get the silent auto-accept — expect a confirm (self-share needs same account; the other two need a Connections-internal flag / a QR handshake).

### E. Teardown (so retry works)
- Sender reader mode auto-disposed via a Compose DisposableEffect (`dnzk.disableReaderMode`) when the share UI leaves composition.
- Connect failure (all media tried) → `dfck.a` throws `dfcj` → reported via `dfet.X`.
- Discovery/advertising torn down + per-service state cleared by `dfhl` when the last client stops.

### ⇒ THE ESSENCE TO REPLICATE
The NFC tap in original Quick Share is a NON-BLOCKING **wake + advertise-self** gesture, fully DECOUPLED from the transfer. The transfer always rides the normal Nearby Connections discovery/connect (Wi-Fi/BLE), which the sender runs concurrently. To "do what Quick Share did": (1) tap arms a non-blocking reader; (2) on tap, hand the tag to the connection layer AND keep the normal discovery running so the woken/advertising receiver is found and connected over Wi-Fi/BLE — do NOT block or dead-end on the NFC ADVERTISEMENT re-poll; (3) match the real APDU framing (SELECT no-Le; ADVERTISEMENT Le=0xFF; hhww with localEndpointId+endpointInfo); (4) reset reader/discovery state after each attempt; (5) accept that a confirm will appear (no self-share for a non-account sender).

## ★ OURS vs ORIGINAL QUICK SHARE — THE DISCONNECT (research comparison, 2026-06-10)
Our impl read: `app/.../nfc/SuperDropTapReader.kt` + `core-protocol/.../nfc/QuickShareNfcCodec.kt` (verified this session).

| Aspect | Original Quick Share (GMS, verified) | Super Drop (ours, verified) | Impact |
|---|---|---|---|
| **Tap architecture** | Reader callback `dnzh` is **NON-BLOCKING**: rebroadcasts the Tag and returns; the connection runs ASYNC in the Nearby engine | `onTag` **BLOCKS** on the binder thread doing a 2.5s ADVERTISEMENT re-poll loop; only calls `onPeerTapped` if it gets a tag | **CORE** |
| **Coupling to transfer** | Tap is DECOUPLED — transfer rides the **concurrent Nearby discovery/connect**; the tag is just one of several endpoints fed in; idle receiver is found via discovery AFTER the wake | Tap is the **SOLE** path: if the NFC re-poll returns `0000` (idle receiver waking), it returns null → **gives up**, no fall-through to discovery/connect | **CORE — this is why it wakes but never receives** |
| **Endpoint sources** | Registers NFC endpoint + **BT-Classic** endpoint + Wi-Fi-LAN caps; connection layer picks medium by priority | Requires the hhwv tag to carry a **Wi-Fi-LAN rxAdv**, else returns null; no BT-Classic path, no discovery fallback | Major |
| **SELECT bytes** | `00 A4 04 00 05 F00000FE2C` (no Le) | `00 A4 04 00 05 F00000FE2C 00` (trailing Le=00) | Minor (likely tolerated) |
| **ADVERTISEMENT Le** | `... FF` (Le=0xFF) | `... 00` (Le=0x00) | Possible — wrong max-resp length |
| **hhww fields** | serviceId(#1) + **localEndpointId(#2)** + **endpointInfo(#3)** — sender advertises ITSELF | serviceId(#1) **only** (codec supports field2/3 but reader passes null) | Major — receiver can't learn the sender's endpoint to connect back (HCE g!=null branch uses #2/#3 via djvf.d) |
| **Reader flags** | `0x181` = NFC-A \| SKIP_NDEF \| NO_PLATFORM_SOUNDS; presence 100ms | NFC-A \| **NFC-B** \| SKIP_NDEF; presence null(default) | Minor |
| **Teardown** | Reader auto-disposed (Compose); discovery/advertising cleared by `dfhl` on last client | `tapReader?.disable()` on tap/destroy; no discovery to reset; stuck if exchange half-completes | Contributes to "won't retry" |

**THE DISCONNECT (one sentence):** original Quick Share uses the tap as a *non-blocking wake* and lets the file transfer complete over a *concurrently-running Nearby discovery/connect* (so an idle receiver is woken, then found via discovery and connected); Super Drop instead makes the tap a *blocking, all-or-nothing NFC exchange* that only connects if the receiver hands back a Wi-Fi-LAN tag during the 2.5 s re-poll — so when the receiver is idle (returns `0000` = wake), ours wakes it but then gives up instead of falling through to discover-and-connect. Secondary divergences (ADVERTISEMENT Le=0x00 vs 0xFF; hhww missing localEndpointId+endpointInfo; no BT-Classic endpoint) would also block the case where the receiver IS already advertising.

## VERIFIED 2026-06-10 — QS reader is ONE-SHOT (no re-poll); + the one remaining gap for a 110% fix
- `djkb.c(Tag)` (GMS): opens IsoDep → `djvh.d()` SELECT → iterates the registered services and sends **ONE**
  ADVERTISEMENT per service (`djvh.b`), tracking a "already read advertisement for service %s" set to AVOID
  re-reading. On an empty/failure response (`djvb.d()`=true, the 0000 wake) it does NOT re-poll the NFC — it
  abandons NFC; the woken receiver's transfer completes over the SEPARATE Nearby discovery. So QS = one-shot
  wake. Our `SuperDropTapReader` does a 2.5s/11-attempt re-poll loop = divergent (waits on NFC vs hands off).
- User CONFIRMED: the underlying Super Drop↔QS transfer ALWAYS worked since the beginning — do NOT touch it;
  only the NFC tap is broken. This RESOLVES the prior make-or-break unknown (transfer interop works).
- Our app ALREADY has a safe "wake→watch discovery→auto-connect, prefer Wi-Fi-LAN" pattern: `onQrPeersResolved`
  / `chooseQrMatch` (the QR-link share path). The NFC tap fix would mirror it.
- ⚠️ THE ONE THING NOT YET 110%: how the QS sender IDENTIFIES the specific woken receiver to auto-connect to,
  since the 0000 wake returns NO endpointId. Candidate (strong, not fully pinned): the woken receiver
  advertises FastInit BLE (FE2C/FC128E) which the SENDER (scanning FastInit) detects = proximity correlation.
  The QR path identifies by a QR key; the NFC tap has no such key. Until this is pinned, an auto-connect to
  "the QS peer that appears after the tap" is a heuristic (safe for the common single-tap case, unproven vs QS).
- ⇒ NOT implementing yet (per user's 110% bar): diagnosis + architecture are verified; the woken-receiver
  identification correlation is the single unpinned link. Options: (a) pin it from the decompile; (b) ship a
  bounded/observable heuristic + device-verify.

## ✅ STEP 1 PINNED (2026-06-11) — identification is POSITIONAL (no token); connect via discovery
- QS RECEIVE surface → ADVERTISES (strings: "Stopping advertising because no receive surface is registered" L22421;
  "Start advertising with mode %s…" L23817; "Cancelling the Fast Init HUN because we're now advertising with a
  foreground receive surface" L24684). QS SEND surface → SCANS/DISCOVERS ("…we don't have a scanning send surface" L7458).
- NFC tag (startNfcAdvertising/djvf.h) is registered ONLY on the startDiscovery path ⇒ **a woken RECEIVER (which
  advertises, does not discover) does NOT present an NFC tag.** A re-read tap → `0000` again.
- HCE wake branch (g==null) does NOT stash the sender's endpoint (djvf.f only; djvf.d runs only on g!=null). So
  the wake carries NO identity. ⇒ **No token. The QS sender connects to the woken receiver its DISCOVERY surfaces
  (positional/temporal: the device that wakes+advertises right after the tap).** Single tap = unambiguous.
- ⇒ FIX (matches QS, reuses proven `onQrPeersResolved` shape): tap = ONE-shot read (no 2.5s blocking loop). On a
  real tag (receiver already advertising) → existing onPeerTapped→connect. On `0000` (wake) → arm a bounded
  "watch discovery, auto-connect to the woken QS receiver, prefer Wi-Fi-LAN, once" window; reset for retry.

## STEP 2 PRE-BUILD RISK PASS (2026-06-11)
1. ASSUMPTIONS: (a) our discovery surfaces the woken QS receiver over mDNS/Wi-Fi — VERIFIED our picker already
   resolves QS devices (trace `endpoint:L6DT`); whether it appears IN TIME post-wake = device-pending. (b) the
   existing onPeerSelected connect path works to QS — USER-CONFIRMED (transfer always worked). (c) reader stays
   armed after a failed tap — VERIFIED (we only disable on success/destroy).
2. UNKNOWNS (make observable): does the woken receiver appear in our discovery within the window? → log it. Does
   it auto-accept? → expect a confirm (non-self-share, verified). Both device-pending, surfaced via diagnostics.
3. PRECONDITIONS: send sheet open (discovery running) — VERIFIED peerPickerController.start() at SendActivity:304.
4. ALL ENTRY POINTS: SuperDropTapReader.onTag/exchange (one-shot + wake signal); SendActivity onNfcPeerTapped
   (unchanged, real-tag path) + NEW onTapWake→arm window + the discovery resolved callback (mirror onQrPeersResolved).
   Do NOT touch the transfer/connect (onPeerSelected→buildOutboundConnection) — user-protected.
5. CROSS-CUTTING: reader callback on binder thread (marshal to UI as onNfcPeerTapped already does); window must
   be single-shot + time-bounded + cleared on connect/teardown (avoid wrong-device / stale auto-connect); no
   main-thread block (remove the 2.5s sleep loop — improves ANR posture).
6. OBSERVABILITY: log "tap: real tag → connect" vs "tap: wake (0000) → arming discovery auto-connect (Ns)"; log
   the woken peer resolved + chosen; log window expiry. All via existing DiagnosticLog/collector.
7. VERIFY: device test = tap idle QS phone (BT+location+visible), watch it open + receive (or confirm). I cannot
   drive it; hand the user a precise test. APDU framing fixes (Le=0xFF, hhww+localEndpointId+endpointInfo) for
   the real-tag path included.

## ⛔ CORRECTION (2026-06-11) — my earlier "Super Drop emits no BLE advertisement" was WRONG
The DECISIVE-section claim "we do NOT emit any BLE advertisement" is FALSE — it was grepped only over `app/`.
Super Drop HAS a full Quick Share interop stack (consistent with user: "the app worked perfectly with QS"):
- `discovery-android/.../ble/BleQuickShareAdvertiser.kt`, `core-protocol/.../endpoint/BleServiceData.kt` — emits
  the BLE FastInitiation advertisement (FE2C / FC128E), endpointId threaded as `secret_id_hash` (type=NOTIFY).
- `app/.../send/SendActivity.kt:237 startSenderGattServer()` + `discovery-android/.../bootstrap/BleGattInitialControlServer.kt` — GATT bootstrap.
- `discovery-android/.../NearbyPeerDiscovery.kt` — discovery; `core-protocol/.../connection/OutboundConnectionDriver.kt` — the transfer (works).
⇒ So the send sheet ALREADY emits FastInit + runs discovery + can connect/transfer to QS. The ONLY gap is the NFC
tap not triggering that existing flow on a 0000 wake. The whole earlier "build a FastInit emitter" detour was
doubly wrong (we already have it). Pausing implementation to READ the existing FastInit/GATT/discovery/connect
path before wiring the tap into it (per the 110% bar). NEXT (step 2, corrected): read BleQuickShareAdvertiser +
NearbyPeerDiscovery + OutboundConnectionDriver + onPeerSelected to learn exactly what the tap must hand off to.

## ⛔ CORRECTION (2026-06-11) — FastInit is NOT part of the NFC mechanism (do not conflate)
User challenged: is FastInit related to NFC, or just the original BLE→P2P sharing? VERIFIED: BLE→P2P only.
- The NFC HCE `NfcAdvertisingChimeraService` references FastInit **0 times** (grep count = 0). The NFC tag
  exchange (SELECT/ADVERTISEMENT/CONNECT/DATA) + tag registration (`djvf.h ← deyl.i ← dfad/dfet/djkb`, the Nearby
  Connections discovery layer) are INDEPENDENT of FastInit.
- FastInit (`dnmj`, FE2C/FC128E BLE adv) → scan → "Device nearby is sharing" HUN = the BLE proximity path.
- ONLY crossover (incidental): the NFC wake PI (`djvf.i`, L29347) is registered inside `X()`, the FastInit-scan
  method (next to `dnmj.q` FastInit scan start, L29365). So both need "foreground nearby presence," but FastInit
  does NOTHING in the NFC tag exchange. 
⇒ For the NFC-tap fix, FastInit is a RED HERRING. The earlier "build a FastInit emitter" detour + the FastInit
frame map were chasing the BLE path, not NFC. NFC-tap fix = tag exchange + wake + hand off to the (already-working)
connection. Do NOT drag FastInit into the NFC fix.

## ✅ IMPLEMENTED (2026-06-11) — NFC tap = one-shot wake → hand off to discovery auto-connect (compile-clean)
Built `:app:assembleDebug` SUCCESSFUL; `super-drop-debug.apk` refreshed. Matches stock Quick Share's tap mechanic.
- `SuperDropTapReader.kt`: tap is now ONE-SHOT (SELECT + ONE ADVERTISEMENT, no 2.5s re-poll loop — matches QS
  `djkb.c`). New result type `TapResult{Resolved|Woke|Failed}`. Real tag → `onPeerTapped` (unchanged). SELECT-ok
  but empty ADVERTISEMENT (`0000`) → `Woke` → new `onTapWake()` callback. SELECT-fail/IO → `Failed`. Removed the
  binder-thread `Thread.sleep` (less ANR risk).
- `SendActivity.kt`: `onNfcTapWake()` opens a 15s single-shot window (`TAP_WAKE_WINDOW_MS`); the discovery stream
  (`onSendPeersResolved` now feeds BOTH the QR path and the tap-wake path) auto-connects to the first hidden/
  nameless (Quick-Share-like) peer, Wi-Fi-LAN preferred, via the SAME `onPeerSelected` the picker/QR use — the
  transfer path that already works (NOT touched). Re-arms on every tap (fixes "second tap did nothing").
  `SendPeerPickerController.resolvedPeers()` added so a peer discovered BEFORE the tap is also considered.
- STATUS: **compile-clean; DEVICE-UNVERIFIED.** The make-or-break (does the woken stock QS advertise reachably +
  accept our inbound connect, expecting a confirm for non-self-share) is the on-device tap test. Deferred refinements:
  ADVERTISEMENT Le 0x00→0xFF + hhww localEndpointId/endpointInfo (only matter for the already-advertising case).

## TIMING (2026-06-11) — why QS→QS is instant; our 15s is a FALLBACK ceiling, not a delay
User: QS→QS tap was ~instantaneous. VERIFIED why: a VISIBLE Quick Share phone advertises in the BACKGROUND
("Starting a sync for background advertising" NearbySharingChimeraService L23169; gated on isVisibleToSomeSender
L27868), app closed. So the SENDER has ALREADY discovered the receiver before the tap; the tap connects to the
already-known peer instantly (+ wakes the receiver UI) — no discovery wait on the critical path.
Our code handles the instant case: `onNfcTapWake` immediately re-checks `peerPickerController.resolvedPeers()`
and auto-connects if the receiver is already discovered (earlier trace proved we discover a visible QS device
over mDNS, `endpoint:L6DT … WIFI_LAN`). So instant when visible. `TAP_WAKE_WINDOW_MS`=15s is ONLY the fallback
ceiling for a not-yet-discovered (colder/visibility-off) receiver — it does NOT delay the instant path.
NOTE: the instant path REQUIRES the receiver to be discoverable (visibility on) so we have it pre-tap — same
precondition QS itself needs.

## OBSERVABILITY ADDED 2026-06-11 (all-at-once, on-screen, no-internet) — after the fix failed with no trace
User's new-build test left ZERO collector entries (collector path is INTERNET-dependent + fragile; the sender
phone's POSTs never arrived). So we were blind. Added comprehensive, on-SCREEN observability across the WHOLE
tap path, NO silent failure paths:
- `SuperDropTapReader`: new `onTapDiagnostic` callback fires for EVERY tap with the raw SELECT/ADV bytes +
  outcome (RESOLVED/WOKE/FAILED). 
- `SendActivity`: `onNfcTapDiagnostic` → Toast + log every tap outcome. `onNfcTapWake` Toasts "WOKE — finding it
  (N peers)" + posts a window-END check that Toasts "NO Quick Share receiver found in 15s (saw N: …)" if nothing
  connected. `onNfcTapWakePeersResolved` LOGS every discovery eval (each peer's hidden/name/lan/connectable) and
  Toasts "connecting to X over wifi-lan" on auto-connect. Connect outcome already shows in the status panel.
- ⇒ each failure mode is now a distinct ON-SCREEN Toast the user can screenshot (no internet/adb/shake needed):
  no-tap-Toast=reader didn't fire; FAILED=not the QS HCE; WOKE+no-receiver=woken phone never appeared in our
  discovery; connecting+status-panel-fail=connect-level. Full trace also in DiagnosticLog ring (shake bug report).
- STATUS: build SUCCESSFUL, `super-drop-debug.apk` refreshed. Instrumentation; behavior of the fix unchanged.

## 🎉 WORKS ON DEVICE (user-verified 2026-06-11) — tap initiates the Quick Share transfer
USER confirmed on real phones: the NFC tap now wakes the receiver AND the share starts/transfers. The one-shot
wake → hand-off-to-discovery auto-connect fix is correct end-to-end (user drove the real UI; I could not).
REMAINING DIFFERENCE (user): stock Quick Share QS↔QS "just started" with NO accept dialog; ours shows a confirm.
That = the SELF-SHARE behavior — QS↔QS on the SAME Google account auto-accepts (no dialog). Super Drop is not a
same-account device, so stock QS shows a confirm (expected). NEXT: verify from QS code whether the no-dialog is
reachable for our sender (self-share cert / isIncomingConnection / QR-handshake) before promising it.

## VERDICT 2026-06-11 — the receiver accept DIALOG is a ceiling (no-dialog NOT achievable for a non-account tap)
Chased the no-dialog (`skipLocal`) gating (user OK'd chasing; decided to leave it):
- `drbk` skipLocal=true iff: (1) self-share (`Ldqtt.e`), OR (2) `drbk.i`, OR (3) QR-handshake (`drcs.h`+`dqwb.b`).
- (2) `drbk.i` ← `dqud.d` ← `Ldelj.e`. `dqud.toString` = `Connected(endpointInfo, rawAuthenticationToken,
  authenticationDigits, isConnectionVerified)` ⇒ `drbk.i` = **isConnectionVerified** (a Nearby Connections flag).
- ⇒ all three are OUT-OF-BAND AUTH: self-share = same-account cert (NOT forgeable by a 3rd-party app);
  isConnectionVerified = the connection's auth token confirmed (our post-wake connect is Wi-Fi-LAN, not a
  trusted OOB channel, so not verified); QR-handshake = a QR pairing, not a tap.
- CONFIDENCE: skipLocal←isConnectionVerified←delj.e VERIFIED from smali; what sets `delj.e` deep in the
  Connections engine (classes13) NOT fully traced = strong inference. TENTATIVE dead-end: skipping the dialog
  for a non-account tap is almost certainly NOT achievable; the dialog is stock QS security. User decision: LEAVE IT.

## ✅ DONE — NFC tap initiates Quick Share transfer (user-verified on device). Remaining diff = receiver confirm
dialog = stock QS behavior for a non-same-account sender (ceiling above). Feature complete for the goal.

## 2026-06-11 — toggle did NOT break sending (proven by diff); fix is INTERMITTENT, need a failing-tap trace
User: "it was sending before" then broke after the toggle build. VERIFIED via `git diff d47fd44(obs,worked)
80f0e6b(toggle)`: the ONLY send-path change is 4 lines in SendActivity (import + `if(!isEnabled())return` Toast
gate + lazy field); `SuperDropTapReader` + `SendPeerPickerController` are byte-identical; toggle defaults ON so
Toast behavior is unchanged too. ⇒ the toggle is functionally INERT for sending — it cannot have broken it.
⇒ Real cause = the fix is INTERMITTENT (worked the once the receiver was already discovered → instant connect;
a colder run can miss the 15s window / the woken receiver may not surface in our discovery). This is the
known reliability risk, not a regression. NEXT (need data, no code guess): user taps once more, screenshots the
on-screen Toasts (ON by default) — distinguishes WOKE+no-receiver (discovery/visibility/timing) vs connect-fail
vs no-tap-read. Then harden the SPECIFIC failing step (candidate: widen/repeat discovery re-check across the
window instead of only on discovery events; relax/verify isLikelyQuickShareReceiver; extend window).

## 2026-06-11 — tap-share WORKS (user); separate issue: a Bluetooth PAIR request appears during the tap
VERIFIED (our code): Super Drop initiates NO Bluetooth pairing — every BT socket is INSECURE:
`BluetoothClassicBootstrapClient/Server` = `createInsecureRfcommSocketToServiceRecord` /
`listenUsingInsecureRfcommWithServiceRecord`; `BluetoothL2capIo` = `createInsecureL2capChannel` /
`listenUsingInsecureL2capChannel`; no encrypted GATT chars; `SendBootstrapPlan` marks `BluetoothClassic` an
"Unsupported route". Insecure sockets don't pair. Nearby/Quick Share itself is pairing-free. ⇒ the pair prompt
is NOT from our connect.
LIKELY SOURCES (unverified — do NOT guess/change code yet): (a) an OEM NFC→Bluetooth handover / "tap to pair"
feature reacting to the NFC tap; (b) Google Fast Pair (NFC/BLE-triggered); (c) Google Quick Share on one of the
phones reacting to the tap; (d) the QS receiver's own Nearby bootstrap. BT pairing is mutual so the prompt can
appear on EITHER phone.
TO PIN IT — need from user: (1) WHICH phone shows the pair request (the Super Drop sender, or the QS receiver?),
(2) what DEVICE NAME the pair request names. Plus the auto-uploaded trace (shows the connect medium used).
