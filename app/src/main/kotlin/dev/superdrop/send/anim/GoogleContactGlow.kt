/*
 * GoogleContactGlow — a faithful, self-contained recreation of the "Google glow": the bezel
 * edge-light animation shown by Google Play Services' Contact Exchange / "Gesture Exchange"
 * (Android's NameDrop clone) when two phones are tapped together. Reverse-engineered verbatim
 * from GMS 25.49.31 (base.apk / classes6.dex, package com.google.android.gms.gestureexchange:
 * obfuscated classes bwbu/bwav/bwat/bwbo/bwbs/bwbq/bwbr; durations from ibwo /
 * GestureexchangeConfig__* flags).
 *
 * WHAT IT LOOKS LIKE: a symmetric light that ignites at the TOP-CENTER of the screen and sweeps
 * DOWN both side edges, tracing the phone's rounded-rectangle (real device corner radii + 16dp
 * inset). Each edge draws two layers: a soft blurred halo (16dp stroke, 16dp blur) and a crisp
 * "comet" (8dp round-cap stroke) whose leading head is solid and whose 30dp tail fades to
 * transparent. On "connected" the streak's head races to the bottom in 433ms while its tail
 * follows over 1510ms (stretch-then-contract). The background dims to 0.6 behind it; a heavy-click
 * haptic fires on start and on connect. (Original also plays two short SoundPool cues — optional
 * here; those .ogg assets are Google's and are NOT bundled.)
 *
 * All timings/sizes/colors are constructor knobs (defaults = the exact GMS values) so a tester app
 * can tweak them live. Pure Jetpack Compose + one android.graphics.Paint for the blur halo.
 *
 * Compose deps: androidx.compose.ui, foundation, animation-core, runtime. minSdk 31 for
 * getRoundedCorner()/VibratorManager (falls back gracefully below 31).
 */
package dev.superdrop.send.anim

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BlurMaskFilter
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The custom "shoot-and-hold" easing (GMS bwat.a): reaches 1.0 by ~25% of the duration, then holds. */
val GlowEasingShoot: Easing = PathEasing(
    Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0.0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1.0f, 1.0f, 1.0f)
    }
)

/** The emphasized decelerate easing (GMS bwat.b) used for the fast leading edge. */
val GlowEasingEmphasized: Easing = CubicBezierEasing(0.3f, 0.0f, 0.1f, 1.0f)

/**
 * @param active       start the opening sweep (top-center → down both edges).
 * @param connected    trigger phase two (loop stretch or exit).
 * @param loopMode      true = GMS "mode -1" steady loop (head+tail slide to bottom); false = exit to 0.
 * @param glowColor    streak/halo color. GMS default = a theme ColorScheme slot; Google's blue used here.
 */
