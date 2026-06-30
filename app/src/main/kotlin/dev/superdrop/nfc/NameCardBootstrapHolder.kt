/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.nfc

import dev.superdrop.protocol.namecard.NameCardBootstrap
import java.security.SecureRandom

/**
 * **Name Card NFC bridge** — process-global handoff between the Name Card NFC
 * components and the (P4) Bluetooth rendezvous, mirroring [NfcLinkHolder].
 *
 * The tap is only a TRIGGER (see `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`).
 * Two sides meet here:
 *
 *  - **Card side** ([NameCardHceService]): on a tap it calls [newSession] to mint
 *    a fresh rendezvous token, returns it over NFC, and leaves it in
 *    [activeToken] so the Bluetooth layer (P4) knows which token to advertise.
 *  - **Reader side** ([NameCardTapReader]): on a tap it parses the peer's token
 *    and calls [onPeerTapped]; the Bluetooth layer (P4) connects by scanning for
 *    that token.
 *
 * Both sides also "wake" the app simply by being invoked (the `HostApduService`
 * callback and the reader Activity both run our process). P4/P5 set
 * [peerTapListener] and consume [activeToken]; until they land, this just records
 * the session so the trigger is observable in diagnostics.
 *
 * `@Volatile` is sufficient: each field is a plain reference write/read across the
 * binder-thread HCE callback and the UI thread, with no compound race.
 */
internal object NameCardBootstrapHolder {
    private val secureRandom = SecureRandom()

    /**
     * The rendezvous token for the current/last tap session, or `null` if none.
     * Written by [newSession] (card side) or [recordPeer] (reader side); read by
     * the Bluetooth rendezvous (P4).
     */
    @Volatile
    var activeToken: ByteArray? = null
        private set

    /**
     * Optional callback invoked (on a binder thread) when the reader side reads a
     * peer's bootstrap. Set by the foreground share controller (P5); the Bluetooth
     * layer then scans for [NameCardBootstrap.token]. Null until wired.
     */
    @Volatile
    var peerTapListener: ((NameCardBootstrap) -> Unit)? = null

    /**
     * Card side: mint a fresh bootstrap (new random token) for THIS tap, remember
     * its token as [activeToken], and return it to emit over NFC.
     */
    fun newSession(): NameCardBootstrap {
        val token = ByteArray(NameCardBootstrap.TOKEN_LEN).also { secureRandom.nextBytes(it) }
        activeToken = token
        return NameCardBootstrap(version = NameCardBootstrap.CURRENT_VERSION, token = token)
    }

    /** Reader side: record the peer's token and notify any [peerTapListener]. */
    fun recordPeer(bootstrap: NameCardBootstrap) {
        activeToken = bootstrap.token
        peerTapListener?.invoke(bootstrap)
    }

    /** Clear session state (call when the exchange finishes or is abandoned). */
    fun clear() {
        activeToken = null
    }
}
