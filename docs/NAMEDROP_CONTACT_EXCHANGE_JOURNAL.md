# Super Drop — NameDrop-style Contact Exchange (NFC tap → share contact when not sending a file)

## CURRENT STATE / NEXT STEP
**Goal:** When two phones running Super Drop are tapped back-to-back and *neither* is
actively sending a file, exchange contact info (name + number) like iPhone NameDrop —
battery-free in the background. Contact comes from an in-app profile, optionally pulled
from the phone's own profile/number; receiver gets an "option to save".

**Target repos (user decision 2026-06-30):** BOTH — build in `bada-fork` (Super Drop,
`dev.superdrop`, branch `fork/superdrop-ui`) first, then port to `bada-debrand`
(`dev.bluehouse.bada`) as an upstream PR to kyujin-cho/Bada later.

**DONE this session:** Fully mapped the existing NFC stack (VERIFIED by reading code — see
log). Key finding: the contact-card *send* side is ~90% already built (`SuperDropNdefApduService`
is a working Type-4 NDEF tag HCE on the standard NDEF AID `D2760000850101`); the *receive*
side does not exist yet. Identified the make-or-break Android NFC-role feasibility unknowns.

**NEXT STEP:** P1 + P2 DONE (see below). Next = **P3: NFC trigger** — new name-card AID HCE + reader
that just WAKES both apps (carries a tiny BLE bootstrap token, not the card), reusing the
F00000FE2C/cold-wake patterns; must not disturb the two existing AIDs. Then P4 BLE exchange, P5
receive activity (NameDrop-look) + save-to-contacts, P6 radio-helper BT-on, P7 diagnostics + test script.

**P2 STATUS (2026-06-30, compile-verified here):** Settings → **"Name Card"** row → **My Name Card**
setup page. Files (app `dev.superdrop.namecard`): `NameCardProfileStore` (SharedPreferences),
`NameCardResolver` (fallback precedence: in-app → device "Me"/SIM → bare number → null;
decoupled via `storedCard:()->NameCard?` + `DeviceContactSources` for testability),
`AndroidDeviceContactSources` (real reads: ContactsContract.Profile + SubscriptionManager.getPhoneNumber
A33+/TelephonyManager.getLine1Number, SecurityException→null), `NameCardSetupActivity` (name/phone/email
fields + "Use my phone info" perm-gated prefill + Save/Clear). UI: `res/layout/activity_name_card_setup.xml`
+ clickable `settings_name_card_row` card in `fragment_settings.xml` + strings + manifest activity +
perms (READ_CONTACTS/READ_PHONE_NUMBERS/READ_PHONE_STATE). Wired in `SettingsFragment.onViewCreated`.
VERIFIED: `:app:assembleDebug` BUILD SUCCESSFUL; `NameCardResolverTest` 7/7 pass; codec 14/14 still pass.
UNVERIFIED (no display here): the screen's actual render + the live "Use my phone info" permission/read
path → user device-test. APK at repo root `super-drop-debug.apk`.

**P1 STATUS (2026-06-30, VERIFIED here):** `NameCard` model + wire codec built in `core-protocol`
(`dev.superdrop.protocol.namecard.NameCard` + `NameCardField`), pure-JVM TLV format (1-byte version +
type(1)/len(2 BE)/value TLVs; known types name=1/phone=2/email=3; unknown TLVs preserved for forward
compat; null-on-malformed; strict UTF-8). 14 JUnit-Jupiter tests in `NameCardTest` — **ALL PASS**,
`./gradlew :core-protocol:test` BUILD SUCCESSFUL (real run; explicitApi + allWarningsAsErrors clean).
Java 21 on PATH, toolchain 17, SDK /opt/android-sdk. This is the ONLY device-independent piece and the
only part verifiable without phones; everything downstream (NFC/BLE/UI) is compile-only here + user device-test.

---

## VERIFIED existing NFC architecture (read 2026-06-30, bada-fork @ fork/superdrop-ui)

Three NFC roles, ALL public APIs (`android.nfc` + `android.nfc.cardemulation`), no OEM privilege:

