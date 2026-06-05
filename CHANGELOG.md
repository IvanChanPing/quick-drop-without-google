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
- **Phase 2 — RECEIVE consent surface redesigned into an OShare-style bottom sheet, opened
  by the Quick Settings tile with a temporary visibility bump.**
  - Ported `RoundedProgressBar` (shareit-bridge Java → Kotlin) to
    `dev.superdrop.ui.sheet.RoundedProgressBar`: pill track + animated blue fill
    (`ValueAnimator`, 180 ms), `setProgress(percent)`, ≥1-dot minimum so 0% reads as
    "started". `AttributeSet` ctor so it can be inflated from XML.
  - New translucent theme `Theme.SuperDrop.ReceiveSheet` (parent `Theme.Bada`,
    mirrors `SendSheet`): `windowIsTranslucent`, transparent `windowBackground`,
    `windowContentOverlay=@null`, `windowNoTitle`, `backgroundDimEnabled` +
    `backgroundDimAmount=0.2`, reuses `WindowAnimation.SuperDrop.SendSheet` slide-up/down.
    Applied to `.consent.ConsentTrampolineActivity` in the manifest, replacing the centered
    `Theme.Bada.ConsentDialog`.
  - `activity_consent_trampoline.xml` rebuilt: root is a transparent `FrameLayout`
    (`consent_sheet_root`) + tap scrim (`consent_sheet_scrim` → dismiss) hosting a
    bottom-anchored `DraggableSheetLayout` (`consent_sheet`, rounded `send_sheet_background`
    surface) wrapping the fixed-480dp inner content frame (`consent_root`, the transition
    scene root). Every consent panel id is preserved verbatim (consent/receiving/completed/
    failed) so the activity's `findViewById` state machine, `submitUserConsent` broadcast
    path, `InboundConnection.state` observation, `onNewIntent`, `setShowWhenLocked`/
    `setTurnScreenOn`, and `ConsentModalRegistry` dismiss hook are untouched. Receiving
    progress switched from `CircularProgressIndicator` → `RoundedProgressBar`.
  - `ConsentTrampolineActivity`: added `wireBottomSheet()` (scrim tap → finish, drag-down
    dismiss, bottom inset, overshoot entrance); dropped the old popup fade transitions (the
    theme's window slide handles enter/exit now). Added a **WAITING** mode — when launched
    by the tile with `ConsentIntents.ACTION_OPEN_RECEIVE_SHEET` and no pending consent entry,
    it shows a "Ready to receive / waiting for sender" panel (`consent_waiting_panel`,
    new strings `consent_state_waiting_title` / `_body`) instead of finishing. `renderEntry`
    swaps waiting → consent in place. `renderProgress` updated for `RoundedProgressBar`.
  - Foreground incoming-consent routing into the open waiting sheet needed NO coordinator
    change: the open sheet is itself a foregrounded Bada activity, so
    `AppForegroundState.isForeground` is true and the existing
    `ConsentCoordinator.raiseConsentSurface` → `Sink.launchModal` →
    `launchConsentTrampolineAsModal` path re-launches the `singleTop` activity with the new
    connection id, delivered via `onNewIntent`.
  - **QS tile (`BadaQuickShareTileService`) replaced the persistent on/off toggle** with a
    momentary capture→bump→open→restore flow: (a) capture `MdnsVisibilityOverrideHolder.isActive`;
    (b) if below visible, set override on + `ReceiverForegroundService.start` (recorded in the
    new `TileVisibilityElevationHolder`); (c) `startActivityAndCollapse` into the receive sheet
    in waiting mode (`ACTION_OPEN_RECEIVE_SHEET` + `EXTRA_TILE_ELEVATED`); (d) the activity's
    `finish()` calls `TileVisibilityElevationHolder.restoreIfArmed` to set the override back and
    stop the service it started. If visibility was ALREADY active, nothing is bumped or restored.
    FGS-start / activity-launch failures roll the bump back. Permission gate unchanged.
  - New `dev.superdrop.service.receiver.TileVisibilityElevationHolder` (in-memory one-shot
    record of the prior visibility/service state; `arm`/`restoreIfArmed`/`disarm`). Cleared on
    `ReceiverForegroundService.stopReceiverAndExit` so a stale armed flag can't later stop a
    service the user controls by other means. Best-effort across process death (in-memory only,
    matching the override holder).
  - New intent constants `ConsentIntents.ACTION_OPEN_RECEIVE_SHEET` / `EXTRA_TILE_ELEVATED`.
  - Protocol/transport, `ConsentNotification.kt` (incoming heads-up), `:core-protocol`, wire
    constants, and discovery networking all untouched.
  - Verified: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 17); `:service-android` consent unit
    tests pass. UI is compile-only — NOT click-tested on a device.
