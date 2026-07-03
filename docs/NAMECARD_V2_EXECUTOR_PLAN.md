# Name Card v2 — Symmetric NameDrop (both-background tap → mutual consent → BLE swap)
# EXECUTOR-GRADE PLAN — written so a weaker model can build it without improvising.

> **2026-07-02 UPDATE — no Phase-0 gate.** The wake mechanism is documented + spec-guaranteed;
> the real bada-fork feature is its own wake probe via the diagnostic log. Phase-0 harness
> `/root/agent-work/projects/namecard-tap-harness` was built (git `8a129ba`, served at
> `https://204-168-163-118.sslip.io/trackers/static/namecard-tap-harness.apk`) and is RETAINED
> as an isolated clean-room diagnostic tool, but is NOT a prerequisite. Phases 1–3 build directly
> and are gated behind a `nameCardV2` debug pref (default OFF), so the shipped asymmetric flow
> stays untouched until the user flips the pref and tests the new flow on-device.

## 0. EXECUTION RULES (read first, follow always)
1. Repo `/root/agent-work/projects/bada-fork`, branch `fork/superdrop-ui`. Find the build command in
   the repo's README/CHANGELOG/prior commits — do NOT guess gradle paths; the repo's own docs are
   authoritative. Commit + CHANGELOG entry after EVERY phase (granular).
2. Journal = `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`; spec = `docs/NAMECARD_CONSENT_REDESIGN_PLAN.md`
   (§2 scenarios, §3 matrix). Read both before coding. Update the journal after every discovery/turn.
3. **Never guess a byte, slot, or API behavior.** If anything below is ambiguous, read the named
   reference (file paths given) before coding. If still unresolved → STOP and ask the user; do not
   substitute a plausible value.
4. **Never claim device-verified.** Everything from this box is "compile+unit-test verified only";
   on-device steps ship as ordered user scripts.
5. No physics-bounce animation specs (no `spring()`, no bare `animate*AsState`) — `tween()`+easing only.
   Write the word as "bounce" everywhere.
6. Do not spawn sub-agents. Do not use `sleep`. APK must land at repo top level after builds.

