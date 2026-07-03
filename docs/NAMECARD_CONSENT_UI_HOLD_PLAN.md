# Name Card v2 — "hold on screen until both choose" UI flow (PLAN, NOT BUILT)

Status: **plan only**, 2026-07-03. Do not implement until the user says go. A refinement of the
Phase-3 consent UI (`NameCardTransferActivity`, v2 path). Backend/consent semantics are UNCHANGED —
this is purely about *when* the on-screen animation plays and *when* the screen exits.

## 1. The problem with the current flow
Today (v2, as shipped in PR #251): you tap **Share** or **Receive Only** →
[`onV2LocalChoice`](../app/src/main/kotlin/dev/superdrop/namecard/NameCardTransferActivity.kt) disables
both buttons and feeds the machine; then whichever machine effect resolves first drives the visuals.
For the **second** person to choose (the common case — the peer has usually already chosen), the
`SaveCardAndRipple` effect fires immediately → ripple + `saveAndFinish` → the app exits **the instant
you tap**. You never get a beat to see "I've chosen, now we're both in." For the **first** chooser it's
the opposite: a static screen + a heads-up notification, with no clear on-screen "you're locked in".

## 2. The desired flow (user, 2026-07-03)
Regardless of which button is tapped, the screen **stays** and plays **no** ripple/exit animation yet.
On the tap:
1. **The two buttons merge/expand into a single `Done` button.** (Both pills animate together into one
   full-width Done.)
2. **The card's info-edit control locks** — you can no longer open the "change what info I share"
   pop-up (see §5 open item: this control may not exist yet).
3. The screen is otherwise **identical** — you're now visibly in a "chosen, waiting for the other
   person" state. The app does **not** exit.
4. `Done` is available as a manual escape (dismiss/bail out of waiting).

**The payoff animation only plays once BOTH sides have chosen** (or you tap `Done`):
- If the outcome is that you **received** the peer's card (they shared) → the **ripple** plays, card
  saves, contact opens.
- If **you chose Share** → the **send/exit** animation (card flies up + ripple) plays.
- If **you chose Receive Only** and there's nothing to receive (peer also declined) → **the card /
  picture fades away** to the declined message.

So: no immediate exit. Exit happens only (a) after both have selected and the resolution animation
finishes, or (b) when you tap `Done`.

## 3. What DOESN'T change (important — don't regress the consent model)
- **Per-side data transmission stays as-is (plan D1).** Your card is still sent the moment YOU tap
  Share — that's a backend `TransmitCard` effect and it does not wait for the peer. Only the *on-screen
  animation + exit* is what defers to mutual resolution. Keep these two decoupled.
- The `NameCardConsentMachine` and its effects likely need **no change** — it already defers
  `SaveCardAndRipple` to `PeerCardArrived` and the terminal effects to resolution. This is almost
  entirely a presentation-layer change in the activity.

## 4. Implementation mapping (all in `NameCardTransferActivity`, v2 path)
1. **`onV2LocalChoice(share)`** — instead of just `pressAnim` + disable, run a **buttons→Done merge**
   animation: animate `nameCardPrimary` + `nameCardSecondary` collapsing into one full-width Done
   (reuse the existing `nameCardDone` top-level button, or morph the row). Wire `nameCardDone` →
   `finish()` (manual bail). Lock the info control (§5). Then feed the machine exactly as today. Do
   **not** trigger any ripple/exit here.
2. **`onV2Effect`** — the animation/exit effects already only arrive on resolution; keep it that way.
   Ensure `SaveCardAndRipple` (→ `v2SaveReceived`) and the terminal effects (`FadeToDeclined`,
   `ShowNoResponse` → `v2ShowTerminal`) are the ONLY places that play ripple/fade/exit. Remove any
   path that could animate/exit directly from the local tap.
3. **Per-outcome animation** (gated on both-chosen, already true):
   - received a card → `playSendRipple()` + save (existing `v2SaveReceived`).
   - you shared, peer declined → the reverse-exit / send animation (currently just a declined terminal
     — add the send animation for the "I shared" case).
   - both declined / no response → fade the card (existing `v2ShowTerminal`).
4. **Waiting state** — after the merge-to-Done, drop the heads-up-notification-only approach in favor
   of the on-screen locked state (keep the heads-up as an optional extra for when the app is
   backgrounded).
5. **`Done` semantics** — during the wait, `Done` = dismiss/close the link (send BYE, finish). After a
   terminal animation, `Done` = finish. Same button, two moments.

## 5. Open items to confirm before building (do NOT guess these)
- **The field-checkbox pop-up ALREADY EXISTS — in the `namecard-tester` app, NOT in bada-fork.**
  `namecard-tester/src/com/namecard/tester/DemoActivity.java:693-767`: tapping the phone/email field
  opens a custom `PopupWindow` centered ABOVE the field, one `CheckBox` per shareable item (phone,
  email, + up to two extra config items), blue checks (`shareColor`), plain rows, grows up from
  bottom-centre; toggling updates a `selectedShares` set and rewrites the card's field line live
  (`joinShares()`). **This is the control to PORT into `bada-fork` `NameCardTransferActivity` + its
  layout.** In this flow, "lock the info control" = after you tap Share/Receive Only, the field taps
  become no-ops (pop-up no longer opens), freezing `selectedShares`. So this is a PORT + a lock, not a
  new invention. Only the resolved card is transmitted (`selectedShares` filters what the card carries).
  Confirm: port as-is from the tester, and does the same checkbox set apply (phone/email/extras)?
- **Exact per-outcome animation** for "I shared, peer declined" — send animation then declined note, or
  just the declined note? (§2 says the send animation plays "if you clicked Share".)
- **Merge animation style** — true morph (two pills → one) vs cross-fade (row out, Done in). Both are
  `tween`-able; no physics bounce spec (project rule).

## 6. Scope / risk
Presentation-only, gated behind the same `Symmetric consent (beta)` pref. Device-UNVERIFIED like the
rest of the v2 UI. Small, self-contained change to one activity + possibly one new layout control (§5).
Would ship as a follow-up commit to PR #251 (or a new PR) once confirmed.
