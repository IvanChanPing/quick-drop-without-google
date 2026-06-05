/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.nfc

/**
 * Process-global handoff between the Send UI (which knows the current
 * Super Drop pairing link) and the [SuperDropNdefApduService] HCE service
 * (which serves that link as an NFC Forum Type-4 NDEF URI record when an
 * iPhone is tapped to the back of the phone).
 *
 * The two live in different Android components — the Activity and the
 * `HostApduService` — that the platform may instantiate in the same
 * process at unrelated times, with no shared `Intent`/`Bundle` channel
 * available at tap time (the reader selects the tag with no app in the
 * foreground). A single `@Volatile` field is the simplest correct bridge:
 *
 *  - [dev.superdrop.send.SendActivity] sets [currentUrl] whenever it
 *    (re)generates the QR pairing URL for display, and clears it back to
 *    `null` when the QR session ends (panel dismissed, auto-connect, or
 *    `onDestroy`).
 *  - [SuperDropNdefApduService] reads [currentUrl] at the moment a reader
 *    SELECTs the NDEF tag application. A non-null value is emitted as a
 *    URI record; `null` yields an empty NDEF message (NLEN = 0), so a tap
 *    while no QR is on screen does nothing rather than opening a stale
 *    link.
 *
 * `@Volatile` guarantees the HCE service (which runs the APDU callbacks on
 * a binder thread) observes the latest write from the UI thread without a
 * full lock; the field is a plain reference assignment so no compound
 * read-modify-write race exists.
 */
public object NfcLinkHolder {
    /**
     * The Super Drop pairing link to broadcast over NFC, or `null` when no
     * QR/link share is currently active. Written by the Send UI, read by
     * the HCE service.
     */
    @Volatile
    public var currentUrl: String? = null
}
