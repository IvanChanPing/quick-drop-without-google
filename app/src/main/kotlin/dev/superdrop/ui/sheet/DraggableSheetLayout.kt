/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.ui.sheet

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import dev.superdrop.discovery.diagnostics.DiagnosticLog

/**
 * Kotlin port of the OShare bottom-sheet card container (see
 * shareit-bridge `com.bridge.share.ui.DraggableSheetLayout`). A
 * bottom-anchored card that:
 *
 *  - slides up from below the bottom edge on entrance (a VIEW-level slide in
 *    [playEntrance], pre-hidden via [prepareOffscreen] and triggered by the host
 *    AFTER the window is shown), then its TOP edge elastically stretches up and
 *    settles with the bottom planted (see [playTopElasticStretch]); the elements
 *    ride up a little but do NOT stretch — only the rounded background does,
 *  - is draggable downward and dismisses on a sufficient swipe-down,
 *  - snaps back with a bounce otherwise.
 *
 * Child buttons still receive taps — the drag only engages once the
 * pointer travels past the touch slop downward.
 */
public class DraggableSheetLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : LinearLayout(context, attrs, defStyleAttr) {
        private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawY: Float = 0f
        private var startTransY: Float = 0f
        private var dragging: Boolean = false
        private var onDismiss: (() -> Unit)? = null

        /**
         * Optional content wrapper (every visible element — the device-name pill +
         * the state frame) that is counter-scaled by the inverse of the sheet's
         * entrance-bounce stretch, about its OWN CENTRE, so the elements keep their
         * exact size (do NOT stretch) and merely ride up a little with the stretch
         * while the rounded background stretches around them. Null (default) = the
         * whole sheet (background + content) stretches together. Set via
         * [setBounceContent].
         */
        private var bounceContent: View? = null

        init {
            orientation = VERTICAL
            isClickable = true
        }

        public fun setOnDismiss(r: (() -> Unit)?) {
            this.onDismiss = r
        }

        /**
         * Provide the content wrapper to counter-scale during the entrance bounce so
         * only the sheet's rounded background stretches — the elements keep their
         * size and just ride up (see [bounceContent]). Pass null to stretch the whole
         * sheet (background + content).
         */
        public fun setBounceContent(view: View?) {
            this.bounceContent = view
        }

        /**
         * Pre-hide the sheet fully BELOW the screen, immediately, BEFORE the first
         * frame is drawn — so it is invisible until [playEntrance] slides it up (no
         * flash of the sheet at its resting position). The host calls this during view
         * setup (SendActivity.wireBottomSheet) BEFORE the window becomes visible. A
         * full screen-height translate guarantees off-screen without needing the
         * not-yet-measured sheet height; [playEntrance] then refines to a precise
         * just-below-the-edge start. ONLY the send sheet pre-hides; a caller that does
         * NOT call this (e.g. the receive sheet) leaves the sheet at rest and
         * [playEntrance] skips the slide (bounce only).
         */
        public fun prepareOffscreen() {
            translationY = resources.displayMetrics.heightPixels.toFloat()
            scaleY = 1f
        }

