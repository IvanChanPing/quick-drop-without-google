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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.view.animation.PathInterpolatorCompat

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
         * Optional content view that is counter-scaled during the entrance bounce
         * so ONLY the sheet's rounded background stretches and the content stays
         * put. Null (default) = the whole sheet (background + content) bounces.
         * Set via [setBounceContent].
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
         * Provide the content wrapper to counter-scale during the entrance bounce
         * so only the sheet's rounded background stretches (the icons/text stay
         * put). Pass null to bounce the whole sheet (background + content).
         */
        public fun setBounceContent(view: View?) {
            this.bounceContent = view
        }

        /**
         * Entrance animation. Two stages, by design:
         *  1. The WHOLE sheet slides up from below the bottom of the screen and
         *     LANDS cleanly — iOS-style ease-out ([PathInterpolatorCompat], variable
         *     speed), NO whole-card overshoot (this is the change from the old
         *     [OvershootInterpolator] slide, which bounced the entire card up and
         *     down past its resting spot).
         *  2. Anchored at the sheet's BOTTOM edge, only the rounded BACKGROUND then
         *     stretches up and snaps back (the content is counter-scaled to stay
         *     put). A smooth single hump — no wobble, not jerky. See
         *     [playTopElasticStretch].
         */
        public fun playEntrance(onComplete: (() -> Unit)? = null) {
            post {
                // Start fully BELOW the bottom of the screen (own height + bottom
                // padding/inset + a margin) so it visibly slides up into place.
                translationY = (height + paddingBottom + ENTRANCE_OFFSET_PX).toFloat()
                scaleY = 1f
                animate()
                    .translationY(0f)
                    .setDuration(ENTRANCE_DURATION_MS)
                    // iOS-style ease-out: fast start, long gentle settle (variable
                    // speed) rather than a constant glide.
                    .setInterpolator(PathInterpolatorCompat.create(0.16f, 1f, 0.3f, 1f))
                    .withEndAction { playTopElasticStretch(onComplete) }
                    .start()
            }
        }

        /**
         * Stage 2 of the entrance: a bottom-anchored stretch of the TOP edge that
         * affects ONLY the sheet's rounded background. [pivotY] is the sheet's
         * bottom so scaling Y leaves the bottom planted and the top extends up,
         * then returns to rest via [topStretchProfile] — a SINGLE smooth hump
         * (extend + snap back, NO wobble / no dip below rest), with zero velocity
         * at both ends so the hand-off from the slide and the settle are not jerky.
         * When [bounceContent] is set, it is counter-scaled by the inverse about
         * the SAME bottom pivot, so the content (icons/text) does NOT move — only
         * the card surface stretches. Tune with [ENTRANCE_STRETCH] (extend amount)
         * and [STRETCH_DURATION_MS] (shorter = snappier). Resets scales on end.
         */
        private fun playTopElasticStretch(onComplete: (() -> Unit)? = null) {
            pivotY = height.toFloat() // bottom edge = anchor; top is free to stretch
            val content = bounceContent
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STRETCH_DURATION_MS
                interpolator = LinearInterpolator() // the hump shape drives the motion
                addUpdateListener { a ->
                    val k = 1f + ENTRANCE_STRETCH * topStretchProfile(a.animatedFraction)
                    scaleY = k
                    // Counter-scale the content about the same screen point as the
                    // sheet's bottom pivot (content-local: its own height + the
                    // sheet's bottom padding). k * (1/k) about one point = identity,
                    // so the content stays put while the background stretches.
                    content?.let {
                        it.pivotY = it.height.toFloat() + paddingBottom
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

        public fun dismiss() {
            animate()
                .translationY((height + paddingBottom + ENTRANCE_OFFSET_PX).toFloat())
                .setDuration(DISMISS_DURATION_MS)
                .withEndAction { onDismiss?.invoke() }
                .start()
        }

        public companion object {
            private const val ENTRANCE_OFFSET_PX = 80

            // Stage 1 — whole-sheet slide-up from below the screen, iOS-style
            // ease-out (variable speed, set in [playEntrance]); lands clean.
            private const val ENTRANCE_DURATION_MS = 300L

            // Stage 2 — bottom-anchored stretch of ONLY the card surface (content
            // counter-scaled): extends up ONCE and snaps back, NO wobble / no dip
            // below rest (a single smooth raised-cosine hump with zero velocity at
            // both ends so it is not jerky). ENTRANCE_STRETCH = peak scaleY delta
            // (~4.5% top stretch); raise for a bigger extend. STRETCH_DURATION_MS =
            // how long the extend+snap takes (shorter = snappier / feels more real).
            private const val ENTRANCE_STRETCH = 0.045f
            private const val STRETCH_DURATION_MS = 260L

            /** Total wall-time of the entrance (slide + top stretch). Callers use
             *  it to time a follow-on reveal (e.g. delaying the peer icons until the
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
