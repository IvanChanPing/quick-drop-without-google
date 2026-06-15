/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.content.Context
import android.content.SharedPreferences

/**
 * User toggle for the **on-screen NFC tap diagnostics** — the Toasts that the send
 * sheet shows for every NFC tap (read outcome SELECT/ADV bytes, "WOKE", auto-connect
 * target, "no receiver found in 15s", …). It gates ONLY the visible Toasts; the silent
 * in-memory `DiagnosticLog` ring keeps recording regardless (so a bug report still has
 * the full trace).
 *
 * What it's called: "Show NFC tap diagnostics" switch in Settings
 * (`R.id.settings_nfc_diagnostics_switch`). Read at the single Toast choke point
 * `SendActivity.nfcTapToast`. Default ON so existing behavior is preserved; flip it OFF
 * for a clean, Toast-free tap once everything is working.
 */
internal class NfcTapDiagnosticsPreferences(
    private val prefs: SharedPreferences,
) {
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    internal companion object {
        private const val PREFS_NAME = "bada.nfc_tap_diagnostics"
        private const val KEY_ENABLED = "nfc_tap_diagnostics_enabled"

        /** ON by default: preserve the current (post-fix) on-screen diagnostics. */
        private const val DEFAULT_ENABLED = true

        fun from(context: Context): NfcTapDiagnosticsPreferences =
            NfcTapDiagnosticsPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
