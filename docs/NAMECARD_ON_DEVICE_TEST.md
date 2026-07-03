# Name Card (tap-to-share contacts) — on-device test script

Everything in this feature is **compile-verified only** in the build env (no NFC, no Bluetooth, no
second phone). This is the script to verify it for real. You need **two Android phones**, both with
`super-drop-debug.apk` installed (repo root), **NFC + Bluetooth ON**, both **unlocked**.

## 0. Setup (once per phone)
1. Install `super-drop-debug.apk` on both phones (same signed debug build).
2. Open Super Drop → **Settings**. Confirm the **"Name Card"** row shows a **red dot** (not set up yet).
3. Tap the row → **My Name Card** → enter Name / Phone / Email (or tap **Use my phone info**) → **Save**.
4. Back on Settings, confirm the **red dot is gone**.
5. Repeat on the second phone with different details.
6. First run will need: NFC enabled. **Bluetooth** is force-enabled at tap time via the bundled
   **Radio Helper** (install it when prompted; same signing key) — but for the first test it's simplest
   to have Bluetooth already ON. Grant any Bluetooth permission prompts (onboarding requests them).
   The helper also runs a 5s heartbeat and restores Bluetooth to its prior state after the swap.

## 1. The tap (core flow)
- **Phone A:** leave it **unlocked**, on its home screen or any screen, app **closed** (it's the "card").
- **Phone B:** **open Super Drop** to the main screen (this arms the reader — no button to press).
- Tap the **backs of the phones together** (NFC antennas — usually upper-middle on the back).

### Expected
- Phone B: a full-screen **Name Card** screen → briefly "Connecting…" → Phone A's card (avatar +
  name/phone/email) with a top **glow** + buttons **Receive Only** / **Share**.
- Tap **Share** on B → B saves A's card (or the system Add-contact screen opens) AND A pops the same
  full-screen card showing B's card with **Save** / **Done**.
- Tap **Receive Only** on B → B saves A's card; A shows nothing (B didn't send back).
- Verify the saved contact(s) appear in the phone's Contacts app.

## 2. Lock gate (privacy)
- Lock Phone A, repeat the tap. **Expected: nothing happens** (A's card is not served while locked).

## 3. Capture diagnostics (if it doesn't work)
Run on each phone over USB (or use the app's existing diagnostics upload if wired):
```
adb logcat -s NameCardHce NameCardTapReader NameCardExchangeSvc NameCardBle NameCardTransfer NameCardSaver
```
Send the lines from both phones. Key checkpoints:
- `NameCardHce: ... EXCHANGE → bootstrap ... + server FGS` (A answered the tap, started serving)
- `NameCardTapReader: ... peer resolved` (B read A's token)
- `NameCardBle: client: token match ... → connecting` / `server: advertising token` (BLE rendezvous)
- `NameCardBle: client: read peer card` / `server: received peer card` (the swap)

## Make-or-break unknowns to watch (device-only; cannot be checked in the build env)
1. **The tap triggering at all** when B has the app open and A is idle/unlocked (NFC reader↔HCE).
   If nothing fires: try with A's screen ON; confirm both NFC on; confirm B is on the main screen.
2. **BLE rendezvous** — does B find A by the token + connect? (Bluetooth on both; ~1–2 s.)
3. **Long read / MTU** for a full card (name+phone+email). Watch for a truncated/garbled card.
4. **Both phones with the app open** = both readers = won't connect (one must be idle). Expected limitation.
5. Contact save: direct (if WRITE_CONTACTS granted) vs the system Add-contact fallback.

## Known refinements (not bugs)
- Server side auto-shares its card when tapped (its "Receive Only" = just don't Save). The per-tap
  Receive Only/Share choice is on the reader (tapper) side. Refine to two-way consent later if wanted.
- `saveDirect` runs on a background thread (no ANR).
- Look/animation of the transfer screen is a first pass — tell me what to change after you see it.

---

# Name Card v2 (symmetric consent) — on-device test

**Status: compile + JVM-unit-tested only. The BLE consent transport + the whole v2 UI click-path are
DEVICE-UNVERIFIED** (no radio / no display in the build env). Only the codec + choice state machine
are unit-tested. This script proves the rest on hardware.

APK: `super-drop-debug.apk` (repo root) or
`https://204-168-163-118.sslip.io/trackers/static/super-drop-namecard-v2-debug.apk`.

## 0. Enable v2 (once per phone)
1. Install the APK on **both** phones; open Super Drop once (stopped-state → NFC intents dispatch).
2. Settings → **Name Card** → set up your card (as in the v1 script above).
3. On the same screen, turn ON **"Symmetric consent (beta)"** (new switch under the master switch).
   Accept the notification-permission prompt (so the "waiting/declined" heads-up can show).
4. Do this on **both** phones. Keep NFC on; Bluetooth is force-enabled at tap by the Radio Helper.

## 1. The tap (both phones idle)
- Both phones **awake + unlocked**, Super Drop **closed on both**.
- Tap the backs together.
- **Expected:** BOTH phones open the full-screen Name Card screen showing **their OWN** card with
  **Share** / **Receive Only** — buttons briefly disabled ("connecting") then enabled.

## 2. The four consent scenarios (run each on a fresh tap)
| # | Phone A taps | Phone B taps | Expected |
|---|---|---|---|
| 1 | **Share** | **Share** | both get the receive ripple + save each other's contact (opens Contacts) |
| 2 | **Share** | **Receive Only** | A: heads-up "waiting" → "they declined", card fades to "declined", Done. B: saves A's card |
| 3 | **Receive Only** | **Share** | A: saves B's card (ripple). B: heads-up "waiting" → "declined" screen, Done |
| 4 | **Receive Only** | **Receive Only** | both fade to "They declined to share their contact info", Done only, NO ripple |
| 5 | **Share**, other never taps | (leave 30s) | tapper: heads-up "waiting" → screen fades to "No response", Done |

## 3. Mixed-version (legacy fallback)
- Put the **v1** APK (symmetric consent OFF) on one phone, v2 on the other, tap.
- **Expected:** it still completes as a one-sided v1 receive (no crash). This path is best-effort /
  device-tuned — report exactly what happens.

## v2 make-or-break unknowns (device-only)
1. Does the both-idle tap open the screen on BOTH phones? (Phase-1 wake — the prerequisite.)
2. Does each side's Share/Receive-Only reach the other **live** over the CONSENT channel?
3. Timing: the ~1.5s post-BYE close grace and the server-side card read — watch for a truncated
   card or a link that closes a beat too early. Tell me and I'll tune the two grace constants.
4. Heads-up notification text switching "waiting" → "declined".