- **Phase 3 — RECEIVE completion notification with an "Open" action (the missing third
  notification in the consent / progress / completion trio).**
  - New `dev.superdrop.service.receiver.progress.TransferCompleteNotification` (object),
    mirroring `TransferProgressNotification` / `ConsentNotification`: own channel
    `transfer_complete` (IMPORTANCE_HIGH, sound + vibration — the user wants a heads-up on
    completion, unlike the quiet progress channel); disjoint stable notification-id base
    `0x436F_5663` ("CoVc"); `setAutoCancel(true)` + `CATEGORY_STATUS`. Title
    "Received N file(s) from <sender>" (sender name reused from the same consent/progress
    metadata). `ensureChannel` / `post` / `dismiss` / `build` surface.
  - **Open action + body tap**: for a single received FILE whose `content://` Uri resolves
    from `MediaStore.Downloads` by display name → `ACTION_VIEW` on that Uri with
    `FLAG_GRANT_READ_URI_PERMISSION` (mirrors `ConsentTrampolineActivity.findReceivedImageUri`
    but against the Downloads collection, where received files land via
    `MediaStoreDownloadsEnvironment`); for multiple files, an unresolvable single Uri
    (collision-suffixed commit name, un-indexed row, user-chosen SAF tree), or pre-API-29 →
    falls back to the system Downloads view (`DownloadManager.ACTION_VIEW_DOWNLOADS`). Uri
    resolution is best-effort (a query miss / `SecurityException` → Downloads view), so the
    action is always tappable.
  - Wired into the existing terminal path: `TransferProgressCoordinator.Sink` gained a
    `postComplete(connectionId, sourceDeviceName, items)` method, called from the `Completed`
    branch of `observeOneConnection` (which already dismisses the progress card on the same
    transition — so the completion heads-up effectively *replaces* the progress card; the two
    use disjoint ids). Production `Sink` in `ReceiverForegroundService.startProgressCoordinator`
    routes it to `TransferCompleteNotification.post`; the `transfer_complete` channel is
    ensured in `onCreate` alongside the other channels. Not posted on Failed/Cancelled/Rejected.
  - Foreground case unchanged: `ConsentTrampolineActivity` still shows the inline completion +
    View-image panel; the notification posts in addition (acceptable per the design).
  - `ConsentNotification.kt`, `:core-protocol`, the protocol/wire code, and the MediaStore
    factory were NOT changed (the factory's committed-row Uri is re-resolved by display-name
    query rather than threading a Uri back through `ReceivedItem`).
  - New strings in `:service-android` `strings.xml`: `transfer_complete_channel_name` /
    `_description`, `transfer_complete_title_with_name` / `_unknown_sender`,
    `transfer_complete_action_open`.
  - Tests: `RecordingSink` extended with `postComplete`; two new
    `TransferProgressCoordinatorTest` cases (posts completion with sender name on `Completed`;
    does NOT post on `Failed`). All `TransferProgressCoordinatorTest` cases pass.
  - Verified: `:app:assembleDebug` BUILD SUCCESSFUL (JDK 17); `:service-android`
    `compileDebugUnitTestKotlin` + `TransferProgressCoordinatorTest` pass. Compile-only — the
    notification + Open-action click path is NOT device click-tested.

### 2026-06-05 — All 4 UI phases complete + on-device UI check
- Phase 1 send bottom sheet (cad8d21), Phase 2 receive sheet + tile (ee2fc13), Phase 4 NFC link
  broadcast (merged d3f1bea), Phase 3 completion notification (14cfd3b). Final assembleDebug green.
- On-device (redroid A16 x86_64, :5573) UI-verified by screenshot: app renders as "Super Drop"; SEND
  opens as a bottom sheet over the dimmed home with the picker + QR button; in-sheet QR shows the live
  pairing link; RECEIVE waiting sheet ("Ready to receive") renders via the tile action.
- UNVERIFIED (need 2 physical phones / NFC hardware): populated peer icons, real consent/PIN/progress/
  completion+Open flow, completion notification, tile visibility bump/restore, NFC tap→Safari, and any
  real Quick Share interop transfer (redroid lacks real Wi-Fi/BLE).
