/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import android.content.Context
import android.content.SharedPreferences
import dev.superdrop.protocol.namecard.NameCard

/**
 * **My Name Card profile store** — persists the contact card the user sets up
 * in the "Name Card" settings screen ([NameCardSetupActivity]) and shares by
 * tapping phones (NameDrop-style; see `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`).
 *
 * Backed by a private [SharedPreferences] file. Each field (name / phone /
 * email) is stored independently and may be blank; [load] assembles them into a
 * [NameCard], or returns `null` when the user has set up nothing (no name, no
 * phone, no email) so callers can fall back to device sources via
 * [NameCardResolver].
 *
 * Blank strings are normalised to "unset" (trimmed-empty → `null`) so an
 * accidentally-saved space never produces an empty TLV on the wire.
 *
 * How to test: pure SharedPreferences round-trip; exercised indirectly by
 * [NameCardResolver] tests (which inject a fake card). Status: compile-only here
 * (SharedPreferences needs an Android context); on-device verified via the
 * setup screen save/reload.
 */
internal class NameCardProfileStore(
    private val prefs: SharedPreferences,
) {
    /** The saved display name, or `null`/blank if unset. */
    fun displayName(): String? = prefs.getString(KEY_NAME, null)?.trimToNull()

    /** The saved phone number, or `null`/blank if unset. */
    fun phoneNumber(): String? = prefs.getString(KEY_PHONE, null)?.trimToNull()

    /** The saved email, or `null`/blank if unset. */
    fun email(): String? = prefs.getString(KEY_EMAIL, null)?.trimToNull()

    /** True once the user has entered at least one of name / phone / email. */
    fun isConfigured(): Boolean =
        displayName() != null || phoneNumber() != null || email() != null

    /**
     * Assemble the saved fields into a [NameCard], or `null` if nothing is set.
     * Never throws: a card requires ≥1 field, which [isConfigured] guarantees
     * before we construct it.
     */
    fun load(): NameCard? {
        if (!isConfigured()) return null
        return NameCard(
            displayName = displayName(),
            phoneNumber = phoneNumber(),
            email = email(),
        )
    }

    /** Persist the three fields (each blank → cleared). Applied asynchronously. */
    fun save(
        name: String?,
        phone: String?,
        email: String?,
    ) {
        prefs.edit()
            .putString(KEY_NAME, name?.trimToNull())
            .putString(KEY_PHONE, phone?.trimToNull())
            .putString(KEY_EMAIL, email?.trimToNull())
            .apply()
    }

    /** Wipe the saved card. */
    fun clear() {
        prefs.edit().remove(KEY_NAME).remove(KEY_PHONE).remove(KEY_EMAIL).apply()
    }

    private fun String.trimToNull(): String? = trim().ifEmpty { null }

    companion object {
        private const val PREFS_NAME = "bada.name_card_profile"
        private const val KEY_NAME = "name"
        private const val KEY_PHONE = "phone"
        private const val KEY_EMAIL = "email"

        fun from(context: Context): NameCardProfileStore =
            NameCardProfileStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
