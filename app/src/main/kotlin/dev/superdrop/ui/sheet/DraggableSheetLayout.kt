/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.ui.sheet

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout

/**
 * Kotlin port of the OShare bottom-sheet card container (see
 * shareit-bridge `com.bridge.share.ui.DraggableSheetLayout`). A
 * bottom-anchored card that:
 *
 *  - slides up on entrance with a small bounce (no fade),
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

        /** Slide up from below with a gentle overshoot bounce. */
        public fun playEntrance() {
            post {
                translationY = (height + paddingBottom + ENTRANCE_OFFSET_PX).toFloat()
                animate()
                    .translationY(0f)
                    .setDuration(ENTRANCE_DURATION_MS)
                    .setInterpolator(OvershootInterpolator(ENTRANCE_TENSION))
                    .start()
            }
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
            private const val ENTRANCE_DURATION_MS = 340L
            private const val ENTRANCE_TENSION = 1.1f
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