@Composable
fun GoogleContactGlow(
    active: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
    loopMode: Boolean = true,
    glowColor: Color = Color(0xFF8AB4F8),
    edgeInsetDp: Float = 16f,
    cometStrokeDp: Float = 8f,
    haloStrokeDp: Float = 16f,
    haloBlurDp: Float = 16f,
    tailLengthDp: Float = 30f,
    partialFraction: Float = 0.3f,
    dimBehind: Boolean = true,
    dimAmount: Float = 0.6f,
    haptics: Boolean = true,
    drawHalo: Boolean = true,   // the soft blurred halo layer (toggle to check BlurMaskFilter rendering)
    drawComet: Boolean = true,  // the crisp comet layer (toggle to isolate which layer renders on-device)
    neonParams: NeonParams = NeonParams(),   // live-tunable brightness/blur/width of the neon light passes
    pulseEnabled: Boolean = true,            // gentle "breathing" pulse of the glow WHILE WAITING (idle) — ON by default
    pulseAmount: Float = 0.20f,              // halo expand/contract depth at the trough (0 = none) — user-tuned
    pulsePeriodMs: Int = 4160,               // pulse period ms (higher = slower) — the "speed" control — user-tuned
    waveCount: Float = 3f,                   // variation: max independent oscillation rate → more = more random/chaotic twinkle
    // exact GMS default durations (ms):
    startDelayMs: Int = 300,
    openDurationMs: Int = 1992,
    openPauseMs: Int = 166,
    loopHeadDurationMs: Int = 433,
    loopTailDurationMs: Int = 1510,
    exitCancelDurationMs: Int = 1992,
    onOpeningAnimationFinished: () -> Unit = {},
    onAnimationFinished: () -> Unit = {},
) {
    val view = LocalView.current
    val density = LocalDensity.current

    // Four animated 0..1 drivers (GMS dasVar6/3/4/5).
    val glowAlpha = remember { Animatable(0f) }   // halo alpha; snapped to 1 on open, →0 on finish
    val openProgress = remember { Animatable(0f) } // opening sweep head 0→1
    val loopHead = remember { Animatable(0f) }     // steady leading edge (433ms / emphasized)
    val loopTail = remember { Animatable(0f) }     // steady trailing edge (1510ms / shoot)
    val idlePulse = remember { Animatable(0f) }    // 0↔1 breathing while waiting (idle pulse)

    // Neon light buffer — renders the lit edge segment as REAL light: several BlurMaskFilter passes
    // (wide+faint → tight+bright) plus a near-white HOT CORE on top, into an offscreen SOFTWARE bitmap
    // (BlurMaskFilter always renders on a software canvas — dodges the hardware-canvas "just a line"),
    // then composites that buffer. This is what makes it read as emitted light, not a blurred stroke.
    val neon = remember { NeonBuffer() }
    DisposableEffect(Unit) { onDispose { neon.recycle() } }

    // ---- OPENING (GMS bwbo): delay → dim + haptic → snap alpha → sweep → pause → callback ----
    // The drivers persist across recompositions, so every phase must (re)initialise its OWN animators and
    // the "off" state must actively clear them — otherwise a button's result depends on leftover values from
    // the previous path (START not re-animating, MATCH collapsing to nothing, RESET leaving the glow on screen).
    LaunchedEffect(active) {
        if (!active) {
            // RESET / pre-start: zero every driver so NOTHING is shown (was: last frame stayed on screen).
            openProgress.snapTo(0f); loopHead.snapTo(0f); loopTail.snapTo(0f)
            glowAlpha.snapTo(0f); idlePulse.snapTo(0f)
            clearWindowDim(view)
            return@LaunchedEffect
        }
        // Fresh opening: reset the progress drivers FIRST so the sweep always replays from the top regardless
        // of prior state (fixes "START sometimes doesn't play the entrance animation").
        openProgress.snapTo(0f); loopHead.snapTo(0f); loopTail.snapTo(0f)
        delay(startDelayMs.toLong())
        if (dimBehind) setWindowDim(view, dimAmount)
        if (haptics) heavyClick(view)
        glowAlpha.snapTo(1f)
        openProgress.animateTo(1f, tween(openDurationMs, easing = GlowEasingShoot))
        delay(openPauseMs.toLong())
        onOpeningAnimationFinished()
    }

    // ---- FINISH / LOOP (GMS bwbs): haptic → if loop: slide head+tail; else exit to 0 → callback ----
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        if (haptics) heavyClick(view)
        if (loopMode) {
            if (dimBehind) clearWindowDim(view)
            loopHead.snapTo(0f); loopTail.snapTo(0f)   // start the slide from the top (fixes "MATCH sometimes just disappears")
            launch { loopTail.animateTo(1f, tween(loopTailDurationMs, easing = GlowEasingShoot)) }
            launch { loopHead.animateTo(1f, tween(loopHeadDurationMs, easing = GlowEasingEmphasized)) }
        } else {
            openProgress.animateTo(0f, tween(exitCancelDurationMs, easing = GlowEasingShoot))
            glowAlpha.animateTo(0f, tween(exitCancelDurationMs, easing = GlowEasingShoot))
            if (dimBehind) clearWindowDim(view)
            onAnimationFinished()
        }
    }

    // ---- IDLE PULSE: gentle breathing of the glow while WAITING (opened, not yet connected). ----
    // NOTE: the real GMS glow does NOT do this — it opens then holds static; this is an addition.
    // Driven FRAME-BY-FRAME (not an infiniteRepeatable tween): idlePulse is advanced by dt/period each frame,
    // where `period` is read LIVE via rememberUpdatedState. This means the "Pulse speed ms" slider changes the
    // tempo INSTANTLY (next frame) with NO effect restart and NO re-wait — the old code keyed the effect on
    // pulsePeriodMs, so every speed tweak cancelled the pulse and re-ran the ~2.46s startup delay (→ speed
    // slider "unreliable / never the slow speed", and pulseAmount not updating because no frames were drawn).
    val periodState = rememberUpdatedState(pulsePeriodMs)   // latest speed value, without re-keying the effect
    LaunchedEffect(active, connected, pulseEnabled) {        // NOTE: pulsePeriodMs deliberately NOT a key
        idlePulse.snapTo(0f)
        if (active && !connected && pulseEnabled) {
            delay((startDelayMs + openDurationMs + openPauseMs).toLong())   // wait until the opening sweep settles
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (last != 0L) {
                    val dtMs = (now - last) / 1_000_000f
                    val period = periodState.value.coerceAtLeast(200).toFloat()   // live "Pulse speed ms"
                    var v = idlePulse.value + dtMs / period                        // advance phase 0..1/period
                    v -= kotlin.math.floor(v)                                      // wrap to [0,1) → seamless loop
                    idlePulse.snapTo(v)
                }
                last = now
            }
        }
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val inset = edgeInsetDp.dp.toPx()
        val (tl, tr, bl, br) = deviceCornerRadiiPx(view, density, inset)
        val cometPx = cometStrokeDp.dp.toPx()
        val tailPx = tailLengthDp.dp.toPx()
        val haloExtendPx = cometPx  // GMS extends the halo sub-path by ~one stroke width

        // Right full: center-top → right edge → bottom-center (rounded TR, BR).
        val rightFull = Path().apply {
            moveTo(w / 2f, 0f); lineTo(w - tr, 0f); quadraticBezierTo(w, 0f, w, tr)
            lineTo(w, h - br); quadraticBezierTo(w, h, w - br, h); lineTo(w / 2f, h)
        }
        // Left full: center-top → left edge → bottom-center (rounded TL, BL).
        val leftFull = Path().apply {
            moveTo(w / 2f, 0f); lineTo(tl, 0f); quadraticBezierTo(0f, 0f, 0f, tl)
            lineTo(0f, h - bl); quadraticBezierTo(0f, h, bl, h); lineTo(w / 2f, h)
        }
        // Partials = top `partialFraction` only (rounded top corner).
        val rightPartial = Path().apply {
            moveTo(w / 2f, 0f); lineTo(w - tr, 0f); quadraticBezierTo(w, 0f, w, tr); lineTo(w, h * partialFraction)
        }
        val leftPartial = Path().apply {
            moveTo(w / 2f, 0f); lineTo(tl, 0f); quadraticBezierTo(0f, 0f, 0f, tl); lineTo(0f, h * partialFraction)
        }

        val rFullLen = rightFull.measureLength()
        val lFullLen = leftFull.measureLength()
        val rPartLen = rightPartial.measureLength()
        val lPartLen = leftPartial.measureLength()

        // Lit window [start,end] along each edge.
        val opening = !connected || !loopMode
        val (rStart, rEnd) = window(opening, openProgress.value, loopHead.value, loopTail.value, rFullLen, rPartLen)
        val (lStart, lEnd) = window(opening, openProgress.value, loopHead.value, loopTail.value, lFullLen, lPartLen)

        // 1) Neon light: multi-pass blur bloom + hot white core (offscreen software bitmap), alpha = glowAlpha.
        if (drawHalo) {
            neon.render(
                this, glowColor, glowAlpha.value, cometPx, haloStrokeDp.dp.toPx(), haloBlurDp.dp.toPx(), neonParams,
                if (pulseEnabled) pulseAmount else 0f, waveCount, idlePulse.value,
                rightFull, rStart, (rEnd + haloExtendPx).coerceAtMost(rFullLen),
                leftFull, lStart, (lEnd + haloExtendPx).coerceAtMost(lFullLen),
            )
        }

        // 2) Crisp comet: solid head + gradient tail.
        if (drawComet) {
            drawAnimatedPath(rightFull, rStart, rEnd, tailPx, cometPx, glowColor)
            drawAnimatedPath(leftFull, lStart, lEnd, tailPx, cometPx, glowColor)
        }
    }
}

