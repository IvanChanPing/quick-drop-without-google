# Super Drop — reported problems (from real-device testing, 2026-06-06)

Source: user testing on a OnePlus (non-GMS) + a GMS phone. Most NOT reproducible on the
redroid emulators here (no real Wi-Fi/BLE/NFC; OEM behavior differs) — fixes are code-based,
final verification needs the user's phones.

## Open

1. **[QUESTION] Heads-up notification button placement.** Can Accept / Decline go on
   OPPOSITE sides, or must they be next to each other? → Standard `NotificationCompat`
   actions render in a row at the bottom, next to each other (max 3, in add order). You
   CANNOT put them on opposite sides in a standard notification — that needs a custom
   RemoteViews content view (`setCustomContentView`/`setCustomHeadsUpContentView`), which
   gives full placement + colors but some OEMs restyle custom layouts. DECISION NEEDED:
   keep standard (next-to-each-other) or build a custom RemoteViews layout.

2. **[BUG] Battery exemption no-op on OnePlus.** The one-tap ALLOW popup did NOT actually
   exempt the app on the user's OnePlus (ColorOS) — only worked after going into App Info.
   Same OEM silent-no-op class already documented for vivo. Current fallback only fires if
   the popup can't LAUNCH, not when ALLOW is a no-op. FIX: after the attempt, on resume
   re-check `PowerManager.isIgnoringBatteryOptimizations`; if still not exempt, escalate to
   App Info (`ACTION_APPLICATION_DETAILS_SETTINGS`) / OEM battery activity.

3. **[BUG] Device shows an IP address instead of its name.** A device is displayed as an
   IP/address tuple instead of the Quick Share device name peers see. From
   `NearbyPeer.displayName()` the name = `endpointInfo?.deviceName`, falling back to a LAN
   address when endpointInfo isn't parsed. INVESTIGATE: why `endpointInfo.deviceName` is
   null/empty in the user's case (mDNS `n` base64 TXT parse, or peer not advertising name).

4. **[BUG] Tile opens INSIDE the app, not its own floating activity.** Tapping the receive
   tile takes the user into the app + opens the receive sheet, instead of the sheet being a
   standalone foreground activity floating over the current screen. CAUSE (from code):
   `ConsentTrampolineActivity` uses the app's default taskAffinity, so launching it with
   FLAG_ACTIVITY_NEW_TASK lands in the Super Drop task and brings MainActivity forward.
   FIX: `android:taskAffinity=""` (its own task) + NEW_TASK so the translucent sheet floats
   over whatever's there.

5. **[BUG] Tile visibility not restored.** Tapping the tile a second time / pressing back /
   leaving did NOT set visibility back to off — the receiver stays visible / tile stays on.
   The restore (`TileVisibilityElevationHolder.restoreIfArmed` from `ConsentTrampolineActivity.finish()`)
   isn't running or isn't restoring. Likely coupled to #4 (wrong task/instance, or finish()
   not firing on back/dismiss). INVESTIGATE finish()/onDestroy path + restore logic.

## Notes
- #4 and #5 are both tile/visibility and likely share the task-handling root cause.
- Fix verification: code + redroid where possible (tile launch task, name fallback); the
  OnePlus battery no-op and real peer-name interop need the user's phones.

## Fixed (code) 2026-06-06 — verification notes per item
- #2 battery: one-tap attempt now re-checks on resume and escalates to App Info (ACTION_APPLICATION_DETAILS_SETTINGS) when the OEM popup silently no-ops (OnePlus/vivo). [device-unverified: redroid popup works, cannot repro the OnePlus no-op here]
- #3 name: displayName() no longer falls to the raw stableId/IP; hidden peers (stock QS omits the name) show "Quick Share device". Our own adv is hidden=false (real name). [device-unverified: needs a real peer]
- #4 tile: ConsentTrampolineActivity now android:taskAffinity="" so it floats in its own task over the current screen instead of pulling the Super Drop app forward.
- #5 tile restore: restoreIfArmed now also runs from onDestroy (backstop) and onUserLeaveHint finishes the tile-opened waiting sheet on Home/Recents (gated on armed && !decisionSubmitted), so leaving restores the temporary visibility bump.
- #1 notification buttons: still standard side-by-side (DECISION PENDING: custom RemoteViews for opposite-side/custom-color).

## #1 notification — DONE (custom RemoteViews) 2026-06-06
- Consent heads-up now uses a custom RemoteViews layout (notification_consent.xml) via DecoratedCustomViewStyle: Decline pinned far-LEFT (light pill, red label), Accept far-RIGHT (filled blue), recolored, opposite sides. addAction row removed. Buttons wired to the same accept/reject broadcast PendingIntents. ⚠️ device-UNVERIFIED render: the heads-up only appears on a real incoming transfer (needs a peer); compile-only here.
