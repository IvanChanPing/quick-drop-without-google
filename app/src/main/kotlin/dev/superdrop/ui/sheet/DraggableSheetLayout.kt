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
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.view.doOnLayout

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
                translationY = (height + paddingBottom + marginBottom + ENTRANCE_OFFSET_PX).toFloat()
                scaleY = 1f
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
