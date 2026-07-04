/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.send.anim

import android.widget.FrameLayout

/**
 * Drop-in animation contract for the **NFC tap-to-share** file-send flow.
 *
 * The sequence is one animation with a fork, fired by
 * [TapShareAnimationController] at the exact lifecycle points below. It runs
 * ONLY for a tap-initiated send (phone tapped to the peer while the share
 * sheet is open) — never for a peer-icon pick or a QR auto-connect:
 *
 *  1. [begin] — **Part 1**. Plays the instant the phone is tapped to the peer
 *     (the "tap happened" cue), while the outbound connection is being set up.
 *  2. [onTransferStarted] — **Part 2 (success branch)**. Plays when the
 *     receiver ACCEPTS and the payload actually starts sending.
 *  3. [onTransferFailed] — **Part 3 (failure branch)**. Plays on any
 *     non-success end: the receiver declined, the connection/transfer errored,
 *     the tapped receiver could not be reached, or the send was cancelled.
 *
 * [cancel] tears any in-flight animation down (a new tap, or activity teardown).
 *
 * Each call receives the full-screen, non-touchable [overlay] the controller
 * owns and mounts over the send card; draw into it, and the controller removes
 * it on [cancel] / the next tap. Exactly one of [onTransferStarted] /
 * [onTransferFailed] fires per tap (whichever outcome lands first).
 *
 * The default [NoOpTapShareAnimator] does nothing, so the tap flow is visually
 * unchanged until a real animator (e.g. the AirDrop-style glow) is dropped in
 * by assigning [TapShareAnimationController.animator].
 */
interface TapShareAnimator {
    /** Part 1 — the phone was tapped to the peer; play the intro cue. */
    fun begin(overlay: FrameLayout)

    /** Part 2 — the receiver accepted and the payload started sending. */
    fun onTransferStarted(overlay: FrameLayout)

    /** Part 3 — the send ended without success (declined / error / unreachable / cancelled). */
    fun onTransferFailed(overlay: FrameLayout)

    /** Tear down any in-flight animation and release the overlay. */
    fun cancel(overlay: FrameLayout)
}

/**
 * No-op default — the tap flow renders exactly as before until a real animator
 * is assigned to [TapShareAnimationController.animator].
 */
object NoOpTapShareAnimator : TapShareAnimator {
    override fun begin(overlay: FrameLayout) = Unit

    override fun onTransferStarted(overlay: FrameLayout) = Unit

    override fun onTransferFailed(overlay: FrameLayout) = Unit

    override fun cancel(overlay: FrameLayout) = Unit
}