1. **iPhone NDEF link (HCE / "card" side)** — `app/.../nfc/SuperDropNdefApduService.kt`.
   - AID **`D2760000850101`** = NFC Forum NDEF Type-4 Tag Application AID (declared in
     `res/xml/superdrop_apduservice.xml`, manifest service exported + `BIND_NFC_SERVICE`,
     `requireDeviceUnlock="false"` so it answers from the lock screen).
   - Full Type-4 state machine: SELECT AID → SELECT CC(E103) → READ CC → SELECT NDEF(E104)
     → READ NLEN → READ NDEF. Serves ONE NDEF **URI** record built from
     `NfcLinkHolder.currentUrl` (set by SendActivity's QR panel); serves an EMPTY NDEF
     (`NLEN=0`) when the holder is null → a stray tap is a no-op.
   - `buildUriNdefMessage()` builds a Well-Known URI record. **This is the exact mechanism
     a NameDrop vCard card needs** — swap the URI record for a vCard MIME record.
   - CONSTRAINT: only ONE HCE service may own a given AID. The contact card MUST be served
     by THIS service (mode-switched: vCard when idle/contact-mode, URI when QR active, empty
     otherwise) — NOT a second NDEF service.

2. **Quick Share file tap — receiver (HCE)** — `nfc/SuperDropTapHceService.kt`, AID
   **`F00000FE2C`** (Nearby Connections advertising AID). Answers SELECT + ADVERTISEMENT with
   an `hhwv` (deym NfcTag identity + Wi-Fi-LAN `rxInstantConnectionAdv` IP:port), gated by
   `protocol.nfc.NfcTapLinkHolder.current` (receiver service live). Cold-tap primes a TCP
   listener via `NfcColdReceiverPrimer` + wakes `ReceiverForegroundService`.

3. **Quick Share file tap — sender (reader-mode)** — `nfc/SuperDropTapReader.kt`. While the
   send sheet is open, `enableReaderMode(activity, ::onTag, FLAG_READER_NFC_A|B|SKIP_NDEF, null)`;
   on a tag, ISO-DEP transceive SELECT + ONE ADVERTISEMENT (no re-poll, mirrors stock), parses
   peer → `SendActivity` auto-connects over Wi-Fi-LAN. **Reader-mode requires a foreground
   Activity** (this is why "receive in background" can only be the HCE side, never the reader).

Helpers: `NfcPreferredService` (`CardEmulation.setPreferredService` to win the shared
`F00000FE2C` AID vs Google Quick Share while a receive surface is foreground);
`NfcTapSharePreferences` (Mode SHEET_OPEN / APP_FOREGROUND / BACKGROUND = when this device is
tappable); `NfcLinkHolder` (`@Volatile currentUrl`, UI→HCE bridge).

There is currently **NO Android-side NDEF *reader* / `ACTION_NDEF_DISCOVERED` receiver** — i.e.
nothing to *receive* a tapped contact card. That must be added.

## VERIFIED Android NFC realities (general, confirmed against the code above)
- NFC is **asymmetric**: one phone polls (reader), one emulates (card). Android removed P2P
  (Beam/SNEP/LLCP) in Android 10 — there is no two-idle-phones peer mode.
- **HCE / card side** runs in the background, screen can be OFF, battery-free until a field
  wakes it (the NFC controller answers; the app process is only invoked on a tap). ← this is
  the "battery efficient background" the user wants.
- **Reader / receive side**: the platform auto-dispatches `ACTION_NDEF_DISCOVERED` to launch an
  app ONLY when the reading phone's screen is ON + unlocked (NFC polling is suspended screen-off
  on essentially all phones). So to RECEIVE a contact, the receiving phone must be screen-on +
  unlocked (no app need be open — the platform launches us). Foreground dispatch / reader-mode
  needs an Activity already up.

## MAKE-OR-BREAK feasibility unknowns (device-only; cannot test in this env)
- **U1 — bidirectional role arbitration.** If BOTH phones have screen on + both run the contact
  HCE, which one reads which is NFC-controller-dependent and non-deterministic (iPhone solves it
  with proprietary negotiation; Android does not expose this). → One-directional per tap (one
  phone the pure card, the other the reader) is deterministic; true 2-way from a single tap is
  the risk. MITIGATION if 2-way wanted: NFC bootstraps ONE direction (reader gets the card +
  the card-phone's LAN endpoint), then the app exchanges both cards over the EXISTING Wi-Fi-LAN
  transport (same pattern as the file path) — sidesteps NFC arbitration entirely.
- **U2 — does `ACTION_NDEF_DISCOVERED` from another phone's HCE vCard launch US** (vs the OS
  routing a `text/vcard` record to the stock Contacts app)? MIME-type intent-filter specificity
  + device behavior. Device test required.
- **U3 — environment**: NO NFC hardware and no two phones in this build env → everything is
  build/compile-verified only; the tap path is user-device-verified. (Same caveat as all
  existing NFC code here.)

## Scope decisions (user, 2026-06-30)
1. Exchange model: **BIDIRECTIONAL.** User clarified the NFC tag is used only for (a) QR→URL to
   iPhone and (b) ready-to-send-a-file; OUTSIDE those two it's free for us to use however. This is
   a full app-specific name-card feature, Android↔Android, **nothing to do with iPhone**, and NOT
   just a URL — our own card-exchange protocol. "the two apps would then connect to each other and
   quickly send the card."
2. Contact source: **in-app setup + fallback to whatever's already on the phone.**
3. Availability/battery: **user delegated to me — plan the most battery-efficient approach.**
4. Save method: **must save as a real contact; user delegated the how (in-app WRITE_CONTACTS vs
   system insert) to me — plan it.**

## THE PLAN (v1, 2026-06-30) — "Super Drop Name Card"

### A. At-rest behavior = ZERO battery (the key efficiency answer)
Nothing runs in the background. The ONLY always-on element is the **passive NFC HCE tag**
registration — the NFC controller answers a SELECT and Android only binds our `HostApduService`
when a reader physically taps and selects our AID. Our process need not even be running at rest.
**No BLE advertising, no mDNS, no foreground service, no sockets at rest** — those would cost
battery; the passive HCE is free. So the design is *tap-triggered*, not *broadcast-always*: the
"always available" part is the free passive tag; the tap spins up a short-lived connection.
(Strictly more efficient than a BLE/mDNS always-broadcast.)

