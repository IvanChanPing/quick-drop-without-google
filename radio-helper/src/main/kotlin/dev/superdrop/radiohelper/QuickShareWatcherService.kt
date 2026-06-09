/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * WHAT THIS IS
 * ------------
 * `QuickShareWatcherService` — an AccessibilityService that DETECTS when Google
 * Quick Share's UI comes to the foreground and, on its own (no command from the
 * Super Drop app), runs the helper's normal share-radio flow:
 *   - Quick Share opens  → [ShareRadioSession.prepare] (capture original Wi‑Fi/BT
 *     state, turn on whatever is off) + start a 5 s heartbeat.
 *   - while Quick Share stays in front → the heartbeat keeps pushing the restore out.
 *   - Quick Share leaves the foreground → [ShareRadioSession.finish] (restore exactly
 *     what we turned on) [LEFT_GRACE_MS] after the LAST heartbeat.
 *
 * USER-FACING NAME / HOW IT'S INVOKED
 * -----------------------------------
 * Listed in **Settings → Accessibility** as "Super Drop Quick Share auto-detect".
 * The user enables it ONCE (button on [SelfTestActivity]); Android re-binds enabled
 * accessibility services on every boot, so there is NO per-reboot manual step.
 *
 * HOW IT DETECTS QUICK SHARE
 * --------------------------
 * Quick Share is hosted inside Google Play services. Its UI activities live under
 * `com.google.android.gms.nearby.sharing.*` (verified in the GMS 26.18.x manifest:
 * `…nearby.sharing.main.MainActivity`, `…nearby.sharing.ConsentsActivity`). On each
 * `TYPE_WINDOW_STATE_CHANGED` we check the event's packageName == GMS and the
 * className contains [QS_CLASS_MARKER]. EVERY GMS window class seen is recorded to
 * [QuickShareWatchStatus.lastWindow] so, if a device reports a different class, it
 * is visible on-screen and the matcher can be corrected (no guessing/decompile).
 *
 * RESTORE TIMING (heartbeat model)
 * --------------------------------
 * We deliberately do NOT try to detect "transfer finished" — that is app-specific
 * and would strand the radios ON forever on a cancel / failure. Instead, while Quick
 * Share is in the foreground a [HEARTBEAT_MS] (5 s) heartbeat keeps re-arming the
 * restore alarm to fire [LEFT_GRACE_MS] (20 s) out. So the radios restore ~20 s after
 * the LAST heartbeat — i.e. ~20 s after Quick Share leaves the foreground (or after
 * our process loses track of it). A long transfer is never cut while its UI is up,
 * because each 5 s tick pushes the 20 s restore further out.
 * NOTE: our app CANNOT heartbeat for Quick Share (it is Google's app), so the
 * heartbeat is SELF-generated from the accessibility foreground signal.
 * KNOWN EDGE: a transfer the user sends to the BACKGROUND (QS no longer foreground)
 * is restored ~20 s later (Quick Share also manages its own radios).
 *
 * THREADING / DURABILITY
 * ----------------------
 * [onAccessibilityEvent] runs on the main thread. [ShareRadioSession.prepare] can
 * block (the silent Wi‑Fi ladder does socket/mDNS I/O) and the [heartbeat] re-posts
 * itself, so both run on a background [HandlerThread] — never main. The RESTORE is
 * deliberately NOT an in-process timer: it is an AlarmManager alarm
 * ([ShareRadioSession.scheduleRestoreIn] → [ShareWatchdogReceiver] →
 * [ShareRadioSession.finish]) so it fires even if our process is frozen/killed after
 * Quick Share closes. (An earlier `Handler.postDelayed` grace timer lost the restore
 * entirely when ColorOS killed the process — the radios stayed on; this fixes that.)
 *
 * STATUS: compile-only — the detection class match and the radio flip are
 * device-UNVERIFIED until run on the OnePlus with the service enabled. Test:
 * enable in Accessibility, open Quick Share, watch logcat tag "QuickShareWatcher"
 * and the status line on SelfTestActivity (Wi‑Fi/BT should flip ON), then leave
 * Quick Share and confirm the radios restore ~20 s later (and that a long transfer
 * with the QS screen still up is NOT cut).
 */
internal class QuickShareWatcherService : AccessibilityService() {
    // Worker thread for the blocking prepare() ladder — never main. bg is `by lazy`
    // (not lateinit) so it's safe even if an event arrives before onServiceConnected:
    // HandlerThread.getLooper() blocks until ready.
    private val worker = HandlerThread("qs-watcher").apply { start() }
    private val bg: Handler by lazy { Handler(worker.looper) }

    // quickShareInFront — whether Quick Share's UI is the CURRENT foreground window.
    // Tracks the enter/leave TRANSITIONS so we act once per transition, not on every
    // sub-window event. The "is a restore pending" truth lives in the persisted
    // session (ShareRadioSession.isSessionActive), which survives our process being
    // killed; this flag is only for in-process edge detection.
    @Volatile private var quickShareInFront = false

    // heartbeat — while Quick Share is in the foreground this re-posts itself on the
    // worker thread every HEARTBEAT_MS, each tick pushing the restore alarm back out
    // to LEFT_GRACE_MS. Net effect: the radios restore ~LEFT_GRACE_MS after the LAST
    // tick, so a long transfer (QS still in front) is never cut, but a leave/loss of
    // tracking restores promptly. Stopped (removeCallbacks) on leave and onDestroy.
    private val heartbeat =
        object : Runnable {
            override fun run() {
                if (!quickShareInFront) return // stopped between posts — do nothing
                if (ShareRadioSession.isSessionActive(applicationContext)) {
                    ShareRadioSession.scheduleRestoreIn(applicationContext, LEFT_GRACE_MS)
                }
                bg.postDelayed(this, HEARTBEAT_MS)
            }
        }

    override fun onServiceConnected() {
        Log.i(TAG, "connected — watching for Quick Share")
        QuickShareWatchStatus.update("enabled — watching for Quick Share")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString().orEmpty()

        // Record GMS windows so a mismatched class name is visible & fixable.
        if (pkg == GMS_PKG) QuickShareWatchStatus.window("$pkg / $cls")

        val isQuickShare = pkg == GMS_PKG && cls.contains(QS_CLASS_MARKER)
        if (isQuickShare) {
            // ENTER (first time only): prepare the radios and start the 5 s heartbeat,
            // which keeps the restore pushed out to LEFT_GRACE_MS while QS is in front.
            if (!quickShareInFront) {
                quickShareInFront = true
                Log.i(TAG, "Quick Share foreground ($cls) → prepare + start heartbeat")
                QuickShareWatchStatus.update("Quick Share detected → enabling Wi‑Fi/Bluetooth")
                bg.post {
                    // prepare() is re-entrant/idempotent (seeds from the persisted session).
                    ShareRadioSession.prepare(applicationContext, ShareRadioSession.RADIO_BOTH)
                }
                bg.removeCallbacks(heartbeat)
                bg.post(heartbeat) // first tick arms the 20 s restore, then self-reposts
            }
        } else if (quickShareInFront) {
            // LEAVE: foreground moved off Quick Share (user left — done / cancel /
            // fail; we don't care which). Stop the heartbeat and arm the DURABLE
            // restore alarm LEFT_GRACE_MS out (survives our process being frozen/killed
            // by ColorOS), NOT an in-process timer. Skip if we turned nothing on.
            quickShareInFront = false
            bg.removeCallbacks(heartbeat)
            bg.post {
                if (ShareRadioSession.isSessionActive(applicationContext)) {
                    Log.i(TAG, "Quick Share left → restore alarm in ${LEFT_GRACE_MS / 1000}s")
                    ShareRadioSession.scheduleRestoreIn(applicationContext, LEFT_GRACE_MS)
                    QuickShareWatchStatus.update("Quick Share left → restoring radios in ${LEFT_GRACE_MS / 1000}s")
                } else {
                    QuickShareWatchStatus.update("Quick Share left (nothing to restore)")
                }
            }
        }
    }

    override fun onInterrupt() {
        // No-op: we act only on window-state changes, nothing to interrupt.
    }

    override fun onDestroy() {
        bg.removeCallbacks(heartbeat)
        worker.quitSafely()
        QuickShareWatchStatus.update("disabled")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "QuickShareWatcher"

        /** Google Play services hosts the Quick Share UI. */
        const val GMS_PKG = "com.google.android.gms"

        /**
         * Substring every Quick Share UI activity class shares
         * (`com.google.android.gms.nearby.sharing.main.MainActivity`,
         * `…nearby.sharing.ConsentsActivity`, …). Verified in the GMS manifest.
         */
        const val QS_CLASS_MARKER = "nearby.sharing"

        /**
         * Restore the radios this long after the LAST heartbeat — i.e. ~20 s after
         * Quick Share leaves the foreground (or after our process stops ticking). Each
         * heartbeat re-arms the restore alarm this far out, so while QS stays in front
         * the restore is continually pushed back and a long transfer is never cut.
         */
        const val LEFT_GRACE_MS = 20_000L

        /** Heartbeat interval while Quick Share is in the foreground. Must be well
         * below [LEFT_GRACE_MS] so a tick always lands before the restore would fire. */
        const val HEARTBEAT_MS = 5_000L
    }
}
