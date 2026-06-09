/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * WHAT THIS IS
 * ------------
 * `QuickShareWatcherService` — an AccessibilityService that DETECTS when Google
 * Quick Share's UI comes to the foreground and, on its own (no command from the
 * Super Drop app), runs the helper's normal share-radio flow:
 *   - Quick Share opens  → [ShareRadioSession.prepare] (capture original Wi‑Fi/BT
 *     state, turn on whatever is off).
 *   - Quick Share leaves the foreground for [GRACE_MS] → [ShareRadioSession.finish]
 *     (restore exactly what we turned on).
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
 * RESTORE TIMING (KNOWN LIMITATION)
 * ---------------------------------
 * We cannot see Quick Share's transfer state from outside, so "transfer finished"
 * is approximated as "Quick Share has been out of the foreground for [GRACE_MS]".
 * A large transfer that continues in the background AFTER the UI is dismissed for
 * longer than the grace window could be restored mid-transfer. Mitigations: Quick
 * Share manages its own radios too, and [ShareRadioSession]'s 20‑minute watchdog is
 * the backstop if this service is killed. A future NotificationListener reading
 * Quick Share's progress notification could gate restore precisely.
 *
 * THREADING
 * ---------
 * [onAccessibilityEvent] runs on the main thread. [ShareRadioSession.prepare]/
 * [finish] can block (the silent Wi‑Fi ladder does socket/mDNS I/O), so the actual
 * work is posted to a background [HandlerThread]; the grace timer uses the main
 * looper only to schedule (its body re-posts to the worker). Never blocks main.
 *
 * STATUS: compile-only — the detection class match and the radio flip are
 * device-UNVERIFIED until run on the OnePlus with the service enabled. Test:
 * enable in Accessibility, open Quick Share, watch logcat tag "QuickShareWatcher"
 * and the status line on SelfTestActivity (Wi‑Fi/BT should flip ON), then leave
 * Quick Share for >2 min and confirm they restore.
 */
internal class QuickShareWatcherService : AccessibilityService() {
    // Worker thread for the blocking prepare()/finish() ladder — never main.
    // bg is `by lazy` (not lateinit) so it's safe even if an event were delivered
    // before onServiceConnected: HandlerThread.getLooper() blocks until ready.
    private val worker = HandlerThread("qs-watcher").apply { start() }
    private val bg: Handler by lazy { Handler(worker.looper) }

    // Main-looper handler used ONLY to schedule the debounced restore.
    private val main = Handler(Looper.getMainLooper())

    // restoreRunnable — fires GRACE_MS after Quick Share last left the foreground;
    // if it hasn't come back, restore the radios we turned on.
    private val restoreRunnable =
        Runnable {
            if (QuickShareWatchStatus.inSession) {
                QuickShareWatchStatus.inSession = false
                Log.i(TAG, "Quick Share gone ${GRACE_MS}ms → restoring radios")
                QuickShareWatchStatus.update("Quick Share closed → restoring radios")
                bg.post { ShareRadioSession.finish(applicationContext) }
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
            // Quick Share is (still) in front — cancel any pending restore.
            main.removeCallbacks(restoreRunnable)
            if (!QuickShareWatchStatus.inSession) {
                QuickShareWatchStatus.inSession = true
                Log.i(TAG, "Quick Share foreground ($cls) → prepare share radios")
                QuickShareWatchStatus.update("Quick Share detected → enabling Wi‑Fi/Bluetooth")
                // prepare() is re-entrant/idempotent (seeds from the persisted
                // session), so a repeated detect won't double-record.
                bg.post { ShareRadioSession.prepare(applicationContext, ShareRadioSession.RADIO_BOTH) }
            }
        } else if (QuickShareWatchStatus.inSession) {
            // Foreground moved off Quick Share — start/refresh the grace timer.
            main.removeCallbacks(restoreRunnable)
            main.postDelayed(restoreRunnable, GRACE_MS)
        }
    }

    override fun onInterrupt() {
        // No-op: we act only on window-state changes, nothing to interrupt.
    }

    override fun onDestroy() {
        main.removeCallbacks(restoreRunnable)
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
         * How long Quick Share must stay OUT of the foreground before we restore
         * the radios. 2 min balances "don't cut a short transfer" against "don't
         * leave radios on too long"; the 20‑min ShareRadioSession watchdog backstops.
         */
        const val GRACE_MS = 120_000L
    }
}
