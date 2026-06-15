/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.nfc

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-global handoff carrying the receiver's CURRENT tap-to-share
 * identity from the receiver service ([dev.bluehouse.bada.service] —
 * `ReceiverForegroundService`) to the Quick Share HCE
 * (`BadaTapHceService` in `:app`).
 *
 * Mirrors the role of `NfcLinkHolder` (which carries the iPhone-link QR
 * URL) but for the Quick Share tap path: the HCE service has no shared
 * `Intent`/`Bundle` channel with the receiver service at tap time, so a
 * single `@Volatile`/atomic field is the simplest correct bridge.
 *
 *  - The receiver service publishes [Link] whenever it (re)binds its TCP
 *    listener / refreshes its advertised identity, and clears it to `null`
 *    when the receiver stops.
 *  - The HCE service reads [current] at the moment a reader SELECTs the
 *    `F00000FE2C` application and on each ADVERTISEMENT APDU, building its
 *    `hhwv` response from the live link. A `null` link means "we are not a
 *    live receiver right now" and the HCE answers SELECT but returns no
 *    tag (so a stray tap is a no-op rather than pointing at a dead port).
 *
 * The [serviceIdHash] is carried alongside the endpoint so the HCE never
 * has to depend on the discovery/identity modules.
 */
public object NfcTapLinkHolder {
    /**
     * Snapshot of the receiver's live tap-to-share link, or `null` when no
     * receiver is currently advertising.
     *
     * @property endpointId the receiver's 4-byte endpoint id (ASCII).
     * @property serviceIdHash the 3-byte Nearby service-id hash prefix.
     * @property endpointInfo the serialized Nearby EndpointInfo blob.
     * @property address the receiver's Wi-Fi-LAN address.
     * @property port the receiver's bound TCP port.
     */
    public data class Link(
        val endpointId: ByteArray,
        val serviceIdHash: ByteArray,
        val endpointInfo: ByteArray,
        val address: InetAddress,
        val port: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Link) return false
            return endpointId.contentEquals(other.endpointId) &&
                serviceIdHash.contentEquals(other.serviceIdHash) &&
                endpointInfo.contentEquals(other.endpointInfo) &&
                address == other.address &&
                port == other.port
        }

        override fun hashCode(): Int {
            var result = endpointId.contentHashCode()
            result = 31 * result + serviceIdHash.contentHashCode()
            result = 31 * result + endpointInfo.contentHashCode()
            result = 31 * result + address.hashCode()
            result = 31 * result + port
            return result
        }
    }

    private val ref: AtomicReference<Link?> = AtomicReference(null)

    /** The current receiver link, or `null` when no receiver is live. */
    public val current: Link?
        get() = ref.get()

    /** Publish (or replace) the live receiver link. */
    public fun set(link: Link) {
        ref.set(link)
    }

    /** Clear the link (receiver stopped). */
    public fun clear() {
        ref.set(null)
    }
}
