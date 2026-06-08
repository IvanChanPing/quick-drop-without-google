/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.superdrop.radiohelper.adbwifi.AdbWifiRadio

/**
 * WHAT THIS IS
 * ------------
 * `AdbWifiBootService` — the boot-time worker started by [AdbWifiBootReceiver].
 * It owns JOB #1 of the self-ADB Wi-Fi feature: re-warming the connection after a
 * reboot so the tap-time toggle is instant.
 *
 * WHAT IT DOES
 * ------------
 * On start it runs [AdbWifiRadio.ensureReady] on a BACKGROUND thread (re-enable
 * `adb_wifi_enabled` via the already-granted WRITE_SECURE_SETTINGS, mDNS-discover
 * the randomized adbd port, cache it), then stops itself. No UI, no notification —
 * it's a short-lived warm-up, not a persistent foreground service.
 *
 * WHY IT EXISTS / HOW IT FITS
 * ---------------------------
 * Keeps the boot-persistence concern OUT of `RadioService` (which only does the
 * tap-time toggle). Boot → [AdbWifiBootReceiver] → this service → AdbWifiRadio.
 * This is deliberately NOT in RadioService: re-enabling debugging + the ~10s mDNS
 * discovery must happen at boot, not lazily on the first tap after a reboot.
 *
 * THREADING / STATUS
 * ------------------
 * `ensureReady` blocks, so it runs on a spawned thread; [onStartCommand] returns
 * immediately. compile-only / device-UNVERIFIED.
 */
internal class AdbWifiBootService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Thread {
            val port = runCatching { AdbWifiRadio.ensureReady(this) }.getOrDefault(-1)
            Log.i(TAG, "boot warm-up done (port=$port)")
            // Short-lived: stop once warm-up finishes (success or not).
            stopSelf(startId)
        }.start()
        // Don't auto-restart if killed; the next boot re-arms via the receiver.
        return START_NOT_STICKY
    }

    private companion object {
        const val TAG = "AdbWifi/Boot"
    }
}
