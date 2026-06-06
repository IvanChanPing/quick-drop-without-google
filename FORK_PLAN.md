# Super Drop — Fork Plan

Fork of [Bada](https://github.com/kyujin-cho/Bada) (clean-room Google Quick Share /
Nearby Share for Android, upstream package `dev.bluehouse.bada`). Tracked as the
`upstream` remote so we can pull protocol fixes.

## Hard rule for this fork
**The protocol/transport stack (`:core-protocol`, `:discovery-android`,
`:service-android` networking) is NOT to be changed.** Interop with stock Quick
Share depends on byte-exact framing and the wire identifiers below. We only change
**app identity (package/name)** and the **UI entry points** (how the user invokes
send/receive). If a UI change forces a protocol-layer touch, stop and flag it.

### Wire identifiers that must NEVER be renamed (interop-critical)
- mDNS service type `_FC9F5ED42C8A._tcp`
- BLE service UUIDs `0xFE2C` (sender pulse), `0xFEF3` (receiver fast-advert), `0xFC73`
- All `.proto` message shapes and the Samsung quirk constants
  (`multiplex_socket_bitmask=0`, `safe_to_disconnect_version=1`,
  `keep_alive_timeout_millis=600000`, the `LAST_CHUNK` terminator, `FileMetadata.id`)

## 1. App identity
- Display name: **Super Drop** (confirm spelling)
- applicationId / namespace: `dev.peskoff.superdrop` (debug suffix `.debug`)
- Rename touch points to be enumerated by the source-map pass before any edit
  (build.gradle.kts applicationId+namespace per module, kotlin package dirs,
  manifest refs, proguard, FileProvider authority, logcat tags, log file path,
  string app_label). Wire identifiers above are EXCLUDED from the rename.

## 2. UI redesign — goal: never drag the user into the full app

### Design reference = our shareit-bridge "OShare" sheets
Mirror the look/feel of `/root/agent-work/projects/shareit-bridge/src/main/java/com/bridge/share/ui/`:
`DraggableSheetLayout` (slide-up OvershootInterpolator entrance, drag-down dismiss, animateGrow,
nav-inset padding), `SendSheetActivity` (transparent activity, light scrim 0x33000000, bottom rounded
card #F4F4F7 r28dp, title + ✕, spinner "Scanning…" row, HorizontalScrollView of circular peer icons
that bounce + show a ring on tap), `ReceiveBottomSheetActivity`/`ReceiveCard` (scrim 0x88000000, 120dp
thumbnail + green check on complete, Decline/Accept → Receiving… → Close/Open). Rebuild in Bada's
Kotlin + Views/ViewBinding world (NOT Compose); keep Bada's protocol controllers.

### Send (from the system share sheet)
- Today: "Send via Quick Share" launches a full-screen SendActivity.
- Target: the share-sheet target opens an **OShare-style bottom sheet** (translucent activity, bottom
  card, slide-up) — circular peer icons, PIN compare, progress all inside the sheet. No navigation into
  the full app. Dismiss returns to wherever the user shared from.
- **QR in the sheet:** port Bada's existing QR handshake (ShowQrActivity / QrBitmapRenderer / QrUrl /
  QrTlvMatcher) into the sheet as an inline "Show QR" panel (SendActivity already has the
  sendShowQrButton + onQrPeersResolved auto-connect hooks — reuse that logic, new presentation).
