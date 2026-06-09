# NFC "Super App" — one tap against Quick Share, OShare/OnePlus, AND iPhone

**Feasibility + design doc.** Goal: a single Super Drop / Bada app whose NFC tap interoperates with
*any* nearby phone — tap a Google **Quick Share** Android, an **OnePlus/OPPO (OShare / O+ Connect)**,
or an **iPhone** — ideally with the NFC capability available in the **background** (no app open).

This doc is research/design only. No code here. Every protocol claim is cited to decompiled smali
(`file:line`) or to an authoritative Android/Apple doc URL. Anything not directly read this session is
marked **UNVERIFIED**.

Sources read for this doc:
- Quick Share HCE: `/root/agent-work/projects/quickshare-nfc/base/` (GMS 26.18.33).
  - `smali8/.../nearfieldcommunication/NfcAdvertisingChimeraService.smali` (HCE service).
  - `smali8/dfdo.smali` (post-tap connect runnable — the BT-MAC branch, read directly below).
  - Byte map `docs/NFC_INTEROP_BYTEMAP.md` (hhww/hhwv tags, deym layout, rxAdv DE-TLV) — verified.
- OShare/iPhone NDEF: `/root/agent-work/projects/oshare-nfc-tap/` (built T4T NDEF HCE, with OnePlus
  firmware `HostEmulationManager.java:72/611` cites recorded in its source comments — **see caveat §1.2**).
