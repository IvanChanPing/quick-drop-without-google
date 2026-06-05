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

### Send (from the system share sheet)
- Today: "Send via Quick Share" launches the app's SendActivity (a full screen).
- Target: the share-sheet target opens a **bottom sheet** as the foreground surface
  — peer list, PIN compare, progress all inside the sheet. No navigation into the
  full app. Dismiss returns the user to wherever they shared from.
- Implementation direction (pending source map): make the share-intent Activity a
  translucent / `Theme.*.Dialog`-style trampoline that hosts a ModalBottomSheet
  (Compose) or BottomSheetDialog, `excludeFromRecents`, transparent background so
  the underlying app shows through.

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