- **NFC — two separate things:**
  - *Link broadcast gating (DONE/verified 2026-06-05):* `NfcLinkHolder.currentUrl` is set ONLY in
    SendActivity.onShowQrClicked (the QR button) and cleared on panel-dismiss/auto-connect/onDestroy;
    HCE serves empty NDEF when null → iPhone tap broadcasts the link ONLY while the QR/link panel is open.
    (Optional further tightening — fully disable the HCE service component except while the panel is open —
    NOT done; offered to user.)
  - *Tap-to-share (NEW, user wants it; CORRECTED 2026-06-06):* the real Google Quick Share NFC tap-to-share.
    Mechanism mapped = ISO-DEP/APDU, AID F00000FE2C, HCE(receiver/advertiser) + reader-mode(sender). CORRECTION:
    it DOES work stock-to-stock when both are in the QS app (NFC is in the advertising/connection/discovery
    medium sets; only the *instant* set excludes it) — user confirmed by tapping 2 phones. So STOCK INTEROP is
    plausibly feasible on the non-GMS phone (it can own F00000FE2C; no GMS to collide). Full mechanism +
    corrected verdict in memory [[reference_quickshare_nfc_tap_to_share_apdu_2026_06_05]]. A 2nd RE pass is
    pinning the byte-exact hhww/hhwv protos + the POST-TAP connection medium (the crux: does stock connect over
    Wi-Fi-LAN/mDNS which we host, or BT/Wi-Fi-Direct) before implementing. Do NOT build until that map is done.
    - **Receiver-side NFC availability (user, 2026-06-06): make it configurable — always on / only when a
      receive (share) sheet is open / always in background.** Fold into the receiver visibility model: when the
      receiver HCE tag-advertising is active mirrors the chosen mode (tie to the existing Off/10-min/Always
      visibility setting + a background option). Sender-side NFC reader-mode runs while the send sheet is open
      (and switches to the iPhone-link NDEF HCE only while the QR panel is open — they're mutually exclusive).
  - *NFC link broadcast* (WANTED): an HCE NDEF Type-4 tag that broadcasts the live QR/pairing link
    (Bada's `QrUrl`), modeled on our `oshare-nfc-tap` (NdefAppStoreApduService, AID D2760000850101) BUT
    serving the REAL pairing URL instead of the App Store URL — so an iPhone tapped to the phone opens
    that link in Safari (CoreNFC background read). Pairs with the in-sheet QR + link.

### Receive heads-up notification (original Bada) — options for our redesign
`ConsentNotification.kt` = plain NotificationCompat heads-up (channel `incoming_transfer`, IMPORTANCE_HIGH,
CATEGORY_CALL, ongoing, no auto-cancel). Two actions added in order **Accept (ic_menu_send) then Reject
(ic_menu_close_clear_cancel)**; body tap → setContentIntent + setFullScreenIntent → ConsentTrampolineActivity
dialog. NO custom colors (system accent only). Placement/color levers:
- A. Keep standard heads-up → only action ORDER + count; color = `setColor()` accent tint only.
- B. Custom RemoteViews heads-up → full control of button placement + per-button colors (OShare Decline-gray
  / Accept-blue), but OEM heads-up restyling risk (Samsung/vivo).
- C. No heads-up when foreground → the tile-opened bottom sheet shows the rich colored UI directly; heads-up
  only when nothing is foreground. (Natural fit with the tile→sheet plan.)
DECISION: PENDING user.

### LOCKED receive decisions (2026-06-05)
- **Incoming heads-up notification: UNCHANGED.** Keep `ConsentNotification.kt` (Accept/Reject) as-is.
- **NEW receive notification lifecycle (Bada stops after Accept — it has no progress/complete notifs):**
  1. Incoming (background): existing heads-up with Accept/Reject [unchanged].
  2. After Accept: a **persistent progress notification** in the shade showing transfer % [NEW].
  3. On complete: cancel the progress notif, post a **completion heads-up** with an **"Open" action**
     to open the received file [NEW].
  - All of the above is the BACKGROUND path; when the bottom sheet is foreground it shows the same
    progress→complete(+Open) inline. Notifications are "in addition to the bottom sheet if applicable."
  - Distinct from the always-on receiver foreground-service notification (`ReceiverNotification`,
    "Receiving on <SSID>"); this is a per-transfer progress/complete notif.
- **Receive surface = re-skin `ConsentTrampolineActivity` into the OShare bottom sheet** (translucent
  activity, bottom card slide-up, Decline-gray/Accept-blue, PIN, progress, thumbnail+green-check). The
  notification still opens this same activity (so "don't change the notification" holds); it just looks
  like a sheet now.
- **Routing: sheet-if-foreground-else-heads-up** — already how `ConsentCoordinator` swaps surfaces
  (foreground→modal/trampoline, background→notification). Keep that.
- **Tile** opens this same sheet (waiting state) + does the visibility bump/restore below.

### Receive (Quick Settings tile)
- Today: receiving surfaces via the foreground service + notification/consent flow.
- Target: add a **QuickSettings TileService**. Tapping the tile opens a **bottom
  sheet as the foreground activity**; all receive UI (incoming request, PIN compare,
  Accept/Decline, progress) happens in that sheet. No navigation into the full app.
- **Tile ↔ visibility behavior (exact):** tapping the tile captures the receiver's
  current visibility. If it is *below* "visible" (off / contacts-only), the tile
  raises it to **visible only while the sheet is open**. On sheet dismiss, restore
  the exact prior state. If it was already visible, leave it unchanged.

## 3. Open future note (not now)
- Eventually we may import the same send/receive bottom-sheet UI used by the O+
  (OnePlus / O+ Connect) share work, for visual consistency across our share apps.

## Status
- [x] Clean clone from GitHub (HEAD 62d60f3), branch `fork/superdrop-ui`, origin→upstream
- [ ] Full source map (sub-agent, in progress)
- [ ] Package rename
- [ ] Send bottom-sheet redesign
- [ ] Receive QS tile + bottom-sheet redesign
- [ ] Build + on-device verification
