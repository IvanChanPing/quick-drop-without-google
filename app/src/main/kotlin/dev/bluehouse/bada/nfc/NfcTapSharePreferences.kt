/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.content.Context
import android.content.SharedPreferences

/**
 * User choice for WHEN this device is tappable for NFC tap-to-share — the
 * receiver-side window during which the tap-to-share HCE advertisement is
 * live. This is a SEPARATE setting from the mDNS/BLE "visible" toggle (by
 * user request); the NFC tap-to-share HCE reads this to decide its
 * lifetime. Default is the most conservative mode.
 *
 * NOTE: the tap-to-share HCE itself is implemented in a later change; this
 * preference + its Settings control exist now so the choice is visible and
 * persisted, and the HCE wires to it when it lands.
 */
internal class NfcTapSharePreferences(
    private val prefs: SharedPreferences,
) {
    /** When the receiver is tappable for NFC tap-to-share. */
    internal enum class Mode {
        /** Only while a receive sheet (the tile-opened / consent sheet) is open. */
        SHEET_OPEN,

        /** Whenever the app is in the foreground. */
        APP_FOREGROUND,

        /** Always, including while the app is backgrounded (persistent). */
        BACKGROUND,
    }

    fun mode(): Mode =
        when (prefs.getString(KEY_MODE, null)) {
            Mode.APP_FOREGROUND.name -> Mode.APP_FOREGROUND
            Mode.BACKGROUND.name -> Mode.BACKGROUND
            else -> Mode.SHEET_OPEN
        }

    fun setMode(mode: Mode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    internal companion object {
        private const val PREFS_NAME = "bada.nfc_tap_share"
        private const val KEY_MODE = "nfc_tap_share_mode"

        fun from(context: Context): NfcTapSharePreferences =
            NfcTapSharePreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