## 1. Context
Shipped Name Card (PR #247) is asymmetric: sharer must OPEN the app (foreground NFC reader-mode);
"Receive Only" silently disconnects. Target = real NameDrop: **both phones awake+unlocked, app CLOSED
on both** → tap → both apps wake → each user独立 taps **Share / Receive Only** → both react live.
Research (2026-07-01/02, AOSP source + ECMA-340, persisted in memory
`reference_android_both_background_nfc_trigger_apis_2026_07_01.md`) proved: OS polls AND HCE-listens
simultaneously on every awake+unlocked phone (NfcService.java:4554,4569-73); ECMA-340 RF collision
avoidance makes first-to-field the reader, other stays card; AAR launches the reading phone's app
from closed. Only open number = per-tap success % → measured in Phase 0.

## 2. Existing code map (verified this session — REUSE, don't rewrite)
All under `app/src/main/kotlin/dev/superdrop/`:
- `nfc/NameCardHceService.kt` — HCE, proprietary AID `F0534443415244` ("F0"+ASCII SDCARD), APDUs:
  SELECT-by-name → `9000`; EXCHANGE `80 10 00 00 00` → 17B bootstrap+`9000`; locked → `6982`;
  disabled-pref → `6A82`. On EXCHANGE: `NameCardBootstrapHolder.newSession()` then
  `NameCardExchangeService.start(this, token)`. AID xml: `res/xml/superdrop_namecard_apduservice.xml`.
- `nfc/NameCardTapReader.kt` — foreground reader-mode (`enableReaderMode`, IsoDep SELECT→EXCHANGE).
  Will be PARKED (debug-only) — see Phase 1 step 4.
- `nfc/NameCardBootstrapHolder.kt` — mints session `NameCardBootstrap` (version + 16B token).
- `namecard/NameCardBleExchange.kt` — GATT both roles. `startServer(token, localCard, …)` =
  advertise token as service data (`SERVICE_DATA_UUID`) + serve card on `CARD_CHARACTERISTIC_UUID`;
  `startClient(token, …)` = scan for token, connect, read card; `shareBack(localCard)` = client
  writes own card; `declineShare()`; `stop()`. Grep the exact UUID constants in this file.
- `namecard/NameCardExchangeService.kt` — FGS wrapping the server role (BT-grace retry loop).
- `namecard/NameCardTransferActivity.kt` — the transfer screen; `setupServer()` (peer card already
  arrived → Save/Done), `setupClient()` (connecting), `onPeerCardReceived`, ripples
  `playTriggerRipple`/`playSendRipple` (tester-approved AGSL + `rippleTriggerStartT` hook),
  `ShareRadioController`/`RadioHelperClient` for BT-on, `NameCardSaver` for ContactsContract.
- Tests pattern: `app/src/test/kotlin/dev/superdrop/namecard/NameCardResolverTest.kt` (plain JVM).

## 3. Target flow
```
tap (both closed, awake+unlocked; OS arbitrates roles)
├─ CARD phone: OS routes T4T APDUs → SuperDropNdefApduService (owns D276..., see G2) → serves NDEF{token-record, AAR}
│    on NDEF READ: newSession() → NameCardExchangeService (BLE server) → TransferActivity(SERVER)
└─ READER phone: OS tag dispatch reads NDEF → AAR/intent-filter launches
     TransferActivity(CLIENT) with the NdefMessage → parse token → BLE scan+connect
THEN identical UX both sides: own card + [Share][Receive Only]; live CONSENT channel; §3 matrix.
```

## 4. PHASE 0 — Tap-reliability harness (BUILT 2026-07-02; diagnostic tool, NOT a gate — see top banner)
New tiny standalone project `/root/agent-work/projects/namecard-tap-harness` (copy the dep-free
`namecard-tester/build.sh` build pattern; own applicationId `com.namecard.tapharness`; git init +
CHANGELOG per APK-project rule).
Contents:
- `HarnessHceService` implementing the FULL T4T machine of §5 below, NDEF = AAR(`com.namecard.tapharness`)
  + text record with a per-boot counter.
- Manifest: `NDEF_DISCOVERED` filter (§6) on `HarnessActivity`; `android.permission.NFC`.
- `HarnessActivity`: full-screen scrolling log ("bigLogList — white monospace log lines filling the
  screen; newest on top"), each line timestamped: `WOKE-AS-READER (ndef=<payload>)` when launched by
  tag dispatch / `WOKE-AS-CARD (T4T read reached NDEF READ)` when the HCE served the NDEF file, plus
  every APDU received (hex) — observability is the whole point. Persist log to a file; "Copy log" button.
- Serve APK at the Caddy static dir (`/root/agent-work/projects/tracker-bridge/static/` →
  `https://204-168-163-118.sslip.io/trackers/static/<name>.apk`); curl-verify HTTP 200.
User script (write into the harness README): install BOTH phones → open once (stopped-state) →
close app both sides → 20 taps; per tap record: which phone logged READER, which CARD, or nothing;
retap when dead. Also: 5 taps with one phone LOCKED (expect nothing — HCE unlock gate),
and on the OnePlus check whether `WOKE-AS-CARD` ever fires (AID conflict check, §9-G1).
**NO GATE (user decision 2026-07-02):** Phases 1–3 build directly; the `nameCardV2` debug pref
(default OFF) protects the shipped flow until the user flips it on and tests on-device. The harness
stays as a clean-room repro tool if the real feature misbehaves.

## 5. T4T NDEF emulation — byte-exact spec
AID (NFC Forum Type-4 NDEF application): `D2 76 00 00 85 01 01`.
**Byte source (VERIFIED 2026-07-02):** every APDU/CC value below is confirmed against the NFC Forum
Type-4 Tag Operation spec AND the known-good open reference
`MichaelsPlayground/NfcHceNdefEmulator` (`MyHostApduService.java`, read this session): its
SELECT_APPLICATION, CC bytes, SELECT_CC/E103, SELECT_NDEF/E104, READ-BINARY-offset handling, and
status words (`90 00` success / `6A 82` not-found) all match. Do NOT invent bytes; if a value ever
needs to change, re-derive from that spec + reference, not from guessing.
APDU conversation the stock Android reader performs (respond exactly; all else → `6D00`):
1. `00 A4 04 00 07 D2760000850101 00` (SELECT NDEF app by name) → `90 00`.
2. `00 A4 00 0C 02 E1 03` (SELECT CC file) → `90 00`.
3. `00 B0 0000 0F` (READ CC, 15B) → CC bytes + `90 00`. CC (15B, byte-exact from the verified ref):
   `00 0F` (CCLEN=15) · `20` (T4T v2.0) · `00 3B` (MLe=59, max bytes per READ) · `00 34` (MLc=52) ·
   `04 06 E1 04 00 FF 00 FF` (NDEF-file control TLV: T=04, L=06, fileId `E1 04`, **max NDEF size
   `00 FF`=255**, read-access `00`=open, write-access `FF`=locked → read-only). Our NDEF (~80B: token
   record + AAR) fits in 255; if the served NDEF ever exceeds 255B, raise this field AND MLe together.
4. `00 A4 00 0C 02 E1 04` (SELECT NDEF file) → `90 00`.
5. `00 B0 0000 02` (READ NLEN) → 2B big-endian NDEF length + `90 00`.
6. `00 B0 <offset> <le>` (READ NDEF, chunked by offset) → requested window + `90 00`.
   → This step firing = "card-side tap happened": mint token, start server, launch UI (Phase 1).
Keep a per-session `selectedFile` var (CC vs NDEF) keyed off step 2/4; reset in `onDeactivated`.
NDEF message (build with `android.nfc.NdefMessage`/`NdefRecord`, then `toByteArray()`; prepend NLEN):
- Record 1: `NdefRecord.createExternal("superdrop.dev", "namecard", payload)` (TNF_EXTERNAL_TYPE,
  all-lowercase — must match the §6 filter), payload = `[0x01 version][16B token]`
  (token = `NameCardBootstrapHolder` session token).
- Record 2 (LAST): `NdefRecord.createApplicationRecord(BuildConfig.APPLICATION_ID)` — use
  BuildConfig, never a literal (debug id `dev.superdrop.debug` ≠ release).
Fresh message per tap (first READ after a SELECT-app mints a new session).

## 6. Reader-side wake — manifest + parsing (bada-fork Phase 1)
On `NameCardTransferActivity` (add a `<intent-filter>`; keep `exported="true"`).
**DOC-VERIFIED 2026-07-02** (developer.android.com/develop/connectivity/nfc/nfc): Android converts a
TNF_EXTERNAL_TYPE record `urn:nfc:ext:<domain>:<type>` to the URI
`vnd.android.nfc://ext/<domain>:<type>`; the filter below is the documented matching form verbatim —
copy it exactly:
```xml
<intent-filter>
  <action android:name="android.nfc.action.NDEF_DISCOVERED"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <data android:scheme="vnd.android.nfc"
      android:host="ext"
      android:pathPrefix="/superdrop.dev:namecard"/>
</intent-filter>
```
Create the record with `NdefRecord.createExternal("superdrop.dev", "namecard", payload)` — keep
domain AND type ALL-LOWERCASE everywhere (record + filter) so no case-normalization mismatch is
possible; the pathPrefix must match `/<domain>:<type>` including the leading slash and colon.
Add `<uses-permission android:name="android.permission.DISPATCH_NFC_MESSAGE"/>` and, if
targetSdk ≥ 37, the permission attribute on the activity per the NFC-basics doc. minSdk unaffected.
In `onCreate`: if `intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED`, take
`intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)`, first `NdefMessage`, find the
`superdrop.dev:namecard` record, parse `[ver][16B token]` → run the existing `setupClient()` path
with that token (client scans/connects exactly as today). Malformed/absent record → show the
existing diagnostic line + finish (logged, not silent).

## 7. Symmetric consent protocol (Phase 2) — wire + state machine
New characteristic on the EXISTING GATT service in `NameCardBleExchange`:
`CONSENT_CHARACTERISTIC_UUID = "7b2fdd3e-9a41-4e2c-b7a4-5c1e6f3d0a11"` (fixed, both sides),
properties WRITE + NOTIFY (+ CCCD descriptor `00002902-0000-1000-8000-00805f9b34fb`).
Wire messages (1 opcode byte; JVM-tested codec `NameCardConsentCodec`):
`0x01 HELLO(ver=0x01)` · `0x02 CHOICE_SHARE` · `0x03 CHOICE_RECEIVE_ONLY` · `0x04 BYE`.
Client → server: writes to CONSENT. Server → client: notifies CONSENT. Both send HELLO on
connect/subscribe; missing HELLO within 3s → treat as legacy peer → fall back to today's v1 flow.
Card payload transport keyed to consent: client shares via existing `shareBack` write AFTER local
SHARE; server shares by notifying/serving `CARD_CHARACTERISTIC_UUID` ONLY after local SHARE (change:
today it serves immediately — gate the read/notify behind `localChoice == SHARE`).
`NameCardConsentMachine.kt` (pure Kotlin, no Android imports):
states `IDLE→CONNECTED→{WAITING_PEER, DONE_*}`; inputs: localShare, localReceiveOnly, peerShare,
peerReceiveOnly, peerCardArrived, timeout(30s), disconnect. Outputs (effects list):
SendChoice(x), TransmitCard, SaveCardAndRipple, ShowWaiting, ShowHeadsUp, UpdateHeadsUpDeclined,
FadeToDeclined, ShowNoResponse, CloseLink. Encode the §3 matrix EXACTLY:
| me \ peer     | SHARE                          | RECEIVE_ONLY                     | (none, 30s)   |
| SHARE         | both TransmitCard; both SaveCardAndRipple | I TransmitCard; my heads-up → "declined"; peer saves mine | heads-up "waiting" → NoResponse |
| RECEIVE_ONLY  | peer TransmitCard; I SaveCardAndRipple; peer just completes | BOTH FadeToDeclined, Done-only, NO ripple | ShowWaiting → NoResponse |
Disconnect before both chose → NoResponse (never a crash). Exhaustive JVM test: all 9 cells + both
timeout rows + early-disconnect + legacy-peer fallback.

### 7b. Pinned design decisions (Fable, 2026-07-03 — verified against NameCardBleExchange.kt this session; Opus implements EXACTLY this)
**D1 — Consent semantic is PER-SIDE, not mutual-gated (user's Scenario B, redesign plan §2):** your
card is transmitted the moment YOUR user taps Share, regardless of whether the peer has chosen yet
("my card still reached them" when the peer later declines). Never wait for both choices before
transmitting; only the LOCAL choice gates the local card. What v2 removes is v1's zero-consent serve.

**D2 — Card transport mechanisms (reuse v1's proven paths, add gates; no new chunking protocol):**
- CLIENT card → server: unchanged `shareBack()` write (NameCardBleExchange.kt:222) — already fires
  only after the local Share tap; already gated correctly. Server's write handler (:284) unchanged.
- SERVER card → client: keep the CARD-characteristic READ path (offset-aware long read, :272-282 —
  needed because a card can exceed one MTU; do NOT push the card through notifies). Gate it:
  `onCharacteristicReadRequest` answers card bytes ONLY when server `localChoice == SHARE` for a
  v2 peer; otherwise respond `GATT_READ_NOT_PERMITTED`. Remove the `it.value = cardBytes` bake at
  :141 (the read handler is the single source; no cached-value leak). The client performs the read
  only AFTER receiving the server's `CHOICE_SHARE` notify — peer CHOICE_SHARE doubles as
  "card now readable"; no extra CARD_READY opcode.
- Ripple/save trigger = card BYTES arriving+parsing (client: read completes; server: write arrives),
  never the CHOICE message alone.

**D3 — Version/legacy detection (deterministic, no guessing):**
- Client side: after `discoverServices`, CONSENT characteristic ABSENT from the service → legacy v1
  server → run today's v1 flow verbatim (immediate CARD read, old UI). PRESENT → v2: subscribe CCCD
  → write `HELLO` → consent machine. GATT ops are client-serialized, so subscribe+HELLO always
  precede any v2 CARD read.
- Server side: a CARD read request arriving from a device that has NOT sent `HELLO` → legacy v1
  client → serve the card v1-style (unconditional) and run the v1 flow for that session. `HELLO`
  seen → v2 gating (D2) applies. The 3s HELLO timer is only the backstop for a half-open peer;
  char-absence / read-before-HELLO are the primary, deterministic detectors.

**D4 — Order independence:** the consent machine consumes `localShare/localReceiveOnly/peerShare/
peerReceiveOnly/peerCardArrived` as independent events in ANY interleaving (both-tap-simultaneously
race included) and must be tested for permuted orderings, not just the 9 matrix cells.

**D5 — Timers:** the 30s no-response timer lives in the consent machine (starts at link-up = HELLO
exchanged; cancelled on terminal). The hardware `MAX_SESSION_MS` backstop (:437, currently 30s)
rises to 60s in v2 sessions so the UX timer, not the radio teardown, is what users see; v1/legacy
sessions keep 30s. `BYE` is sent on every terminal state before close so the peer distinguishes
"done" from "link dropped" (dropped before both chose → NoResponse, D4).

**D6 — CONSENT characteristic:** properties WRITE+NOTIFY, CCCD `00002902-...`, client choice writes
use WRITE_TYPE_DEFAULT (with response). Client→server = writes; server→client = notifies (§7 wire).
HELLO carries the 1-byte protocol version (0x01) for future evolution.

## 8. Transfer-screen states (Phase 3) — NameCardTransferActivity
Both roles render the SAME layout (own card + two buttons). Wire machine effects:
- ShowWaiting: button pop (existing pressAnim), ripple suppressed, add "waitingLine — small gray
  'waiting…' text under the buttons".
- SaveCardAndRipple: `playSendRipple()` + `NameCardSaver` + open contact (existing
  `commitWithSendRipple` path).
- ShowHeadsUp / UpdateHeadsUpDeclined: NotificationChannel `namecard_consent`; heads-up
  (IMPORTANCE_HIGH) "Waiting for <peer> to respond" → mutate same notification id to
  "<peer> declined to share their info"; cancel on any terminal state + in onDestroy.
- FadeToDeclined: tween alpha 1→0 on the card fields (300ms, standard easing), swap in
  "declinedLine — centered gray text 'They declined to share their contact info'", remove both
  buttons, show single Done ("doneButtonFullWidth — full-width blue pill").
- NoResponse: same fade pattern, text "No response", Done only.
Every transition → `DiagnosticLog` + the on-screen status line. No silent path.
30s timer starts when the link is up; cancel on terminal.

## 9. Gotchas the executor MUST handle (each has a decided resolution — don't re-litigate)
- **G1 OnePlus AID conflict:** `com.heytap.accessory` (PantaConnect) already registers
  `D2760000850101` on OnePlus firmware. Two apps registering one AID in category "other" → routing
  conflict (the MichaelsPlayground README documents per-tap conflict resolution). RESOLUTION: still
  register; Phase 0 harness explicitly tests whether our HCE ever gets the READ on the OnePlus. If
  it never does → that phone can still be the READER side (tap still works one-directional per tap;
  UX unchanged since either wake path opens both apps via BLE). Record the observed behavior in the
  journal; only escalate to the user if BOTH directions fail on OnePlus.
- **G2 — SUPERSEDED 2026-07-02 (in-app AID conflict found + resolved).** bada-fork ALREADY owns
  `D2760000850101` via `nfc/SuperDropNdefApduService` (the iPhone-tap pairing-link NDEF). Two
  services in one APK must NOT both register that AID. **RESOLUTION (decided): do NOT add the NDEF
  aid-group to NameCardHceService.** Instead extend `SuperDropNdefApduService`, whose at-rest state
  is currently a deliberate dead tap (serves EMPTY NDEF when `NfcLinkHolder.currentUrl == null`):
  in `refreshNdefForCurrentLink()` (~line 144), replace the empty-NDEF branch with: if Name Card
  enabled (`NameCardPreferences.isEnabled` + `nameCardV2` pref) AND device unlocked
  (`KeyguardManager.isDeviceLocked == false`) → serve the Name Card NDEF (external record
  `superdrop.dev:namecard` `[0x01][16B token]` via `NameCardBootstrapHolder.newSession()` + AAR
  from `BuildConfig.APPLICATION_ID`, built with NdefMessage/NdefRecord + existing `buildNdefFile`),
  and start `NameCardExchangeService` on the first NDEF-file READ (mirror NameCardHceService's
  EXCHANGE side-effect); else serve empty NDEF exactly as today. Armed pairing link ALWAYS wins
  (mid-share tap = link; at-rest tap = Name Card — NameDrop-like priority). **USER REQUIREMENT
  (2026-07-02): Name Card is the DEFAULT NFC feature — the one running ANY time the other two
  (QR-link tap, send-sheet Quick Share tap) aren't actively armed. QR panel open → link takes the
  D276 slot; reader-mode windows suppress own HCE; ALL other times → Name Card NDEF served.
  Feature-2 receiver (F00000FE2C) + Name Card (D276) may be armed simultaneously — distinct AIDs,
  platform routes per-AID.** iPhone flow preserved:
  iOS background read ignores external-type records (no URI) → same no-op as today's empty NDEF.
  `NameCardHceService` keeps ONLY the legacy proprietary AID `F0534443415244` (unchanged file).
  The T4T machine/CC/NLEN/URI builders in SuperDropNdefApduService stay byte-identical.
- **G3 Reader-mode deadlock:** never call `enableReaderMode` in the new flow (two open apps would
  both suppress their cards). Park `NameCardTapReader` behind `NameCardPreferences` debug flag
  `debugForegroundReader` (default false); MainActivity only arms it when that flag is on.
- **G4 AAR package:** always `BuildConfig.APPLICATION_ID`. A literal breaks the debug build silently.
- **G5 Stopped state:** app must be opened once post-install or NFC intents are never dispatched.
  Put this in the user test script AND show it on the setup screen ("Tap works after first open").
- **G6 Android 16 allowlist / 17 permission:** log `NfcAdapter` state + (A16+) tag-intent-allowed
  state on the diagnostics screen; add the A17 permission now (harmless below 37).
- **G7 Threading:** HCE `processCommandApdu` runs on a binder thread — no UI calls; GATT callbacks
  likewise (existing marshaling pattern in NameCardBleExchange — follow it). Keep APDU handling
  synchronous and fast (<50ms) or the reader times out.
- **G8 Locked phone:** keep `isDeviceLocked` gate → NDEF group answers `6982` when locked (same as
  legacy AID). Test case in user script.
- **G9 Notification permission:** POST_NOTIFICATIONS runtime grant needed (A13+) for the heads-up —
  request on the setup screen with the other Name Card permissions; absent → skip notification,
  still show the in-activity waiting state (logged).
- **G10 Back-compat:** old-version peer speaks only the legacy AID+APDU flow. HELLO-timeout fallback
  (§7) preserves v1 behavior; do not remove the EXCHANGE APDU path.

## 10. Build order & commits (each = commit + CHANGELOG + journal update)
1. Phase 0 harness project + served APK + README test script. ← USER GATE (20-tap numbers)
2. `NameCardNdef.kt` (T4T codec, pure fns) + `NameCardNdefTest` (vectors from §5: every APDU→response
   pair, chunked reads, wrong-AID `6A82`, locked `6982`).
3. `NameCardConsentCodec` + `NameCardConsentMachine` + exhaustive tests (§7 table).
4. HCE dual-AID + manifest + reader-intent parsing (Phases 1) — compile + unit green.
5. BLE CONSENT char + service/activity wiring (Phases 2–3) — compile + unit green.
6. Ship: APK to repo top level + Caddy; full 4-scenario user script (§11).
Label every claim: "compile+JVM-tested on this box; on-device UNVERIFIED until your run".

## 11b. Concrete artifacts (copy-ready — removes the last ambiguity)
**~~Second aid-group XML~~ SUPERSEDED — see G2.** NO xml changes at all: `superdrop_apduservice.xml`
(already registers `D2760000850101` → `SuperDropNdefApduService`) and
`superdrop_namecard_apduservice.xml` (legacy `F0534443415244` → `NameCardHceService`) both stay
byte-identical. The only code change is inside `SuperDropNdefApduService.refreshNdefForCurrentLink()`
(+ a small NameCard-NDEF builder + the first-READ side-effect), per G2. Unlock gating is IN CODE
(serve empty NDEF while locked), preserving the no-two-tap behavior.

**Golden test WITHOUT hand-computed hex (do NOT hardcode an NDEF byte string — build+round-trip):**
`NameCardNdefTest` (JVM):
- Assert the fixed CC array == `00 0F 20 00 3B 00 34 04 06 E1 04 00 FF 00 FF` (byte-exact).
- Assert SELECT-app(`D2760000850101`)→`9000`; wrong AID→`6A82`; SELECT-CC(E103)→`9000`;
  SELECT-NDEF(E104)→`9000`; unknown APDU→`6D00`.
- ROUND-TRIP: build the NDEF via `NdefMessage(arrayOf(externalRecord(token), createApplicationRecord(pkg)))`,
  drive the HCE's `processCommandApdu` with the exact READ sequence (READ NLEN 2B, then chunked READ at
  offsets in ≤MLe=59B windows to NLEN), concatenate the responses (strip the trailing `9000` each),
  parse the reassembled bytes back with `NdefMessage(ByteArray)`, and assert record[0] payload ==
  `[0x01][token]` and record[1] is the AAR for `pkg`. This proves the byte machine end-to-end with
  zero magic hex — the Android NDEF encoder is the source of truth, our reader logic is what's tested.
- Locked-state: with a stubbed "locked" flag, SELECT/READ on the NDEF group → `6982`, no token minted.

## 11. Verification
- This box: `NameCardNdefTest` + `NameCardConsentMachineTest` green; assemble clean; APK at top level.
- User Phase 0: 20-tap protocol (+5 locked-phone taps + OnePlus G1 check) → success %, per-OEM
  reader bias, retap behavior.
- User full feature (app closed both sides before each): A) RecvOnly then peer Share → waiting… then
  ripple+save exactly when peer acts. B) Share first → heads-up; peer RecvOnly → heads-up text flips
  to declined; peer still got my card. C) both RecvOnly → both fade to declined, Done only, NO ripple.
  D) both Share → both ripple+save. E) stray tap, walk away → 30s auto-dismiss, nothing saved.
  F) one phone locked → nothing at all. Capture DiagnosticLog per run.