/** GMS window(): opening grows 0→partial; steady slides tail(full) and head(partial→full). */
private fun window(
    opening: Boolean, open: Float, head: Float, tail: Float, fullLen: Float, partLen: Float
): Pair<Float, Float> =
    if (opening) 0f to (open * partLen)
    else (tail * fullLen) to (partLen + head * (fullLen - partLen))

/** GMS drawAnimatedPath (bwbu.c): solid head [start,d] + gradient tail [d,end] color→transparent. */
private fun DrawScope.drawAnimatedPath(
    full: Path, start: Float, end: Float, tailPx: Float, strokePx: Float, color: Color
) {
    if (end <= start) return
    val pm = android.graphics.PathMeasure(full.asAndroidPathCompat(), false)
    val len = pm.length
    val d = (end - (len - end).coerceIn(0f, tailPx)).coerceAtLeast(start)

    val head = android.graphics.Path()
    pm.getSegment(start, d, head, true)
    drawPath(head.toComposePath(), color, style = Stroke(strokePx, cap = StrokeCap.Butt))

    if (d < end) {
        val tail = android.graphics.Path()
        pm.getSegment(d, end, tail, true)
        val p0 = FloatArray(2); val p1 = FloatArray(2)
        pm.getPosTan(d, p0, null); pm.getPosTan(end, p1, null)
        val brush = Brush.linearGradient(
            listOf(color, Color.Transparent),
            start = Offset(p0[0], p0[1]), end = Offset(p1[0], p1[1])
        )
        drawPath(tail.toComposePath(), brush, style = Stroke(strokePx, cap = StrokeCap.Butt))
    }
}

