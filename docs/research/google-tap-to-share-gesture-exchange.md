# Google "Tap to Share" / "Gesture Exchange" — research & groundwork

**Status: RESEARCH / GROUNDWORK ONLY. Nothing here is wired into the app. This is a written
head‑start for if/when Google officially ships the feature.** It is *not* interop with Google, and
it does not "auto‑activate" when Google releases — see "Common misconception" below.

Maps to **SUPERDROP‑CHANGES.txt groundwork item #15.**

Last updated 2026‑06‑11.

---

## What this is about

Google is building an AirDrop / NameDrop–style **"Tap to Share"** into Android: tap two phones
together to share files/contacts. Multiple APK teardowns (Android Authority, Gadget Hacks, Sept 2025
→ Apr 2026) confirm it spans three codebases — Google Play Services, Samsung One UI 9, and Android 17 —
and as of those reports it is **unreleased** (Canary/beta behind flags, no announced date).

Inside Google Play Services the mechanism is the package **`com.google.android.gms.gestureexchange`**
(internal/UI name "Gesture Exchange"; OS‑level service "TapToShare"). It was first scoped to contacts
("Gesture Exchange", analogous to NameDrop) and later extended to files.

We investigated whether this could be brought into Super Drop. This doc records what was verified, what
is reusable, and what is blocked.

## How it works (verified from decompiled GMS)

Verified by reading GMS smali on disk (versions 26.18.33 and a freshly pulled 26.22.32 DEV build):

1. **The NFC tap is a connection *bootstrap*, not the data carrier.** It rides the **standard Nearby
   Connections** stack — the same transport Super Drop already mimics for Quick Share. Proof: the
   string *"BandwidthUpgradeManager ignored bandwidth upgrade … because it is a Gesture Exchange
   connection on a HIGH quality medium"* (`detm.smali`), and an `NfcEndpointChannel` that bootstraps
   then upgrades to Wi‑Fi LAN / Wi‑Fi Aware for the real payload.
2. **NFC side = plain framework HCE.** Receiver runs a `HostNdefApduService` selected via
   `CardEmulation.setPreferredService`; sender uses reader‑mode. Gated only by the public
   `BIND_NFC_SERVICE` — the same APIs Super Drop's existing tap code already uses.
3. **No first‑party wall.** No DroidGuard, no Play Integrity / SafetyNet, no Google‑account / OAuth, no
   signature check on the handshake. The data‑model classes carry no credential fields:
   `InitiatorRequest{gestures, applicationLink, WorkSource}`, `ResponderRequest{gestures}`,
   `GestureMessageParcel{int type, byte[] payload}`. (The `PERFORM_GESTURE_EXCHANGE` /
   `ACCESS_GESTUREEXCHANGE` permissions only gate *binding GMS's own* service; they are irrelevant to
   a clean‑room reimplementation that never touches GMS.)
4. **Contacts vs files is an activity‑level split** (`CONTACTS_EXCHANGE` vs `INTENT_FULFILLMENT`
   Chimera activities); file transfer hands back to the normal Nearby Sharing send surface.

