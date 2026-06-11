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
import android.view.Choreographer
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
 *  - slides up on entrance and lands clean, then its TOP edge elastically
 *    stretches up and settles with the bottom planted (no fade),
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
         * Optional body view that is counter-scaled about its BOTTOM during the
         * entrance bounce so it stays perfectly planted and does NOT stretch while
         * the rounded background stretches around it. Null (default) = the whole
         * sheet (background + content) scales together. Set via [setBounceContent].
         */
        private var bounceContent: View? = null

        /**
         * Optional view pinned to the TOP of the card (the device-name pill) that
         * is translated up during the bounce so it stays glued to the stretching
         * top edge instead of being left behind. Set via [setBounceTopRider].
         */
        private var bounceTopRider: View? = null

        init {
            orientation = VERTICAL
            isClickable = true
        }

        public fun setOnDismiss(r: (() -> Unit)?) {
            this.onDismiss = r
        }

        /**
         * Provide the content wrapper to counter-scale during the entrance bounce
         * so only the sheet's rounded background stretches (the icons/text stay
         * put). Pass null to bounce the whole sheet (background + content).
         */
        public fun setBounceContent(view: View?) {
            this.bounceContent = view
        }

        /**
         * Provide the top-pinned view (the device-name pill) that should ride up
         * with the stretching top edge during the entrance bounce so it stays glued
         * to the top of the card. Pass null to leave it where it is.
         */
        public fun setBounceTopRider(view: View?) {
            this.bounceTopRider = view
        }

        /**
         * Entrance: ONE view-level motion. (1) The whole sheet slides up from below
         * the bottom of the screen and lands ([DecelerateInterpolator]); the activity
         * window's own open animation is a no-op so this is the only slide and it is
         * actually visible/verifiable. (2) Overlapping the slide's tail
         * ([BOUNCE_OVERLAP_MS] before it ends, so it flows out of the slide rather
         * than pausing) the card's rounded BACKGROUND stretches up at the top and
         * snaps back — one smooth hump, no wobble; the body stays planted and the
         * pill rides the top edge (see [playTopElasticStretch]).
         */
        public fun playEntrance(onComplete: (() -> Unit)? = null) {
            // The slide is a VIEW-level translate (the window's own open animation is
            // a no-op, see Theme.SuperDrop.SendSheet) so there is ONE slide and it is
            // actually visible/verifiable. doOnLayout guarantees a real [height] so the
            // start offset truly puts the sheet below the screen.
            doOnLayout {
                val marginBottom = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                // Start the whole sheet just below the bottom edge of the screen.
                val startTransY = (height + paddingBottom + marginBottom + ENTRANCE_OFFSET_PX).toFloat()
                translationY = startTransY
                scaleY = 1f

                // OBSERVABILITY (#15 OnePlus slide gap): record the exact geometry +
                // the global animation scale so an on-device bug report reveals whether
                // the view slide actually ran, what distance it travelled, and whether
                // the OEM/dev-option "remove animations" setting collapsed it. Without
                // this the OnePlus failure ("fix is in the APK but slide not visible")
                // is invisible in logs. Routed through DiagnosticLog.e so it survives
                // OxygenOS/Funtouch's Log.i filtering and lands in the on-disk ring.
                val animScale = currentAnimatorDurationScale()
                val animatorsOff = !animatorsEnabled()
                DiagnosticLog.e(
                    DIAG_TAG,
                    "playEntrance RUN: height=$height padBottom=$paddingBottom " +
                        "marginBottom=$marginBottom startTransY=$startTransY " +
                        "animDurScale=$animScale animatorsEnabled=${!animatorsOff}",
                )

                if (animatorsOff || animScale <= 0f) {
                    // The user has animations disabled (OnePlus "Remove animations" /
                    // developer-option animator_duration_scale = 0 / reduced-motion).
                    // ViewPropertyAnimator AND ValueAnimator are BOTH multiplied by that
                    // scale, so animate().setDuration(300) would collapse to an instant
                    // jump and the slide would NOT be visible — which is the exact
                    // OnePlus symptom (fix present, slide invisible). Drive the slide off
                    // the Choreographer frame clock instead, which is NOT subject to
                    // animator_duration_scale, so the slide ALWAYS plays. The bounce is
                    // skipped here (it is a scale animator and would also be zeroed; the
                    // slide is the important, requested motion).
                    DiagnosticLog.e(
                        DIAG_TAG,
                        "playEntrance: animators disabled -> Choreographer slide fallback",
                    )
                    slideWithChoreographer(startTransY) {
                        // Land clean, then reveal — no bounce when motion is disabled.
                        translationY = 0f
                        onComplete?.invoke()
                    }
                    return@doOnLayout
                }

                animate()
                    .translationY(0f)
                    .setDuration(ENTRANCE_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator(ENTRANCE_DECEL))
                    .start()
                // Kick the bounce just BEFORE the slide lands so it flows out of the
                // slide's momentum (not "slide stops, pause, then bounce").
                postDelayed(
                    { playTopElasticStretch(onComplete) },
                    (ENTRANCE_DURATION_MS - BOUNCE_OVERLAP_MS).coerceAtLeast(0L),
                )
            }
        }

        /**
         * Animation-scale-proof slide used by [playEntrance] when the device has
         * animations disabled (OnePlus "Remove animations" / developer-option
         * `animator_duration_scale` = 0 / reduced-motion). Both
         * [android.view.ViewPropertyAnimator] and [ValueAnimator] are multiplied by
         * that global scale by the platform, so they would collapse to an instant
         * jump and the entrance slide would never be visible. The [Choreographer]
         * vsync frame clock is NOT scaled, so interpolating [translationY] off the
         * raw frame timestamps guarantees the slide actually plays over
         * [ENTRANCE_DURATION_MS] regardless of the user's animation setting. Bails
         * cleanly (snaps to 0 + invokes [onEnd]) if the view detaches mid-slide so
         * no frame callback fires against a dead view.
         */
        private fun slideWithChoreographer(
            startTransY: Float,
            onEnd: () -> Unit,
        ) {
            val interpolator = DecelerateInterpolator(ENTRANCE_DECEL)
            val durationNanos = ENTRANCE_DURATION_MS * 1_000_000.0
            val startNanos = System.nanoTime()
            val choreographer = Choreographer.getInstance()
            val callback =
                object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!isAttachedToWindow) {
                            translationY = 0f
                            onEnd()
                            return
                        }
                        val raw = ((frameTimeNanos - startNanos) / durationNanos).toFloat()
                        val t = raw.coerceIn(0f, 1f)
                        val eased = interpolator.getInterpolation(t)
                        translationY = startTransY * (1f - eased)
                        if (t >= 1f) {
                            translationY = 0f
                            onEnd()
                        } else {
                            choreographer.postFrameCallback(this)
                        }
                    }
                }
            choreographer.postFrameCallback(callback)
        }

        /**
         * The device-global `animator_duration_scale` (1.0 = normal, 0 = animations
         * off). Read from [Settings.Global]; defaults to 1 if unreadable. A value of
         * 0 means [android.view.ViewPropertyAnimator] / [ValueAnimator] durations are
         * zeroed by the platform — the trigger for the [slideWithChoreographer]
         * fallback in [playEntrance].
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
         * 26 we fall back to the [currentAnimatorDurationScale] check.
         */
        private fun animatorsEnabled(): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                currentAnimatorDurationScale() > 0f
            }

        /**
         * Stage 2 of the entrance — the bounce. Visual model (user-chosen): the
         * BOTTOM stays planted, the card's rounded TOP edge extends up by a fixed
         * [ENTRANCE_TOP_EXTEND_DP] pixels and snaps back ([topStretchProfile] — one
         * smooth hump, no wobble), and:
         *   - the rounded BACKGROUND does that stretch (sheet [scaleY] about the
         *     bottom, so the top rises by exactly `rise` px and the bottom holds);
         *   - [bounceContent] (the body) is counter-scaled about its BOTTOM so it
         *     stays planted and does NOT stretch;
         *   - [bounceTopRider] (the device pill) is translated up by `rise` so it
         *     stays GLUED to the top edge.
         * Net: only the empty card area between the pill and the body stretches;
         * nothing distorts. Kicked overlapping the window slide so it feels
         * continuous. Resets all transforms on end.
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
            val topRider = bounceTopRider
            val extendPx = ENTRANCE_TOP_EXTEND_DP * resources.displayMetrics.density
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STRETCH_DURATION_MS
                interpolator = LinearInterpolator() // the hump shape drives the motion
                addUpdateListener { a ->
                    val rise = extendPx * topStretchProfile(a.animatedFraction) // px the top extends
                    val k = 1f + rise / h // scaleY that lifts the top edge by exactly `rise`
                    scaleY = k
                    content?.let {
                        // Counter-scale about the sheet's bottom (content-local: its
                        // own height + the sheet's bottom padding) => body stays put.
                        it.pivotY = it.height.toFloat() + paddingBottom
                        it.scaleY = 1f / k
                    }
                    topRider?.translationY = -rise // pill follows the top edge up
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        // Fires on natural end AND on cancel, so [onComplete] (e.g.
                        // revealing delayed peer icons) can never be skipped.
                        override fun onAnimationEnd(animation: Animator) {
                            scaleY = 1f
                            content?.scaleY = 1f
                            topRider?.translationY = 0f
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

        public fun dismiss() {
            animate()
                .translationY((height + paddingBottom + ENTRANCE_OFFSET_PX).toFloat())
                .setDuration(DISMISS_DURATION_MS)
                .withEndAction { onDismiss?.invoke() }
                .start()
        }

        public companion object {
            /** DiagnosticLog tag for the entrance-slide observability lines (#15
             *  OnePlus slide gap). grep `SendSheetEntrance` in a bug report to see
             *  whether the view slide ran, its geometry, and the device animation
             *  scale. */
            private const val DIAG_TAG = "SendSheetEntrance"

            private const val ENTRANCE_OFFSET_PX = 80

            // Stage 1 — VIEW-level slide-up from below the screen (the window's open
            // animation is a no-op, so this is the only slide). Decelerates to a clean
            // landing. The bounce is kicked BOUNCE_OVERLAP_MS before this ends so it
            // flows out of the slide (not "slide stops, pause, then bounce").
            private const val ENTRANCE_DURATION_MS = 300L
            private const val ENTRANCE_DECEL = 1.6f
            private const val BOUNCE_OVERLAP_MS = 90L

            // Stage 2 — bottom-anchored stretch of the card's rounded BACKGROUND: the
            // top edge extends up by ENTRANCE_TOP_EXTEND_DP and snaps back, NO wobble
            // (a single smooth raised-cosine hump). Body counter-scaled to stay
            // planted; the pill rides the top edge. A fixed dp (not a % of the tall
            // card) so the extend is small + consistent. STRETCH_DURATION_MS = length.
            private const val ENTRANCE_TOP_EXTEND_DP = 16f
            private const val STRETCH_DURATION_MS = 260L

            /** Total wall-time of the entrance (slide + bounce). Callers use it to
             *  time a follow-on reveal (e.g. delaying the peer icons until the
             *  entrance has finished). */
            public const val ENTRANCE_TOTAL_MS: Long = ENTRANCE_DURATION_MS + STRETCH_DURATION_MS

            private const val GROW_DURATION_MS = 420L
            private const val GROW_TENSION = 1.4f
            private const val SNAP_DURATION_MS = 220L
            private const val SNAP_TENSION = 1.0f
            private const val DISMISS_DURATION_MS = 200L
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
