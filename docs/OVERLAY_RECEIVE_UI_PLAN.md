# Implementation Plan — Overlay Receive UI (floating island/card) for Super Drop

Scope: RECEIVING only. Add a shareit-bridge–style `TYPE_APPLICATION_OVERLAY`
floating card as a THIRD consent/receive surface alongside the existing
heads-up notification and the `ConsentTrampolineActivity` bottom sheet. The
overlay requires the draw-over-other-apps special access
(`SYSTEM_ALERT_WINDOW` / `Settings.canDrawOverlays`).

All file paths below are absolute. Everything in this plan was derived from
reading the actual source listed in "Verified facts" — no inference.

---

## 0. Verified facts (read this session — load-bearing)

Reference (shareit-bridge, Java/Kotlin):
- `OverlayReceiver.kt` hosts the card in a `WindowManager` window. The exact
  app-overlay params it uses (the part we mirror — ignore the
  `TYPE_ACCESSIBILITY_OVERLAY` "island" branch, which depends on a separate
  enabled accessibility service `IslandA11yService`):
  - **window type**: `Build.VERSION.SDK_INT >= O ? TYPE_APPLICATION_OVERLAY : TYPE_PHONE`
  - **size**: `MATCH_PARENT` width, `WRAP_CONTENT` height
  - **flags**: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS | FLAG_LAYOUT_IN_SCREEN | FLAG_HARDWARE_ACCELERATED`
  - **format**: `PixelFormat.TRANSLUCENT`
  - **gravity**: `Gravity.TOP`
  - add via `wm.addView(card, lp)` wrapped in try/catch — a thrown exception
    == "no overlay permission" → returns false → caller falls back.
  - removal via a `Runnable` that calls `wm.removeView(card)`, posted on the
    card; completion auto-removes after a delay (`postDelayed(remove, 8000)`).
- `ReceiveUi.java` is the surface chooser: `overlayEnabled(ctx)` ==
  `Settings.canDrawOverlays(ctx)`; `show()` does
  `if (overlayEnabled && OverlayReceiver.show(...)) return; else startSheet()`.
- `ReceiveCard.java` is the SHARED card view, hosted by BOTH
  `ReceiveBottomSheetActivity` (no-perm path) and `OverlayReceiver`
  (overlay path) — identical visuals. It is a plain
  `DraggableSheetLayout`-based view + an `Actions` callback interface
  (`onAccept`/`onDeclineOrClose`/`onOpen`) + state setters
  (`setIncoming`/`setProgress`/`setComplete`). This shared-card pattern is
  exactly what we replicate on the Super Drop side.
- `ReceiveController.java` (`bind(Ui)` with `onIncoming/onProgress/onComplete/onCanceled`)
  is shareit-bridge's engine seam; Super Drop already has the equivalent in
  `InboundConnection.state` (a `StateFlow`), so we do NOT need a new seam.

Target (Super Drop, Kotlin, pkg `dev.superdrop`):
- `ConsentCoordinator.kt` (`:service-android`) is the surface chooser. It
  picks Notification vs Modal purely on `appForegroundState.isForeground`
  (`raiseConsentSurface`). The `Sink` interface has 4 methods:
  `postConsent` / `dismissConsent` (notification) and `launchModal` /
  `dismissModal` (activity). Surface state per id is an enum
  `Surface { Notification, Modal, Decided }`. `onForegroundChanged` swaps
  surfaces in lock-step on every fg/bg transition; `Decided` is a tombstone.
- The `Sink` is implemented inline in `ReceiverForegroundService.startConsentCoordinator`.
  `launchModal` → `launchConsentTrampolineAsModal(connectionId)` →
  `startActivity(ConsentTrampolineActivity, ACTION_SHOW_CONSENT, EXTRA_CONNECTION_ID)`.
  `dismissModal` → `ConsentModalRegistry.instance.dismiss(connectionId)`.
- `ConsentRegistry.instance` (process singleton) maps `connectionId -> Entry`.
  `Entry` carries `connection: InboundConnection`, `sourceDeviceName`, `pin`,
  `itemCount`, `totalSize`, `items: List<TransferItem>`, and a
  `submitConsent: (Boolean)->Unit` (defaults to `connection::submitUserConsent`).
- `ConsentTrampolineActivity.kt` (`:app`) drives consent today:
  `wireButtons` → `submit(entry, accepted)`. On accept it broadcasts
  `ACTION_ACCEPT` (so the FSM can't tell which surface decided), then
  `switchToReceivingPanel()` + `startObservingConnectionState()` which
  `lifecycleScope.launch { connection.state.collect { ... } }` to render
  Receiving/Completed/Cancelled/Failed/Rejected. The PIN/files/buttons live
  in `R.layout.activity_consent_trampoline` (panels:
  `consent_panel` / `consent_waiting_panel` / `consent_receiving_panel` /
  `consent_completed_panel` / `consent_failed_panel`, hosted in
  `consent_sheet` = a `DraggableSheetLayout`).
- `InboundConnection.submitUserConsent(accepted: Boolean)` and `.state:
  StateFlow<InboundConnectionState>` are the only engine calls needed.
  States: `Idle/Handshaking/Negotiating/WaitingForUserConsent(metadata)/
  Receiving(progress,…)/Completed(items)/Rejected/Cancelled(cause)/Failed(reason)`.
- `ConsentBroadcastReceiver` is registered `RECEIVER_NOT_EXPORTED` by the
  service; accept/reject is delivered by `sendBroadcast(Intent(action).setPackage(pkg).putExtra(EXTRA_CONNECTION_ID,id))`.
  This is the surface-agnostic decision path the overlay MUST reuse.
- Sheet primitives already ported to Kotlin under
  `app/src/main/kotlin/dev/superdrop/ui/sheet/`:
  `DraggableSheetLayout` (host-agnostic — it's a `LinearLayout`, no Activity
  coupling), `RoundedProgressBar`, `RingProgressView`, `DeviceIconView`.
- `Theme.SuperDrop.ReceiveSheet` parent is `Theme.Bada` (AppCompat family) —
  usable to wrap a service `Context` via `ContextThemeWrapper` for the
  overlay (mirrors OverlayReceiver's `ContextThemeWrapper(app, Theme_AppCompat_…)`).
- `FullScreenIntentPermission.kt` + `FullScreenIntentPreferences.kt` are the
  in-repo template for a special-access permission flow (detect-applicable /
  detect-granted / open-settings + a "shown once" pref + a Settings-tab card
  refreshed in `onResume`). The overlay permission flow mirrors this 1:1.
- Manifest currently declares NO `SYSTEM_ALERT_WINDOW`. There is NO existing
  overlay/`WindowManager.addView` code anywhere in bada-fork.

### Key architectural difference from shareit-bridge (drives the threading design)
shareit-bridge's `OverlayReceiver.show()` is called from a UI/activity-ish
context and binds a `ReceiveController.Ui` callback. In Super Drop the
overlay must be raised **from the service process** (`ReceiverForegroundService`
is where the `Sink` lives) and driven by `InboundConnection.state`, a
coroutine `StateFlow`. There is **no `lifecycleScope`** in a service-hosted
`WindowManager` view. We therefore drive the overlay from a plain
`CoroutineScope` (the service's `serviceScope`, which is
`SupervisorJob + Dispatchers.IO`) and **marshal every view mutation to the
main thread** (`view.post { … }`, exactly as OverlayReceiver does with
`card.post { card.liveProgress(...) }`). The overlay's own coroutine must be
cancelled when the window is removed.

---

## Phase 1 — Shared receive-card view builder (refactor, no behavior change)

Goal: factor the consent card's view construction + state rendering out of
`ConsentTrampolineActivity` into a host-agnostic class that BOTH the activity
and the overlay inflate, so they stay pixel-identical (mirrors
shareit-bridge `ReceiveCard` shared by activity + overlay).

The activity currently inflates `R.layout.activity_consent_trampoline` and
manipulates panels by id. The cleanest, lowest-risk refactor that keeps the
device-tested activity path byte-identical:

1. **New file** `app/src/main/kotlin/dev/superdrop/consent/ReceiveCardView.kt`
   - Class `ReceiveCardView(host: Context, root: View, callbacks: Callbacks)`.
   - `root` is the inflated `R.layout.activity_consent_trampoline`'s
     `consent_sheet_root` subtree (the activity passes its own inflated
     content view; the overlay inflates the SAME layout via
     `LayoutInflater.from(themedContext)`).
   - Move into this class (lifted verbatim from the activity, parameterised on
     `root` instead of `this`/`findViewById`):
     `renderEntry`, `renderItemList`, `itemLine`, `classifyPayload`,
     `showWaitingPanel`, `switchToReceivingPanel`, `renderProgress`,
     `showCompletedPanel`, `bindCompletedPreview`, `applyBlurredCardBackground`,
     `fadeInBlurBackdrop`, `buildPrettyBlurEffect`, `decodeSampledBitmap`,
     `findReceivedImageUri`, `showFailedPanel`, `beginPanelTransition`,
     `configureCompletedActionButton`, plus the panel companion constants.
   - `Callbacks` interface: `onAccept()`, `onReject()`, `onClose()`,
     `onViewImage(uri: Uri)` (the activity already inlines the
     `ACTION_VIEW` + `finish()`; the overlay needs `FLAG_ACTIVITY_NEW_TASK` +
     remove-overlay, so the host owns the click reaction).
   - All async work (`MediaStore` lookup, bitmap decode) must run on a
     caller-supplied `CoroutineScope` parameter, NOT `lifecycleScope` —
     the activity passes `lifecycleScope`, the overlay passes its own scope
     (see Phase 4). This is the single substantive change vs the current code.
2. **Edit** `ConsentTrampolineActivity.kt`
   - Replace the inline panel logic with delegation to a `ReceiveCardView`
     built over `findViewById(R.id.consent_sheet_root)`, passing
     `lifecycleScope` as the async scope and wiring `Callbacks` to the
     existing `submit(...)` / `finish()` / `ACTION_VIEW` code.
   - Keep `wireBottomSheet`, `applyIncomingCallFlags`, `bindIntent`,
     `registerModal`, `TileVisibilityElevationHolder` handling, and the
     broadcast `submit()` in the activity — those are activity-only concerns.
   - Acceptance: the activity path must render identically (this is a pure
     extract-class refactor; the device-verified consent/receive/complete
     flow must not regress).

> Why factor at the layout level (not rebuild the card in code like
> shareit-bridge's `ReceiveCard`): Super Drop's card is already an XML layout
> with five panels and a blur backdrop. Re-implementing it in code would risk
> visual drift. Inflating the SAME `R.layout` in both hosts guarantees parity
> with far less churn. The overlay just needs a `Context` that can inflate it.

---

## Phase 2 — Overlay permission plumbing (special access)

Mirror the `FullScreenIntentPermission` pattern exactly.

1. **New file** `app/src/main/kotlin/dev/superdrop/consent/OverlayPermission.kt`
   - `fun isGranted(ctx): Boolean = Settings.canDrawOverlays(ctx)`
     (on API < 23 `canDrawOverlays` returns true / perm is install-time; our
     `minSdk` is 24 per the activity comment, so `canDrawOverlays` is always
     available).
   - `fun isApplicable(): Boolean = true` (the access exists on every
     supported API — unlike FSI which is API 34+).
   - `fun openSettings(ctx)`: `Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
     Uri.fromParts("package", ctx.packageName, null)).addFlags(FLAG_ACTIVITY_NEW_TASK)`,
     with a fallback to `ACTION_APPLICATION_DETAILS_SETTINGS` (copy the
     try/catch ladder from `FullScreenIntentPermission.openSettings`).
   - NOTE in code + onboarding copy: this is a **special app access**, NOT a
     runtime `requestPermissions` dialog — you cannot grant it with an
     `ActivityResultContracts.RequestPermission`. The user must toggle it on
     the system "Display over other apps" page.
2. **New file** `app/src/main/kotlin/dev/superdrop/consent/OverlayReceivePreferences.kt`
   - Mirror `FullScreenIntentPreferences`: a SharedPreferences-backed boolean
     **`overlay_receive_enabled`** (the user opt-in toggle; default **false**)
     plus an optional `overlay_prompt_dismissed` "shown once" flag.
   - `PREFS_NAME = "bada.overlay_receive"`. Expose `isEnabled(): Boolean`,
     `setEnabled(b)`, and `from(context)`.
   - This MUST live in a module readable by `:service-android` (the
     coordinator/Sink consults it). `FullScreenIntentPreferences` is in
     `:app`, but the Sink lives in `:service-android`. To avoid a
     `:service-android → :app` dependency, **place
     `OverlayReceivePreferences` in `:service-android`** (e.g.
     `service-android/.../service/receiver/consent/OverlayReceivePreferences.kt`)
     and have the `:app` Settings UI read/write it through the same singleton.
     (The "enabled" decision is needed inside the service; the SharedPreferences
     name is process-global so both modules see the same value.)
3. **Manifest** — add to `app/src/main/AndroidManifest.xml` (top, beside the
   other `<uses-permission>`s):
   ```xml
   <!-- Floating overlay receive card (opt-in). Special app access:
        the user grants it on the "Display over other apps" system page;
        there is no runtime dialog. When absent we fall back to the
        consent activity / heads-up notification. -->
   <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
   ```

---

## Phase 3 — The overlay window host (service-process `WindowManager`)

**New file**
`service-android/src/main/kotlin/dev/superdrop/service/receiver/consent/OverlayConsentPresenter.kt`
(in `:service-android` because the Sink calls it; it must not statically
depend on `:app`. It inflates `R.layout.activity_consent_trampoline`, which
lives in `:app`'s resources — see "Resource-access constraint" below).

Responsibilities (mirror `OverlayReceiver.show` minus the a11y-island branch):

1. `show(connectionId: Long, entry: ConsentRegistry.Entry): Boolean`
   - `val app = ctx.applicationContext`
   - `val wm = app.getSystemService(WINDOW_SERVICE) as WindowManager`
   - `val themed = ContextThemeWrapper(app, <ReceiveSheet/AppCompat theme>)`
     — AppCompat theme is required because the card uses AppCompat widgets /
     `TextAppearance.AppCompat.*` (OverlayReceiver hit the same requirement).
   - Inflate the consent layout: `LayoutInflater.from(themed).inflate(R.layout.activity_consent_trampoline, null)`.
   - Build a `ReceiveCardView` (Phase 1) over the inflated root, async scope =
     the presenter's own scope (Phase 4), `Callbacks` wired to:
       - `onAccept` → broadcast `ACTION_ACCEPT` (Phase 5) + switch to receiving
         panel (the card does this) — do NOT remove the window yet.
       - `onReject` → broadcast `ACTION_REJECT` + `post(remove)`.
       - `onClose` → `post(remove)`.
       - `onViewImage(uri)` → `startActivity(ACTION_VIEW, uri, FLAG_ACTIVITY_NEW_TASK | FLAG_GRANT_READ_URI_PERMISSION)` then `post(remove)`.
   - Render the consent panel immediately from `entry` (sender name, PIN,
     file list) — same `renderEntry` the activity uses.
   - **LayoutParams** (mirror OverlayReceiver app-overlay path exactly):
     ```
     type   = SDK>=O ? TYPE_APPLICATION_OVERLAY : TYPE_PHONE
     w/h    = MATCH_PARENT / WRAP_CONTENT
     flags  = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS
              | FLAG_LAYOUT_IN_SCREEN | FLAG_HARDWARE_ACCELERATED
     format = PixelFormat.TRANSLUCENT
     gravity= Gravity.TOP        (top "island"; see position decision below)
     ```
   - `try { wm.addView(root, lp); true } catch (e: Exception) { remove-scope, return false }`.
     A throw here == permission was revoked between check and add → caller
     falls back. (OverlayReceiver treats addView failure as "no permission".)
2. `remove`: a `Runnable` that `runCatching { wm.removeView(root) }` and
   **cancels the presenter's coroutine scope/job** (Phase 4). Posted on the
   root view (`root.post(remove)`) so removal always happens on the main thread.
3. Keep a per-`connectionId` handle (the root view + remove runnable + the
   state-collection `Job`) in a small `ConcurrentHashMap` inside the presenter
   so `dismiss(connectionId)` (from the Sink) can tear a specific window down.

### Focusability decision (PIN + buttons)
The card has tappable Accept/Reject/Close buttons but NO text input (the PIN
is display-only — the user reads it; it is not typed). Therefore
`FLAG_NOT_FOCUSABLE` is correct (matches OverlayReceiver) and **must be kept**:
the overlay must not steal IME/focus from the app underneath. Buttons still
receive touches because `FLAG_NOT_TOUCH_MODAL` is set and the window is sized
to wrap the card, so taps inside the card are consumed and taps outside pass
through to the app below. (No focusable variant is needed — if a future design
adds a typed PIN entry, drop `FLAG_NOT_FOCUSABLE`; not in scope here.)

### Position decision (island vs card)
shareit-bridge uses `Gravity.TOP` and positions a pill over the camera. Super
Drop's existing consent UI is a **bottom sheet**. For the overlay, anchor
**`Gravity.TOP`** (a top "island" that does not fight the nav bar and reads as
a system-style heads-up surface) but keep the inner card visuals identical to
the bottom-sheet card. The `DraggableSheetLayout` drag-to-dismiss still works
(drag down to remove → wire its `setOnDismiss { onReject }` to a decline+remove,
matching the activity's tap-outside == reject-free dismiss semantics — see
"Dismiss semantics" below). This is a deliberate, documentable choice; flag it
for the designer to confirm top-island vs bottom-card.

### Screen-off / lock-screen behavior (vs the activity's setShowWhenLocked)
The activity uses `setShowWhenLocked(true)` + `setTurnScreenOn(true)` to wake
the device over the keyguard. A `TYPE_APPLICATION_OVERLAY` window **cannot wake
the screen or show over a secure keyguard** — Android draws app overlays
*below* the keyguard, and an overlay has no way to call `setTurnScreenOn`.
**Therefore the overlay is the wrong surface for the screen-off / locked case.**
The coordinator MUST treat overlay as a *foreground-only* surface: when the
screen is off / the device is locked, fall back to the existing
notification + full-screen-intent activity path (which already handles wake +
keyguard via `applyIncomingCallFlags`). This is called out concretely in
Phase 6. (Do not attempt `FLAG_SHOW_WHEN_LOCKED` / `FLAG_TURN_SCREEN_ON` on the
overlay window — those are window flags honored for Activities, not for app
overlays; relying on them is unverified and OEM-fragile.)

### Resource-access constraint (must resolve before coding Phase 3)
`R.layout.activity_consent_trampoline`, all `R.id.consent_*`, `R.string.*`,
`R.dimen.*`, `R.drawable.*` referenced by the card currently live in **`:app`**.
`OverlayConsentPresenter` is proposed in **`:service-android`**, which cannot
see `:app`'s R class. Two options — pick ONE:
- **(A) Presenter in `:app`, injected like the trampoline target.** Mirror the
  existing `ReceiverForegroundService.consentTrampolineTarget: Class<*>` seam:
  add `ReceiverForegroundService.overlayPresenterFactory` (a
  `@Volatile` functional slot the `:app` `BadaApplication.onCreate` populates),
  and have the Sink call through it. This keeps the layout/R access in `:app`
  and avoids moving resources. **Recommended** — it's the established pattern
  in this codebase (`openAppTarget`, `consentTrampolineTarget`).
- **(B) Move the card layout + its resources into `:service-android`.** More
  invasive; risks breaking the activity. Not recommended.

> Decision: use **(A)**. Define a small interface in `:service-android`
> (`OverlayConsentSurface { fun show(id, entry): Boolean; fun dismiss(id) }`),
> implement it in `:app` (`OverlayConsentPresenter` there, with full R access),
> and register the impl via a new `@Volatile` slot on
> `ReceiverForegroundService` set in `BadaApplication.onCreate` — exactly like
> `consentTrampolineTarget`. The presenter file therefore lives at
> `app/src/main/kotlin/dev/superdrop/consent/OverlayConsentPresenter.kt`, and
> only the tiny interface + slot live in `:service-android`.

---

## Phase 4 — Overlay state observation + threading

The overlay is driven by `InboundConnection.state` from the service process.

1. The presenter owns a child scope:
   `val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
   per shown window (so view mutations are already on main; heavy IO inside the
   card uses `withContext(Dispatchers.IO)` exactly as the activity does).
