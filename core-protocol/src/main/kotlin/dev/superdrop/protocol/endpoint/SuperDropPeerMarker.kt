/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.protocol.endpoint

/**
 * Super Drop "recognise-Bada" peer marker.
 *
 * ### What this is / what it's called
 * A small, reserved-type [TlvRecord] that Super Drop appends to its **advertised
 * receiver [EndpointInfo]** so that *another* Super Drop device can tell, in its
 * send picker, that a discovered peer is Super Drop (Bada) rather than a stock
 * Google Quick Share / Samsung receiver. User-facing surface: the discovered
 * device chip in the send sheet shows a small **"Super Drop"** badge under the
 * device name (see `DeviceIconView.setSuperDrop`). There is no setting to toggle
 * it — it rides every visible receiver advertisement automatically.
 *
 * ### Why a TLV (and why this is safe)
 * The Quick Share `EndpointInfo` wire format ends in zero or more `type|len|value`
 * TLV records. **Unknown TLV types round-trip verbatim and are ignored by stock
 * parsers** (see [TlvRecord] doc and [EndpointInfo.parse]), so adding our own
 * reserved type is invisible to Google/Samsung peers and does not change how they
 * see or connect to us. Verified in this codebase:
 *  * The compact BLE `0xFEF3` primary advertisement is a *fixed-size* GATT header
 *    (`BleAdvertisementHeader.encodeSingleSlot`); the EndpointInfo bytes only feed
 *    its 4-byte content hash, so this marker does **not** consume the scarce 31-byte
 *    legacy advertising budget. The full EndpointInfo (and thus this marker) travels
 *    over the GATT-slot read, the extended visible advertisement, and mDNS TXT.
 *  * Peer identity is the 16-byte `EndpointInfo.metadata`, not a hash of the whole
 *    blob, so a trailing marker cannot break stock contacts/visibility recognition.
 *  * `QrTlvMatcher` skips non-`TLV_TYPE_QR_CODE` records, so QR matching is untouched.
 *
 * ### Wire shape
 * `type = `[TYPE]` (0xBA)`, `value = `["SD" magic][VERSION]` (3 bytes). Detection
 * requires **both** the type byte and the "SD" magic prefix, so even if a future
 * stock Quick Share TLV happens to reuse type `0xBA`, its value will not match our
 * magic and we will not mis-badge a stock device as Super Drop.
 *
 * ### How to test
 * Pure-JVM KAT in `SuperDropPeerMarkerTest` (round-trips through
 * [EndpointInfo.serialize]/[EndpointInfo.parse], confirms stock-shaped EndpointInfos
 * are not detected, and confirms existing TLVs are preserved). End-to-end (two real
 * devices: one Super Drop receiver shows the badge in the other's picker) is
 * **UNVERIFIED** until tested on hardware.
 *
 * Status: implemented; pure-JVM logic unit-tested; on-device badge UNVERIFIED.
 */
public object SuperDropPeerMarker {
    /**
     * Reserved TLV type byte for the Super Drop marker. `0xBA` (mnemonic "BA"da)
     * is deliberately high to avoid colliding with Google's low, sequentially
     * allocated TLV types (`1` = QR code, `2` = vendor id are already defined).
     */
    public const val TYPE: Int = 0xBA

    /** Format version of the marker [value] payload. Bumped if the value layout changes. */
    public const val VERSION: Int = 0x01

    /** ASCII magic prefix ("SD") that distinguishes our marker from a coincidental type reuse. */
    private val MAGIC: ByteArray = byteArrayOf('S'.code.toByte(), 'D'.code.toByte())

    /** The full marker value: `MAGIC || VERSION`. */
    private val VALUE: ByteArray = MAGIC + byteArrayOf(VERSION.toByte())

    /** Builds a fresh [TlvRecord] carrying the Super Drop marker. */
    public fun record(): TlvRecord = TlvRecord(type = TYPE, value = VALUE.copyOf())

    /** True if [record] is a Super Drop marker (correct type *and* "SD" magic prefix). */
    public fun isMarker(record: TlvRecord): Boolean =
        record.type == TYPE &&
            record.value.size >= MAGIC.size &&
            record.value.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
}

/**
 * Returns a copy of this TLV list with the Super Drop marker appended, unless a
 * marker is already present (idempotent — safe to call when carrying forward a
 * previous identity's records). Existing records are preserved in order.
 */
public fun List<TlvRecord>.withSuperDropMarker(): List<TlvRecord> =
    if (any(SuperDropPeerMarker::isMarker)) {
        this
    } else {
        this + SuperDropPeerMarker.record()
    }

/** True if this peer's [EndpointInfo] carries the Super Drop marker TLV. */
public fun EndpointInfo.hasSuperDropMarker(): Boolean = tlvRecords.any(SuperDropPeerMarker::isMarker)
