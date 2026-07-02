# Name Card — Consent / Waiting Flow Redesign (PLAN — NOT STARTED)

Status: **planning only.** Do not implement until the user says go. Research (iPhone NameDrop
flow) is a step to do AFTER this plan is approved, not before.

## 1. Corrected architecture (user, 2026-07-01)
- Neither phone has the app open in the foreground. **Both phones have the app in the BACKGROUND,
  both broadcasting the same rendezvous.** Tapping the phones together — with **NFC merely the
  trigger to open both apps** — brings up the card on both.
- The two phones are **symmetric peers**: each person independently taps **Share** or **Receive Only**,
  and the two apps talk to each other so each side learns the other's choice and always "sees something
  while waiting."
- (The CURRENT code is asymmetric: the foreground phone is the reader/client and the idle phone is the
  HCE/server; Receive Only just disconnects with no signal to the peer. This redesign moves to the
  symmetric peer-consent model above. See §5 Android constraint to reconcile.)

## 2. Target consent + animation flow (exact behavior the user described)
Each phone has a choice state: **not chosen yet / SHARE / RECEIVE ONLY**. The peer's choice arrives
over the link. The **ripple only ever plays on a successful receive** (the peer shared) — never on a decline.

**Scenario A — I tap Receive Only, peer hasn't chosen yet**
- My button-press animation fires, but the **ripple does NOT play**. I sit in a **waiting** state with
  text like "waiting…".
- If the peer then taps **Share**: the apps communicate, the peer's animation plays, and I then get the
  **ripple** and everything else (their card arrives + is saved).

**Scenario B — I tap Share, peer hasn't chosen yet**
- All my animations play as normal, BUT I get a **heads-up notification**: waiting for the other person
  to respond.
- If the peer taps **Share**: everything continues as normal (mutual exchange).
- If the peer taps **Receive Only**: my heads-up notification **changes** to say they declined to share
  their contact info. (My card still reached them; for them it works as normal — they received mine.)

**Scenario C — BOTH tap Receive Only**
- The app animations fire as normal **except the ripple**. Instead, the contact info on the card
  **fades away** and is replaced by a message that the other person declined to share their information.
- The only available action at that point is **Done**.

**Scenario D — both tap Share (implied happy path)**
- Normal mutual exchange: ripple on both sides, each saves the other's card.

## 3. Summary matrix (my choice ↓ / peer's choice →)
| me \ peer | Share | Receive Only | not yet |
|---|---|---|---|
| **Share** | mutual: ripple + save both | heads-up "they declined"; I keep waiting-note; they got mine | animations play + heads-up "waiting for other person" |
| **Receive Only** | I get ripple + their card saved; they got nothing from me | Scenario C: no ripple, card fades → "declined" message → Done only | button anim only, NO ripple, "waiting…" text |
| **not yet** | (peer waiting on me) | (peer waiting on me) | both just showing the card |

## 4. What this requires (implementation to-do — after approval)
1. **Persistent, live, bidirectional link — choices propagate INSTANTLY (user requirement 2026-07-01).**
   The moment the two phones connect (right after the tap, while the card is showing on both), the GATT
   link stays **open the whole time** and both apps keep talking in the background so that whatever a
   user selects, the other side reacts **instantaneously** — no reconnect, no re-tap, no polling.
   Mechanism: each side pushes its choice the instant it's made over a **live channel** (GATT
   notify/indicate that both subscribe to, or an immediate write), and the peer handles it on arrival.
   Today Share writes the card once and Receive Only silently disconnects, so the peer can't tell the
   difference and nothing is real-time — this is the core change.
   - Explicit signal for BOTH choices: SHARE = send the card; RECEIVE ONLY = send a decline marker.
   - Hold the connection open until BOTH sides have chosen (or the ~30s timeout), each learning the
     other's outcome the moment it happens, so the waiting→ripple / waiting→declined transitions fire
     immediately when the peer acts.
2. **Symmetric peer handling** — both ends can send a choice and receive the peer's; decouple "who was
   the NFC initiator" from "who shares." Both ends run the same consent state machine.
3. **New UI states in the transfer screen** (`NameCardTransferActivity`):
   - waiting ("waiting…" text; button pressed but ripple suppressed),
   - peer-shared → play the ripple + save,
   - peer-declined → fade the card content to a "they declined to share" message, leaving only **Done**.
4. **Ripple gating** — ripple fires only when a card is actually received (peer shared); suppressed on
   any decline / waiting state.
5. **Heads-up notification** for the "I shared, peer hasn't answered" case, updated to "declined" if the
   peer chooses Receive Only.