Architecturally this is the same shape Super Drop already implements for tap‑to‑share (groundwork #3):
NFC handoff → Nearby/Wi‑Fi transfer.

## What is blocked (the make‑or‑break gap)

The byte‑level spec we would need to **interoperate with real Google devices** is **not obtainable
right now**:

- The gesture HCE *implementation* — the exact **AID**, the **NDEF handover record** the tap carries,
  and the **ConnectivityInfo serialization** inside `GestureMessage.Payload` — lives in a separately
  delivered **GMS Chimera module**, not in the base APK.
- Verified across three builds (26.18.33 on disk, the full base in our research assets, and a
  freshly‑pulled **26.22.32 DEV** build): the base APK contains the gesture **API / registrar /
  manifest surface** (and it is *growing* version‑over‑version — 26.22.32 added the
  `ACCESS_GESTUREEXCHANGE` and `INJECT_GESTURE_EVENT` permissions, the `START_GESTURE_INITIATOR`
  intent action, and `RemoteGesturesService` / `GestureMessageListenerService`), **but not** the HCE
  byte logic or any gesture AID. Control test: the *shipped* Nearby Sharing AID (`F00000FE2C`) and its
  HCE implementation **are** present in the base — confirming that when a feature ships, its AID + impl
  ride in the base. The gesture module is genuinely not shipped to the base at any available version
  yet.
- A Play / APKPure base APK — at any version, including DEV builds — therefore cannot yield the spec.
  Obtaining it requires the on‑demand Chimera module from a **device that has the feature flag
  provisioned** (it would land as a `dl-*.apk` under GMS's `app_chimera/m/`), or a GMS‑module‑level
  mirror.

## Common misconception (why this is groundwork, not a live feature)

Shipping stubs that *mirror* Google's `gestureexchange` package (its service names, permissions, intent
actions) into Super Drop would be **inert**: those components are GMS/system‑owned, a sideloaded app
cannot host Google's `HostNdefApduService`, and **there is no mechanism by which Google's release wires
up a third‑party app's matching stubs**. Such stubs would do nothing and would never "turn on." We
deliberately did **not** add them.

> One genuinely open *future* seam, **unverified**: Gesture Exchange has an `INTENT_FULFILLMENT` path
> with an `applicationLink` deep link and a `START_GESTURE_INITIATOR` intent action. It is *possible*
> that, once shipped, third‑party apps could be invoked through an intent / share surface. Whether that
> is open to third parties is unknowable until release. Worth watching; not something to build against
> now.

## Recreate inventory — what it would take to add our OWN tap‑to‑share

For a **Super Drop ↔ Super Drop** equivalent (our own protocol, same UX — *not* talking to Google),
the work is roughly **⅔ reuse, ⅓ new**:

- **Reuse (already in this repo):** HCE (`app/.../nfc/SuperDropTapHceService.kt`,
  `SuperDropNdefApduService.kt`), reader (`SuperDropTapReader.kt`, `NfcPreferredService.kt`,
  `QuickShareNfcCodec.kt`), the Nearby‑style transport (`core-protocol/.../medium/
  NearbyMultiplexClientTransport.kt`, `NearbyBleSocketFrames`, `NearbyMultiplexFrames`,
  `endpoint/{EndpointInfo,BleAdvertisementHeader,BleServiceData,TlvRecord}`), BLE adv/scan + mDNS
  (`discovery-android`, `QuickShareMdns`), cold‑tap wake
  (`service-android/.../receiver/NfcColdReceiverPrimer.kt`), radio auto‑enable (`radio-helper`).
- **New (our design, nothing blocking):** (1) a small handover record carrying our own
  `ConnectivityInfo` (IP/port/endpointId) in the NDEF/APDU payload — today Super Drop's tap tag is
  near‑empty / wake‑only; (2) wiring the tap to kick the existing Wi‑Fi‑LAN transfer (today the tap
  only *wakes* the receiver, it does not hand off a connection blob); (3) the overlap UX
  ("keep both phones together until it glows"). Reference for the blob fields = Google's Nearby
  `*ConnectivityInfo` classes.

**Important labeling rule:** a Super Drop ↔ Super Drop build must **not** be described as "works with
Google Tap to Share." It is our own feature until Google's byte spec is obtained *and* a physical
two‑phone tap is tested.

## Revisit trigger

Pick this back up when **both** are true: (a) Google ships Tap to Share to a stable channel, and
(b) we have an NFC test device with the feature enabled (to pull the Chimera module, map the bytes, and
run a real tap test). Until then this stays groundwork.

## Provenance

Findings cross‑checked in the agent memory note `reference_gms_gestureexchange_taptoshare_2026_06_11`.
Teardown sources: androidauthority.com (`android-tap-to-share-ui-apk-teardown-3656467`,
`android-tap-to-share-quick-share-3652981`), gadgethacks.com
(`android-tap-to-share-feature-what-the-code-signals-for-quick-share`). GMS builds examined: 26.18.33,
26.22.32 DEV. Related in‑repo: groundwork item #3 (existing tap‑to‑share), `docs/NFC_INTEROP_BYTEMAP.md`.
