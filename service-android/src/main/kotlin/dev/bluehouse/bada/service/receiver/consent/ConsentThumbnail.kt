/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver.consent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/**
 * ConsentThumbnail — generates the small placeholder "photo" bitmap shown on the
 * RIGHT side of the incoming-transfer consent notification
 * ([R.layout.notification_consent], the RECOLORED default style).
 *
 * ### Why a generated placeholder
 *
 * The consent prompt fires BEFORE the transfer happens, so no real file preview
 * exists yet ([ConsentRegistry.Entry] carries no bitmap). A neutral grey
 * "image" glyph (rounded rectangle + sun + mountains) communicates "a file is
 * coming" without implying a specific preview. Drawing it with [Canvas] works
 * everywhere `RemoteViews.setImageViewBitmap` needs a [Bitmap] and sidesteps the
 * historical "vector drawable inside RemoteViews" rendering pitfalls — no asset
 * files, no density folders.
 *
 * Bound at runtime in [ConsentNotification.build].
 */
public object ConsentThumbnail {
    /** Pixel size of the square right-side thumbnail bitmap. */
    public const val THUMB_PX: Int = 132

    /** Brand-blue tint for the notification (colours the left small-icon circle). */
    public const val LEFT_ICON_TINT: Int = 0xFF0A84FF.toInt()

    /**
     * A rounded grey "photo" placeholder: light-grey rounded rectangle with a
     * sun circle and two mountain triangles — the universal "image" glyph.
     */
    @Suppress("MagicNumber")
    public fun photo(
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val radius = min(w, h) * 0.14f

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD7DBE2") }
        c.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, bg)

        val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB9C0CC") }
        c.drawCircle(w * 0.72f, h * 0.30f, min(w, h) * 0.12f, sun)

        val rock = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF99A1AE") }
        c.drawPath(
            Path().apply {
                moveTo(w * -0.02f, h * 0.98f)
                lineTo(w * 0.34f, h * 0.46f)
                lineTo(w * 0.60f, h * 0.98f)
                close()
            },
            rock,
        )
        c.drawPath(
            Path().apply {
                moveTo(w * 0.40f, h * 0.98f)
                lineTo(w * 0.70f, h * 0.56f)
                lineTo(w * 1.02f, h * 0.98f)
                close()
            },
            rock,
        )
        return bmp
    }
}
