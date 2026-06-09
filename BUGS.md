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

6. **[BUG] NFC tap-to-share — real-device test matrix (2026-06-09).** First on-device test
   of the tap feature (it shipped compile-only). Observed:
   - Android 14, RECEIVE from native Quick Share via tap: WORKED — but it was "fighting"
     with native Quick Share over the tap; the user had to keep selecting our app each tap.
   - Android 14, SEND (to native Quick Share OR to another Super Drop) via tap: did NOT work.
   - Android 15, either direction: did NOT work at all.

   Grounded analysis (from code + the verified Quick Share NFC notes; the exact failure point
   on each path needs an on-device logcat capture — the code already logs it, see below):
   - "Had to keep selecting our app" = AID **F00000FE2C collision**. Our HCE
     (`SuperDropTapHceService`, category="other") and GMS Quick Share both register that AID,
     so Android shows the app-disambiguation chooser on every tap. This is a known/documented
     collision; there is no clean "win the routing" for a category-other AID shared with GMS.
   - SEND fails because our reader (`SuperDropTapReader`) only yields a peer if the tapped HCE
     answers SELECT+ADVERTISEMENT with a hhwv carrying a NfcTag AND a Wi-Fi-LAN rxAdv:
       * to another Super Drop: phone B must be in RECEIVE with `NfcTapLinkHolder` populated
         (receiver live + visible + the tap-share setting permitting). If B wasn't in that
         state — or both phones were on the SEND sheet (both in reader-mode, so neither
         presents an HCE — the "two senders can't tap" hardware limit) — the reader gets no
         tag. Likely logcat: "hhwv carried no NfcTag (peer not a live receiver)".
       * to native Quick Share: a native phone just sitting there isn't armed as a receiver,
         and its HCE is gated; whether GMS returns a usable Wi-Fi-LAN tag to a third-party
         reader is unconfirmed. Likely logcat: "ADVERTISEMENT not OK / empty" or "no NfcTag".
   - Android 15 nothing worked (incl. receive): UNKNOWN — must capture logcat. Candidates to
     CHECK (not yet confirmed): A15 HCE/observe-mode or AID-routing changes; cold-wake
     foreground-service-from-HCE restrictions; reader-mode behavior; tap-share setting default.

   HOW TO LOCALIZE (on-device, the instrument already exists): `adb logcat -s SuperDropTapReader
   SuperDropTapHceService BadaNfcWake BadaNfcColdPrime` while tapping. The reader prints exactly
   where it stops (SELECT / ADVERTISEMENT / no NfcTag / resolved); the HCE prints whether it was
   asked and whether the link holder was live. Do NOT change code before these logs localize it.

   UPDATE (user clarifications, same day):
   - The OTHER Super Drop phone WAS in its default receive state (not in send). So "two senders
     can't tap" is NOT the cause for the Super Drop→Super Drop case.
   - Native Quick Share was OPEN in receive mode and STILL nothing — so "native not armed as a
     receiver" is also NOT the cause.

   Refined analysis (code-grounded; still needs logcat to confirm):
   - Default NFC tap-share mode is SHEET_OPEN (verified in `NfcTapSharePreferences.mode()`), so an
     idle Super Drop does NOT publish a live `NfcTapLinkHolder`. BUT `SuperDropTapHceService.
     handleAdvertisement` has a COLD-PRIMER fallback: if the phone has a Wi-Fi-LAN IP at tap time it
     synthesises a real connectable tag on the FIRST tap (`NfcColdReceiverPrimer.prime`). So an idle
     Super Drop on Wi-Fi SHOULD still answer — unless it had no Wi-Fi IP, or the tap never reached
     our HCE at all (next point).
   - LEADING HYPOTHESIS now: the AID F00000FE2C collision bites the **tapped/HCE side of every
     tap**, not just our own receive. When our reader taps a phone that has stock Quick Share
     (native QS phone, OR a Super Drop phone that also has GMS), that phone's NFC may route the
     SELECT to GMS's HCE (or show a chooser its screen-off/locked state can't answer) instead of our
     `SuperDropTapHceService` → our reader never gets a Super Drop tag → send fails. This single
     cause fits ALL the data: A14 receive worked (WE were the HCE and the user could pick our app);
     A14 send to native failed (native routes to its own GMS HCE); A14 send to Super Drop failed if
     that phone also has GMS; A15 stricter routing/observe-mode could even kill the receive chooser.
   - MUST confirm with: (a) sender logcat — does SELECT get 9000 and does ADVERTISEMENT come back
     empty/error?  (b) does the tapped phone have stock Google Quick Share installed?  (c) try the
     receiver's NFC tap-to-share = "Always (background)" + receiver on Wi-Fi, to isolate the primer.
   - If the AID-collision-on-the-tapped-side hypothesis holds, sending via this AID to a
     GMS-equipped phone may be fundamentally unwinnable (you can't out-route GMS for a shared
     category-other AID) — a design constraint to surface, not a quick fix.

   CONFIRMED (user, same day): BOTH phones had stock Quick Share, and even the case that WORKED
   (A14 receive) popped the Android "choose which NFC app" chooser — the user had to pick Super Drop.
   That chooser is Android's HCE AID-disambiguation, which only appears when two apps register the
   SAME AID → the F00000FE2C collision is CONFIRMED as the mechanism (no longer just a hypothesis).
   Receive works because the chooser is on OUR (tapped) phone so the user can pick us; send fails
   because the chooser/route is on the OTHER (tapped) phone and lands on GMS / isn't pickable there.

   Candidate fix (NOT built — design decision needed):
   - Super Drop ↔ Super Drop: register a SECOND, PRIVATE AID that only Super Drop uses (add an
     aid-filter to `superdrop_tap_apduservice.xml`; the reader SELECTs the private AID first). A
     uniquely-registered AID routes straight to our HCE with NO chooser and NO GMS fight. Keep
     F00000FE2C only as the native-QS-interop fallback. This is the clean win for our own two-device
     tap. (Android HCE routing fact — confirm on-device.)
   - Send → native Quick Share via tap: likely NOT winnable. We can't out-route GMS for its own AID
     on a GMS phone, and Google's tap is server-flag/attestation gated. Receiving FROM native (with
     the chooser) is the realistic ceiling.

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
