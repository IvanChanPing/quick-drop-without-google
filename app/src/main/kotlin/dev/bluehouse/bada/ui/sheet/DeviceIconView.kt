/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui.sheet

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One discovered device in the
 * send sheet, rendered as a circular [RingProgressView] icon, the device
 * name below it, and a status line ("Connecting", "Sending"). Tapping
 * plays a bounce so the user sees the tap registered.
 *
 * [peerId] carries the discovered peer's stable identity so the host can
 * route this chip's click back through the unchanged selection path.
 */
public class DeviceIconView(
    context: Context,
    public val peerId: String,
    name: String,
) : LinearLayout(context) {
    private val ring: RingProgressView
    private val nameView: TextView

    // badaBadge — a small rounded "Bada" pill (white text on a translucent
    // Bada blue fill, ~10sp, centred just under the device name). Shown ONLY when
    // the discovered peer advertises the Bada marker TLV (see [setBada]); GONE
    // for stock Quick Share / Samsung peers. Lets the user tell a Bada device apart
    // from an "original" Quick Share device at a glance in the send picker.
    private val badaBadge: TextView
    private val statusView: TextView

    init {
        val d = context.resources.displayMetrics.density
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = (8 * d).toInt()
        setPadding(pad, pad, pad, pad)

        ring = RingProgressView(context)
        val size = (64 * d).toInt()
        addView(ring, LayoutParams(size, size))

        nameView =
            TextView(context).apply {
                text = name
                setTextColor(NAME_COLOR)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, (6 * d).toInt(), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        addView(nameView, LayoutParams((96 * d).toInt(), LayoutParams.WRAP_CONTENT))

        badaBadge =
            TextView(context).apply {
                text = SUPER_DROP_BADGE_TEXT
                setTextColor(BADGE_TEXT_COLOR)
                textSize = 10f
                gravity = Gravity.CENTER
                val hPad = (6 * d).toInt()
                val vPad = (1 * d).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                background =
                    GradientDrawable().apply {
                        cornerRadius = 8 * d
                        setColor(BADGE_FILL_COLOR)
                    }
                visibility = View.GONE
            }
        addView(
            badaBadge,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * d).toInt()
            },
        )

        statusView =
            TextView(context).apply {
                setTextColor(STATUS_COLOR)
                textSize = 11f
                gravity = Gravity.CENTER
            }
        addView(statusView)
    }

    /** Bounce the icon to acknowledge a tap. */
    public fun bounce() {
        ring
            .animate()
            .scaleX(BOUNCE_DOWN_SCALE)
            .scaleY(BOUNCE_DOWN_SCALE)
            .setDuration(BOUNCE_DOWN_MS)
            .withEndAction {
                ring
                    .animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(OvershootInterpolator(BOUNCE_TENSION))
                    .setDuration(BOUNCE_UP_MS)
                    .start()
            }.start()
    }

    /** Transfer complete: icon morphs to a green check, with a little bounce. */
    public fun complete() {
        ring.setComplete()
        bounce()
    }

    public fun setStatus(status: String?) {
        statusView.text = status ?: ""
    }

    public fun setProgress(percent: Int) {
        ring.setProgress(percent)
    }

    public fun setName(name: String) {
        nameView.text = name
    }

    /**
     * Show or hide the "Bada" badge under the device name. Called by the
     * send picker with `true` when the discovered peer advertises the Bada
     * marker TLV (`EndpointInfo.hasBadaMarker()`), `false` for stock peers.
     * Cheap visibility toggle — safe to call on every render.
     */
    public fun setBada(isBada: Boolean) {
        badaBadge.visibility = if (isBada) View.VISIBLE else View.GONE
    }

    public companion object {
        private const val NAME_COLOR = 0xFF111114.toInt()
        private const val STATUS_COLOR = 0xFF0A84FF.toInt()
        private const val SUPER_DROP_BADGE_TEXT = "Bada"

        // Badge pill: white text on a solid Bada blue (#0A84FF) fill.
        private const val BADGE_TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val BADGE_FILL_COLOR = 0xFF0A84FF.toInt()
        private const val BOUNCE_DOWN_SCALE = 0.82f
        private const val BOUNCE_DOWN_MS = 90L
        private const val BOUNCE_UP_MS = 320L
        private const val BOUNCE_TENSION = 3.5f
    }
}
