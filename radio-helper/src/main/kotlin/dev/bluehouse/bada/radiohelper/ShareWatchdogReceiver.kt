/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * WHAT THIS IS
 * ------------
 * `ShareWatchdogReceiver` — the SAFETY backstop for the share session. It fires
 * from the AlarmManager alarm armed by [ShareRadioSession.prepare] and, if the app
 * never called `transferFinished` (crash / force-kill mid-transfer), restores the
 * user's original Wi-Fi/BT so the radios are never stranded ON.
 *
 * WHY
 * ---
 * `transferFinished` is the PRIMARY restore (fired on any terminal: success / fail
 * / cancel / closed). This receiver only covers the abnormal case where that call
 * never arrives. The reboot case is covered separately by
 * [ShareRadioSession.restoreStaleOnBoot] (alarms don't survive reboot).
 *
 * THREADING / STATUS
 * ------------------
 * `ShareRadioSession.finish` blocks (silent toggle ladder) → run on a background
 * thread via [goAsync], not the receiver's main thread (avoids ANR). Not exported;
 * only our own alarm PendingIntent fires it. compile-only / device-UNVERIFIED.
 */
internal class ShareWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != ShareRadioSession.ACTION_WATCHDOG) return
        Log.w(TAG, "watchdog fired — app never called transferFinished; restoring radios")
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                ShareRadioSession.finish(appContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "ShareRadioSession/WD"
    }
}