private fun DrawScope.drawIntoCanvasHalo(
    full: Path, start: Float, end: Float, paint: android.graphics.Paint
) {
    if (end <= start) return
    val pm = android.graphics.PathMeasure(full.asAndroidPathCompat(), false)
    val seg = android.graphics.Path()
    pm.getSegment(start, end, seg, true)
    drawContext.canvas.nativeCanvas.drawPath(seg, paint)
}

// ---- small helpers ----

private fun Path.measureLength(): Float =
    android.graphics.PathMeasure(this.asAndroidPathCompat(), false).length

/** Device rounded-corner radii (px) + inset; TL/TR/BL/BR. Falls back to `inset` below API 31. */
private data class Radii(val tl: Float, val tr: Float, val bl: Float, val br: Float)
private operator fun Radii.component1() = tl
private operator fun Radii.component2() = tr
private operator fun Radii.component3() = bl
private operator fun Radii.component4() = br

private fun deviceCornerRadiiPx(view: View, density: Density, insetPx: Float): Radii {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return Radii(insetPx, insetPx, insetPx, insetPx)
    }
    val insets = view.rootWindowInsets
    fun r(pos: Int): Float =
        ((insets?.getRoundedCorner(pos)?.radius) ?: 0).toFloat() + insetPx
    return Radii(
        tl = r(RoundedCorner.POSITION_TOP_LEFT),
        tr = r(RoundedCorner.POSITION_TOP_RIGHT),
        bl = r(RoundedCorner.POSITION_BOTTOM_LEFT),
        br = r(RoundedCorner.POSITION_BOTTOM_RIGHT),
    )
}