2. On accept (after broadcasting `ACTION_ACCEPT`), start observing — mirror
   `ConsentTrampolineActivity.startObservingConnectionState`:
   ```
   scope.launch {
     entry.connection.state.collect { state -> when (state) {
       Receiving  -> card.renderProgress(bytesReceived, totalBytes)
       Completed  -> card.showCompletedPanel(items)   // then auto-remove after delay
       Cancelled  -> card.showFailedPanel("cancelled"); scheduleRemove()
       Failed     -> card.showFailedPanel("failed", reason); scheduleRemove()
       Rejected   -> card.showFailedPanel("failed"); scheduleRemove()
       else -> Unit
     } }
   }
   ```
   - Capture `entry.connection` and `entry.totalSize` BEFORE broadcasting
     accept (the broadcast unregisters the `ConsentRegistry` entry — same race
     the activity guards against in `submit()`).
   - On `Completed`, auto-remove after a delay (mirror OverlayReceiver's
     `postDelayed(remove, 8000)`) so a finished overlay never hangs; the user
     can tap Close/View sooner.
3. **Every** card mutation from the collector is already on `Dispatchers.Main.immediate`,
   so direct calls are safe; if any path ends up off-main, wrap in `root.post{}`
   (OverlayReceiver wraps with `card.post { … }` for exactly this reason).