- Android HCE rules: https://developer.android.com/develop/connectivity/nfc/hce (fetched 2026-06-08).
- iPhone background NFC: idownloadblog/NFCW (2018 launch) + GoToTags help
  (https://gototags.com/help/ios/nfc/reading/background, fetched 2026-06-08).
- Existing fork NFC code: `app/src/main/res/xml/superdrop_apduservice.xml`,
  `superdrop_tap_apduservice.xml`, `app/src/main/AndroidManifest.xml` (two HCE services today).

---

## TL;DR / VERDICT

| Target | Our NFC role | AID SELECTed | What we serve | What the tap triggers | Background (no app open)? |
|--------|--------------|--------------|----------------|------------------------|----------------------------|
| **Quick Share** (Android) | **HCE (we are the receiver)** | `F00000FE2C` | `hhwv` proto = `deym` NfcTag + Wi-Fi-LAN `rxAdv` | Other phone's QS connects to us over Wi-Fi-LAN/BT | **YES** (HCE is reader-driven; OS wakes our service) — *but* the **other** Android must be in its QS send/discovery UI |
| **OShare / OnePlus** (Android) | (NDEF tag, see §1.2) | `D2760000850101` | NDEF URI record (a link) | Other phone opens the link / app | Tag side: yes. Full bidirectional OShare interop: **NOT mapped** (§1.2) |
| **iPhone** | **HCE = NDEF Type-4 tag** | `D2760000850101` | NDEF URI record (http/https link) | iOS shows a banner → user taps → opens URL/App Store/universal link | **YES** on iPhone XS+; the iPhone needs screen-on + unlocked-once |

**Can ONE app serve all three?** **Yes for the HCE/tag side.** One app can register **both** AIDs
(`F00000FE2C` and `D2760000850101`), in either one multi-group `HostApduService` or two services, and
branch on the selected AID. Both are `category="other"` so there is no payment-role conflict. All three
"tap us" directions want us to be the **HCE/tag**, which is the passive, background-capable role — they do
**not** conflict; they are different AIDs routed independently by the OS.

**The honest blockers** (detail in §2–§5):
1. **AID collision on GMS phones.** If real Google Quick Share (GMS) is installed on the *same* phone as
   our app, **both** register `F00000FE2C` → the OS must pick one or prompt the user (Android HCE conflict
   rules). On a non-GMS phone (Oppo/Vivo without GMS QS) we own it cleanly. **VERIFIED** rule, see §2.
2. **Reader-mode (us as SENDER) needs the app foreground.** `NfcAdapter.enableReaderMode` is an activity
   API; you cannot poll/read another phone's tag from the background. Only the **HCE** (tag) side is
   background-capable. So "tap THEM and we initiate" is foreground-only. **VERIFIED** by API shape.
3. **iPhone only reads NDEF, only specific URL schemes, only with a user tap on the banner.** We cannot
   make an iPhone do anything but open a link in the background. **VERIFIED** (§1.3).
4. **OShare full interop is NOT byte-mapped.** We can serve the *iPhone-style NDEF link* that OShare's own
   firmware emits, but a complete "tap a OnePlus, files flow OShare-native" two-way protocol is **not**
   mapped here (§1.2). What *is* solid: the OShare NDEF link is identical in shape to the iPhone case.
5. **Nothing here is device-tested.** All protocol mapping; **no live NFC hardware test performed.**

---

## §1 — Per-target NFC-layer breakdown (reader vs HCE, AID, bytes, trigger)

### §1.1 Google Quick Share — raw ISO-DEP/APDU (NOT NDEF)

**Roles (VERIFIED):** asymmetric.
- **Receiver/advertiser = HCE.** `NfcAdvertisingChimeraService extends
  com.google.android.chimera.HostApduService` — confirmed at
  `smali8/.../nearfieldcommunication/NfcAdvertisingChimeraService.smali:2` (`.super`),
  `:392` (`processCommandApdu([BLandroid/os/Bundle;)[B`), `:258` (`onDeactivated(I)`).
- **Sender/discoverer = reader-mode** (`NfcAdapter` reader-mode + `IsoDep.transceive`, via `djkb`/`djvh`).

**AID SELECTed (VERIFIED):** `F00000FE2C` (string present in
`/root/agent-work/projects/quickshare-nfc/base/allstrings.txt`; routing XML category `other`,
requireDeviceUnlock=false per the prior memory map). SELECT APDU = `00 A4 04 00 05 F0 00 00 FE 2C 00`
→ HCE replies `90 00`.

**Bytes exchanged (VERIFIED, from `docs/NFC_INTEROP_BYTEMAP.md`):**
- Reader → HCE **ADVERTISEMENT** APDU: `80 01 00 00 <Lc> <hhww-bytes> FF`. `hhww` (protobuf-lite,
  `Lhhww;` in classes13.dex): field1 string `serviceId` (= "NearbySharing"), field2 string
  `localEndpointId`, field3 bytes extra.
- HCE → Reader **response**: `<hhwv-bytes> 90 00`. `hhwv` (`Lhhwv;`): field1 bytes = `deym` NfcTag,
  field2 bytes = `rxInstantConnectionAdv`, field3 bytes = extra; `b:int` hasbit mask (c=0x1/d=0x2/e=0x4).
- `deym` NfcTag layout (VERIFIED, `dfdo.run` deserializer): `[1B header=(ver<<5)|pcp]
  [4B endpointId][3B serviceIdHash][1B infoLen][N endpointInfo][6B BT-Classic MAC][1B flags]`.
  header for QS = `0x23` (ver1, **pcp 3 = P2P_POINT_TO_POINT** — `dfet.x`: STAR=1, CLUSTER=2,
  POINT_TO_POINT=3; QS file-transfer pins POINT_TO_POINT). serviceIdHash = SHA-256("NearbySharing")[:3]
  = `FC 9F 5E`.
- `rxInstantConnectionAdv` (VERIFIED, `dfga.a` parser + `denp.g` encoder): EncryptionKey DE
  (type `0x17`, 32B) + Wi-Fi-LAN ConnectivityCapability DE
  (`[0x8D,0x15,0x02, ip(4B), port(2B BE), bssid(6B)]` for IPv4).

**What the tap triggers (VERIFIED — the crux, read directly this session):** the NFC tap opens **no
transport of its own**. `dfdo.run` (`smali8/dfdo.smali`) injects the tapped peer into the normal Nearby
Connections discovery pipeline (`onEndpointFound`), then the standard medium upgrade runs over
Wi-Fi-LAN / Wi-Fi-Direct / hotspot / Aware / BT.

> **Conflict resolved this session — BT-Classic MAC is OPTIONAL, not mandatory.**
> Two prior memory notes disagreed: one (`reference_quickshare_nfc_tap_protocol_2026_06_06.md`) claimed
> "BT MAC MANDATORY → aborts before any Wi-Fi"; the newer byte map said optional. I read
> `smali8/dfdo.smali` directly:
> - Lines ~1090–1158: the Wi-Fi/LAN medium is registered **first** — `dfet.ad(endpointId, hhyx.h)`
>   (`hhyx.h` = NFC=7) then **`dfet.U(desg, dfcn)`** (the `onEndpointFound` handoff) at the
>   `invoke-virtual {v5, v7, v0}, Ldfet;->U(...)` call (~line 1142 region), plus `dfet.av`/`dfet.at`.
> - Line ~1170 `:cond_177`: it reads `deym.g` (BT MAC String). `if-nez v0, :cond_185` — if the MAC is
>   **non-null** it jumps ahead to also register a BT-Classic candidate. If the MAC **is null**, it logs
>   `"No Bluetooth Classic MAC address found in NfcTag %s"` and `return-void` (line ~1186).
> - **Therefore the `return-void` aborts only the *additional* BT-Classic registration. The Wi-Fi-LAN
>   endpoint was already handed to `onEndpointFound` before the check.** The "mandatory" memory note is
>   **wrong**; a pure-Wi-Fi-LAN tag IS injected into discovery. (Whether a stock *sender* then actually
>   completes a connect over LAN with no BT bootstrap is a separate upper-layer policy question — still
>   **UNVERIFIED**, no device test.)

### §1.2 OnePlus / OPPO OShare (O+ Connect) — NDEF Type-4 link

**What is solidly known (VERIFIED from our built artifact + its firmware cites):**
- OShare's iPhone-facing tap = a standard **NFC Forum NDEF Type-4 Tag**, AID **`D2760000850101`**
  (the NFC Forum NDEF Tag Application AID), serving a single NDEF **URI** record =
  `https://connect.oppo.com/oshare/clips` (a universal link → O+ Connect app, else App Store).
- Our `/root/agent-work/projects/oshare-nfc-tap/src/.../NdefAppStoreApduService.java` reproduces the full
  T4T read sequence (SELECT AID → SELECT CC `E103` → READ CC → SELECT NDEF `E104` → READ NLEN → READ
  message) using only public `android.nfc.cardemulation` APIs. **VERIFIED-as-built** (compiles; byte
  layout per NFC Forum T4T + NDEF URI RTD specs).
- The source comments cite OnePlus firmware `HostEmulationManager.java:72` (routes the AID) and `:611`
  (checks the payload contains hex `636F6E6E6563742E6F70706F2E636F6D2F6F73686172652F636C697073`).

> **CAVEAT (honesty):** I did **not** re-open the OnePlus firmware `HostEmulationManager.java` in *this*
> session — those line cites are carried from the `oshare-nfc-tap` source comments, not re-verified now.
> Treat the AID + URI as solid (the URI is a public universal link; the AID is the standard NDEF AID),
> but the firmware `file:line` as **prior-work, not re-verified this session**.

**What is NOT mapped here:** a *full bidirectional* "tap a OnePlus and do OShare-native file transfer
both ways" protocol. OShare's real device-to-device flow is **BLE-advertise + GATT handshake + relay/Wi-Fi
transfer**, not NFC — the NFC tap is just the *bootstrap link* (same role NFC plays for Quick Share). The
deep OShare BLE/GATT interop is tracked separately in
`reference_oshare_contacts_only_verification_2026_06_02.md` and `project_oconnect_debug_state_*` and is
**out of scope for the NFC layer**. **At the NFC layer, "interop with OShare" == serve the same NDEF link
OShare serves** — which is the §1.3 iPhone case byte-for-byte.

### §1.3 iPhone — stock CoreNFC background NDEF read

**Roles (VERIFIED via Apple-launch coverage + GoToTags):** the **iPhone is the reader** (background
polling, built into iOS — no app). We are the **HCE/tag** serving NDEF.

**AID / bytes:** the iPhone's background reader is an NDEF reader; we present an **NDEF Type-4 Tag** under
AID `D2760000850101` (same as §1.2) with a single **URI** record.

**Conditions & trigger (VERIFIED, GoToTags https://gototags.com/help/ios/nfc/reading/background, fetched
2026-06-08):**
- Models: **iPhone XS and newer** only. (Background reading shipped with iOS 12 on XS/XR per the 2018
  launch coverage — idownloadblog/NFCW; "Apple didn't change background reading in iOS 13.")
- Required conditions, quoted: "The iPhone must have been **unlocked once since startup**"; "A Core NFC
  session or Apple Pay transaction must **not** be in progress"; "The camera is **not** in use";
  "Airplane mode is **not** enabled."
- **Supported URL schemes only:** `http://`, `https://`, `mailto:`, `sms:`, `tel:`, `facetime://`,
  `facetime-audio://`, `X-HM://` (HomeKit). **Custom schemes are NOT supported.** → our record must be an
  **https** universal link.
- **User action required:** "A pop-up notification is displayed… The user **must click on the pop-up** to
  perform the action; this is an opt-in privacy/security feature." If the URL is an **Apple Universal
  Link**, iOS routes straight to the matching app; otherwise it opens in Safari (→ can be your App Store
  page). **We cannot do anything in the background on iPhone except surface a link the user taps.**

---

## §2 — Can ONE app serve all three via HCE? (Android HCE routing rules)

**Yes.** Verified against https://developer.android.com/develop/connectivity/nfc/hce (fetched 2026-06-08).

**Multiple AIDs / groups in one service — ALLOWED.** Quote: *"a single HostApduService can register
multiple AIDs organized into AID groups"* / *"an HCE service may need to register multiple AIDs … in order
to implement a certain application."* Per-group guarantee: *"All AIDs in the group are routed to this HCE
service [or] No AIDs in the group are routed"* (all-or-nothing **within a group**).

→ **Design choice:** put `F00000FE2C` and `D2760000850101` in **separate `<aid-group>`s** (they belong to
different protocols and you want them routed independently — you do not want the all-or-nothing coupling).
They can live in **one** `HostApduService` (branch in `processCommandApdu` on the selected AID — the first
SELECT-by-name tells you which protocol) **or** in **two** services (what the fork does today:
`SuperDropNdefApduService` + `SuperDropTapHceService`). Both are valid; see §5 recommendation.

**Branching in `processCommandApdu`:** the first APDU after the field engages is `00 A4 04 00 <Lc> <AID>`.
- AID `D2760000850101` → run the **Type-4 NDEF state machine** (SELECT CC/NDEF, READ_BINARY) → serve the
  link (handles BOTH iPhone and OShare-NDEF). The fork already does this in `SuperDropNdefApduService.kt`
  and the standalone `NdefAppStoreApduService.java`.
- AID `F00000FE2C` → run the **Quick Share APDU codec** (SELECT→9000, `80 01` ADVERTISEMENT → build
  `hhwv`). The fork already does this in `SuperDropTapHceService.kt` + `QuickShareNfcCodec.kt`.

**Category rules (VERIFIED):** both AIDs are `category="other"`. Quote: *"AID groups in [CATEGORY_OTHER]
can be always active."* The payment restriction (*"Only one AID group in CATEGORY_PAYMENT can be enabled
at any time"*, and on Android 15+ payment needs the Wallet-role/default or foreground `setPreferredService`)
**does not apply** to us. So **no payment-role gymnastics**, and both groups are always active.

**`requireDeviceUnlock` (VERIFIED):** *"By default, device unlock is not required, and your service is
invoked even if the device is locked."* Both fork XMLs set `requireDeviceUnlock="false"` → tap answered
from the lock screen. (Caveat: §3 — Secure NFC / screen-off.)

**Limits / conflicts (VERIFIED):**
- **AID collision** (the real blocker): *"the same AID can be registered by more than one service"* →
  resolution order: (1) default wallet app if it registered the AID; (2) the single service that
  registered it; (3) **if more than one service registered it, Android asks the user which to invoke.**
  → If GMS Quick Share is installed on the same device, it **also** owns `F00000FE2C` → conflict → user
  prompt or it routes to GMS. **`D2760000850101` is less likely to collide** (it's the generic NDEF Tag
  AID; few apps register it as HCE), but a stock OEM NDEF service *could*.
- **Foreground override:** *"Apps in the foreground can invoke `setPreferredService` … This foreground app
  preference overrides the AID conflict resolution."* → if our app is foreground (e.g. receive UI open),
  we can call `setPreferredService` to win `F00000FE2C` even on a GMS phone **while foreground**. Does NOT
  help the pure-background case on a GMS phone.

---

## §3 — What "broadcast NFC in the background" realistically means

**HCE (tag) = background-capable. Reader-mode (active polling) = foreground-only. Android Beam = gone.**

- **HCE is reader-driven and needs no foreground app (VERIFIED).** Quote: *"tapping the device against
  the NFC reader starts the correct service if it is not already running and executes the transaction in
  the background."* So our **tag side for all three targets works with no app open** — the OS routes the
  tap to our registered service. This is the "broadcast in the background" that is actually achievable.
- **Screen-off / locked caveats (VERIFIED):**
  - Android ≤9: *"the NFC controller and the application processor are turned off completely when the
    screen … is turned off. HCE services therefore don't work when the screen is off."*
  - Android 10+ **Secure NFC**: *"While Secure NFC is on, all card emulators … are unavailable when the
    device screen is off."* Many OEMs ship Secure NFC **on** by default → **screen must be on** for our
    HCE to answer (lock screen is fine if `requireDeviceUnlock=false`, but the screen must be awake).
  - So realistic baseline: **screen on (lock screen OK), no app needed.** Truly screen-off background HCE
    is not guaranteed (depends on Secure NFC). **Treat "screen must be on" as the safe assumption.**
- **Reader-mode is NOT background.** `NfcAdapter.enableReaderMode(Activity, …)` is bound to a foreground
  activity; you cannot actively poll for / read another phone's HCE tag from a background service.
  → The direction "**we tap them and we initiate**" (us = Quick Share *sender* reader-mode) is **only
  possible with our send/receive activity in the foreground.** **VERIFIED by the API contract.**
- **Android Beam / NDEF push removed (VERIFIED):** `android.nfc.NfcAdapter` NDEF push (`setNdefPushMessage`
  / Android Beam) was deprecated in API 29 and removed — we cannot rely on Beam for any emit path. HCE is
  the only background-capable emit mechanism. (Standard platform fact; reflected in the AOSP API.)
- **On one NFC controller, HCE and reader-mode are mutually exclusive at a given instant** — you can host
  HCE (background, all targets) by default, and only switch to reader-mode while a send activity is
  foreground (and switch back after).

**Net:** "Background NFC broadcast" = **we sit as an HCE tag on both AIDs; any of the three target phones
that taps us (with *their* sender/reader active) gets our payload, no app open on our side, screen on.**

---

## §4 — Direction / role conflicts: can ONE idle HCE satisfy all three at once?

**Yes — there is no role conflict, because all three want us to be the HCE/tag, and they use different
AIDs that the OS routes independently.**

- Quick Share sender → reads our `F00000FE2C` HCE. iPhone reader → reads our `D2760000850101` NDEF HCE.
  OShare (NDEF-link bootstrap) → reads our `D2760000850101` NDEF HCE. These are **different AIDs**; a given
  tap SELECTs exactly one; the OS routes each independently. They can all be registered and live
  simultaneously while we are idle/background. **No mutual exclusion between the two AIDs** — mutual
  exclusion is only HCE-vs-reader-mode on the same controller (§3), not HCE-vs-HCE across AIDs.
- **The genuine conflict is the GMS AID collision** (§2): on a phone that *also* runs Google Quick Share
  (GMS), `F00000FE2C` is registered twice → Android routes to the default/asks the user, and may pick GMS.
  - **Mitigations:** (a) target non-GMS phones (Oppo/Vivo) where we own it cleanly — **the primary
    intended deployment**; (b) when our receive UI is foreground, call `setPreferredService` to win it
    (foreground only); (c) accept that on a GMS phone in pure-background, the Quick Share-AID tap may go to
    GMS — the iPhone/OShare NDEF AID still works. There is **no API to force-win a background AID conflict**
    against the system default. **VERIFIED limitation.**
- **A subtle correctness point:** when *we* are an HCE on `F00000FE2C`, a **real Google Quick Share
  sender** tapping us would parse our `hhwv` and try to connect to us over Wi-Fi-LAN. That requires our
  emitted `deym`/`rxAdv` to be byte-correct AND our LAN receiver live — protocol-mapped (VERIFIED bytes)
  but **NOT device-tested**, and the "LAN-only without BT MAC actually completes on the sender" question
  is still open (§1.1). So claiming "tap a Quick Share phone and it just works" is **inferred, not
  proven.**

---

## §5 — Recommended architecture for the super app's NFC

### AID set to register
Register **both** AIDs, each in its **own** `<aid-group android:category="other">`,
`requireDeviceUnlock="false"`:
- `D2760000850101` — NDEF Type-4 Tag App AID → serves an **https universal link** (covers **iPhone** and
  **OShare-NDEF** in one). The link should be a universal link you control that deep-links Super Drop if
  installed, else lands on the right store page (mirrors `connect.oppo.com/oshare/clips`). https scheme is
  mandatory for iPhone background read (§1.3).
- `F00000FE2C` — Quick Share Nearby NFC advertising AID → serves the `hhwv` (deym + Wi-Fi-LAN rxAdv) for
  an Android **Quick Share** sender.

### One service or two?
Either works (§2). **Recommendation: keep them as two services** (current fork state:
`SuperDropNdefApduService` + `SuperDropTapHceService`) **or** merge into one service that branches on the
SELECT-by-name AID. Two services is cleaner for independent enable/disable (e.g. let the user turn off the
iPhone-link tag without touching Quick Share interop) and avoids one protocol's bug blanking the other.
**Do NOT put both AIDs in the same `<aid-group>`** — the all-or-nothing routing guarantee would couple
them (§2).

### Branch logic (in `processCommandApdu`)
1. First APDU = `00 A4 04 00 <Lc> <AID>`. Read the AID.
2. `D2760000850101` → Type-4 NDEF state machine → serve link. (iPhone + OShare.)
3. `F00000FE2C` → SELECT→`9000`; on `80 01 …` build `hhwv` from the live receiver (deym header `0x23`,
   serviceIdHash `FC9F5E`, endpointInfo, 6×00 MAC, flags; rxAdv = EncryptionKey DE + Wi-Fi-LAN DE with our
   IP:port). Gate emission on a live receiver (the fork already gates via `NfcTapLinkHolder` /
   `NfcLinkHolder`).

### Background availability (what to promise)
- **Background, no app open: YES** for the tag/HCE side of all three, **screen on** (lock screen OK with
  `requireDeviceUnlock=false`; screen-off not guaranteed due to Secure NFC, §3).
- **Foreground only:** us acting as the Quick Share **sender** (reader-mode) to tap another phone and
  initiate — bind reader-mode to the send activity, switch HCE↔reader-mode around it (§3).

### What is BLOCKED vs FEASIBLE

| Capability | Status | Why |
|---|---|---|
| Serve iPhone an https link on tap (background) | **FEASIBLE** (verified protocol; not device-tested) | NDEF T4T HCE, iPhone XS+ reads it, user taps banner |
| Serve OShare's NDEF bootstrap link on tap | **FEASIBLE** (same as iPhone) | identical NDEF AID + URI |
| Full OShare-native 2-way file transfer via the tap | **NOT MAPPED here** | OShare's real transport is BLE/GATT/Wi-Fi, not NFC (§1.2) |
| Serve a Quick Share Android sender our `hhwv` (background) | **FEASIBLE on a non-GMS phone** (verified bytes; not device-tested) | own `F00000FE2C` cleanly; LAN-handoff verified, completion unproven |
| Same, on a phone that ALSO has Google Quick Share (GMS) | **BLOCKED in background / partial** | AID collision → OS routes to GMS or prompts; foreground `setPreferredService` can win, background cannot (§2/§4) |
| We tap THEM and initiate (we = reader/sender) | **FEASIBLE but FOREGROUND-ONLY** | reader-mode is an activity API (§3) |
| Background screen-OFF HCE | **NOT GUARANTEED** | Secure NFC disables card emulation with screen off (§3) |
| Make an iPhone do more than open a tapped link | **BLOCKED** | iOS background NFC = NDEF link + user tap only (§1.3) |
| Custom URL scheme to iPhone | **BLOCKED** | iOS background read = http/https/mailto/sms/tel/facetime/X-HM only (§1.3) |

### Device-test gaps (NOTHING below is end-to-end verified — no NFC hardware tested this session)
1. Stock Quick Share **sender** actually completing a connect to our HCE over **Wi-Fi-LAN with no BT MAC**
   (upper-layer medium policy past `onEndpointFound` not traced; §1.1).
2. Whether a stock QS sender **accepts our random 32-byte EncryptionKey** (parser only checks size/type).
3. Real **iPhone XS+** showing the banner and following our universal link to the store/app.
4. Real **OnePlus** treating our NDEF tag the same as a native OShare tap (and re-verifying the firmware
   `HostEmulationManager.java:72/611` cites, which were **not** re-read this session — §1.2).
5. **AID-collision behavior on a real GMS phone** (does it silently route to GMS, or prompt?).
6. **Secure NFC** screen-off behavior on the actual target devices.

---

## Appendix — claims I could NOT fully verify this session (flagged honestly)
- OnePlus firmware `HostEmulationManager.java:72` (AID routing) and `:611` (payload hex check): carried
  from `oshare-nfc-tap` source comments; **not re-opened this session.**
- Apple's exact iOS version for XS background reading (12 vs 13): launch coverage says it shipped with the
  XS (2018, iOS 12) and iOS 13 did **not** change background reading; I did not load the primary Apple doc
  body (the developer page is JS-rendered and returned only a title). Schemes/conditions are from GoToTags,
  which is a reputable secondary source, **not** apple.com.
- GMS `F00000FE2C` aid-group `category`/`requireDeviceUnlock` attributes: the binary AXML manifest stores
  them as resource refs (not inline-readable); the `category="other"` value is from a prior memory map of
  res `7uS.xml`, not re-decoded this session. (The string `F00000FE2C` itself **was** confirmed present in
  `allstrings.txt` this session.)
