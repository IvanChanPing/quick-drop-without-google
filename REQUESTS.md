# Super Drop — everything requested (running list, 2026-06-05 → 06)

Status legend: ✅ done + on-device UI-verified · 🟡 done but device-UNVERIFIED (compile/render only —
needs physical phones or a real transfer) · 💬 answered/explained (no build) · ⏸ deferred (user's call) ·
🔄 in progress.

## Fork + identity
- ✅ Fork `kyujin-cho/Bada` → app **"Super Drop"**, package **`dev.superdrop`** (rename build-verified; renders as "Super Drop"). Protocol/wire stack untouched.

## Send
- ✅ Share-sheet send becomes an **OShare-style bottom sheet** (floats over the app, no full-screen takeover); modelled on the shareit-bridge "osh" sheets.
- ✅ In-sheet **QR + link** panel (live pairing URL) ported from Bada's QR feature.
- 🟡 **Circular peer icons** coded (no 2nd device here to populate the row).

## Receive
- ✅ **QS tile** opens the OShare receive bottom sheet ("Ready to receive"); tile launches in its **own task over the launcher**, not into the app (bug #4 — verified fixed).
- 🟡 Tile **visibility bump → restore on leave** (bug #5 fixed: restore on onDestroy + onUserLeaveHint; device-unverified).
- 🟡 **Completion notification with an "Open" action** (replaces the progress notification on complete).

## Notifications (incoming consent)
- 🟡 Custom heads-up: **recolored Accept/Decline**, moved to a **centered pair** (closer).
- ✅/🟡 **3-way "Incoming transfer style" setting** (card renders ✅; the styles themselves render only on a real transfer 🟡):
  1. **Recolored buttons** (default).
  2. **Bridge card style** — RemoteViews designed to look like the **shareit-bridge overlay receive card** (design only).
  3. **Bottom sheet only** — the **original** minimal notification whose **full-screen-intent raises the built bottom sheet** (confirmed = the FSI-raised sheet).
- 💬 "Notification should **slide away** on accept/reject" → not possible for a system notification (the system owns the animation); a slide-away surface = the bottom sheet / overlay (our own windows).

## NFC
- 🟡 **iPhone link broadcast** — HCE NDEF serves the QR pairing link; active **only while the QR panel is open** (verified gating; iPhone-tap→Safari unverified, no NFC HW).
- 🟡 **Tap-to-share, BOTH directions** (HCE receiver AID `F00000FE2C` + reader-mode sender), Quick Share byte formats from the decompile; codec golden-byte tests pass; some stock-interop bytes best-effort. No NFC HW → tap/transfer **unverified**.
- ✅/🟡 **Dedicated NFC tap-to-share setting** (3 modes: sheet-open / app-open / background), separate from the visible toggle (card renders ✅; behavior wires when tap-to-share is device-validated).

## Battery
- ✅ **One-tap "Allow background activity" exemption popup** (verified: tap → system ALLOW → exempt).
- 🟡 **OnePlus no-op fallback** → escalate to **App Info** when the popup silently fails (device-unverified — can't repro the OnePlus no-op here).

## Other bug fixes (you reported from your phones)
- 🟡 **Device showed an IP instead of a name** (#3) → never show the raw address; hidden peers show "Quick Share device" (our own adv carries the real name).

## Answered / explained (no build)
- 💬 Per-device (make/model/Android version) branch for "live alerts" → all modern OEM islands (Pixel, Xiaomi HyperOS 3.1+, OnePlus OxygenOS 16) consume the **standard Android 16 Live Updates API** — no per-OEM custom layout needed.
- 💬 "Visible on scan" label → **unchanged**.
- 💬 The bottom sheet **is** raised by the consent notification's full-screen-intent (confirmed).

## Deferred (your call)
- ⏸ **Live Updates / dynamic-island ("live alerts")**: groundwork in (permission + TODO); BLOCKED on a **toolchain bump** (AGP 8.7.3→8.9.1 + androidx.core →1.17) — NOT to bump without confirming it won't break (your instruction).
- ⏸ **Overlay receive UI** (draw-over-apps "island" card): plan written (`docs/OVERLAY_RECEIVE_UI_PLAN.md`); build deferred.

## In progress
- 🔄 **Upstream contribution plan** — Option A (one feature per PR, **rename-free**, against `dev.bluehouse.bada`); agent writing `/root/agent-work/superdrop-pr-plan.md` (recommended first PRs + gh/fork/auth prereqs).