4. `remove` cancels `scope` (`scope.cancel()`) so the state collector stops
   when the window goes away.

---

## Phase 5 — Wiring the decision back (surface-agnostic)

The overlay must drive accept/reject through the SAME path the activity and
notification use so the FSM/bookkeeping can't tell surfaces apart:

- Build the broadcast exactly like `ConsentTrampolineActivity.submit`:
  `Intent(ACTION_ACCEPT|ACTION_REJECT).setPackage(packageName).putExtra(EXTRA_CONNECTION_ID, id)`
  → `app.sendBroadcast(intent)`. (The presenter has only an app `Context`, not
  an Activity — `sendBroadcast` works from any `Context`.)
- Do NOT call `entry.submitConsent` / `connection.submitUserConsent` directly:
  the broadcast path is what the coordinator's surface state machine and the
  `ConsentBroadcastReceiver` expect; bypassing it would desync the
  `Surface.Decided` tombstone and could double-handle the decision.
- After broadcasting accept, the overlay stays up and switches to the
  receiving panel (Phase 4). After broadcasting reject, remove the window.

---

## Phase 6 — Coordinator: overlay as a third surface

Extend `ConsentCoordinator` + the Sink. The coordinator currently has a binary
`Surface { Notification, Modal, Decided }` chosen on `isForeground`.

