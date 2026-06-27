/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.bluehouse.bada.radiohelper.adbwifi.AdbWifiRadio

/**
 * The actual radio toggling. Works only because this APK targets API 28:
 * `WifiManager.setWifiEnabled()` is allowed for targetSdk <= 28 and
 * `BluetoothAdapter.enable()/disable()` for targetSdk <= 32 (verbatim AOSP
 * docs). The same calls return `false` (no-op) in the main app, which targets
 * a modern SDK — that is precisely why this companion module exists.
 *
 * All methods are best-effort: they return the platform call's result (or a
 * captured boolean), never throw, so a denied/OEM-restricted toggle is just a
 * `false` the caller can react to.
 */
internal object RadioToggler {
    private const val TAG = "RadioToggler"

    fun isWifiOn(context: Context): Boolean {
        val wm =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false
        return wm.isWifiEnabled
    }

    /** @return the `setWifiEnabled` result (true = request accepted). */
    fun setWifi(
        context: Context,
        on: Boolean,
    ): Boolean {
        val wm =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false
        return runCatching { wm.setWifiEnabled(on) }.getOrDefault(false)
    }

    /** Outcome of the Wi-Fi fallback ladder. */
    enum class WifiOutcome {
        /** Toggled with no user interaction (direct setWifiEnabled or Shizuku). */
        SILENT_OK,

        /** Couldn't toggle silently — caller should open the Wi-Fi panel. */
        NEEDS_USER,
    }

    /**
     * Diagnostic result of running the Wi-Fi ladder once. [success] = a silent
     * rung toggled the radio; [path] = which rung won ("direct"/"self-adb"/
     * "shizuku"/"panel"/"none"); [steps] = the per-rung outcome lines, in order,
     * for on-screen display AND logcat. This is what makes the failure LEGIBLE
     * (the user's complaint: no logging → couldn't see why self-ADB was skipped).
     */
    data class WifiLadderResult(
        val success: Boolean,
        val path: String,
        val steps: List<String>,
    )

    /**
     * Wi-Fi enable/disable ladder — the SINGLE source of truth, run by both the
     * test UI and [RadioService]. Order of preference:
     * 1. direct `setWifiEnabled` — silent; works only on OEMs that still honour
     *    the legacy targetSdk path (NOT ColorOS). No ADB grant helps: the gate
     *    is the signature-level NETWORK_SETTINGS, and WRITE_SECURE_SETTINGS is
     *    not consulted by this API.
     * 2. self-ADB ([AdbWifiRadio]) — silent; `svc wifi` over the helper's OWN
     *    paired Wireless-Debugging connection (shell UID). Preferred over Shizuku
     *    because it self-starts on boot with NO per-reboot manual step. Requires
     *    the one-time pairing; reports "NOT PAIRED" (not a silent false) until then.
     * 3. Shizuku — silent; the shell-UID `svc wifi` path, only if the user
     *    already runs Shizuku (manual per-reboot start → optional fallback only).
     * 4. panel — no silent path available; [openWifiPanel] (only if [allowPanel]).
     *
     * EVERY rung logs to logcat (tag `RadioToggler`) and appends a line to the
     * returned [WifiLadderResult.steps], so a fall-through to the panel is no
     * longer silent — you can see direct=false, "self-ADB: NOT PAIRED",
     * "Shizuku: not running", etc.
     *
     * Blocks (self-ADB/Shizuku do socket/mDNS I/O) — call OFF the main thread.
     */
    @Suppress("ReturnCount") // one early return per rung of the fallback ladder.
    fun runWifiLadder(
        context: Context,
        on: Boolean,
        allowPanel: Boolean,
    ): WifiLadderResult {
        val steps = mutableListOf<String>()

        fun step(line: String) {
            steps += line
            Log.i(TAG, line)
        }

        val direct = setWifi(context, on)
        step("1. direct setWifiEnabled = ${if (direct) "OK (silent)" else "false (OEM-clamped)"}")
        if (direct) return WifiLadderResult(true, "direct", steps)

        val adb = AdbWifiRadio.setWifi(context, on)
        step("2. self-ADB = ${if (adb) "OK (silent)" else "FAILED"} — ${AdbWifiRadio.lastStatus}")
        if (adb) return WifiLadderResult(true, "self-adb", steps)

        val shiz = ShizukuRadio.trySetWifi(context, on)
        step("3. Shizuku = ${if (shiz) "OK (silent)" else "FAILED"} — ${ShizukuRadio.lastStatus}")
        if (shiz) return WifiLadderResult(true, "shizuku", steps)

        if (allowPanel) {
            val opened = openWifiPanel(context)
            step("4. panel = ${if (opened) "opened (user taps)" else "FAILED to open"}")
            return WifiLadderResult(false, if (opened) "panel" else "none", steps)
        }
        step("4. panel = skipped (allowPanel=false)")
        return WifiLadderResult(false, "none", steps)
    }

    /**
     * Wi-Fi ladder for the test UI: runs all silent rungs, then the panel if none
     * worked. Returns the full diagnostic so the screen can show every rung.
     */
    fun setWifiWithDiagnostics(
        context: Context,
        on: Boolean,
    ): WifiLadderResult = runWifiLadder(context, on, allowPanel = true)

    /**
     * Wi-Fi ladder result as a coarse outcome (kept for existing callers).
     * direct → self-ADB → Shizuku; no panel.
     */
    fun setWifiSmart(
        context: Context,
        on: Boolean,
    ): WifiOutcome =
        if (runWifiLadder(context, on, allowPanel = false).success) {
            WifiOutcome.SILENT_OK
        } else {
            WifiOutcome.NEEDS_USER
        }

    /**
     * Silent-only Wi-Fi toggle: direct `setWifiEnabled` → self-ADB → Shizuku, NO
     * panel. For the headless [RadioService] (bound by the main app) — returns
     * `true` only if a silent path succeeded, so the caller knows whether it
     * must fall back to the panel ([openWifiPanel]) itself (foreground).
     *
     * NOTE: blocks (self-ADB does socket/mDNS I/O) — [RadioService] already calls
     * this on its background HandlerThread, never the main thread.
     */
    fun setWifiSilent(
        context: Context,
        on: Boolean,
    ): Boolean = runWifiLadder(context, on, allowPanel = false).success

    /**
     * Open the system Wi-Fi settings panel (API 29+ inline slide-up; older =
     * full Wi-Fi settings) so the user can flip Wi-Fi with one tap. There is
     * no one-tap "allow turn on Wi-Fi" dialog like Bluetooth's
     * ACTION_REQUEST_ENABLE — the panel is the closest equivalent.
     */
    fun openWifiPanel(context: Context): Boolean =
        runCatching {
            val action =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_WIFI
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)

    fun isBluetoothOn(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    /** @return the `enable()`/`disable()` result (true = request accepted). */
    fun setBluetooth(on: Boolean): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return runCatching { if (on) adapter.enable() else adapter.disable() }.getOrDefault(false)
    }
}
