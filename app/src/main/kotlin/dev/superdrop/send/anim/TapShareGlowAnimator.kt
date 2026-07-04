/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.send.anim

import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView

/**
 * **Tap-to-share glow** — the concrete [TapShareAnimator] that renders the Google
 * Contact-Exchange edge glow ([GoogleContactGlow]) for the file-share tap, in three
 * parts driven by the tap lifecycle:
 *
 *  - [begin]  → **START**: `active=true, connected=false` — the light ignites at
 *    top-center and sweeps down both screen edges, then idle-pulses while waiting.
 *  - [onTransferStarted] → **MATCH**: `connected=true, loopMode=true` — the streak
 *    races to the bottom (the "connected" completion). Fired when the receiver accepts.
 *  - [onTransferFailed]  → **NO-MATCH**: `connected=true, loopMode=false` — the sweep
 *    retracts and fades to nothing (dissipate). Fired on any non-success end.
 *
 * What it looks like: a symmetric blue edge light tracing the phone's rounded-rect
 * border (top-center → down both sides → bottom-center). START = ignite + sweep;
 * MATCH = race to the bottom and hold; NO-MATCH = fade out.
 *
 * Hosted in a full-screen [ComposeView] added to the controller's overlay (the send
 * sheet's window is full-screen translucent, so the edge glow spans the whole screen).
 * `dimBehind=false` so it never touches the host activity's window flags. State is held
 * in Compose [mutableStateOf] and flipped from the controller's UI-thread callbacks.
 *
 * Assigned via `TapShareAnimationController(root, TapShareGlowAnimator())`.
 */
class TapShareGlowAnimator : TapShareAnimator {
    // active = opening sweep on; connected = phase two requested; loopMode = MATCH (true) vs NO-MATCH exit (false).
    private val activeState = mutableStateOf(false)
    private val connectedState = mutableStateOf(false)
    private val loopModeState = mutableStateOf(true)
    private var composeView: ComposeView? = null

    /** START — ignite the sweep and idle-pulse while awaiting the outcome. */
    override fun begin(overlay: FrameLayout) {
        loopModeState.value = true
        connectedState.value = false
        activeState.value = true
        ensureComposeView(overlay)
    }

    /** MATCH — receiver accepted: streak races to the bottom and holds. */
    override fun onTransferStarted(overlay: FrameLayout) {
        ensureComposeView(overlay)
        loopModeState.value = true
        connectedState.value = true
    }

    /** NO-MATCH — non-success end: the sweep retracts and fades out. */
    override fun onTransferFailed(overlay: FrameLayout) {
        ensureComposeView(overlay)
        loopModeState.value = false
        connectedState.value = true
    }

    /** Tear down: clear the glow and drop the ComposeView (Compose disposes on detach). */
    override fun cancel(overlay: FrameLayout) {
        activeState.value = false
        connectedState.value = false
        composeView?.let { overlay.removeView(it) }
        composeView = null
    }

    private fun ensureComposeView(overlay: FrameLayout) {
        if (composeView != null) return
        val cv =
            ComposeView(overlay.context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                setContent {
                    GoogleContactGlow(
                        active = activeState.value,
                        connected = connectedState.value,
                        loopMode = loopModeState.value,
                        // Never touch the host activity's window (the send sheet has its own dim).
                        dimBehind = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        overlay.addView(cv)
        composeView = cv
    }
}
