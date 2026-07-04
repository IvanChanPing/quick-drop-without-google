/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import dev.superdrop.protocol.namecard.NameCard

/**
 * **Name Card source resolver** — decides WHICH card this phone shares when two
 * phones tap (NameDrop-style; see `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`).
 *
 * Per-field precedence (user's decision): each field prefers the in-app **My Name
 * Card** ([NameCardProfileStore]) value and falls back to the device Contacts
 * profile for any field the user left blank in the app:
 *  - name  = in-app name  ?: Contacts "Me"/profile display name
 *  - phone = in-app phone ?: Contacts profile phone ?: SIM line number
 *  - email = in-app email ?: Contacts profile email
 *
 * All device reads go through [DeviceContactSources] (permission-gated; any read
 * may return `null`). When every field is blank across both sources, [resolve]
 * returns `null` and the UI prompts the user to set up a card.
 *
 * The device-side reads (SIM number, "Me" name) sit behind [DeviceContactSources]
 * so this precedence logic is pure and unit-testable without Android (see
 * `NameCardResolverTest`). [AndroidDeviceContactSources] is the real implementation.
 *
 * Status: precedence logic unit-tested here; the Android source reads are
 * device-verified (permissions + OEM behaviour).
 */
internal class NameCardResolver(
    /** Loads the in-app My Name Card, or `null` if not set up. Normally `store::load`. */
    private val storedCard: () -> NameCard?,
    private val deviceSources: DeviceContactSources,
    /**
     * The fields the user chose to share (keys [FIELD_NAME]/[FIELD_PHONE]/[FIELD_EMAIL]),
     * or `null` to share every present field. Normally `store::shareSelection`. Applied
     * after the per-field merge so an unselected field is dropped from the shared card.
     */
    private val shareSelection: () -> Set<String>? = { null },
) {
    /**
     * Resolve the card to share, or `null` when this phone has nothing to offer
     * (no in-app card AND no device name/number) — callers then nudge the user
     * to fill in their Name Card.
     */
    fun resolve(): NameCard? {
        val stored = storedCard()

        // Per-field: in-app value wins; Contacts profile (then SIM for phone) fills any blank.
        val name = stored?.displayName ?: clean(deviceSources.profileDisplayName())
        val phone = stored?.phoneNumber
            ?: clean(deviceSources.profilePhoneNumber())
            ?: clean(deviceSources.simPhoneNumber())
        val email = stored?.email ?: clean(deviceSources.profileEmail())

        // Drop any field the user unchecked in "Choose what to share" (null = share all).
        val selection = shareSelection()
        val outName = if (selection == null || FIELD_NAME in selection) name else null
        val outPhone = if (selection == null || FIELD_PHONE in selection) phone else null
        val outEmail = if (selection == null || FIELD_EMAIL in selection) email else null

        if (outName == null && outPhone == null && outEmail == null) return null
        return NameCard(displayName = outName, phoneNumber = outPhone, email = outEmail)
    }

    private fun clean(value: String?): String? = value?.trim()?.ifEmpty { null }

    companion object {
        /** Share-selection field keys (persisted in [NameCardProfileStore.shareSelection]). */
        const val FIELD_NAME = "name"
        const val FIELD_PHONE = "phone"
        const val FIELD_EMAIL = "email"
    }

    /** True when [resolve] would return a card (in-app or device fallback). */
    fun canResolve(): Boolean = resolve() != null
}

/**
 * Device-side fallback sources for [NameCardResolver]. Abstracted behind an
 * interface so the resolver's precedence is testable on a plain JVM. The real
 * reads require runtime permissions and may legitimately return `null` (denied,
 * unavailable, eSIM with no readable number, no "Me" contact).
 */
internal interface DeviceContactSources {
    /** The device owner's display name from the "Me"/profile contact, or `null`. */
    fun profileDisplayName(): String?

    /** A phone number from the device "Me"/profile contact, or `null`. */
    fun profilePhoneNumber(): String?

    /** An email address from the device "Me"/profile contact, or `null`. */
    fun profileEmail(): String?

    /** The SIM/line phone number, or `null` if unavailable/denied. */
    fun simPhoneNumber(): String?
}
