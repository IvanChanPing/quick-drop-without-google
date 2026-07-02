# Issue #248 — Sender ignores receiver's cancel during payload streaming (PLAN / sketch)

## CURRENT STATE / NEXT STEP
- **Goal:** make the SENDER stop and surface `Cancelled(PEER)` when the RECEIVER cancels mid-transfer, instead of streaming the whole file and reporting 100%/success.
- **State (VERIFIED 2026-07-01):** root cause pinned from code + the reporter's `outbound.log`. This is the unfixed **mirror** of a bug we already fixed on the inbound side. No code changed yet — this is a design sketch only.
- **NEXT STEP:** before writing code, **capture what a stock Quick Share receiver actually sends on cancel** (Disconnection frame? a Sharing `CANCEL` BYTES payload? just a socket half-close?) — that determines which detection branch is load-bearing. Then implement Option B (robust concurrent reader) or, if time-boxed, Option A (targeted, BLE→Wi-Fi-Direct path only).

---

## Problem (VERIFIED)
Report #248 (kyujin-cho/Bada): "cancel receiving on the other device → Bada keeps sending, shows successful after 100%." Reproduced from the reporter's attached `outbound.log`: a 233 MB send that upgraded to Wi-Fi Direct to a native **Quick Share device (MN3H)** ran to `streamOneFile loop end … bytesSent=233606120` → `DONE` → `all files streamed, sending Disconnection`, i.e. the receiver's cancel was never observed. Not device-specific; a logic/concurrency gap.

