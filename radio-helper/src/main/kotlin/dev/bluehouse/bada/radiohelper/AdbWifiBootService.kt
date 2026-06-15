/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.bluehouse.bada.radiohelper.adbwifi.AdbWifiRadio

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
 * BOOT TIMING (grounded in the adb-auto-enable reference, which does this exact
 * thing on OnePlus/ColorOS Android 14): right after boot, Wi-Fi and adbd aren't up
 * yet, so a single immediate attempt races and fails. So we wait
 * [INITIAL_DELAY_MS] for the system to stabilise, then retry ensureReady up to
 * [MAX_ATTEMPTS] times [RETRY_DELAY_MS] apart until the adbd port is found. Even if
 * all boot attempts fail, the tap path self-heals — [AdbWifiRadio.setWifi] calls
 * ensureReady itself on the first NFC tap — so warm-up failure is not fatal, just
 * a slower first tap. (Thread.sleep here is legitimate app-side boot pacing.)
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
        // FOREGROUND so the ~60-90s warm-up isn't killed by ColorOS background
        // limits (the adb-auto-enable reference runs its boot work foreground for
        // the same reason). Brief, low-importance notification; removed when done.
        runCatching { startForeground(NOTIF_ID, warmupNotification()) }
            .onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
        Thread {
            // FIRST: if a share session was stranded by a reboot mid-transfer,
            // restore the user's original Wi-Fi/BT now (alarms don't survive reboot,
            // and Android remembers Wi-Fi as the ON state we set). Done before the
            // warm-up delay so radios aren't left wrong any longer than necessary.
            runCatching { ShareRadioSession.restoreStaleOnBoot(this) }
            runCatching { Thread.sleep(INITIAL_DELAY_MS) }
            var ready = false
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                ready = runCatching { AdbWifiRadio.ensureReady(this) }.getOrDefault(false)
                Log.i(TAG, "boot warm-up attempt $attempt/$MAX_ATTEMPTS -> ready=$ready")
                if (ready) break
                if (attempt < MAX_ATTEMPTS) runCatching { Thread.sleep(RETRY_DELAY_MS) }
            }
            Log.i(TAG, "boot warm-up done (ready=$ready). Tap path self-heals if this failed.")
            // Short-lived: drop the foreground notification and stop.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
            stopSelf(startId)
        }.start()
        // Don't auto-restart if killed; the next boot re-arms via the receiver.
        return START_NOT_STICKY
    }

    /** Minimal low-importance notification required to run as a foreground service. */
    private fun warmupNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Radio helper startup", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Brief: re-establishing silent Wi-Fi toggling after boot." },
            )
        }
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Radio helper")
            .setContentText("Preparing silent Wi-Fi toggling…")
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "AdbWifi/Boot"
        const val CHANNEL_ID = "adb_boot_warmup"
        const val NOTIF_ID = 4712

        // Matches the adb-auto-enable reference's boot pacing for OnePlus/ColorOS:
        // wait for the system to settle, then retry until adbd advertises a port.
        const val INITIAL_DELAY_MS = 60_000L
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 15_000L
    }
}
