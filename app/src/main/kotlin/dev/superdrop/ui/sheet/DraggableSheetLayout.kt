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
         * Optional content view that is counter-scaled (about its own centre)
         * during the entrance bounce so it does NOT stretch but still RIDES with
         * the card; the rounded background does the visible stretching. Null
         * (default) = the whole sheet (background + content) scales together.
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
         * Entrance animation. Two stages that OVERLAP so they read as one motion:
         *  1. The WHOLE sheet slides up from below the bottom of the screen and
         *     LANDS cleanly — [DecelerateInterpolator] (variable speed), NO
         *     whole-card overshoot.
         *  2. Starting just BEFORE the slide ends (so the bounce continues the
         *     slide's momentum rather than pausing), the card's rounded BACKGROUND
         *     stretches up at the top and snaps back — one smooth hump, no wobble.
         *     The content rides with it but does not stretch (see [bounceContent]).
         *     See [playTopElasticStretch].
         */
        public fun playEntrance(onComplete: (() -> Unit)? = null) {
            // doOnLayout guarantees a valid measured [height] before we compute the
            // start offset. A bare post{} can run while height is still 0, which made
            // the sheet start only a few px low and look like it FADED in near its
            // resting spot instead of sliding up from the bottom of the screen.
            doOnLayout {
                val marginBottom = (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                // Whole sheet starts just below the bottom edge of the screen.
                translationY = (height + paddingBottom + marginBottom + ENTRANCE_OFFSET_PX).toFloat()
                scaleY = 1f
                animate()
                    .translationY(0f)
                    .setDuration(ENTRANCE_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator(ENTRANCE_DECEL))
                    .start()
                // Kick the top stretch BEFORE the slide finishes (overlap its tail)
                // so the bounce flows continuously out of the slide's momentum — not
                // "slide stops, pause, then bounce". Negative/short delays clamp to 0.
                postDelayed(
                    { playTopElasticStretch(onComplete) },
                    (ENTRANCE_DURATION_MS - BOUNCE_OVERLAP_MS).coerceAtLeast(0L),
                )
            }
        }

        /**
         * Stage 2 of the entrance: a bottom-anchored stretch of the card's rounded
         * BACKGROUND. [pivotY] is the sheet's bottom so the bottom stays planted and
         * the TOP extends up, then returns via [topStretchProfile] — a SINGLE smooth
         * hump (extend + snap back, NO wobble), started overlapping the slide so it
         * feels continuous. When [bounceContent] is set it is counter-scaled by the
         * inverse about its OWN CENTRE: that cancels the STRETCH (content keeps its
         * size, doesn't distort) while still letting the content RIDE up/down with
         * the bounce (it stays "attached" to the card). Tune with [ENTRANCE_STRETCH]
         * and [STRETCH_DURATION_MS]. Resets scales on end.
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
                    // Inverse-scale about the content's own centre: removes the
                    // vertical stretch (no distortion) but, because the parent scale
                    // still moves the content's position, it rides up/down with the
                    // bounce — "moves with it but doesn't stretch".
                    content?.let {
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

        public fun dismiss() {
            animate()
                .translationY((height + paddingBottom + ENTRANCE_OFFSET_PX).toFloat())
                .setDuration(DISMISS_DURATION_MS)
                .withEndAction { onDismiss?.invoke() }
                .start()
        }

        public companion object {
            private const val ENTRANCE_OFFSET_PX = 80

            // Stage 1 — whole-sheet slide-up from below the screen, decelerating to
            // a clean landing (variable speed; the gentler decelerate the user
            // preferred over a sharper ease).
            private const val ENTRANCE_DURATION_MS = 300L
            private const val ENTRANCE_DECEL = 1.6f

            // Stage 2 — bottom-anchored stretch of the card's rounded BACKGROUND:
            // the top extends up ONCE and snaps back, NO wobble / no dip below rest
            // (a single smooth raised-cosine hump). The content is counter-scaled
            // about its own centre so it RIDES with the bounce but does NOT stretch.
            // ENTRANCE_STRETCH = peak scaleY delta (~4.5%); STRETCH_DURATION_MS =
            // how long the extend+snap takes (shorter = snappier / feels more real).
            private const val ENTRANCE_STRETCH = 0.045f
            private const val STRETCH_DURATION_MS = 260L

            // How long BEFORE the slide ends to kick the bounce, so it flows out of
            // the slide's momentum instead of "slide stops, pause, then bounce".
            private const val BOUNCE_OVERLAP_MS = 90L

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