## Root cause (VERIFIED, file: `core-protocol/.../connection/OutboundConnectionDriver.kt`)
- `streamOneFile` (`:1430`) writes all chunks in a tight loop and, between chunks, only polls the **local** user-cancel: `externalEvents.tryReceive()` → `UserCancel` (`:1458-1461`). It never reads inbound frames from the peer.
- Two streaming entry points, both funnel through `streamFilesAndComplete` → `streamOneFile`, and **neither reads the peer during the byte loop**:
  1. **Normal dispatch path** (`handleBytesComplete` → `streamFilesAndComplete`, `:1347-1348`): called synchronously *inside* `dispatchLoop`, so the dispatch loop is blocked for the whole send. The inbound pump (`runInboundPump`, `:603/:1112`) keeps reading but parks on the **RENDEZVOUS** `wireChannel` (`:602`) because nobody is consuming — the peer CANCEL sits undelivered until streaming ends.
  2. **BLE → Wi-Fi-Direct path** (`:783-812`): calls `streamFilesAndComplete` inside its own `coroutineScope` with **only a keep-alive ticker — no inbound pump at all** during streaming. *(This is the reporter's failing path.)*
- Docstring at `:1358-1361` states the intent plainly: "the dispatch loop is suspended while we run, but `externalEvents` is polled on each chunk so a `cancel()` call still emits a CANCEL frame." — i.e. only *local* cancel was ever designed in.

## We already fixed the MIRROR (inbound side) — reuse the shape
The RECEIVER honoring the SENDER's cancel mid-receive works, because the inbound side is event-driven (pump → dispatch, not blocked by a write loop):
- `InboundConnectionDriver.handleInboundOfflineFrame` (`:496`):
  - `frame.isDisconnection()` → if not all announced items received → `publishCancelled(CancelCause.PEER)` / `InboundResult.Cancelled(PEER)` (`:501-530`).
  - `PAYLOAD_TRANSFER` → `assembler.onPayloadTransfer` → `BytesComplete` → `SharingFrames.parse` → FSM handles `CANCEL` (`:545-598`).
  - ignores `KEEP_ALIVE` / other frames (`:545-551`).
- Keep-alive ticker rationale for long transfers: `:388-398`.

The outbound driver **already has the equivalent reader** — it's just not called during streaming:
- `handleBleWifiDirectInboundFrame` (`:872`): `isDisconnection()` → `cancelFromPeer()` (→ `Cancelled(PEER)`, `:1556`); `PAYLOAD_TRANSFER` → `inboundAssembler.onPayloadTransfer` → `BytesComplete` → `SharingFrames.parse` → FSM (`handlePeerCancel` on `CANCEL`, `OutboundSharingFsm.kt:100-101,222-224`).
- Non-blocking peek already exists and is used: `channel.hasBufferedInput()` + `channel.receiveOfflineFrame()` in `pollBleWifiDirectOffer` (`:972-973`), which also shows the KEEP_ALIVE-ignore pattern (`:978-980`).

So the building blocks (peer-frame → cancel decision, non-blocking peek, `inboundAssembler`, `cancelFromPeer()`) are all present.

---

## The subtlety that decides the fix (VERIFIED)
The two streaming paths have **different concurrency models**, so a naïve "just read the channel in `streamOneFile`" is wrong:
- **BLE→Wi-Fi-Direct path:** no pump during streaming → `streamOneFile` **can** safely poll `channel.hasBufferedInput()` / `receiveOfflineFrame()` directly. (Reporter's path.)
- **Normal dispatch path:** the pump IS reading the same socket concurrently → `streamOneFile` must **not** read the channel directly (double-read race); it would have to consume from `wireChannel` instead.

This is why a single uniform edit isn't a 3-liner. Pick one:

### Option A — Targeted, minimal (fixes the reported case only)
- In `streamOneFile`, between chunks, add a peer-cancel poll **guarded to the no-pump (BLE→Wi-Fi-Direct) path** (pass a `readPeerFrames: Boolean` or a `peerFrameSource` into `streamFilesAndComplete`/`streamOneFile`).
- When enabled: `if (channel.hasBufferedInput()) { val f = channel.receiveOfflineFrame(); … }` → reuse `handleBleWifiDirectInboundFrame`'s logic (Disconnection → `cancelFromPeer()`; PAYLOAD_TRANSFER→assembler→`SharingFrames.parse`→CANCEL → `Cancelled(PEER)`; ignore KEEP_ALIVE). On cancel, early-return an `OutboundResult` up through `streamFilesAndComplete`.
- **Pros:** small, low-risk, doesn't touch the working normal-path completion. **Cons:** normal path (Bluetooth-classic / LAN without WD upgrade) still can't detect a receiver cancel mid-stream. Coverage note MUST be logged, not silently narrowed.

### Option B — Robust, unified (recommended, "more robust" per Mike)
- Make BOTH paths stream **concurrently with an inbound reader coroutine** (mirror the inbound driver's pump/dispatch split). Structure:
  - `coroutineScope { launch { peerReader } ; streamJob = launch { writeChunks } ; select/await first terminal }`.
  - The peer reader runs the existing `handleBleWifiDirectInboundFrame`-style pipeline; on peer CANCEL / Disconnection it cancels the write job and yields `Cancelled(PEER)`.
  - On the normal path, feed the reader from the existing `wireChannel` (pump output) instead of the raw channel, so there's still exactly one socket reader.
- **Pros:** one model, both paths covered, matches the inbound design. **Cons:** more care around cancellation/teardown, the RENDEZVOUS back-pressure, the safe-disconnect drain (`shouldDrainForSafeDisconnect`, `:637`) and not racing the keep-alive ticker's shared send-mutex.

### Option C — Heartbeat / liveness (leave open, complementary)
- Independent of frame parsing: if the receiver dies/leaves without a clean CANCEL, detect it via **missing inbound KEEP_ALIVE** within a window (stock sends KEEP_ALIVE ~every 10 s; see inbound ticker rationale `:388-398` and the outbound ticker `:604-617`). If no keep-alive/ack for N seconds during a long stream → abort as `Cancelled(PEER)`/`Failed`.
- **Why keep it open:** covers abrupt disappearance (Wi-Fi Direct link drop, app kill) that a CANCEL-frame check alone won't catch. Pairs with B.

---

## OPEN INVESTIGATION (do FIRST) — what does Quick Share actually do on receiver-cancel?
We must not jam a fix into a guessed slot (map-first rule). Capture, from a real stock Quick Share receiver cancelling mid-transfer, which of these the sender sees:
1. a **Disconnection** OfflineFrame (clean), or
2. a Sharing **CANCEL** frame carried as a PAYLOAD_TRANSFER BYTES payload, or
3. just a **socket half-close / RST** (no frame), or
4. **nothing** until keep-alive timeout.
- How: instrument a build to log every inbound frame type during streaming (temporary), or read the reporter's transport if the zip carries enough, or drive two devices. The answer decides whether Option A/B's frame branch is enough or whether Option C (heartbeat) is required for the reported Quick-Share case specifically.
- The reporter's log ends with the SENDER sending Disconnection after DONE — it does **not** show what the receiver sent, so this is genuinely unknown right now.

## Risks / regressions to guard (pre-build risk pass)
- **Don't misread a normal completion as a cancel** — the happy path (`DONE` → sender-initiated Disconnection + safe-disconnect drain) must be untouched.
- **One socket reader only** — never let `streamOneFile` and the pump both call `receiveOfflineFrame()` on the same channel (normal path).
- **KEEP_ALIVE inline** — must be ignored during any poll (see `:978-980`), or it'll be misparsed.
- **Post-Wi-Fi-Direct channel swap** — `hasBufferedInput()`/reads must target the *active* (upgraded) channel, not the original TCP socket.
- **Teardown** — on peer cancel, still send our Disconnection appropriately and don't hang the safe-disconnect drain (`cancelFromPeer` deliberately doesn't drain, `:634-636`).

## Test plan (on-device, 2 phones — cannot be verified in this env)
1. Send a large file; **cancel on the receiver at ~50%** → sender must stop promptly and show **Cancelled**, not 100%/success. Try both a Bada receiver and a **stock Quick Share** receiver.
2. Force each transport: Bluetooth-classic, LAN, and **BLE→Wi-Fi-Direct** (the reported path).
3. **Regression:** a normal, *non-cancelled* transfer of the same file still completes and shows success on both ends.
4. Abrupt-disappearance (if doing Option C): turn off the receiver's Wi-Fi mid-transfer → sender aborts within the heartbeat window.

## Status
Diagnosis VERIFIED (code + reporter log). Any code written from this plan is **compile-only until the 2-device tests above pass** — the failing path is real-device BLE→Wi-Fi-Direct and can't be exercised here.
