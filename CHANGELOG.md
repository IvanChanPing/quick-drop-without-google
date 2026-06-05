# Changelog — Super Drop (fork of Bada)

All notable changes to this fork. Upstream Bada history is preserved in git;
entries here cover only fork-specific changes.

## [Unreleased]

### 2026-06-05
- Forked `kyujin-cho/Bada` at upstream HEAD `62d60f3` (release `20260604.02`).
- Renamed git remote `origin` → `upstream`; created working branch `fork/superdrop-ui`.
- Added `FORK_PLAN.md` documenting the two UI redesigns (send = bottom sheet from
  share sheet; receive = Quick Settings tile → bottom-sheet foreground activity),
  the exact tile↔visibility behavior, the planned app identity
  (`dev.peskoff.superdrop`, name "Super Drop"), and the interop-critical wire
  identifiers that must not be renamed.
- Renamed package `dev.bluehouse.bada` → `dev.superdrop` (all 5 modules: source dirs,
  package/import, 3 Gradle namespaces + applicationId + `.debug` suffix, 3 custom-View
  FQCNs in XML, Robolectric shadow ref), `rootProject.name`→`SuperDrop`, `app_name`→"Super Drop".
  Wire identifiers (`_FC9F5ED42C8A._tcp.`, `NearbySharing`, BLE UUIDs) left untouched.
  Verified: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 17), app-debug.apk produced.
- Protocol/transport stack untouched.
- **Phase 1 — SEND share-sheet redesigned into an OShare-style bottom sheet.**
  - Ported 3 OShare UI primitives from shareit-bridge (Java) to Kotlin under
    `dev.superdrop.ui.sheet`: `DraggableSheetLayout` (slide-up OvershootInterpolator
    entrance, drag-down-to-dismiss, `animateGrow`, `applyBottomInset`),
    `RingProgressView` (blue disc + glyph + progress arc + green-check complete),
    `DeviceIconView` (circular icon + name + status + tap bounce). Programmatic
    custom Views, no XML.
  - New translucent theme `Theme.SuperDrop.SendSheet` (parent `Theme.Bada`):
    `windowIsTranslucent`, transparent `windowBackground`, `windowContentOverlay=@null`,
    `windowNoTitle`, `backgroundDimEnabled` + `backgroundDimAmount=0.2`, slide-up/down
    window animation (`WindowAnimation.SuperDrop.SendSheet` → new `@anim/slide_up_in`
    / `@anim/slide_down_out`). Applied to `.send.SendActivity` in the manifest.
  - `activity_send.xml` + `layout-land/activity_send.xml` restructured: root is now a
    transparent `FrameLayout` scrim (`send_sheet_scrim` → tap dismisses) hosting a
    bottom-anchored `DraggableSheetLayout` (`send_sheet`, rounded 28dp `send_sheet_background`
    surface). Old toolbar removed; the device-name pill (`send_device_pill` /
    `send_device_pill_text`) relocated into the sheet header. Every binding id
    preserved (QR panel, PIN, status panel, progress, cancel/done, show-qr).
  - Peers now render as a HORIZONTAL row of circular `DeviceIconView` icons inside a
    `HorizontalScrollView` (was vertical `item_peer_row` rows), deduped by display name;
    tap routes through the unchanged `onPeerSelected` path. Discovery /
    OutboundConnection / QR logic untouched — view layer + presentation only.
  - `SendActivity` gained `wireBottomSheet()` (scrim tap → finish, sheet dismiss →
    finish, bottom-inset, entrance) and a `DraggableSheetLayout` import; new dimen
    `send_sheet_base_bottom_padding`.
  - Verified: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 17). UI not yet exercised on a
    device — compile-verified only.
