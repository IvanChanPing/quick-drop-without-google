/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.service.receiver.consent

import android.content.Context
import android.content.SharedPreferences

/**
 * User choice for HOW the incoming-transfer consent notification is
 * presented. Lives in `:service-android` so [ConsentNotification.build]
 * can read it when it constructs the heads-up; the `:app` SettingsFragment
 * reads + writes it too (the app module depends on `:service-android`).
 *
 * Three single-choice styles:
 *  - [Style.RECOLORED] (default) — the existing custom RemoteViews
 *    heads-up (`notification_consent`) with recolored Decline/Accept as a
 *    centered pair, via DecoratedCustomViewStyle.
 *  - [Style.BRIDGE] — a custom RemoteViews (`notification_consent_bridge`)
 *    styled like the shareit-bridge receive card (light surface, bold dark
 *    title, gray subtitle, Decline | divider | Accept button row).
 *  - [Style.SHEET] — no custom view at all; the standard/minimal
 *    notification (BigTextStyle + addAction Accept/Reject), with the
 *    full-screen / content intent raising the consent bottom sheet as the
 *    real surface.
 *
 * Mirrors the simple single-value prefs pattern used elsewhere
 * (e.g. `NfcTapSharePreferences`, `BugReportPreferences`).
 */
public class ConsentNotificationStylePreferences(
    private val prefs: SharedPreferences,
) {
    /** How the consent notification is presented. */
    public enum class Style {
        /** Existing custom RemoteViews heads-up; recolored centered button pair. */
        RECOLORED,

        /** Custom RemoteViews styled like the shareit-bridge receive card. */
        BRIDGE,

        /** Standard minimal notification; the bottom sheet is the surface. */
        SHEET,
    }

    public fun mode(): Style =
        when (prefs.getString(KEY_MODE, null)) {
            Style.BRIDGE.name -> Style.BRIDGE
            Style.SHEET.name -> Style.SHEET
            else -> Style.RECOLORED
        }

    public fun setMode(mode: Style) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    public companion object {
        private const val PREFS_NAME = "bada.consent_notification_style"
        private const val KEY_MODE = "consent_notification_style_mode"

        public fun from(context: Context): ConsentNotificationStylePreferences =
            ConsentNotificationStylePreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
