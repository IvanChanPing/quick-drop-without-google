/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.ui.sheet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of the OShare receive-card progress bar (see shareit-bridge
 * `com.bridge.share.ui.RoundedProgressBar`): a pill-shaped light track
 * with a blue rounded fill that animates smoothly between values. Nicer
 * than the stock [android.widget.ProgressBar] for the Quick-Share look,
 * and used by the receive bottom sheet's "Receiving…" panel.
 */
public class RoundedProgressBar
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        /** Currently-drawn fill fraction, 0..1. Animated by [setProgress]. */
        private var fraction: Float = 0f
        private var animator: ValueAnimator? = null

        init {
            trackPaint.color = TRACK
            fillPaint.color = FILL
        }

        /** Animate to the given percent (0..100). */
        public fun setProgress(percent: Int) {
            val target = max(0, min(100, percent)) / 100f
            animator?.cancel()
            animator =
                ValueAnimator.ofFloat(fraction, target).apply {
                    duration = ANIM_DURATION_MS
                    addUpdateListener { a ->
                        fraction = a.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
        }

        override fun onMeasure(
            widthSpec: Int,
            heightSpec: Int,
        ) {
            val h = (BAR_HEIGHT_DP * resources.displayMetrics.density).toInt()
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), h)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            rect.set(0f, 0f, w, h)
            c.drawRoundRect(rect, r, r, trackPaint)
            // At least a dot so the bar reads as "started" even at 0%.
            val fw = max(h, w * fraction)
            rect.set(0f, 0f, fw, h)
            c.drawRoundRect(rect, r, r, fillPaint)
        }

        public companion object {
            private const val TRACK = 0x22000000
            private const val FILL = 0xFF0A84FF.toInt()
            private const val BAR_HEIGHT_DP = 6
            private const val ANIM_DURATION_MS = 180L
        }
    }