### 6a. Sink — add overlay methods
In `ConsentCoordinator.Sink` add:
```
fun launchOverlay(connectionId: Long, entry: ConsentRegistry.Entry): Boolean
fun dismissOverlay(connectionId: Long)
```
`launchOverlay` returns `false` when the overlay could not be shown (perm
revoked / `addView` threw / no presenter registered) so the coordinator can
fall back. Implement in `ReceiverForegroundService.startConsentCoordinator`'s
inline Sink:
- `launchOverlay` → `overlaySurface?.show(connectionId, entry) ?: false`
  (the `:app`-registered impl from Phase 3 option A).
- `dismissOverlay` → `overlaySurface?.dismiss(connectionId)`.

### 6b. Surface enum — add `Overlay`
`enum class Surface { Notification, Modal, Overlay, Decided }`.

### 6c. Surface choice (`raiseConsentSurface`)
Replace the binary choice with a 3-way gated on (i) the user opt-in pref and
(ii) the live permission, and crucially gated on **foreground only** (overlay
cannot wake/lock-screen — Phase 3):
```
val overlayWanted = OverlayReceivePreferences.from(ctx).isEnabled()
                    && Settings.canDrawOverlays(ctx)
val isForeground  = appForegroundState.isForeground
target = when {
  // Foreground + overlay opted-in & permitted → overlay card.
  overlayWanted && isForeground -> Surface.Overlay
  isForeground                  -> Surface.Modal
  else                          -> Surface.Notification
}
```
- When `target == Overlay`, call `sink.launchOverlay(...)`. If it returns
  `false` (perm revoked at the last instant / addView failed), **fall back to
  Modal if foreground else Notification** and record THAT surface in
  `surfaceForId` instead — so the surface map never claims an overlay that
  isn't actually on screen. (Mirror `ReceiveUi.show`'s
  `if (overlayEnabled && OverlayReceiver.show(...)) return; else startSheet()`
  fallthrough.)
