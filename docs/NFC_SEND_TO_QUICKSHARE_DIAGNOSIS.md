# Diagnosis & Fix Plan — Super Drop SENDER → tap → NATIVE Google Quick Share RECEIVER

> **THIS FILE IS THE TASK JOURNAL** (per the task-journal rule). Update the block below after every
> turn; append a dated entry on every discovery. A fresh session should resume from here + the memory index.

## CURRENT STATE / NEXT STEP   (updated 2026-06-10 15:1x)
- **⛔ SCOPE CORRECTION (user, 2026-06-10, emphatic):** the Super Drop app ALREADY worked perfectly with
  Quick Share — DO NOT rewrite it or change what it did. The ONLY broken thing is **the NFC tap initiating a
  share**, and that is the ONLY thing to fix. FORBIDDEN: building new behaviors (e.g. a BLE FastInitiation
  advertiser while the share sheet is open — REJECTED), and analyzing Super Drop / bada to engineer the fix.
  ALLOWED: analyze **Quick Share only** to learn how its NFC tap initiates a share, then make our tap do that.
- **GOAL (user, 2026-06-10, FINAL):** find out exactly what the ORIGINAL Google Quick Share does for its
  NFC-tap-to-share, and make our tap do THAT. Map the COMPLETE ORDERED flow from GMS first (no piecemeal),
  then replicate. Mapping in progress via parallel GMS decompile readers; see §COMPLETE FLOW MAP (building).
- **DONE (verified this session):**
  - Round-2 device trace captured (`/root/nfc-diag/collector.log`): SELECT=`9000` OK, ADVERTISEMENT=`0000`
    on all 11 re-poll attempts + later taps. Re-poll hypothesis OVERTURNED; not a byte bug, not timing.
  - Full registration map proven from baksmali of all 15 GMS 26.18.33 dexes (`/root/nfc-diag/gms-smali`):
    a QS phone returns a real NFC tag ONLY while running **NFC-enabled startDiscovery** (the QS *send*
    sheet open). Idle/receiving phone → `djvf.g==null` → HCE returns `0000`. Wake (`djvf.f`) is a no-op
    unless FastInit scanning registered a wake PI (screen unlocked + QS enabled). See the dated entry below.
  - Reader instrumentation (hex SELECT/ADV bytes + tag-loss timing) already shipped in `super-drop-debug.apk`.
  - Collector revived + `COLLECTOR_URL` re-baked to live tunnel (for any follow-up trace).
- **OPEN DECISION FORK (needs user):** (A) use the tap only as a trigger and connect to the QS phone over
  mDNS/Wi-Fi-LAN (`192.168.1.139:53601`, already resolved in-trace) with NO tag — verify stock QS accepts
  an inbound Nearby connection while idle/visible; (B) require the receiver to be on the QS *send* sheet for
  the tag path; (C) accept tap-to-a-QS-*receiver* as a QS design limitation.
  - **Fork (A) sender side ALREADY EXISTS in the shipped APK (code-proven this session):** the QS mDNS
    device reaches our picker as a `NearbyPeerEvent.Resolved` (the trace's `discovery: resolved peer=endpoint:L6DT`
    line is emitted by `SendPeerPickerController.onDiscoveryEvent`); the picker filters ONLY on
    `SendBootstrapPlan.resolve(peer).isConnectable`, NOT on `hidden`; a WIFI_LAN peer with a primary address +
    port (`192.168.1.139:53601`) yields a `NearbyPeerRoute.Lan` → `isConnectable=true` → it renders as a
    selectable chip "Quick Share device (L6DT)". Tapping that chip → `onPeerSelected` → the same Wi-Fi-LAN
    connect loop the NFC tap uses (`SendActivity onNfcPeerTapped→onPeerSelected→604→671`). So NO NFC tag and NO
    new build are needed to ATTEMPT a Wi-Fi-LAN send to a visible QS phone.
- **NEXT STEP (no build needed — device test of fork A):** on the sender, open Super Drop's send sheet, pick a
  file, set the OTHER phone's Quick Share to visible/"Everyone", wait for the **"Quick Share device (XXXX)"**
  chip to appear in the picker, and TAP THE CHIP (not the NFC tap). Observe: does our app connect over Wi-Fi-LAN
  and does the QS phone show an incoming-transfer prompt? PROVEN-from-code = the chip appears + we dial the LAN
  endpoint; UNVERIFIED = whether stock QS ACCEPTS our inbound Nearby Connections handshake (the make-or-break
  unknown). If it connects → tap was never needed. If it fails at the handshake → map our outbound Nearby
  connection vs what stock QS's WIFI_LAN listener expects.
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