---

# ============================================================================
# APPENDIX A — PHASE 1 EXACT STEP-BY-STEP (for Opus; execute IN ORDER)
# Every signature/const below was code-verified 2026-07-02. Do NOT guess; if a
# symbol differs from what's written, STOP and re-read that file. Build after
# EACH numbered step (see A0) so a break is localized. Commit after each step.
# ============================================================================

## A0. Build/verify commands (use these exact ones)
- Compile Kotlin (fast, catches type errors):
  `cd /root/agent-work/projects/bada-fork && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:compileDebugKotlin -q`
  (SDK at /opt/android-sdk; this exact cmd is recorded working in the journal.)
- Full debug APK when a step is done: `./gradlew :app:assembleDebug` (place-apk hook copies to repo root).
- Unit tests (Phase-1 has one new test target): `./gradlew :app:testDebugUnitTest --tests '*NameCard*'`
- After each step: `git add -A && git commit` with a one-line message + append a CHANGELOG.md entry +
  update docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md CURRENT-STATE block. Branch = fork/superdrop-ui.

## A1. Add the `nameCardV2` pref (dev gate) — file: `namecard/NameCardPreferences.kt`
Verified current state: class has `isEnabled()` (KEY_ENABLED "enabled", default true), PREFS_NAME
"bada.name_card_prefs". ADD a second flag, same file, same prefs:
```kotlin
private const val KEY_V2 = "v2_symmetric"        // in companion, beside KEY_ENABLED
fun isV2Enabled(): Boolean = prefs.getBoolean(KEY_V2, false)   // DEFAULT OFF (dev gate)
fun setV2Enabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_V2, enabled).apply() }
```
Rationale: default OFF protects the shipped asymmetric flow until on-device proof; once proven, the
user flips default to true (Name Card is the always-on default per the priority ladder). Build (A0).