- `Settings.canDrawOverlays` requires a `Context`; the coordinator currently
  takes none. Pass an `overlayAllowed: () -> Boolean` and
  `overlayEnabled: () -> Boolean` provider into the coordinator constructor
  (defaulted to `{ false }` so existing unit tests are unaffected); the service
  wires them to the real pref + `canDrawOverlays`. This keeps the coordinator
  JVM-pure and testable (matches how `stateExtractor`/`consentSubmitter` are
  injected today).

### 6d. Foreground/background transitions (`onForegroundChanged` / `applySurfaceSwitch`)
Today fg↔bg swaps Notification↔Modal. Extend so an Overlay surface follows the
user correctly:
- **Overlay → background**: the overlay is foreground-only. On background,
  `dismissOverlay` + `postConsent` (raise the notification). Set surface to
  `Notification`.
- **Notification → foreground**: if `overlayWanted`, raise the overlay
  (`dismissConsent` + `launchOverlay`; on false, fall back to `launchModal`).
  Else keep the existing Notification→Modal behavior.
- **Overlay → foreground** (already overlay): no-op (same target).
- Keep `Decided` as the terminal tombstone (unchanged).
- Implement this by making `target` in `onForegroundChanged` overlay-aware
  (reuse the 6c gate) and extending `applySurfaceSwitch` with `Surface.Overlay`
  / and the Notification/Modal cases dismissing the overlay first when leaving it.