### B. The tap flow (one tap → both cards = bidirectional), reusing the proven NFC-bootstrap-then-LAN pattern
1. **Tap.** Idle phone A's HCE `processCommandApdu` fires (cold-wakes A's app). In that callback A:
   - serves an NDEF message with a **custom MIME record `application/vnd.superdrop.namecard`**
     carrying A's **full card** (name+number+email — tiny, fits T4T) PLUS a small **bootstrap**
     {A's LAN IP:port just primed, a random session nonce};
   - **primes a short-lived TCP "card-exchange" listener** synchronously (mirrors
     `NfcColdReceiverPrimer`) and fires a brief FGS wake so A can accept a reverse connection.
2. **Receiver B (screen on + unlocked; app may be closed)** — the platform NDEF-dispatches the
   custom MIME to Super Drop (manifest `ACTION_NDEF_DISCOVERED` filter for our MIME → cold-launch).
   B now HAS A's card from pure NFC (works even offline → one-way always succeeds).
3. **B connects to A's IP:port over Wi-Fi-LAN**, authenticates with the nonce, sends **B's own card**.
4. **A receives B's card.** Both phones now have both cards → each shows a "Save <name>?" sheet.
   - **Offline / no shared LAN fallback:** step 3 fails → B still has A's card (one-way done); B
     offers "share yours back" → B briefly serves ITS card as the NDEF tag and a second tap (A reads
     B) completes 2-way. NO hotspot needed for a contact (unlike the file path). Degrades to 2 taps.

### C. Card data format
- Wire format: a compact self-defined blob (CBOR or length-prefixed fields) — name, phone, email,
  optional photo (defer photo to v2), schema version. NOT a raw vCard on the wire (we control both
  ends); convert to a real contact only at save time.
- Custom NDEF MIME `application/vnd.superdrop.namecard` so the receiving Android routes it to US,
  never to the stock Contacts app (sidesteps the vCard→Contacts dispatch risk).

### D. Your profile (source) — in-app setup + phone fallback
- New **"My Name Card" settings screen**: name, phone, email (+ photo later). Stored in
  SharedPreferences/DataStore.
- Fallback when empty: read `ContactsContract.Profile` ("Me" card) for name + `SubscriptionManager
  .getPhoneNumber()` (API33+) / `TelephonyManager.getLine1Number()` for the number
  (needs READ_CONTACTS / READ_PHONE_NUMBERS — requested only when the user taps "pull from phone").
- If still empty: prompt the user to fill the card before the feature is usable.

### E. Receive + save
- Receive sheet shows the incoming card (name/number/email) + Accept/Dismiss.
- Save path: **on Accept, write directly via `WRITE_CONTACTS`** (seamless, stays in Super Drop);
  if the permission is denied/unavailable, **fall back to the system Add-Contact insert intent**
  (`ACTION_INSERT`, no permission). Best of both, always works.

### F. NFC mode arbitration on the shared NDEF service (AID `D2760000850101`)
`SuperDropNdefApduService` mode-switches its served NDEF by priority:
1. QR session active (`NfcLinkHolder.currentUrl != null`) → URL URI record (existing iPhone path, untouched).
2. else Name-Card enabled + **device unlocked** → our namecard NDEF (new).
3. else → empty NDEF (existing no-op).
(The `F00000FE2C` file-tap HCE is separate and unchanged.) Unlock-gating done **in code**
(`KeyguardManager.isDeviceLocked()`), leaving the XML `requireDeviceUnlock=false` so the URL path
is unchanged — only the card is withheld while locked (privacy: don't leak your number off a locked phone).

### G. Permissions / manifest
- New: `ACTION_NDEF_DISCOVERED` activity intent-filter for `application/vnd.superdrop.namecard`.
- `WRITE_CONTACTS` (save), `READ_CONTACTS`/`READ_PHONE_NUMBERS` (optional pull-from-phone) — all runtime, requested on demand.
- Reuse existing INTERNET/Wi-Fi for the LAN exchange.

### H. MAKE-OR-BREAK unknowns (device-only — restated, still unresolved)
- U1 superseded: bidirectionality now via LAN, not NFC role-swap → arbitration risk removed for the happy path.
- **U2 — does a manifest custom-MIME `ACTION_NDEF_DISCOVERED` cold-launch our app** from another
  phone's HCE-emulated NDEF tag (screen on+unlocked, app closed)? Standard Android dispatch, but
  HCE-tag→NDEF-dispatch reliability is OEM-variable. Fallback: when the app IS open, use
  `enableForegroundDispatch`/reader-mode (always works). Receiver screen MUST be on+unlocked (NFC
  polling is off screen-off — inherent; matches NameDrop).
- **U3 — the LAN reverse connection** (B→A) succeeds only on a shared network; offline → 2-tap fallback (B).
- U4 — environment: no NFC hardware + no two phones here → compile-verified only; user device-tests.

### I. Build order (once approved)
1. Card model + codec (pure JVM, unit-testable here). 2. Profile store + "My Name Card" screen.
3. Extend `SuperDropNdefApduService` (namecard mode + unlock gate) + a `NameCardHolder`.
4. Cold-prime listener + LAN card-exchange server/client. 5. Receive activity (NDEF dispatch) +
   foreground dispatch + Save (WRITE_CONTACTS → insert-intent fallback). 6. Settings toggle +
   on-screen diagnostics. 7. Manifest. 8. On-device test script.

### Recommendations on the two delegated items
- **Battery:** tap-triggered, nothing-at-rest, passive-HCE-only (section A). Recommended.
- **Save:** direct `WRITE_CONTACTS` on Accept, fallback to system insert intent (section E). Recommended.
- **Privacy:** serve the card only while unlocked (section F). Recommended.

## VERIFIED — the EXACT NameDrop flow (researched 2026-06-30; sources below)
**Apple's official steps** (support.apple.com NameDrop guide, scraped 2026-06-30):
1. "Hold the display of your iPhone a few centimeters from the **top** of the other person's
   iPhone or Apple Watch."
2. "Continue holding your devices near each other until **NameDrop appears on both screens**."
3. "Choose to **share your contact card and receive the other person's**, or to **only receive**
   the other person's." (Two buttons: **Share** / **Receive Only**.)
4. If sharing: tap the disclosure triangle, **select which fields to include**, tap **Save**.
   The same fields are pre-selected next time.
5. Cancel by **moving the devices apart or locking** before the transfer completes.
- Setting: Settings > General > AirDrop > **"Bring Devices Together"** (on by default).
- Apple NOTE (authoritative, contradicts some blogs): "NameDrop only works for **sending NEW**
  contact information, **not updating an existing** contact." The card preview shows name + number.

**The technical mechanism** (corroborated across MacRumors / Apple / multiple explainers):
- **NFC chips near the TOP of each device** detect proximity and **initiate** the handshake (this is
  the trigger; that's why it's top-to-top).
- The system then **hands off to Bluetooth (discovery) + Wi-Fi (AWDL P2P) for the actual contact
  transfer** — the contact data does NOT travel over NFC. UWB (iPhone 11+) assists proximity.
- Visual: the top of the screen glows / light-beam animation during transfer. Managed by `sharingd`.

**KEY TAKEAWAY for our design:** NameDrop uses **NFC only as the trigger/bootstrap**, and a
**separate radio (Wi-Fi/AWDL, BLE for discovery) for the payload** — Apple does this because AirDrop
also moves large files/Contact Posters. **Our card is tiny (~200 B), so we can be SIMPLER than
NameDrop: carry the card itself inside the NFC tap for one direction (instant, offline, no
connection), and use a fast back-channel only for the return direction.** This VALIDATES the
"NFC bootstrap + separate transport" architecture.

## DECISION — transport (user asked: Quick Share protocol? Bluetooth? quickest, since data is tiny)
Ranked for a ~200-byte payload (analysis, device-unverified latencies):
- **NFC-in-the-tap (one direction A→B):** instant, ZERO connection setup, works offline. FASTEST
  possible. We carry the whole card in the NDEF — no second transport needed for this direction.
- **Return direction B→A (to make one tap = both cards):** needs a back-channel:
  - **Bluetooth BLE GATT — RECOMMENDED default:** no shared Wi-Fi needed, low setup for tiny data,
    works anywhere. New code (GATT server/client + BLUETOOTH_ADVERTISE/CONNECT) but modest.
  - **Raw TCP over existing Wi-Fi-LAN:** fastest to first byte IF both already on the same network;
    reuses existing networking. Use as a short-circuit when co-networked.
  - **Quick Share / Nearby Connections transport: NOT recommended for this** — it's the file-stream
    path with a full encrypted Nearby session handshake; for 200 B the setup dominates → SLOWER to
    first byte than BLE/TCP, not faster. Reusing it adds no speed and more coupling. (Pushing back on
    "same protocol as Quick Share if that's quick" — for this payload size it isn't the quick one.)
- **PROPOSED:** A→B card rides in the NFC tap (always, offline). For one-tap bidirectional, return
  B→A over **BLE GATT** (works off-network), short-circuiting to **raw TCP** when already on the same
  Wi-Fi. If both back-channels fail → degrade to a 2nd tap (B presents its card). No hotspot ever
  (unlike files). Battery at rest unchanged: passive NFC HCE only; BLE/TCP exist only during the swap.

Sources: Apple NameDrop guide (support.apple.com/.../iph1b6c664b7); MacRumors iOS 17 AirDrop guide;
arXiv 2606.26967 (AirDrop/Quick Share protocol research — confirms sharingd, AWDL/BLE split).

## RESOLVED ARCHITECTURE (2026-06-30, after user feedback) — supersedes the split-transport idea above
User points: (a) both cards should take the SAME route, not different ones; (b) must feel instant,
BLE ok if near-instant, Quick-Share-style only if fastest, else "whatever"; (c) wants it to LOOK like
NameDrop (full-screen vs heads-up?) and wants the Receive-Only / Share-both choice.

**Final model = mirror NameDrop exactly:**
1. **NFC = trigger ONLY** (like NameDrop's top-to-top NFC). The tap carries a tiny bootstrap
   (initiator's BLE connection handle + a one-time nonce). NO card data in the NFC itself — both
   cards go over ONE channel, symmetric. (Resolves "why different routes": they don't.)
2. **One persistent back-channel carries BOTH cards** = **BLE GATT** (works anywhere, no shared
   Wi-Fi/hotspot, ~1-2 s connect ≈ real NameDrop's own speed). RACE a plain **TCP** socket when both
   are on the same Wi-Fi and take whichever connects first (TCP → sub-second). NOT the Quick Share
   Wi-Fi-Direct/hotspot transport — its radio setup is the SLOWEST (seconds) for a tiny card.
   VERIFIED rationale: NameDrop is NOT instant either — Apple says "continue holding... until NameDrop
   appears on both screens" (~2-3 s) because of the BT→Wi-Fi handoff. BLE matches that feel.
3. **The link must PERSIST during the human Share/Receive-Only choice** — an NFC tap is too brief to
   hold while a user reads + decides. This is exactly why NameDrop uses Wi-Fi not NFC for the payload,
   and why pure-NFC can't offer the consent buttons. BLE stays up for the choice, then both cards cross.
4. **UI = full-screen, like NameDrop.** Reuse the app's existing keyguard-bypass full-screen pattern
   (`ConsentTrampolineActivity` already pops over the lock screen) for a full-screen **Name Card**
   activity on BOTH phones: card preview + glow (tween/overshoot easing, NOT a physics bounce) +
   **Receive Only** / **Share** buttons. NOT a heads-up notification (lower fidelity); NOT
   SYSTEM_ALERT_WINDOW overlay (permission-heavy, unneeded).
5. Consent both ways: initiator pre-consents by opening "Share my card" (or a QS tile); receiver gets
   the full-screen prompt with **Receive Only** (take theirs, don't send) vs **Share** (reciprocate).
6. Battery at rest unchanged: passive NFC HCE only; BLE/TCP exist only during the few-second exchange.
NEW permissions vs prior plan: BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT / BLUETOOTH_SCAN (BLE GATT).
Make-or-break unknowns now: BLE GATT connect latency on the user's OEMs; reliable cold-launch of the
receiver's full-screen activity from the NFC bootstrap (NDEF dispatch / the existing trampoline path).

## RESOLUTIONS round 2 (2026-06-30, user feedback) — the role problem + UI + source

**R1 — ROLE ASSIGNMENT (user's key point: both phones run the same app, so both broadcast the same
HCE; one isn't "set to receive" while the other broadcasts).** CORRECT, and this is THE core Android
constraint. Resolution: the two phones are symmetric AT REST (both register the same passive name-card
HCE = "both broadcasting"), but become asymmetric AT SHARE TIME: the person who **opens "Share My
Card" flips THEIR phone into NFC reader-mode** (reader-mode needs a foreground Activity + suppresses
that phone's own HCE) → that phone is the READER; the other phone stays the passive CARD (its
background HCE answers; app can be closed, just needs to be unlocked). Deterministic role assignment —
this is the ONLY way on Android (no iPhone-style controller auto-negotiation). The NFC exchange is a
**custom-AID APDU** (like the existing F00000FE2C file path), NOT NDEF auto-dispatch (that relied on
flaky two-HCE arbitration — DROPPED). Both apps always register the new name-card AID.
  - EDGE: if BOTH people open "Share" at once → both readers → no card to read → detect + show
    "only one of you taps Share; the other just unlocks their phone." (No silent failure.)
  - NOTE: this supersedes the earlier NDEF custom-MIME `application/vnd.superdrop.namecard`
    auto-dispatch idea — we use reader-mode + custom AID instead (deterministic). The Mengram
    auto-recall lines mentioning the NDEF MIME are echoes of the SUPERSEDED plan, not verified facts.

**R2 — UI is a NEW dedicated screen, NOT a reuse of existing activities** (user: reusing the app's
existing stuff "would just mess up the app"). Build a brand-new `NameCardActivity` (own class + own
layout) that only BORROWS THE TECHNIQUE (keyguard-bypass window flags: setShowWhenLocked/
setTurnScreenOn + full-screen) from `ConsentTrampolineActivity` — does NOT reuse or modify that class
or the file-share consent flow. Keeps existing app untouched.

**R3 — card source fallback chain** (user): (1) in-app name card made beforehand (primary); (2) if
none → device's own SIM number (`SubscriptionManager.getPhoneNumber`/`TelephonyManager.getLine1Number`,
READ_PHONE_NUMBERS) + the device "Me" profile (`ContactsContract.Profile`, READ_CONTACTS); (3) bare
number if nothing else.
  - **Emergency-profile fallback (user idea) = NOT FEASIBLE via public API (VERIFIED 2026-06-30).**
    Android's `android.telephony.emergency` package is emergency *numbers* (911) only; the personal
    Emergency Information (name/medical/ICE contacts) is owned by the Emergency app
    (`com.android.emergency` / OEM safety apps) in its OWN private storage with no documented
    third-party read API. So drop it; the `ContactsContract.Profile` "Me" card + SIM number cover the
    same fallback intent and ARE readable with permission. (Tentative — revisit if a public API appears.)

**OPEN TRADE-OFF to put to the user (the one real tension):** per-tap **Receive Only / Share** choice
(which the user likes) requires the link to PERSIST during the human decision → needs **BLE (~1-2 s, =
real NameDrop speed; NameDrop is NOT instant either)**. A truly **instant** one-tap both-cards swap is
only possible if the recipient **pre-consents** (a "share my card automatically when tapped" setting) so
the cards can cross in the NFC tap itself with no buttons. Can't have both per-tap-buttons AND instant.
NameDrop itself = buttons + ~2 s, so "exactly like NameDrop" ⇒ the BLE path.

## RESOLUTIONS round 3 (2026-06-30) — FINAL design choices; ready to build after go-ahead

**Q (user): at rest, before anything is opened, if two phones tap, can NFC still trigger it?**
ANSWER (honest, with the one device-only unknown):
- A phone that is **unlocked + screen-on** keeps its NFC controller in the normal poll/listen discovery
  loop, so it can READ another phone's HCE tag **without our app being open** → the platform launches
  our app via tag dispatch. So YES, an at-rest tap CAN trigger it **as long as at least one phone is
  unlocked/screen-on** (locked → nothing, which the user WANTS).
- THE device-only unknown (U-NFC, cannot verify here — no 2 phones/NFC): when BOTH phones are active
  HCEs (both our app's tag registered, both screens on), NFC is reader↔tag by nature, so the two
  controllers must arbitrate which becomes the momentary reader. This active-active case is
  OEM/timing-dependent — usually one wins and reads the other, but it can collide / not connect on some
  devices. → We do NOT depend on it: it's the "nice when it works" path. The GUARANTEED path is one
  person opening **"Share My Card"** → explicit reader-mode → deterministically reads the other's tag.
  Build BOTH; surface which fired in diagnostics.
- Once ANY direction triggers, hand off to **BLE** and let the apps **negotiate over BLE** (user's
  idea) — direction no longer matters because BLE is bidirectional.

**FINAL transport/consent (user decided):** NFC = trigger/bootstrap only; **BLE carries the cards +
the negotiation**. NOT instant-NFC. Buttons (Receive Only / Share) are fine; latency must be
"not a massive lag" (BLE ~1-2s acceptable, = NameDrop's own speed). If BOTH tap Share → both send →
full swap (no leader election needed; "Receive Only" simply means "don't send mine").

**FINAL lock behavior (user):** it must **NOT work while locked**. So: (a) the HCE tag answers ONLY
when unlocked (`KeyguardManager.isDeviceLocked()` gate); (b) the receive screen is a PLAIN activity
with **NO keyguard-bypass flags** (drop setShowWhenLocked/setTurnScreenOn entirely — simpler, and it
naturally won't show on a locked phone). This SUPERSEDES R2's "borrow the keyguard-bypass technique" —
we borrow nothing; plain full-screen activity, only ever shown unlocked.

**=> FROZEN ARCHITECTURE:**
1. At rest: both apps register a passive name-card HCE tag (free, battery-0), answering only while
   unlocked. Nothing else runs.
2. Trigger: a tap. Either the platform auto-reads (both at rest, ≥1 unlocked — best effort) OR one
   person opens "Share My Card" → reader-mode (guaranteed). The tag/exchange carries a tiny BLE
   bootstrap (addr + nonce), no card data.
3. BLE GATT link comes up; apps negotiate; both show a PLAIN full-screen `NameCardActivity`
   (unlocked-only) with the card preview + Receive Only / Share.
4. On choice, full cards cross BLE (same route both ways). Save → write contact (WRITE_CONTACTS) or
   system Add-Contact insert fallback; offer update-existing (better than NameDrop).
5. Card source: in-app card → SIM number + "Me" profile → bare number. (Emergency profile = no API.)
6. Perms: NFC (have), BLUETOOTH_ADVERTISE/CONNECT/SCAN, WRITE_CONTACTS, optional READ_CONTACTS/
   READ_PHONE_NUMBERS. minSdk check needed (BLE/contacts APIs).
7. Build is compile-only here (no NFC/BLE/2 phones) → ship + on-device test script. U-NFC + BLE
   latency = the device-verify items.

**NEXT STEP:** get explicit go-ahead, then build device-independent core FIRST (card model + codec +
profile store + "My Name Card" settings screen — all unit-testable here), then the NFC/BLE/receive
wiring, then the test script. Repos: bada-fork first → bada-debrand upstream PR later.

## ⛔ FEASIBILITY WALL (VERIFIED 2026-06-30 from docs) — "both phones at rest, no app, tap-only" is NOT possible on a normal Android app
User hard requirement (latest): NEITHER person opens anything or pushes a button; the TAP ALONE
triggers; "Share My Card" appears only AFTER the tap; works only when BOTH phones are UNLOCKED.
Researched the official docs + real-world evidence. VERIFIED findings:

1. **Android NFC needs exactly ONE reader + ONE card.** Official HCE doc: HCE is purely reactive
   (listens for APDUs; no simultaneous polling). Multiple sources: "you cannot have two HCE devices
   communicating directly with each other" — one must be the card (HCE), the other the reader.
2. **The reader side needs `enableReaderMode`, which REQUIRES a foreground Activity.** A closed/
   background app cannot be the reader. So two at-rest (app-closed) phones = two cards = nothing.
3. **The feature that DID do "two idle phones tap → trigger" was Android Beam — deprecated in
   Android 10, REMOVED in Android 14.** That capability is gone for normal apps.
4. **Even Google's OWN Quick Share** (what this app mirrors; reverse-engineered here) **arms NFC
   reader-mode via the share sheet** — i.e. the SENDER must have the share UI in the foreground. The
   receiver can be idle, but one side is ALWAYS a foreground reader. (Memory: "Quick Share sender
   acts as NFC reader/initiator. The share sheet arms NFC reader mode.")
5. The upcoming **Android "Tap to Share"** can do both-at-rest ONLY because it's built into the OS
   (privileged) — a sideloaded third-party app cannot replicate an always-on OS-level reader.

⇒ CONCLUSION: the pure "both fully at rest, zero app, tap-only" trigger is **not achievable** for a
sideloaded app (would need OS/system privilege, or root, or the removed Beam). This is a hard wall,
not a tuning problem. Surfaced to user as required pushback.

### What IS achievable (closest to the wish, still "no button presses"):
- **SENDER**: just needs Super Drop **in the foreground** — the app auto-arms reader-mode the instant
  it's open (NO button to press inside it). Or a **Quick Settings tile** that arms it (one tile tap).
- **RECEIVER**: can be **fully at rest** — app closed, screen on, just unlocked. Its background HCE
  answers and the app cold-wakes to show the card. Receiver presses nothing, opens nothing.
- **Both unlocked** enforced; locked → nothing (matches the user's requirement exactly).
- So the realistic flow: one person has the app open (or taps a QS tile) — no in-app buttons — and
  taps it to the other's unlocked, idle phone; both then get each other's card.

PENDING USER DECISION: accept the "sender has app open / QS tile, receiver fully idle" model (the
Android maximum), OR change the goal. Cannot proceed to build until the trigger model is agreed,
because everything hangs off it. (Also save this as a reusable memory: Android two-phone NFC wall.)

## ⚠️ CORRECTION to the "wall" (2026-06-30) — user reframed: tap = TRIGGER only, not data carrier
The earlier "FEASIBILITY WALL" over-stated it. The wall (two cards can't EXCHANGE over NFC) is true
only if NFC must carry the data. User's reframe: **the tap just needs to WAKE both apps; the exchange
happens AFTER over BLE** (scan/connect-to-nearest/negotiate/swap). That is EXACTLY Google's own design.

VERIFIED from my own GMS decompile notes ([[reference_gms_gestureexchange_taptoshare_2026_06_11]]):
Google "Tap to Share" = `com.google.android.gms.gestureexchange`: **NFC HCE is connection BOOTSTRAP, not
data carrier** (verified strings: BandwidthUpgradeManager "Gesture Exchange connection on a HIGH quality
medium"; NfcEndpointChannel is one Nearby transport; tap bootstraps → upgrades to Wi-Fi/Aware). Two HCE
entry points (HostApduService + HostNdefApduService), `setPreferredService`, gated only by public
BIND_NFC_SERVICE (sideload-replicable), NO attestation on the handshake. So the user's architecture is
literally Google's.

CORRECTED feasibility (from [[reference_nfc_two_phone_role_control_2026_06_02]] + HCE docs):
- A single successful NFC read **wakes BOTH apps**: the READER phone launches via tag dispatch
  (ACTION_NDEF_DISCOVERED / TECH), AND the CARD phone's `HostApduService.processCommandApdu` fires →
  its process wakes → it can start BLE. One tap → both alive.
- Role determinism when BOTH idle is OEM-variable (NFC must pick who reads) — but for "just wake both"
  EITHER direction works, so non-determinism is acceptable here (unlike a data exchange).
- Deterministic levers if needed: `enableReaderMode` suppresses that phone's own card → guaranteed
  reader (ALL Android versions, needs foreground Activity); **Observe Mode** `setObserveModeEnabled`
  (Android 15+) lets a BACKGROUND card defer without foreground. CATEGORY_OTHER AID can be always-active.
- => The earlier "hard wall / not achievable" verdict is SUPERSEDED. Achievable as a trigger-only tap;
  reliability spectrum: best-effort when both fully idle, deterministic when ≥1 app foreground / A15+.

## FROZEN DESIGN v2 (2026-06-30) — matches Google's gestureexchange
1. **At rest:** both apps register a passive HCE (CATEGORY_OTHER), answering only while UNLOCKED. Zero
   battery, no sockets/BLE/FGS running.
2. **Tap = TRIGGER ONLY:** wakes both apps (reader via dispatch + card via HCE callback). NFC carries at
   most a tiny bootstrap token (so the two apps target each other on BLE, not a random 3rd device) — NOT
   the contact card.
3. **BLE does the work:** woken apps scan/advertise, connect to the tapped peer (token-matched), and
   negotiate. (TCP shortcut if same Wi-Fi.) Mirrors Nearby bandwidth-upgrade.
4. **Card screen AFTER:** a new plain full-screen `NameCardActivity` (own class, only shows when
   unlocked) with the card preview + Receive Only / Share. (NameDrop-like; tween/overshoot, no physics bounce.)
5. **Save:** WRITE_CONTACTS, system-insert fallback; can update existing.
6. **Source:** in-app card → SIM number + "Me" profile → bare number. (Emergency profile = no public API.)
7. **Both unlocked** enforced; locked → nothing.
8. Perms: NFC (have), BLUETOOTH_ADVERTISE/CONNECT/SCAN, WRITE_CONTACTS, optional READ_CONTACTS/READ_PHONE_NUMBERS.

## PRE-BUILD RISK PASS (v2, 2026-06-30)
- ASSUMPTIONS: HCE callback wakes process [VERIFIED, file path uses NfcColdReceiverPrimer]; tag dispatch
  launches app [VERIFIED platform behavior]; BLE GATT pairing-free works app-to-app [VERIFIED — memory
  says BLE path already does file transfer]; both-idle tap wakes both [UNVERIFIED, OEM — device test].
- UNKNOWNS: which phone reads when both idle (OEM); BLE connect latency on user's phones. → make
  OBSERVABLE (on-screen + DiagnosticLog which path fired) + on-device test script. Not a blocker (trigger-only).
- PRECONDITIONS: both unlocked (gate in HCE + activity); NFC on; BT on (use radio-helper to force BT on at trigger).
- ENTRY POINTS: new HCE/reader for a NEW name-card AID (do NOT disturb F00000FE2C file path or
  D2760000850101 iPhone-URL path); new BLE service; new activity; new settings screen; manifest.
- CROSS-CUTTING: no main-thread block (BLE/IO off-main); lifecycle (tear down BLE after swap); minSdk
  guards (Observe Mode A15+, getPhoneNumber A33+); permission-denied paths; BT-off path (radio-helper).
- OBSERVABILITY: DiagnosticLog + on-screen tap/connect outcome from the start (reuse DiagnosticUploader pattern).
- VERIFY REACHABILITY: compile + unit-test the device-independent core HERE; NFC/BLE/2-phone = user test script.

## CLARIFICATIONS round 4 (user, 2026-06-30)
- **Transfer/exchange screen: NO overlay permission.** It's a normal **full-screen Activity** the tap
  drags you INTO the app for, and it EXITS when done. (Confirms P5 = plain Activity, NOT SYSTEM_ALERT_WINDOW.)
- **The active-transfer UI looks like NameDrop** (the glow/light-beam + card while transferring). → P5.
- **Name Card settings row gets the red dot when NOT set up** — DONE (P2.1, below).
- **FUTURE / plan-mode (save-for-later, NOT building now):** make it work with **native Quick Share** —
  since stock Quick Share has no contact feature, do NOT reuse our NFC/BLE method; instead a SEND-ONLY
  path that just sends your contact as a **vCard (.vcf, `text/x-vcard`)** over the normal Quick Share/
  Sharesheet (`ACTION_SEND` + FileProvider `EXTRA_STREAM`). Full plan in memory
  `project_superdrop_namecard_quickshare_send_plan_2026_06_30` + the FUTURE section here. Researched,
  unbuilt.

## P2.1 (2026-06-30, compile-verified) — red dot on the Name Card row when not set up
`settings_name_card_dot` (8dp `@drawable/update_badge_dot`, the SAME red dot as the update badge) added
to the row, before the chevron; `visibility=gone` default. `SettingsFragment.refreshNameCardDot()`
(called from `onStart`) shows it when `NameCardProfileStore.isConfigured()` is false (in-app card only;
a device SIM/"Me" fallback still counts as "not set up"). Clears on return after a save. `:app:assembleDebug`
BUILD SUCCESSFUL. On-screen render device-UNVERIFIED.

## FUTURE — native Quick Share contact send (plan only, not built)
See memory `project_superdrop_namecard_quickshare_send_plan_2026_06_30`. Summary: vCard 3.0 (.vcf,
`text/x-vcard`) via `ACTION_SEND` + FileProvider `EXTRA_STREAM` → Quick Share chooser; send-only,
no NFC/BLE; build a `NameCard→vCard` converter (pure, in `:core-protocol`) when we do it.

## BUILD ORDER (P3 next)
P1 (device-independent, unit-testable HERE): NameCard model + wire codec + profile store. 
P2: "My Name Card" settings screen + source-fallback resolver. P3: name-card HCE (new AID) + reader +
bootstrap token + cold-wake. P4: BLE GATT exchange (+TCP shortcut) + negotiation. P5: NameCardActivity
(post-tap, unlocked-only) + Save (WRITE_CONTACTS/insert). P6: manifest + perms + radio-helper BT-on.
P7: diagnostics + on-device test script. Then bada-debrand upstream PR.

## Log
- **2026-06-30** Mapped the NFC stack. Researched + recorded the exact NameDrop flow (Apple official
  + mechanism) and the transport analysis. Pending: user picks transport (BLE-default vs TCP-only
  vs reuse-QuickShare) → then write the complete spec + pre-build risk pass + implement. No code yet.