## A2. NameCard NDEF builder — NEW file `nfc/NameCardNdef.kt` (pure, unit-testable)
> **CORRECTION 2026-07-02 (IMPLEMENTED this way — the code block below is superseded):** the repo has
> NO Robolectric, so a test using `android.nfc.NdefMessage`/`NdefRecord` can't run in plain-JVM unit
> tests. IMPLEMENTED codec is **pure raw bytes** (no android.nfc), matching the existing
> `SuperDropNdefApduService.buildUriNdefMessage` house style: `build(token,pkg): ByteArray` (raw NDEF
> message: external record `superdrop.dev:namecard [0x01][16B token]` + hand-rolled AAR
> `android.com:pkg`=pkg) and `parseToken(ndef: ByteArray): ByteArray?`. The card side already needs raw
> bytes (`buildNdefFile(ByteArray)`); the READER side calls `parseToken(ndefMessage.toByteArray())`.
> 7 pure junit4 tests pass. See the shipped `nfc/NameCardNdef.kt` for the authoritative code.
No Android Service deps — just NdefMessage/NdefRecord + a couple helpers so it's JVM-unit-testable.
Verified inputs: `NameCardBootstrap` (core-protocol) has `version:Int`, `token:ByteArray`,
`TOKEN_LEN=16`, `CURRENT_VERSION=1`, `serialize()`. We do NOT reuse `serialize()` for the NDEF payload
(keep NDEF self-describing); we embed `[0x01][16B token]`.
```kotlin
package dev.superdrop.nfc
import android.nfc.NdefMessage
import android.nfc.NdefRecord
object NameCardNdef {
    const val EXT_DOMAIN = "superdrop.dev"   // ALL lowercase (matches manifest filter, §6)
    const val EXT_TYPE   = "namecard"        // ALL lowercase
    const val PAYLOAD_VERSION: Byte = 0x01
    /** NDEF message = external record [ver][16B token] + AAR(pkg). */
    fun build(token: ByteArray, packageName: String): NdefMessage {
        require(token.size == 16) { "token must be 16 bytes" }
        val payload = ByteArray(1 + token.size)
        payload[0] = PAYLOAD_VERSION
        System.arraycopy(token, 0, payload, 1, token.size)
        val ext = NdefRecord.createExternal(EXT_DOMAIN, EXT_TYPE, payload)
        val aar = NdefRecord.createApplicationRecord(packageName)
        return NdefMessage(arrayOf(ext, aar))
    }
    /** Parse the token from a received NDEF (reader side). Null if not our record / bad version.
     *  NOTE: the AAR is ALSO a TNF_EXTERNAL_TYPE record (type "android.com:pkg"), so we MUST match our
     *  exact type "superdrop.dev:namecard" — not just any external record. */
    private val EXT_TYPE_BYTES = "$EXT_DOMAIN:$EXT_TYPE".toByteArray(Charsets.US_ASCII)
    fun parseToken(msg: NdefMessage): ByteArray? {
        for (r in msg.records) {
            if (r.tnf == NdefRecord.TNF_EXTERNAL_TYPE && r.type.contentEquals(EXT_TYPE_BYTES)) {
                val p = r.payload
                if (p.size == 17 && p[0] == PAYLOAD_VERSION) return p.copyOfRange(1, 17)
            }
        }
        return null
    }
}
```
Build (A0). NOTE: `createExternal`/`createApplicationRecord` are API 14+ — fine for minSdk.

