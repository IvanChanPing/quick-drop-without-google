/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import android.content.Context
import android.content.SharedPreferences

/**
 * **Name Card feature on/off preference.** Master switch for the NameDrop-style
 * tap-to-share-contacts feature (see the Name Card design notes).
 * Set from the **"Share my card when phones tap"** switch on the My Name Card
 * setup screen ([NameCardSetupActivity]).
 *
 * Read at the two entry points so a tap is a complete no-op when OFF:
 *  - [dev.bluehouse.bada.nfc.NameCardHceService] — when OFF it does not answer the
 *    Name Card AID (no token minted, nothing served) so this phone is not
 *    tappable as a card.
 *  - [dev.bluehouse.bada.MainActivity] — when OFF it does not arm the reader, so
 *    tapping another phone does nothing.
 *
 * Default **ON** (the feature is the point; it still does nothing until the user
 * has set up a card / has a device fallback). Backed by a private SharedPreferences
 * file. Status: compile-only here; on-device verified via the switch.
 */
internal class NameCardPreferences(
    private val prefs: SharedPreferences,
) {
    /** True (default) when tap-to-share-contacts is enabled. */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * **Name Card v2 (symmetric NameDrop) dev gate.** When true, the both-background
     * NDEF+AAR tap trigger is live: [dev.bluehouse.bada.nfc.BadaNdefApduService] serves
     * the Name Card NDEF (token + AAR) whenever the QR pairing link is NOT armed, and
     * [dev.bluehouse.bada.MainActivity] does NOT arm the legacy foreground
     * [dev.bluehouse.bada.nfc.NameCardTapReader] (reader-mode would suppress our own card and
     * break the symmetric model). Default **false** while the v2 flow is device-unproven —
     * flipping the default to true (Name Card = the always-on default NFC feature per the
     * three-feature priority ladder) is the post-verification step. See
     * the Name Card design notes Appendix A1/A5.
     */
    fun isV2Enabled(): Boolean = prefs.getBoolean(KEY_V2, false)

    fun setV2Enabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_V2, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "bada.name_card_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_V2 = "v2_symmetric"

        fun from(context: Context): NameCardPreferences =
            NameCardPreferences(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
