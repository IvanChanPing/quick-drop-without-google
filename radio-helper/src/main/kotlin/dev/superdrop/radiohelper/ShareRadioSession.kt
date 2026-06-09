/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.content.Context
import android.util.Log

/**
 * WHAT THIS IS
 * ------------
 * `ShareRadioSession` — the HELPER-side orchestration for "force the needed radios
 * ON for a transfer, then restore them to their ORIGINAL state when it finishes."
 * This logic lives in the HELPER (not the client app) by design: the client apps
 * are dumb — they only call [prepare] at transfer/NFC-tap start and [finish] at the
 * terminal. The helper decides what was off, turns it on, and undoes ONLY what it
 * turned on.
 *
 * WHY IN THE HELPER
 * -----------------
 * The user's rule (2026-06-09): "the helper should be the one to determine whether
 * Wi-Fi/Bluetooth were already off and turn them on and then set them back to their
 * original. The only thing our app should do is call to it and tell it the transfer
 * finished." So state capture + restore is server-side here, never duplicated per app.
 *
 * PROCESS-DEATH ROBUSTNESS
 * ------------------------
 * The "what we turned on" flags are persisted to SharedPreferences, so if the helper
 * process is killed between [prepare] and [finish], the restore still undoes exactly
 * the radios we enabled (no leaving the user's Wi-Fi/BT on against their original).
 *
 * HOW IT FITS
 * -----------
 * Called by `RadioService` on MSG_PREPARE_SHARE / MSG_TRANSFER_FINISHED. Uses
 * [RadioToggler] for the actual silent toggle ladder. Runs on RadioService's
 * background HandlerThread (the silent Wi-Fi path can block) — never the main thread.
 *
 * STATUS: compile-only / device-UNVERIFIED end-to-end (the underlying toggle ladder
 * was validated separately).
 */
internal object ShareRadioSession {
    private const val TAG = "ShareRadioSession"
    private const val PREFS = "share_radio_session"
    private const val KEY_ENABLED_WIFI = "enabledWifi"
    private const val KEY_ENABLED_BT = "enabledBt"

    /** Radio bitmask used in the prepare request/result (matches the client). */
    const val RADIO_WIFI = 1
    const val RADIO_BT = 2
    const val RADIO_BOTH = RADIO_WIFI or RADIO_BT

    /**
     * Transfer START. For each requested radio that is currently OFF, turn it ON
     * (silent ladder) and remember we did so (persisted). Radios already ON are
     * left untouched and NOT recorded (so [finish] won't turn them off).
     * @param radios bitmask of radios the transfer needs (0 → both).
     * @return bitmask of radios that are ON after this call (Wi-Fi bit set only if
     *         a SILENT path actually enabled it; the caller may ignore this).
     */
    fun prepare(
        context: Context,
        radios: Int,
    ): Int {
        val want = if (radios == 0) RADIO_BOTH else radios
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        var enabledWifi = false
        var enabledBt = false
        var nowOn = 0

        if (want and RADIO_WIFI != 0) {
            if (RadioToggler.isWifiOn(context)) {
                nowOn = nowOn or RADIO_WIFI
            } else if (RadioToggler.setWifiSilent(context, true)) {
                enabledWifi = true
                nowOn = nowOn or RADIO_WIFI
            } else {
                Log.w(TAG, "prepare: Wi-Fi could not be enabled silently (${RadioToggler.javaClass.simpleName})")
            }
        }
        if (want and RADIO_BT != 0) {
            if (RadioToggler.isBluetoothOn()) {
                nowOn = nowOn or RADIO_BT
            } else if (RadioToggler.setBluetooth(true)) {
                enabledBt = true
                nowOn = nowOn or RADIO_BT
            }
        }

        // Persist what WE turned on so finish() restores it even after a process kill.
        prefs.edit()
            .putBoolean(KEY_ENABLED_WIFI, enabledWifi)
            .putBoolean(KEY_ENABLED_BT, enabledBt)
            .apply()
        Log.i(TAG, "prepare(want=$want): enabledWifi=$enabledWifi enabledBt=$enabledBt nowOn=$nowOn")
        return nowOn
    }

    /**
     * Transfer TERMINAL (complete / declined / timeout). Turn back OFF only the
     * radios WE turned on in [prepare], restoring the user's original state. Clears
     * the persisted session.
     */
    fun finish(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabledWifi = prefs.getBoolean(KEY_ENABLED_WIFI, false)
        val enabledBt = prefs.getBoolean(KEY_ENABLED_BT, false)
        if (enabledWifi) RadioToggler.setWifiSilent(context, false)
        if (enabledBt) RadioToggler.setBluetooth(false)
        prefs.edit().clear().apply()
        Log.i(TAG, "finish: restored wifi=$enabledWifi bt=$enabledBt")
    }
}
