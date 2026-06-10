# Diagnosis & Fix Plan — Super Drop SENDER → tap → NATIVE Google Quick Share RECEIVER

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
</content>
