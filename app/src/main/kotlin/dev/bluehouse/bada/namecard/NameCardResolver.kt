/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import dev.bluehouse.bada.protocol.namecard.NameCard

/**
 * **Name Card source resolver** — decides WHICH card this phone shares when two
 * phones tap (NameDrop-style; see `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`).
 *
 * Fallback chain (user's decision):
 *  1. The in-app **My Name Card** the user set up ([NameCardProfileStore]).
 *  2. Else, whatever the phone already has: the device "Me"/profile display name
 *     + the SIM line number (read via [DeviceContactSources], permission-gated).
 *  3. Else, if only a number is known, a **bare-number** card.
 *  4. Else `null` — nothing to share; the UI prompts the user to set up a card.
 *
 * The device-side reads (SIM number, "Me" name) sit behind [DeviceContactSources]
 * so this precedence logic is pure and unit-testable without Android (see
 * `NameCardResolverTest`). [AndroidDeviceContactSources] is the real implementation.
 */
internal class NameCardResolver(
    /** Loads the in-app My Name Card, or `null` if not set up. Normally `store::load`. */
    private val storedCard: () -> NameCard?,
    private val deviceSources: DeviceContactSources,
) {
    /**
     * Resolve the card to share, or `null` when this phone has nothing to offer
     * (no in-app card AND no device name/number) — callers then nudge the user
     * to fill in their Name Card.
     */
    @Suppress("ReturnCount")
    fun resolve(): NameCard? {
        storedCard()?.let { return it }

        val name = deviceSources.profileDisplayName()?.trim()?.ifEmpty { null }
        val number = deviceSources.simPhoneNumber()?.trim()?.ifEmpty { null }
        if (name == null && number == null) return null

        return NameCard(displayName = name, phoneNumber = number)
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

    /** The SIM/line phone number, or `null` if unavailable/denied. */
    fun simPhoneNumber(): String?
}
