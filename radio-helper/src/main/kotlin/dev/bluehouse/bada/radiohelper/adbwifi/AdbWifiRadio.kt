/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper.adbwifi

import android.content.Context
import android.util.Log

/**
 * WHAT THIS IS
 * ------------
 * `AdbWifiRadio` — the **shared self-ADB engine** that the Radio Helper exposes
 * to its two consumers. It wraps the low-level [AdbWifiManager] + [AdbMdns] into
 * two clearly separated jobs so the boot-time and tap-time responsibilities never
 * get conflated again:
 *
 *  - [ensureReady] — the BOOT job. Re-enables Android-11 "Wireless debugging"
 *    (which the OS resets to OFF on every reboot) using the one-time-granted
 *    WRITE_SECURE_SETTINGS, discovers the new randomized adbd port via mDNS, and
 *    caches it. Called by the boot-complete service (`AdbWifiBootReceiver` →
 *    `AdbWifiBootService`) so the connection is WARM before any tap arrives —
 *    NO per-reboot manual step.
 *  - [setWifi] — the TAP job. Flips Wi-Fi via `svc wifi` over the cached/warm
 *    connection. Called by `RadioService` when an NFC tap needs the radio on/off.
 *    If the cached port is stale (e.g. boot service hasn't run yet) it falls back
 *    to a fresh [ensureReady] once, then retries.
 *
 * WHY THE SPLIT
 * -------------
 * Enabling wireless debugging + a ~10s mDNS discovery must happen on BOOT, not
 * lazily inside the tap path — otherwise the first transfer after every reboot
 * eats that cold-start. The boot service warms it; the tap path just toggles.
 *
 * PRECONDITION
 * ------------
 * A ONE-TIME on-device pairing (done via `AdbWifiTestActivity`) + the self-grant
 * of WRITE_SECURE_SETTINGS. After that, this engine needs no user action ever
 * again, across reboots.
 *
 * THREADING / STATUS
 * ------------------
 * Every method BLOCKS (socket + mDNS I/O) — call OFF the main thread. The boot
 * service uses a background thread; `RadioService` runs on its own HandlerThread.
 * NOT device-tested: ColorOS `adb_wifi_enabled` write + reboot key-persistence is
 * UNVERIFIED until the on-device pairing test is run.
 */
internal object AdbWifiRadio {
    private const val TAG = "AdbWifi/Radio"

    /**
     * Human-readable result of the last [setWifi]/[ensureReady] attempt, for
     * on-screen + logcat diagnostics (mirrors [ShizukuRadio.lastStatus]). Lets
     * the Wi-Fi ladder report WHY the self-ADB rung did/didn't fire instead of a
     * silent `false`. Tag for logcat filtering: `AdbWifi/Radio`.
     */
    @Volatile
    var lastStatus: String = "not attempted"
        private set

    /** True only after a genuinely successful pairing (key trusted by adbd). */
    fun isPaired(context: Context): Boolean = AdbWifiManager.isPaired(context)

    /**
     * BOOT job. Re-enable wireless debugging (WSS) and verify the self-connect
     * works (libadb `autoConnect` discovers the adbd port via mDNS + TLS itself —
     * no manual host/port). Idempotent. @return true if a self-ADB shell connected.
     */
    @Suppress("ReturnCount")
    fun ensureReady(context: Context): Boolean {
        if (!isPaired(context)) {
            lastStatus = "NOT PAIRED — pair once via the notification, then this works across reboots"
            Log.w(TAG, "ensureReady: $lastStatus")
            return false
        }
        val enabled = AdbWifiManager.enableWirelessDebugging(context)
        Log.i(TAG, "ensureReady: enableWirelessDebugging(adb_wifi_enabled=1)=$enabled")
        // Probe the self-connect (autoConnect does mDNS + TLS). `echo` is cheap.
        val probe = AdbWifiManager.runShell(context, "echo ready")
        if (probe != null) {
            lastStatus = "ready (autoConnect OK)"
            Log.i(TAG, "ensureReady: $lastStatus")
            return true
        }
        lastStatus =
            "enabled=$enabled but autoConnect failed: ${AdbWifiManager.lastError ?: "?"}"
        Log.w(TAG, "ensureReady: $lastStatus")
        return false
    }

    /**
     * TAP job. Flip Wi-Fi on/off via `svc wifi`. libadb `autoConnect` (inside
     * [AdbWifiManager.setWifi]) handles enabling-debugging-aware mDNS discovery +
     * TLS connect itself. @return true only if the command ran (connection OK).
     * Sets [lastStatus] (incl. the real connect error) either way.
     */
    @Suppress("ReturnCount")
    fun setWifi(
        context: Context,
        on: Boolean,
    ): Boolean {
        if (!isPaired(context)) {
            lastStatus = "NOT PAIRED — pair once via the notification, then this works across reboots"
            Log.w(TAG, "setWifi: $lastStatus")
            return false
        }
        // Make sure wireless debugging is up (resets on reboot); autoConnect then
        // discovers the (randomized) port itself.
        AdbWifiManager.enableWirelessDebugging(context)
        if (AdbWifiManager.setWifi(context, on)) {
            lastStatus = "svc wifi ${if (on) "enable" else "disable"} ran via self-ADB (autoConnect)"
            Log.i(TAG, "setWifi: $lastStatus")
            return true
        }
        lastStatus =
            "self-ADB connect/exec failed — paired but adbd unreachable. " +
            "last error: ${AdbWifiManager.lastError ?: "?"}"
        Log.w(TAG, "setWifi: $lastStatus")
        return false
    }
}
