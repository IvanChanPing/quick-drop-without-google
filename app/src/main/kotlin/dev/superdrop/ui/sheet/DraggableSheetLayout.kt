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
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout

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

        init {
            orientation = VERTICAL
            isClickable = true
        }

        public fun setOnDismiss(r: (() -> Unit)?) {
            this.onDismiss = r
        }

        /**
         * Entrance animation. Two stages, by design:
         *  1. The WHOLE sheet slides up from below and LANDS cleanly —
         *     [DecelerateInterpolator], NO whole-card overshoot (this is the
         *     change from the old [OvershootInterpolator] slide, which bounced
         *     the entire card up and down past its resting spot).
         *  2. Anchored at the sheet's BOTTOM edge, the TOP of the sheet then
         *     stretches up and elastically settles — an over-scroll / rubber-band
         *     feel where the bottom stays planted and only the top extends.
         *     See [playTopElasticStretch].
         */
        public fun playEntrance(onComplete: (() -> Unit)? = null) {
            post {
                translationY = (height + paddingBottom + ENTRANCE_OFFSET_PX).toFloat()
                scaleY = 1f
                animate()
                    .translationY(0f)
                    .setDuration(ENTRANCE_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator(ENTRANCE_DECEL))
                    .withEndAction { playTopElasticStretch(onComplete) }
                    .start()
            }
        }

        /**
         * Stage 2 of the entrance: a bottom-anchored stretch of the TOP edge.
         * [pivotY] is set to the sheet's bottom so scaling Y leaves the bottom
         * planted and lets the top extend up, then snap straight back to rest
         * via [topStretchProfile] — a SINGLE smooth hump (extend + snap back),
         * NO wobble / no bounce. Tune with [ENTRANCE_STRETCH] (how far the top
         * extends) and [STRETCH_DURATION_MS] (shorter = snappier). Resets
         * [scaleY] to 1 on completion.
         */
        private fun playTopElasticStretch(onComplete: (() -> Unit)? = null) {
            pivotY = height.toFloat() // bottom edge = anchor; top is free to stretch
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = STRETCH_DURATION_MS
                interpolator = LinearInterpolator() // the hump shape drives the motion
                addUpdateListener { a ->
                    scaleY = 1f + ENTRANCE_STRETCH * topStretchProfile(a.animatedFraction)
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        // Fires on natural end AND on cancel, so [onComplete] (e.g.
                        // revealing delayed peer icons) can never be skipped.
                        override fun onAnimationEnd(animation: Animator) {
                            scaleY = 1f
                            onComplete?.invoke()
                        }
                    },
                )
                start()
            }
        }

        /**
         * Single-hump stretch profile used by [playTopElasticStretch]: a half-sine
         * that is 0 at t=0, rises to a single peak of 1 at t=0.5, and returns to 0
         * at t=1 — staying >= 0 the whole way, so the top extends up once and snaps
         * back to rest with NO wobble and NO dip below rest (not a bounce/spring).
         */
        private fun topStretchProfile(t: Float): Float {
            return Math.sin(t.toDouble() * Math.PI).toFloat()
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

            // Stage 1 — whole-sheet slide-up (no overshoot; it lands clean).
            private const val ENTRANCE_DURATION_MS = 300L
            private const val ENTRANCE_DECEL = 1.6f

            // Stage 2 — bottom-anchored stretch of the TOP edge: it extends up
            // ONCE and snaps back to rest, NO wobble / no bounce (a single smooth
            // hump that never dips below rest). ENTRANCE_STRETCH = peak scaleY delta
            // (~4.5% top stretch); raise for a bigger extend. STRETCH_DURATION_MS =
            // how long the extend+snap takes (shorter = snappier).
            private const val ENTRANCE_STRETCH = 0.045f
            private const val STRETCH_DURATION_MS = 360L

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