        /**
         * Entrance: a VIEW-level slide-up from just below the bottom edge, then the
         * top-edge bounce ([playTopElasticStretch]).
         *
         * CRITICAL TIMING: the host MUST call this AFTER the activity window has
         * finished its OPEN transition — SendActivity calls it from
         * `onEnterAnimationComplete()`. Triggering the slide earlier (in onCreate /
         * the first `doOnLayout`, while the window is still running its own open
         * animation) is what made the slide invisible on OnePlus: the view slid while
         * the window was still composing, so by the time the window was on screen the
         * sheet had already arrived — it "appeared already settled" with only a fade.
         * Run in the already-visible window, the slide is plainly visible. (The slide
         * is a [android.view.ViewPropertyAnimator]; the device's animators are ON —
         * the bounce visibly animates — so duration is not collapsed.)
         *
         * If the sheet was not pre-hidden ([prepareOffscreen] not called — e.g. the
         * receive sheet), `translationY` is 0, the slide is skipped, and only the
         * bounce runs (preserving that caller's prior behavior).
         */
        public fun playEntrance(onComplete: (() -> Unit)? = null) {
            doOnLayout {
                scaleY = 1f
                val marginBottom = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                val doSlide = translationY != 0f // pre-hidden by prepareOffscreen()
                // OBSERVABILITY: records whether the slide ran, the geometry, and the
                // device animation scale, so an on-device bug report explains a missing
                // slide. Routed through DiagnosticLog.e so it survives OEM Log filtering.
                DiagnosticLog.e(
                    DIAG_TAG,
                    "playEntrance RUN: doSlide=$doSlide height=$height curTransY=$translationY " +
                        "animDurScale=${currentAnimatorDurationScale()} animatorsEnabled=${animatorsEnabled()}",
                )
                if (doSlide) {
                    // Snap to a PRECISE just-below-the-edge start (replacing the coarse
                    // full-screen offset from prepareOffscreen — both off-screen, so no
                    // visible jump), then slide up to rest and decelerate into place.
                    translationY = (height + paddingBottom + marginBottom + ENTRANCE_OFFSET_PX).toFloat()
                    animate()
                        .translationY(0f)
                        .setDuration(ENTRANCE_SLIDE_MS)
                        .setInterpolator(DecelerateInterpolator(ENTRANCE_DECEL))
                        .start()
                    // Kick the top-edge bounce BEFORE the slide fully lands so it flows
                    // OUT of the slide's momentum as ONE continuous motion — not "slide
                    // finishes, pause, then a separate bounce" (which read as weird). The
                    // slide is decelerating (slowing into rest) and the raised-cosine
                    // bounce ramps up from zero, so the two slow phases overlap and blend;
                    // the bounce's peak lands just after the sheet settles. Tune the feel
                    // with BOUNCE_OVERLAP_MS (bigger = bounce starts earlier / more
                    // overlap). Both the slide animator and this postDelayed run on the
                    // real-time clock, so the overlap is stable.
                    postDelayed(
                        { playTopElasticStretch(onComplete) },
                        (ENTRANCE_SLIDE_MS - BOUNCE_OVERLAP_MS).coerceAtLeast(0L),
                    )
                } else {
                    // Not pre-hidden (receive sheet): no slide, just the bounce.
                    postDelayed({ playTopElasticStretch(onComplete) }, WINDOW_SETTLE_MS)
                }
            }
        }

