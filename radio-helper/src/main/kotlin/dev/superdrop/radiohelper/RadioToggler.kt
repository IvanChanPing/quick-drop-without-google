/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

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
     * Wi-Fi enable/disable ladder, in order of preference:
     * 1. direct `setWifiEnabled` — silent; works only on OEMs that still honour
     *    the legacy targetSdk path (NOT ColorOS). No ADB grant helps: the gate
     *    is the signature-level NETWORK_SETTINGS, and WRITE_SECURE_SETTINGS is
     *    not consulted by this API.
     * 2. Shizuku — silent; the shell-UID `svc wifi` path for OEMs (ColorOS)
     *    that clamp #1.
     * 3. NEEDS_USER — neither silent path is available; caller pops the Wi-Fi
     *    settings panel ([openWifiPanel]) so the user flips it with one tap.
     */
    fun setWifiSmart(
        context: Context,
        on: Boolean,
    ): WifiOutcome {
        if (setWifi(context, on)) return WifiOutcome.SILENT_OK
        if (ShizukuRadio.trySetWifi(context, on)) return WifiOutcome.SILENT_OK
        return WifiOutcome.NEEDS_USER
    }

    /**
     * Silent-only Wi-Fi toggle: direct `setWifiEnabled` then Shizuku, NO
     * panel. For the headless [RadioService] (bound by the main app) — returns
     * `true` only if a silent path succeeded, so the caller knows whether it
     * must fall back to the panel ([openWifiPanel]) itself (foreground).
     */
    fun setWifiSilent(
        context: Context,
        on: Boolean,
    ): Boolean = setWifi(context, on) || ShizukuRadio.trySetWifi(context, on)

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