6. **Timeouts / cleanup** for the waiting states + notification dismissal.
7. **Symmetric both-background trigger (NDEF + AAR).** To wake BOTH apps from the background on a tap
   (§5a–5c), change/extend the HCE trigger from the current raw proprietary AID + custom APDU to an
   **NDEF message carrying our app's Android Application Record (AAR)** + the rendezvous token, so the
   reader-side phone auto-launches the app via the AAR while the card-side phone wakes via its HCE
   service. (The current AID/APDU path needs the reader app already foreground — asymmetric.)
   - **NFC API/permission research (VERIFIED 2026-07-01, developer.android.com) — bar = both
     AWAKE+UNLOCKED+app-closed:** NO permission/API blocker; all non-privileged. Perms: `NFC` +
     `BIND_NFC_SERVICE` (on the HCE service) + `DISPATCH_NFC_MESSAGE` (Android 17+). Mechanism: each
     phone's background `HostApduService` serves a hand-rolled Type-4 NDEF carrying an AAR
     (`NdefRecord.createApplicationRecord`) → whichever phone the OS reads gets its app
     launched-from-closed via the AAR; the other wakes via its HCE. `enableReaderMode` needs FOREGROUND
     (why today's design is asymmetric). Observe Mode (Android 15+) = passive polling-loop detection.
     One-time "open app once" required (stopped-state) — NOT per-boot.
   - **RF role arbitration — RESOLVED FROM SOURCE+SPEC (2026-07-02, supersedes "device-test-only"):**
     (a) AOSP `NfcService.computeDiscoveryParameters` (packages/modules/Nfc, NfcService.java:4554 +
     4569–73): awake+unlocked → the OS **polls by default AND host-routing (listen/HCE) is always on
     simultaneously** — no app needed for either role. (b) ECMA-340/NFCIP-1 **initial RF Collision
     Avoidance**: a poller must sense for an external field and must NOT activate its own if one exists
     → on a tap, first-to-field becomes reader, the other stays card — roles resolve deterministically
     below the app. (c) libnfc-nci: discovery loop = fixed `NFA_DM_DISC_DURATION_POLL` (default 500ms),
     NO DH-layer randomization. **VERDICT: the both-awake+unlocked+app-closed tap trigger IS
     documented-feasible** (OS tag dispatch reads our T4T NDEF+AAR → launches app; other side's HCE
     cold-wakes; Beam's removal irrelevant — that was NFC-DEP P2P, this is reader→T4T-HCE). Remaining
     empirical sliver = reliability %: pathological phase alignment of the two fixed 500ms loops (both
     poll bursts collide each cycle) can stall a tap unless CLF firmware jitters (per-OEM) — measure
     with the 2-phone minimal HCE-NDEF+AAR harness over ~20 taps; UX fallback = separate-and-retap.
     Full = [[reference_android_both_background_nfc_trigger_apis_2026_07_01]].
8. **Confirm the both-wake choreography from real code (grounded, not hardware):** read the decompiled
   shipping tap apps on this box — Google `gestureexchange`, O+Connect `IosHostApduService`, Quick Share
   tap — to see exactly how a symmetric tap app arbitrates who-reads-whom and wakes both, then mirror it.
   Do this BEFORE building the trigger (items 7) so it's not guesswork.
9. **Accidental-tap gate — hold-to-confirm (~2s) before the exchange commits (user, 2026-07-01).**
   Stop a brief/accidental phone-to-phone tap from launching NameDrop: require the phones to stay
   together ~2s before anything commits. CRUX (don't build naively): the NFC tap is a MOMENTARY event
   (fires once, then BLE takes over), so "held together" must be detected **continuously**, not by a
   bare timer — e.g. the HCE/reader watches the NFC field and cancels on field-drop (`onDeactivated`)
   before 2s, OR sustained BLE proximity (RSSI above a threshold held for 2s). UI: on wake, show the
   card in a "hold together…" pending state with a ~2s progress ring; commit only if proximity persists,
   cancel + dismiss if they separate early. Make the hold duration a config hook. Applies to the real
   feature (bada-fork), not the single-button tester (which could simulate it with a press-and-hold).
   - **FEASIBILITY — VERIFIED 2026-07-01 (from developer.android.com), feature DEFERRED (not built):**
     YES, an app can measure NFC hold duration — there is NO duration API (and `HostApduService` has no
     `onActivated`), but the **reader** side can hold the `IsoDep` link open and poll: repeated
     `transceive()` succeeds while present and throws `TagLostException` the instant the peer leaves the
     field. `HostApduService.onDeactivated(DEACTIVATION_LINK_LOSS)` corroborates on the card side.
     Implement on the READER with essentially NO card/protocol change: our HCE already answers SELECT
     idempotently (→9000) and only mints on EXCHANGE, so the reader loops SELECT as a presence-poll for
     ~holdMs, then sends EXCHANGE only if held. Change `nfc/NameCardTapReader.kt` `onTag()/exchange()`
     (currently connect→SELECT→EXCHANGE→close immediately) into poll-then-commit; MUST include a
     grace window (~400ms) so cm-range NFC wobble doesn't false-cancel; `holdMs` = config hook; add a
     "hold together…" progress ring via the existing `onTapDiagnostic` sink. Full write-up was in the
     scratch plan `~/.claude/plans/dazzling-coalescing-scroll.md`; memory =
     [[reference_nfc_hold_duration_gate_feasibility_2026_07_01]].
   - **CAVEAT (2026-07-01):** the NFC-field poll gate needs a phone in READER mode = a FOREGROUND
     Activity. It fits the CURRENT asymmetric model (sharer foreground) but does NOT compose with the
     symmetric both-background wake (item 7 / §5), which fires via a MOMENTARY NDEF/AAR dispatch (no
     sustained reader session to poll). And accidental taps are most likely in that symmetric case —
     where this gate can't run. So if we go symmetric, gate via **on-screen hold-to-confirm on both
     phones** (role-agnostic; fits the two-user consent flow) or sustained BLE RSSI post-wake, not the
     NFC-field poll. The reader-poll gate also delays the BLE handoff ~2s even where it works.

## 5. Android trigger mechanism (corrected 2026-07-01 — the app is SYMMETRIC)
Earlier note here wrongly framed this as "one app must take the reader role." That was overcomplicated
and is retracted. Correct understanding:
- **The app does NOT choose reader vs card.** Identical software on both phones is the normal peer case;
  the reader/card role for the instant of the tap is negotiated BELOW the app — by the two NFC
  controllers (RF collision-avoidance) plus the phone's OS-level NFC polling reading whatever HCE card
  the other phone presents. Both phones register the same HCE service; on tap the hardware sorts out
  who-reads-whom, the read side's service cold-wakes + launches, and then both are equal peers on the
  BLE link. No "foreground reader" requirement baked into the app.
- **iPhone NameDrop is symmetric the same way:** both iPhones run the same code, neither is "the
  receiver" by role; the system negotiates transport roles invisibly and BOTH users see the same prompt
  and each independently picks Share / Receive Only. That is the model we copy (§2).

### 5a. Which side wakes — the two sides are NOT symmetric at wake (user question 2026-07-01)
- **Card/HCE side (got read):** OS routes the tap to its HostApduService → it can launch the app. Wakes
  reliably from the background.
- **Reader/poller side (did the reading):** reading alone does NOT launch its own app. To open it from
  the background, what it reads must carry a launch trigger — an **Android Application Record (AAR)**
  inside an NDEF message. Android's NFC dispatch sees the AAR and launches that package on the reading
  phone. This is the standard "tap and the app opens on the other phone" mechanism.
- **To wake BOTH from background:** the trigger must be **NDEF + AAR**: each phone's HCE serves an NDEF
  message carrying our app's AAR (+ the rendezvous token). Whichever phone becomes the reader launches
  via the AAR; the phone that became the card is woken by its HCE service. Both up, then both connect
  over BLE as equal peers (§2).

### 5b. CURRENT code can't do this yet (concrete change needed)
The shipped Name Card feature uses a **proprietary AID + custom APDU** (SELECT/EXCHANGE), NOT NDEF/AAR.
With a raw AID the reader side must drive custom reader-mode, which needs the app **already foreground** —
i.e. the current design is the asymmetric one and cannot wake the reader-side app from the background.
The symmetric "both backgrounded, tap wakes both" model therefore requires **switching/adding an
NDEF+AAR trigger** (each phone's HCE serves an NDEF-with-AAR + token). This is a real added scope item.

### 5c. Honest status + grounded next step (NO hardware — none exists)
AAR launching the reader-side app is documented Android behavior. The exact both-wake choreography (and
how a shipping app arbitrates who-reads-whom) I will confirm by **reading the real decompiled tap apps on
this box** — Google `gestureexchange`, O+Connect `IosHostApduService`, Quick Share tap — and mirror what
they actually do. Grounded in code, not hardware. Affects TRIGGERING only; the consent redesign (§2–§4)
is unaffected.

## 6. NameDrop research (done 2026-07-01, via Groq gpt-oss-120b — CONFIRMS the §2 model)
Findings (corroborated across sources; kept only the reliable parts):
- **Both people must actively choose** before any contact data moves; a unilateral pull is not possible.
- Each phone shows the card and **each person independently picks "Share" or "Receive Only"** (symmetric
  — this is exactly the §2 model). If one never responds, the other waits ~**30s** then gets a
  "no response" state. Decline gives the peer an explicit "declined" signal. Success saves the contact
  automatically (opens it after).
- **Transport (reference only):** NameDrop uses BLE (+ UWB ranging on capable phones) for discovery/
  trigger, then peer-to-peer Wi-Fi (AWDL) carries a **vCard**. OUR Android build deliberately differs:
  NFC = trigger, BLE GATT = carry the card. No change needed there.
- **Flagged UNRELIABLE (not used):** Groq also claimed the labels are initiator "Share My Contact" +
  receiver "Accept/Decline" — that contradicts the real symmetric "Share / Receive Only" model (and an
  Apple/web source). We keep "Share / Receive Only" (the correct NameDrop model + the user's design).
- NET: the §2 flow is aligned with real NameDrop; no revisions required from the research.

## 7. Status
PLAN ONLY. Nothing implemented. PR #247 (current Name Card feature) is unaffected by this document.
Awaiting the user's go before any code, and before the research step.
