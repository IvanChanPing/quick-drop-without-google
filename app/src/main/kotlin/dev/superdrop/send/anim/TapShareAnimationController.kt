/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.send.anim

import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Owns the **tap-to-share animation** lifecycle and the overlay it draws into,
 * so both [dev.superdrop.send.SendActivity] and
 * [dev.superdrop.send.SendActivityInApp] drive the SAME three-part sequence
 * through one object instead of duplicating branch logic (the two send
 * activities are independent `AppCompatActivity` subclasses with no shared
 * base, each with its own copy of the tap flow).
 *
 * The activities call the SEMANTIC entry points at the exact points where they
 * already know the outcome (they own the `OutboundConnectionState` FSM, incl.
 * the retry/fallback distinction that decides whether a `Failed` is terminal):
 *
 *  - [onTapCommitted] — the phone was tapped to the peer. Arms the sequence and
 *    plays Part 1 ([TapShareAnimator.begin]). Because arming happens ONLY here,
 *    a regular (non-tap) send never triggers the branches even though the
 *    terminal/started callbacks below are reached by every send.
 *  - [onTransferStarted] — the receiver accepted; payload sending began. Part 2.
 *  - [onTransferFailed] — any non-success end. Part 3.
 *
 * [onTransferStarted] / [onTransferFailed] each fire at most once per tap
 * (whichever lands first), so a mid-retry failure, a repeated terminal
 * callback, or a post-success completion callback can't double-play.
 *
 * The [animator] is swappable and defaults to [NoOpTapShareAnimator], so the
 * flow is byte-for-byte unchanged until a real drop-in animation (e.g. the
 * AirDrop-style glow) is assigned.
 *
 * Threading: all entry points must be called on the UI thread (the activities
 * already marshal the NFC binder-thread callbacks via `runOnUiThread`). No
 * blocking work is done here — only view add/remove — so there is no ANR risk.
 *
 * Drop in a real animation by assigning [animator]; until then the default
 * no-op leaves the tap flow visually identical.
 */
class TapShareAnimationController(
    private val overlayParent: ViewGroup,
    var animator: TapShareAnimator = NoOpTapShareAnimator,
) {
    private var armed = false
    private var branched = false
    private var overlay: FrameLayout? = null

    /** Part 1 — phone tapped to the peer. Arms the sequence and plays the intro. */
    fun onTapCommitted() {
        teardownOverlay()
        armed = true
        branched = false
        animator.begin(ensureOverlay())
    }

    /** Part 2 (success) — receiver accepted, payload sending started. One-shot; tap-gated. */
    fun onTransferStarted() {
        if (!armed || branched) return
        branched = true
        animator.onTransferStarted(ensureOverlay())
    }

    /** Part 3 (failure) — declined / errored / unreachable / cancelled. One-shot; tap-gated. */
    fun onTransferFailed() {
        if (!armed || branched) return
        branched = true
        animator.onTransferFailed(ensureOverlay())
    }

    /** Tear the sequence + overlay down (new share, activity stop/destroy). Safe anytime. */
    fun reset() {
        armed = false
        branched = false
        overlay?.let { animator.cancel(it) }
        teardownOverlay()
    }

    private fun ensureOverlay(): FrameLayout {
        overlay?.let { return it }
        val o =
            FrameLayout(overlayParent.context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                // Passive full-screen canvas — must never intercept touches meant for the sheet.
                isClickable = false
                isFocusable = false
            }
        // Added last => drawn on top of the send card.
        overlayParent.addView(o)
        overlay = o
        return o
    }

    private fun teardownOverlay() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }
}
