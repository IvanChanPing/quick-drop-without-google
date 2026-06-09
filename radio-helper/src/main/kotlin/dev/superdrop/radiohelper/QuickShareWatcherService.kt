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
 * RESTORE TIMING
 * --------------
 * We deliberately do NOT try to detect "transfer finished" — that is app-specific
 * and would strand the radios ON forever on a cancel / failure. Instead the trigger
 * is "Quick Share LEFT the foreground" (works for any app and any outcome):
 *   - QS leaves the foreground  → restore [LEFT_GRACE_MS] (20 s) later.
 *   - Hard cap [MAX_HOLD_MS] (2 min) from first detection, for the case where we
 *     NEVER observe a leave (our process killed while QS is still in front). It is
 *     re-armed on every QS-foreground event, so an actively-used QS is not cut.
 * KNOWN EDGE: a transfer the user leaves running in the background is restored ~20 s
 * after they leave the QS screen (Quick Share also manages its own radios).
 *
 * THREADING / DURABILITY
 * ----------------------
 * [onAccessibilityEvent] runs on the main thread. [ShareRadioSession.prepare] can
 * block (the silent Wi‑Fi ladder does socket/mDNS I/O), so it is posted to a
 * background [HandlerThread]. The post-share RESTORE is deliberately NOT an
 * in-process timer: it is an AlarmManager alarm ([ShareRadioSession.scheduleRestoreIn]
 * → [ShareWatchdogReceiver] → [ShareRadioSession.finish]) so it fires even if our
 * process is frozen/killed after Quick Share closes. (An earlier `Handler.postDelayed`
 * grace timer lost the restore entirely when ColorOS killed the process — the radios
 * stayed on; this alarm-based path fixes that.)
 *
 * STATUS: compile-only — the detection class match and the radio flip are
 * device-UNVERIFIED until run on the OnePlus with the service enabled. Test:
 * enable in Accessibility, open Quick Share, watch logcat tag "QuickShareWatcher"
 * and the status line on SelfTestActivity (Wi‑Fi/BT should flip ON), then leave
 * Quick Share for >2 min and confirm they restore.
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
            // Quick Share is in the foreground. First time → prepare the radios.
            // Every time → (re)arm the MAX_HOLD hard-cap alarm so an actively-used
            // QS keeps pushing the cap out and is not cut mid-transfer.
            val firstEnter = !quickShareInFront
            quickShareInFront = true
            bg.post {
                if (firstEnter) {
                    Log.i(TAG, "Quick Share foreground ($cls) → prepare share radios")
                    QuickShareWatchStatus.update("Quick Share detected → enabling Wi‑Fi/Bluetooth")
                    // prepare() is re-entrant/idempotent (seeds from the persisted session).
                    ShareRadioSession.prepare(applicationContext, ShareRadioSession.RADIO_BOTH)
                }
                if (ShareRadioSession.isSessionActive(applicationContext)) {
                    ShareRadioSession.scheduleRestoreIn(applicationContext, MAX_HOLD_MS)
                }
            }
        } else if (quickShareInFront) {
            // LEAVE: foreground moved off Quick Share (user left — done / cancel /
            // fail; we don't care which). Restore LEFT_GRACE_MS later via the DURABLE
            // AlarmManager alarm (survives our process being frozen/killed by ColorOS),
            // NOT an in-process timer. Skip if we turned nothing on.
            quickShareInFront = false
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
         * Restore the radios this long AFTER Quick Share LEAVES the foreground (the
         * user navigated away — done, cancelled, or failed; we don't try to tell
         * which). A small cushion in case they bounce straight back into it.
         */
        const val LEFT_GRACE_MS = 20_000L

        /**
         * Hard cap: restore at most this long after Quick Share was first detected,
         * even if we NEVER see it leave (e.g. our process is killed while QS is still
         * in front). Re-armed on every QS-foreground event, so an actively-used QS is
         * not cut — this only bites if QS is killed or sits with no window events.
         */
        const val MAX_HOLD_MS = 120_000L
    }
}