private fun setWindowDim(view: View, amount: Float) {
    (findActivity(view)?.window)?.apply {
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes.apply { dimAmount = amount }
    }
}
private fun clearWindowDim(view: View) {
    (findActivity(view)?.window)?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
}
private fun findActivity(view: View): Activity? {
    var ctx: Context? = view.context
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
private fun heavyClick(view: View) {
    val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (view.context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }
}

// Compose<->Android Path bridges — the real androidx extensions.
private fun Path.asAndroidPathCompat(): android.graphics.Path = this.asAndroidPath()
private fun android.graphics.Path.toComposePath(): Path = this.asComposePath()

/**
 * NeonParams — live-tunable knobs for the four neon light passes (fed from GlowConfig / glow-config.json).
 * additive = accumulate passes to a white-hot core; *Alpha = opacity of each pass; *W = stroke width as a
 * multiple of the comet stroke; *Blur = BlurMaskFilter radius as a multiple of the base blur; *WhiteMix =
 * how far the inner/core colour is pushed toward white (1 = pure white-hot core).
 */
data class NeonParams(
    val additive: Boolean = true,
    val opaqueBase: Boolean = false,   // false = TRANSPARENT buffer (translucent halo edges); true = opaque black base
    val compositeMode: Int = 0,        // final composite: 0 = SRC_OVER, 1 = PorterDuff.ADD, 2 = BlendMode.PLUS
    val outerAlpha: Float = 0.85f,
    val midAlpha: Float = 1f,
    val innerAlpha: Float = 1f,
    val coreAlpha: Float = 1f,
    val outerW: Float = 3.5f,
    val midW: Float = 2.2f,
    val innerW: Float = 1.3f,
    val coreW: Float = 0.9f,
    val outerBlur: Float = 1.6f,
    val midBlur: Float = 0.8f,
    val innerBlur: Float = 0.4f,
    val innerWhiteMix: Float = 0.85f,
    val coreWhiteMix: Float = 1f,
)

/**
 * NeonBuffer — turns the lit edge segment into REAL emitted light (not a blurred stroke).
 * Renders, into a reused offscreen SOFTWARE bitmap (BlurMaskFilter always renders on a software
 * canvas — this is the fix for the "just a line, no glow" hardware-canvas quirk), FOUR stacked
 * strokes of the same path: a wide faint outer bloom → a medium glow → a tight bright inner glow
 * (near-white) → a thin no-blur near-WHITE HOT CORE on top. Wide+faint under narrow+bright + a
 * white core = the neon/edge-light look (bright hot center, colored luminous falloff). The finished
 * light buffer is composited onto the Compose canvas in one drawBitmap. `blurPx` = the "Glow blur dp"
 * slider (outer-bloom radius); `coreWidthPx` = the comet stroke width (core = 0.6× of it).
 */
private class NeonBuffer {
    private var bmp: android.graphics.Bitmap? = null
    private var buf: android.graphics.Canvas? = null
    private var bw = 0
    private var bh = 0
    private fun stroke() = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val outer = stroke()   // widest, faintest bloom
    private val mid = stroke()     // medium glow
    private val inner = stroke()   // tight bright inner glow (near-white)
    private val core = stroke()    // thin near-white hot core (no blur)
    private val seg = android.graphics.Path()
    // MAX-OPAQUE composite: the buffer is now an OPAQUE black bitmap (erased below), so draw it with plain
    // SRC_OVER (no xfermode) → the whole glow layer is fully opaque, never see-through.
    private val comp = android.graphics.Paint()

    fun recycle() { bmp?.recycle(); bmp = null; buf = null }

    fun render(
        ds: DrawScope, color: Color, alpha: Float, coreWidthPx: Float, haloWidthPx: Float, blurPx: Float, np: NeonParams,
        waveAmount: Float, waveCount: Float, wavePhase: Float,
        rFull: Path, rStart: Float, rEnd: Float,
        lFull: Path, lStart: Float, lEnd: Float,
    ) {
        if (alpha <= 0f) return
        val iw = ds.size.width.toInt()
        val ih = ds.size.height.toInt()
        if (iw <= 0 || ih <= 0) return
        if (bmp == null || bw != iw || bh != ih) {
            bmp?.recycle()
            bmp = android.graphics.Bitmap.createBitmap(iw, ih, android.graphics.Bitmap.Config.ARGB_8888)
            buf = android.graphics.Canvas(bmp!!)
            bw = iw; bh = ih
        }
        val c = buf ?: return
        // Base fill: opaque black (fully non-transparent) OR transparent (halo keeps its natural alpha falloff).
        if (np.opaqueBase) bmp!!.eraseColor(android.graphics.Color.BLACK)
        else c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        // Final-composite blend mode (lets you compare SRC_OVER vs PorterDuff.ADD vs BlendMode.PLUS live).
        comp.xfermode = null
        comp.blendMode = android.graphics.BlendMode.SRC_OVER
        when (np.compositeMode) {
            1 -> comp.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
            2 -> comp.blendMode = android.graphics.BlendMode.PLUS
        }

        val warm = color.toArgb()
        val innerC = androidx.compose.ui.graphics.lerp(color, Color.White, np.innerWhiteMix).toArgb()
        val coreC = androidx.compose.ui.graphics.lerp(color, Color.White, np.coreWhiteMix).toArgb()
        val b = if (blurPx < 1f) 1f else blurPx
        // ADDITIVE (PorterDuff.ADD): passes ADD light so overlaps accumulate to a white-hot core (= LIGHT).
        val add = if (np.additive) android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD) else null
        fun aa(f: Float) = (alpha * f * 255f).toInt().coerceIn(0, 255)
        outer.color = warm; outer.alpha = aa(np.outerAlpha); outer.strokeWidth = haloWidthPx * np.outerW
        outer.maskFilter = android.graphics.BlurMaskFilter(b * np.outerBlur, android.graphics.BlurMaskFilter.Blur.NORMAL); outer.xfermode = add
        mid.color = warm; mid.alpha = aa(np.midAlpha); mid.strokeWidth = haloWidthPx * np.midW
        mid.maskFilter = android.graphics.BlurMaskFilter(b * np.midBlur, android.graphics.BlurMaskFilter.Blur.NORMAL); mid.xfermode = add
        inner.color = innerC; inner.alpha = aa(np.innerAlpha); inner.strokeWidth = haloWidthPx * np.innerW
        inner.maskFilter = android.graphics.BlurMaskFilter(b * np.innerBlur, android.graphics.BlurMaskFilter.Blur.NORMAL); inner.xfermode = add
        core.color = coreC; core.alpha = aa(np.coreAlpha); core.strokeWidth = coreWidthPx * np.coreW
        core.maskFilter = null; core.xfermode = add

        // Outer (widest, FADED) halo = the pulsing part: each chunk EXPANDS/CONTRACTS in size (width+blur)
        // on its own random phase; brightness stays steady. mid+inner+core (incl. the bright core) stay steady.
        val outerBaseA = alpha * np.outerAlpha
        val outerBaseW = haloWidthPx * np.outerW
        val outerBaseBlur = b * np.outerBlur
        drawWaveOuter(c, rFull, rStart, rEnd, outerBaseA, outerBaseW, outerBaseBlur, waveAmount, waveCount, wavePhase)
        drawWaveOuter(c, lFull, lStart, lEnd, outerBaseA, outerBaseW, outerBaseBlur, waveAmount, waveCount, wavePhase)
        drawSteady(c, rFull, rStart, rEnd)
        drawSteady(c, lFull, lStart, lEnd)

        ds.drawContext.canvas.nativeCanvas.drawBitmap(bmp!!, 0f, 0f, comp)
    }

    // Steady passes (mid glow + inner + bright white core) — NOT pulsed; one stroke over the whole segment.
    private fun drawSteady(c: android.graphics.Canvas, full: Path, start: Float, end: Float) {
        if (end <= start) return
        val pm = android.graphics.PathMeasure(full.asAndroidPath(), false)
        seg.reset()
        pm.getSegment(start, end, seg, true)
        c.drawPath(seg, mid)
        c.drawPath(seg, inner)
        c.drawPath(seg, core)
    }

    // Outer faded halo drawn as ~20 short chunks, each EXPANDING/CONTRACTING in size INDEPENDENTLY (a random
    // breathing of the blur, NOT a travelling line and NOT a brightness change): every chunk gets a stable
    // pseudo-random phase offset + an INTEGER cycles-per-period rate (1..~waveCount), so neighbouring chunks are
    // unrelated (no coherent sweep) yet each global period loops seamlessly (integer rate → sine identical at
    // wavePhase 0 and 1, no pop). Each chunk's stroke WIDTH and blur RADIUS scale between (1−amt)× and (1+amt)×
    // the base, so the soft halo grows and shrinks; its alpha (brightness) stays steady and the bright core
    // (drawSteady) is untouched. waveAmount = expand depth (0 = no pulse → single base stroke). waveCount = how
    // varied the rates are (higher = more chaotic). wavePhase advances continuously to drive all the oscillators.
    private fun drawWaveOuter(
        c: android.graphics.Canvas, full: Path, start: Float, end: Float,
        baseAlpha: Float, baseWidth: Float, baseBlur: Float, waveAmount: Float, waveCount: Float, wavePhase: Float,
    ) {
        if (end <= start) return
        val pm = android.graphics.PathMeasure(full.asAndroidPath(), false)
        if (waveAmount <= 0f) {   // pulse OFF → single stroke (no chunk seams, cheaper) = identical to before
            seg.reset(); pm.getSegment(start, end, seg, true)
            outer.alpha = (baseAlpha * 255f).toInt().coerceIn(0, 255)
            c.drawPath(seg, outer); return
        }
        outer.alpha = (baseAlpha * 255f).toInt().coerceIn(0, 255)   // brightness steady; only size pulses
        val n = 20
        val stepLen = (end - start) / n
        val maxRate = waveCount.coerceAtLeast(1f)
        for (i in 0 until n) {
            val a = start + stepLen * i
            val bEnd = (start + stepLen * (i + 1) + stepLen * 0.4f).coerceAtMost(end)   // slight overlap → no gaps
            seg.reset()
            pm.getSegment(a, bEnd, seg, true)
            val ph = chunkHash(i, 1)                                  // stable random phase offset 0..1
            val rate = 1 + (chunkHash(i, 2) * maxRate).toInt()        // stable INTEGER cycles per period → seamless loop
            val wave = 0.5f + 0.5f * kotlin.math.sin((2.0 * Math.PI * (wavePhase * rate + ph)).toFloat())
            val sizeMul = (1f - waveAmount) + 2f * waveAmount * wave  // (1−amt)×..(1+amt)× → expand/contract
            outer.strokeWidth = baseWidth * sizeMul
            outer.maskFilter = android.graphics.BlurMaskFilter(
                (baseBlur * sizeMul).coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL
            )
            c.drawPath(seg, outer)
        }
    }

    // Deterministic per-chunk hash → 0..1. Gives each chunk a STABLE (non-flickering) random phase/rate so the
    // twinkle looks random across the edge but is identical every frame (no per-frame jitter). salt varies the stream.
    private fun chunkHash(i: Int, salt: Int): Float {
        var x = i * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0x7fffffff) / 2147483647f
    }
}