## A3. Merge the NameCard NDEF into the at-rest branch — file: `nfc/SuperDropNdefApduService.kt`
VERIFIED current behavior: T4T machine already complete; `refreshNdefForCurrentLink()` (~line 144)
reads `NfcLinkHolder.currentUrl` and, when null/blank, sets `ndefFile = buildNdefFile(EMPTY_NDEF_MESSAGE)`
(the deliberate dead tap). The at-rest null branch is EXACTLY where Name Card goes.
Change ONLY `refreshNdefForCurrentLink()` to:
```kotlin
private fun refreshNdefForCurrentLink() {
    val url = NfcLinkHolder.currentUrl
    val message: ByteArray = when {
        !url.isNullOrBlank() -> buildUriNdefMessage(url)          // feature 1: QR link armed — UNCHANGED, WINS
        nameCardActive() -> {                                     // feature 3: at rest → Name Card
            val bootstrap = NameCardBootstrapHolder.newSession()  // mint fresh 16B token
            pendingNameCardToken = bootstrap.token                // remember for the READ side-effect (A3b)
            NameCardNdef.build(bootstrap.token, packageName).toByteArray()
        }
        else -> EMPTY_NDEF_MESSAGE                                // feature off / locked → dead tap (as today)
    }
    ndefFile = buildNdefFile(message)
    ccFile = buildCapabilityContainer(ndefFile.size)
}

/** Name Card is served only when: master switch ON, v2 gate ON, and device UNLOCKED. */
private fun nameCardActive(): Boolean {
    val prefs = dev.superdrop.namecard.NameCardPreferences.from(this)
    if (!prefs.isEnabled() || !prefs.isV2Enabled()) return false
    val kg = getSystemService(android.app.KeyguardManager::class.java)
    return kg?.isDeviceLocked != true   // unlocked (or no keyguard mgr) → allowed
}
```
Add fields near `selected`:
```kotlin
private var pendingNameCardToken: ByteArray? = null
private var nameCardServerStarted = false
```
Add imports: `dev.superdrop.namecard.NameCardBootstrapHolder`, `dev.superdrop.namecard.NameCardExchangeService`.

