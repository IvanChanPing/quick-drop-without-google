/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager

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
