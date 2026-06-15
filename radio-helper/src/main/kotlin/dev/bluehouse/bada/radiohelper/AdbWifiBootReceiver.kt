/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * WHAT THIS IS
 * ------------
 * `AdbWifiBootReceiver` — a `RECEIVE_BOOT_COMPLETED` broadcast receiver. This is
 * the **self-start** mechanism that satisfies the HARD RULE "no manual process on
 * every restart": after every reboot it kicks [AdbWifiBootService] to re-warm the
 * self-ADB Wi-Fi connection, with zero user action.
 *
 * WHY IT EXISTS
 * -------------
 * Android resets `adb_wifi_enabled` to 0 on every boot, so the device's wireless
 * adbd disappears. Without this receiver the helper would need a manual
 * re-enable each boot (forbidden). With it, the one-time pairing keeps working
 * forever across reboots.
 *
 * HOW IT FITS / FLOW
 * ------------------
 * Boot → this receiver → [AdbWifiBootService] (background thread) →
 * [dev.bluehouse.bada.radiohelper.adbwifi.AdbWifiRadio.ensureReady] (re-enable
 * wireless debugging + mDNS-discover the adbd port + cache it). A broadcast
 * receiver's `onReceive` runs on the main thread and must return fast, so it does
 * NO work itself — it only starts the service.
 *
 * STATUS: compile-only / device-UNVERIFIED — ColorOS boot-broadcast delivery to a
 * sideloaded app and post-reboot `adb_wifi_enabled` write are untested.
 */
internal class AdbWifiBootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "boot completed → warming self-ADB Wi-Fi")
        // Plain startService: the BOOT_COMPLETED delivery grants a short
        // background-start allowance, and the warm-up is brief. runCatching
        // guards the rare IllegalStateException (background-start-not-allowed) so
        // a refusal degrades gracefully — the tap path lazily calls
        // AdbWifiRadio.ensureReady() itself if the boot warm-up didn't run.
        // (Device-UNVERIFIED on ColorOS, which may delay/suppress boot broadcasts
        // to sideloaded apps until the app is first launched.)
        val svc = Intent(context, AdbWifiBootService::class.java)
        runCatching {
            // FGS so the warm-up survives ColorOS background limits; from
            // BOOT_COMPLETED a foreground start is permitted.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        }.onFailure { Log.w(TAG, "could not start boot service: ${it.message}") }
    }

    private companion object {
        const val TAG = "AdbWifi/Boot"
    }
}