### 6e. dismiss / terminal cleanup
`dismissConsentSurface` already switches on the prior surface — add a
`Surface.Overlay -> sink.dismissOverlay(connectionId)` branch. Also add overlay
dismissal to `ReceiverForegroundService.stopActiveReceiverSession` hygiene
(alongside the `ConsentModalRegistry`/notification teardown) so a torn-down
session never leaves a floating card pointing at a dead connection — dismiss
every still-shown overlay id.

---

## Phase 7 — Settings toggle + onboarding copy + clean default

1. **Settings tab** — `app/.../ui/SettingsFragment.kt` + `fragment_settings.xml`.
   Add an "Incoming transfer overlay" card mirroring the existing FSI card
   (`settings_fsi_card` / `settings_fsi_status` / `settings_fsi_open`):
   - A `SwitchCompat` "Show incoming transfers as a floating overlay"
     (bound to `OverlayReceivePreferences.setEnabled`).
   - A status line: "Permission granted / not granted" from
     `OverlayPermission.isGranted`.
   - A button "Open system setting" → `OverlayPermission.openSettings`.
   - Refresh the status line in `onResume` (like
     `refreshFullScreenIntentSection`) so toggling the system page flips the
     label immediately.
   - When the user enables the switch but the permission is not granted, route
     them to `openSettings` and show inline copy that this is a special access
     (no runtime dialog). The switch stays "enabled (pending permission)";
     the coordinator's `canDrawOverlays` gate means it simply has no effect
     until granted, then "just works" — no app restart needed.