### A3b. Fire the BLE server on the FIRST NDEF-file READ (card-side wake side-effect)
In `processCommandApdu`, in the READ_BINARY branch, in the `Selected.NDEF ->` case (where it returns
`ndefFile`), BEFORE returning, add: if `pendingNameCardToken != null && !nameCardServerStarted`, set
`nameCardServerStarted = true` and `NameCardExchangeService.start(this, pendingNameCardToken!!)`
(signature VERIFIED: `fun start(context, token: ByteArray)`), wrapped in `runCatching { }` + a
`DiagnosticLog.w(TAG, "Name Card NDEF read → server FGS")`. This mirrors NameCardHceService's EXCHANGE
side-effect (line 83). Do it on READ (not on SELECT) so a mere SELECT that never reads doesn't spin up BLE.
In `onDeactivated(reason)`: also reset `pendingNameCardToken = null; nameCardServerStarted = false`.
CROSS-CUTTING: `processCommandApdu` is on a binder thread; `NameCardExchangeService.start` just fires an
Intent (non-blocking) — OK. Keep the method fast. Build (A0).

### A3c. Update the file's KDoc header
Add a paragraph: this service now serves TWO payloads on D2760000850101 — the QR pairing link when
armed (feature 1), else the Name Card NDEF+AAR when v2+enabled+unlocked (feature 3, the always-on
default), else empty. Reference the 3-feature map. (document-every-change rule.)