        /**
         * The device-global `animator_duration_scale` (1.0 = normal, 0 = animations
         * off). Read from [Settings.Global]; defaults to 1 if unreadable. A value of
         * 0 means [android.view.ViewPropertyAnimator] / [ValueAnimator] durations are
         * zeroed by the platform — so the entrance bounce (a [ValueAnimator]) would
         * collapse to an instant snap. Logged by [playEntrance] for on-device
         * diagnostics; the slide itself is the window animation and is immune.
         */
        private fun currentAnimatorDurationScale(): Float =
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }.getOrDefault(1f)

        /**
         * Whether the platform currently runs animators at all. On API 26+ this is the
         * authoritative [ValueAnimator.areAnimatorsEnabled] (false when the global
         * scale is 0 OR battery-saver/reduced-motion has disabled animations); below
         * 26 we fall back to the [currentAnimatorDurationScale] check. Logged by
         * [playEntrance] so a missing bounce is explainable from a bug report.
         */
        private fun animatorsEnabled(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                currentAnimatorDurationScale() > 0f
            }

        /**
         * Entrance stage 2 — the bounce. Visual model (user-chosen): the sheet's
         * BOTTOM stays planted; the rounded card BACKGROUND stretches up at the top
         * by [ENTRANCE_TOP_EXTEND_DP] px and snaps back ([topStretchProfile] — one
         * smooth hump, no wobble). The whole sheet scales about its bottom (so the
         * rounded background stretches), and [bounceContent] (every element — the
         * device pill + the state frame) is counter-scaled by the inverse about its
         * OWN CENTRE so the elements:
         *   - do NOT stretch (the inverse scale cancels the parent stretch), and
         *   - ride UP a little with the stretch (the centre pivot — not the bottom —
         *     lets them move instead of staying planted).
         * Net: only the rounded background stretches; the elements keep their size
         * and are pinned/ride up. With no [bounceContent] set the whole sheet
         * (background + content) stretches together. Resets transforms on end.
         */
        private fun playTopElasticStretch(onComplete: (() -> Unit)? = null) {
            val h = height
            DiagnosticLog.e(DIAG_TAG, "playTopElasticStretch RUN: height=$h")
            if (h <= 0) { // not laid out / zero height — nothing to scale about
                onComplete?.invoke()
                return
            }
            pivotY = h.toFloat() // bottom edge = anchor; the TOP is free to stretch up
            val content = bounceContent
            val extendPx = ENTRANCE_TOP_EXTEND_DP * resources.displayMetrics.density
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STRETCH_DURATION_MS
                interpolator = LinearInterpolator() // the hump shape drives the motion
                addUpdateListener { a ->
                    val rise = extendPx * topStretchProfile(a.animatedFraction) // px the top extends
                    val k = 1f + rise / h // scaleY that lifts the top edge by exactly `rise`
                    scaleY = k
                    content?.let {
                        // Counter-scale about the content's OWN CENTRE: the inverse
                        // scale cancels the parent stretch (elements keep their exact
                        // size) while the centre pivot lets them ride up a little with
                        // the stretch instead of staying planted — "elements move but
                        // don't stretch; only the background stretches".
                        it.pivotY = it.height.toFloat() / 2f
                        it.scaleY = 1f / k
                    }
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        // Fires on natural end AND on cancel, so [onComplete] (e.g.
                        // revealing delayed peer icons) can never be skipped.
                        override fun onAnimationEnd(animation: Animator) {
                            scaleY = 1f
                            content?.scaleY = 1f
                            onComplete?.invoke()
                        }
                    },
                )
                start()
            }
        }

        /**
         * Single-hump stretch profile used by [playTopElasticStretch]: a raised
         * cosine `0.5 * (1 - cos(2*pi*t))` that is 0 at t=0, rises to a single peak
         * of 1 at t=0.5, and returns to 0 at t=1 — staying >= 0 the whole way AND
         * with ZERO velocity at both ends (unlike a half-sine, which starts at full
         * speed and feels jerky). One smooth extend + snap back, no wobble.
         */
        private fun topStretchProfile(t: Float): Float {
            return (0.5 * (1.0 - Math.cos(t.toDouble() * 2.0 * Math.PI))).toFloat()
        }

        /**
         * Call AFTER adding content that makes the sheet taller (e.g. the
         * first device icon). Animates the height increase as a smooth
         * slide-up with a slight overscroll settle (translationY overshoot —
         * up then settle), instead of an instant pop. Not a scale/bounce of
         * the whole card.
         */
        public fun animateGrow() {
            val before = height
            post {
                val delta = height - before
                if (delta <= 0) return@post // didn't grow
                translationY = delta.toFloat() // start at the old visual position
                animate()
                    .translationY(0f)
                    .setDuration(GROW_DURATION_MS)
                    .setInterpolator(OvershootInterpolator(GROW_TENSION))
                    .start()
            }
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    startTransY = translationY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.rawY - downRawY > touchSlop) {
                        dragging = true
                        return true
                    }
                }
            }
            return false
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = e.rawY
                    startTransY = translationY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downRawY
                    if (dy > 0) translationY = startTransY + dy
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (translationY > height * DISMISS_FRACTION) {
                        dismiss()
                    } else {
                        animate()
                            .translationY(0f)
                            .setDuration(SNAP_DURATION_MS)
                            .setInterpolator(OvershootInterpolator(SNAP_TENSION))
                            .start()
                    }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }

        /**
         * Dismiss the sheet. The slide-DOWN exit is the activity WINDOW close
         * animation ([R.style.WindowAnimation_SuperDrop_SendSheet] `slide_down_out`,
         * the reverse of the `slide_up_in` entrance), played automatically when the
         * host [finish]es from [onDismiss]. This view does NOT also translate — a
         * second, competing view slide would double the travel — it just notifies the
         * host to finish so the window close animation is the only exit motion.
         */
        public fun dismiss() {
            onDismiss?.invoke()
        }

        public companion object {
            /** DiagnosticLog tag for the entrance observability lines (#15 OnePlus
             *  slide gap). grep `SendSheetEntrance` in a bug report to see that the
             *  entrance ran, its geometry, and the device animation scale. */
            private const val DIAG_TAG = "SendSheetEntrance"

            // Stage 1 — the VIEW-level slide-up (playEntrance, when pre-hidden via
            // prepareOffscreen). Starts ENTRANCE_OFFSET_PX below the bottom edge and
            // decelerates into rest over ENTRANCE_SLIDE_MS. Triggered by the host AFTER
            // the window open transition (SendActivity.onEnterAnimationComplete) so it
            // is not masked by the window's own animation.
            private const val ENTRANCE_OFFSET_PX = 80
            private const val ENTRANCE_SLIDE_MS = 300L
            private const val ENTRANCE_DECEL = 1.4f

            // How long BEFORE the slide lands to kick the top-edge bounce so it overlaps
            // the slide's tail and reads as ONE continuous motion (not "slide ends,
            // pause, bounce"). Bigger = earlier/more overlap. Tunable.
            private const val BOUNCE_OVERLAP_MS = 110L

            // Fallback delay before the bounce on the NO-slide path (receive sheet,
            // which never calls prepareOffscreen): a brief settle so the bounce doesn't
            // fire the instant the window appears.
            private const val WINDOW_SETTLE_MS = 260L

            // Stage 2 — bottom-anchored stretch of the card's rounded BACKGROUND: the
            // top edge extends up by ENTRANCE_TOP_EXTEND_DP and snaps back, NO wobble
            // (a single smooth raised-cosine hump). The elements are counter-scaled to
            // keep their size and just ride up. A fixed dp (not a % of the tall card)
            // so the extend is small + consistent. STRETCH_DURATION_MS = its length.
            private const val ENTRANCE_TOP_EXTEND_DP = 16f
            private const val STRETCH_DURATION_MS = 260L

            /** Total wall-time of the entrance (slide + bounce). Callers use it to time
             *  a follow-on reveal (e.g. delaying the peer icons until the entrance has
             *  finished). Measured from when playEntrance is triggered, NOT from
             *  onCreate (the host adds its own pre-entrance delay). */
            public const val ENTRANCE_TOTAL_MS: Long = ENTRANCE_SLIDE_MS + STRETCH_DURATION_MS

            private const val GROW_DURATION_MS = 420L
            private const val GROW_TENSION = 1.4f
            private const val SNAP_DURATION_MS = 220L
            private const val SNAP_TENSION = 1.0f
            private const val DISMISS_FRACTION = 0.28f

            /**
             * Edge-to-edge: pad the sheet's bottom by the navigation-bar
             * inset so its content sits above the nav buttons, keeping
             * [baseBottomPx] as the design padding.
             */
            @JvmStatic
            public fun applyBottomInset(
                root: View,
                sheet: DraggableSheetLayout,
                baseBottomPx: Int,
            ) {
                root.setOnApplyWindowInsetsListener { _, insets ->
                    val navBottom =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                        } else {
                            @Suppress("DEPRECATION")
                            insets.systemWindowInsetBottom
                        }
                    sheet.setPadding(
                        sheet.paddingLeft,
                        sheet.paddingTop,
                        sheet.paddingRight,
                        baseBottomPx + navBottom,
                    )
                    insets
                }
                root.requestApplyInsets()
            }
        }
    }