2. **Default OFF.** Both the pref default and the effective behavior are off:
   the overlay needs a special permission, so shipping it on by default would
   either silently do nothing (perm absent) or be surprising. Default false in
   `OverlayReceivePreferences`.
3. **Onboarding copy** (strings.xml, new keys): explain "Super Drop can show
   incoming transfers as a floating card over other apps. This needs the
   'Display over other apps' permission, which you grant on a system settings
   page (there's no pop-up to allow it here)." Keep it optional — never block
   first-run on it (parallels the FSI first-launch dialog being skippable).

### Interaction with the tile-opened "waiting" sheet
The QS tile opens `ConsentTrampolineActivity` in waiting mode
(`ACTION_OPEN_RECEIVE_SHEET`, `TileVisibilityElevationHolder` armed). That is an
**activity** path and is foreground by definition. Leave it as-is: when the
tile opens the waiting sheet, the app is foreground and the activity is already
up; an incoming transfer routes into that open activity via `onNewIntent`
(existing behavior). Do NOT raise the overlay on top of the tile-opened waiting
sheet — gate `launchOverlay` so it is skipped when a consent modal activity is
already registered for that id in `ConsentModalRegistry` (check
`ConsentModalRegistry.instance.snapshotIds()` or add an `isShowing(id)` helper).
This avoids a double surface (activity sheet + overlay card) for the same
transfer. Flag for confirmation: alternatively the overlay could be suppressed
whenever `appForegroundState.isForeground && a Bada activity is visibly the
consent host` — the simplest correct rule is "overlay only when foreground AND
no consent activity is currently registered for this id".

---

## Phase 8 — Risks / tradeoffs (call out, don't hide)

- **Overlay cannot wake screen / show over secure keyguard.** Handled by making
  overlay a foreground-only surface (Phase 3/6); screen-off & locked keep using
  the notification + FSI activity. Do not claim overlay covers the
  "device on the table, screen off" case — it does not.
- **OEM overlay restrictions.** Some OEMs (MIUI/ColorOS/OriginOS) gate
  `SYSTEM_ALERT_WINDOW` behind an extra per-app toggle even after
  `canDrawOverlays` returns true, or block overlays over full-screen / system
  UI. `wm.addView` can still throw → the try/catch fallback (Phase 3) is
  mandatory and already returns false → coordinator falls back. This mirrors
  OverlayReceiver treating addView failure as "no permission".
- **Permission friction.** Special access, no runtime dialog → users must visit
  a settings page. Mitigated by default-off + clear Settings card + onboarding
  copy. Never block the receive flow on it.
- **Accessibility.** The overlay is `FLAG_NOT_FOCUSABLE`; TalkBack focus can be
  awkward on non-focusable windows. The card buttons already get
  `configureCompletedActionButton` a11y delegates; ensure Accept/Reject get
  `contentDescription`s too. Because the notification/activity paths remain the
  default, a11y users are never forced onto the overlay.
- **Double-surface race.** fg/bg flapping + the tile waiting sheet could briefly
  show two surfaces. The `Surface` state machine + the
  "skip overlay if a consent activity is registered for this id" gate (Phase 7)
  contain it; verify on a real fg/bg flap.
- **Threading.** The overlay runs in the service process with NO lifecycle —
  scope is owned by the presenter and MUST be cancelled on remove, or the state
  collector leaks for the life of the process. (Phase 4.)
- **Just-fixed tile/visibility interplay.** `TileVisibilityElevationHolder` is
  armed/restored by the ACTIVITY (`finish()` / `onUserLeaveHint` / `onDestroy`).
  The overlay does NOT touch that holder — it's only raised on real incoming
  transfers (never the tile waiting path, per Phase 7), so the tile's
  arm/restore bookkeeping is unaffected.

---

## File change summary

New (`:app`):
- `app/.../consent/ReceiveCardView.kt`            (Phase 1, shared card)
- `app/.../consent/OverlayPermission.kt`          (Phase 2)
- `app/.../consent/OverlayConsentPresenter.kt`    (Phase 3, impl of the surface)

New (`:service-android`):
- `service-android/.../consent/OverlayConsentSurface.kt`        (interface, Phase 3A)
- `service-android/.../consent/OverlayReceivePreferences.kt`    (Phase 2, shared pref)

Edit (`:app`):
- `app/.../consent/ConsentTrampolineActivity.kt`  (delegate to ReceiveCardView)
- `app/.../BadaApplication.kt`                     (register overlay surface impl slot)
- `app/.../ui/SettingsFragment.kt`                 (overlay toggle card)
- `app/src/main/AndroidManifest.xml`              (SYSTEM_ALERT_WINDOW)
- `app/src/main/res/layout/fragment_settings.xml` (overlay settings card)
- `app/src/main/res/values/strings.xml`           (toggle + onboarding copy)

Edit (`:service-android`):
- `service-android/.../consent/ConsentCoordinator.kt`      (Surface.Overlay, 3-way choice, fg/bg, dismiss; new Sink methods + overlay providers)
- `service-android/.../receiver/ReceiverForegroundService.kt` (Sink impl: launch/dismiss overlay via registered surface; `overlaySurface` @Volatile slot; teardown hygiene)

Sequencing: Phase 1 (refactor, ship/verify alone) → Phase 2 (perm/pref) →
Phase 3+4+5 (presenter, behind a slot, not yet wired into the coordinator) →
Phase 6 (coordinator wiring — the behavior switch flips here) → Phase 7
(settings/onboarding) → Phase 8 review.

---

## Open items I could NOT determine from the code (flag for confirmation)
1. **Top-island vs bottom-card position** for the overlay — chose `Gravity.TOP`
   to read as a system heads-up surface; designer should confirm vs reusing the
   bottom-anchored look.
2. **Exact `minSdk`** — the activity comment says "minSdk = 24"; I did not open
   the gradle config to confirm. `TYPE_PHONE` fallback (API < 26) and
   `canDrawOverlays` (API 23+) both hold for 24, but verify
   `app/build.gradle.kts` before relying on the fallback being dead code.
3. **Whether `:service-android` may host a SharedPreferences class** that `:app`
   also reads — confirmed feasible (SharedPreferences are process-global by
   name), but verify there is no module-visibility lint rule forbidding it; if
   so, fall back to defining the pref in `:app` and reading the effective
   "enabled && canDrawOverlays" purely through the injected
   `overlayEnabled: () -> Boolean` provider the service supplies (the service IS
   in-process and can read an `:app`… no — the service can't see `:app`; in that
   case put the pref read in `BadaApplication`/a holder the service already
   knows, e.g. a process-wide `@Volatile` flag mirrored from the toggle, the
   same way `MdnsVisibilityOverrideHolder` works). The `MdnsVisibilityOverrideHolder`
   pattern in `ReceiverForegroundService.kt` is the clean precedent: a
   process-wide holder the `:app` toggle flips and the service reads — prefer
   that over cross-module SharedPreferences if visibility is a problem.