## A4. Reader-side wake — file: `AndroidManifest.xml` + `namecard/NameCardTransferActivity.kt`
A4a. MANIFEST: on the `.namecard.NameCardTransferActivity` <activity> (currently `exported="false"`,
`launchMode="singleTop"`, `excludeFromRecents="true"`) — the AAR launches it, so set
`android:exported="true"` and add the intent-filter (verbatim documented form, §6):
```xml
<intent-filter>
  <action android:name="android.nfc.action.NDEF_DISCOVERED"/>
  <category android:name="android.intent.category.DEFAULT"/>
  <data android:scheme="vnd.android.nfc" android:host="ext"
        android:pathPrefix="/superdrop.dev:namecard"/>
</intent-filter>
```
Also add `<uses-permission android:name="android.permission.DISPATCH_NFC_MESSAGE"/>` near the other
uses-permission lines (harmless < API 37).
A4b. ACTIVITY: current `onCreate` routes on `intent.getStringExtra(EXTRA_ROLE)` (line ~140: client/
server; EXTRA_TOKEN line ~183; EXTRA_PEER_CARD line ~164). ADD a branch BEFORE that when
`intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED`: pull
`intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)`, take first `NdefMessage`,
`NameCardNdef.parseToken(msg.toByteArray())` (codec is raw-bytes, see A2 correction); if non-null → run the EXISTING client path with that token (same code
`setupClient()`/whatever the ROLE_CLIENT branch calls with EXTRA_TOKEN). If null → DiagnosticLog + finish
(logged, not silent). Do NOT duplicate the client logic — refactor the ROLE_CLIENT branch into a
`private fun startClientWithToken(token: ByteArray)` and call it from both. Build (A0).

