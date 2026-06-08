/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper.adbwifi

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
    private const val LOOPBACK = "127.0.0.1"

    /** Last adbd port discovered via mDNS; -1 = unknown/not ready. */
    @Volatile
    private var cachedPort: Int = -1

    /**
     * Human-readable result of the last [setWifi]/[ensureReady] attempt, for
     * on-screen + logcat diagnostics (mirrors [ShizukuRadio.lastStatus]). Lets
     * the Wi-Fi ladder report WHY the self-ADB rung did/didn't fire instead of a
     * silent `false`. Tag for logcat filtering: `AdbWifi/Radio`.
     */
    @Volatile
    var lastStatus: String = "not attempted"
        private set

    /** True once the device has been paired (key+cert persisted). */
    fun isPaired(context: Context): Boolean = AdbWifiManager.isPaired(context)

    /**
     * BOOT job. Re-enable wireless debugging, discover the adbd port, cache it.
     * Idempotent and safe to call repeatedly. @return the discovered port, or -1
     * if not paired / debugging couldn't be enabled / no port advertised.
     */
    fun ensureReady(context: Context): Int {
        if (!isPaired(context)) {
            lastStatus = "NOT PAIRED — run 'Radio Helper: ADB-WiFi Setup' once"
            Log.w(TAG, "ensureReady: $lastStatus")
            return -1
        }
        val enabled = AdbWifiManager.enableWirelessDebugging(context)
        Log.i(TAG, "ensureReady: enableWirelessDebugging(adb_wifi_enabled=1)=$enabled")
        val port = AdbMdns.discoverPort(context)
        if (port < 0) {
            lastStatus =
                "no adbd port via mDNS (enableWirelessDebugging=$enabled — " +
                    "WSS granted? wireless debugging on?)"
            Log.w(TAG, "ensureReady: $lastStatus")
            cachedPort = -1
            return -1
        }
        cachedPort = port
        Log.i(TAG, "ensureReady: warm on port $port (enableWirelessDebugging=$enabled)")
        return port
    }

    /**
     * TAP job. Flip Wi-Fi on/off via `svc wifi` over the warm connection. If the
     * cached port is unknown/stale, re-runs [ensureReady] once then retries.
     * @return true only if the `svc wifi` command actually ran (connection OK);
     * caller should still verify the radio state. Sets [lastStatus] either way.
     */
    fun setWifi(
        context: Context,
        on: Boolean,
    ): Boolean {
        if (!isPaired(context)) {
            lastStatus = "NOT PAIRED — run 'Radio Helper: ADB-WiFi Setup' once"
            Log.w(TAG, "setWifi: $lastStatus")
            return false
        }
        val port = if (cachedPort > 0) cachedPort else ensureReady(context)
        if (port < 0) {
            // ensureReady already set a descriptive lastStatus.
            Log.w(TAG, "setWifi: no port (cached=$cachedPort); status='$lastStatus'")
            return false
        }
        if (AdbWifiManager.setWifi(context, LOOPBACK, port, on)) {
            lastStatus = "svc wifi ${if (on) "enable" else "disable"} ran via ADB port $port"
            Log.i(TAG, "setWifi: $lastStatus")
            return true
        }
        // Stale port (adbd re-advertised on a new port after a re-enable?) —
        // re-warm once and retry before giving up to the next ladder rung.
        Log.w(TAG, "setWifi: cmd failed on port $port — re-warming and retrying once")
        val fresh = ensureReady(context)
        if (fresh > 0 && AdbWifiManager.setWifi(context, LOOPBACK, fresh, on)) {
            lastStatus = "svc wifi ${if (on) "enable" else "disable"} ran via ADB port $fresh (after re-warm)"
            Log.i(TAG, "setWifi: $lastStatus")
            return true
        }
        lastStatus = "ADB connect/exec failed (port $port, re-warm port $fresh) — paired but adbd unreachable"
        Log.w(TAG, "setWifi: $lastStatus")
        return false
    }
}