## A5. Park the foreground reader for v2 — file: `MainActivity.kt` (~line 336)
Current: `nameCardReader ?: NameCardTapReader(...)` armed when Name Card enabled. Guard it so it only
arms when v2 is OFF (in v2 the OS poll is the reader; a foreground reader-mode would suppress our own
HCE and break the both-background model — see G3): wrap the arm call in
`if (!NameCardPreferences.from(this).isV2Enabled()) { …arm NameCardTapReader… }`. Leave the class intact.
Build (A0). This is the ONLY change needed to switch trigger models via the pref.

## A6. Phase-1 exit check (before Phase 2)
- `:app:compileDebugKotlin` clean; `:app:assembleDebug` clean; APK at repo top level.
- New unit test `app/src/test/kotlin/dev/superdrop/nfc/NameCardNdefTest.kt`: round-trip
  `parseToken(build(token,pkg)) == token`; wrong-TNF record → null; 17-byte/version checks; AAR present.
- Manual reasoning check: with `nameCardV2=false` the app behaves EXACTLY as shipped (SuperDropNdef at-rest
  = empty NDEF; NameCardTapReader armed). Flipping `nameCardV2=true` (temporarily default-true or via a
  debug toggle) is what the user installs to test the both-background tap. Commit + journal.
- THEN Phase 2 (consent GATT, §7) and Phase 3 (UI states, §8) — those are unchanged by this appendix.

## A7. What to hand the user after Phase 1
A debug build with `nameCardV2` flipped ON (or a hidden toggle), plus: "app closed on both, awake+
unlocked, tap → both open the Name Card screen." That is the both-background trigger proof. If it fires,
Phases 2–3 layer the consent/animation on top. If it doesn't, the harness (namecard-tap-harness) is the
clean-room repro to localize whether it's routing, AAR-launch, or the OnePlus AID conflict (G1).
