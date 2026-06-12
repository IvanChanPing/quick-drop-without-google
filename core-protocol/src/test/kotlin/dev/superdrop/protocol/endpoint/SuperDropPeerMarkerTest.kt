/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.protocol.endpoint

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the Super Drop "recognise-Bada" peer marker ([SuperDropPeerMarker]).
 *
 * Covers: the marker survives a full [EndpointInfo] serialize/parse round-trip and is
 * then detected by [hasSuperDropMarker]; a stock-shaped EndpointInfo (no marker, or
 * carrying only the standard QR/vendor TLVs) is NOT detected; [withSuperDropMarker] is
 * idempotent and preserves existing TLVs; and a type-collision (a future stock TLV that
 * happens to reuse type `0xBA` with a different value) does NOT false-positive.
 */
class SuperDropPeerMarkerTest {
    private fun metadata(): ByteArray = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() }

    @Test
    fun `marker round-trips through serialize-parse and is detected`() {
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = metadata(),
                deviceName = "Pixel 8",
                tlvRecords = emptyList<TlvRecord>().withSuperDropMarker(),
            )
        val parsed = EndpointInfo.parse(info.serialize())
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.hasSuperDropMarker()).isTrue()
        assertThat(parsed.deviceName).isEqualTo("Pixel 8")
    }

    @Test
    fun `stock EndpointInfo without the marker is not detected`() {
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = metadata(),
                deviceName = "Galaxy S24",
                tlvRecords = listOf(TlvRecord(EndpointInfo.TLV_TYPE_VENDOR_ID, byteArrayOf(1))),
            )
        assertThat(info.hasSuperDropMarker()).isFalse()
        assertThat(EndpointInfo.parse(info.serialize())!!.hasSuperDropMarker()).isFalse()
    }

    @Test
    fun `withSuperDropMarker is idempotent`() {
        val once = emptyList<TlvRecord>().withSuperDropMarker()
        val twice = once.withSuperDropMarker()
        assertThat(twice).hasSize(1)
        assertThat(twice.count { SuperDropPeerMarker.isMarker(it) }).isEqualTo(1)
    }

    @Test
    fun `withSuperDropMarker preserves existing TLV records in order`() {
        val qr = TlvRecord(EndpointInfo.TLV_TYPE_QR_CODE, ByteArray(16) { it.toByte() })
        val result = listOf(qr).withSuperDropMarker()
        assertThat(result).hasSize(2)
        assertThat(result[0]).isEqualTo(qr)
        assertThat(SuperDropPeerMarker.isMarker(result[1])).isTrue()
    }

    @Test
    fun `same type but wrong value is not treated as the marker`() {
        // A hypothetical future stock TLV reusing type 0xBA with a non-"SD" value must
        // NOT be mistaken for our marker.
        val impostor = TlvRecord(SuperDropPeerMarker.TYPE, byteArrayOf(0x00, 0x01, 0x02))
        assertThat(SuperDropPeerMarker.isMarker(impostor)).isFalse()
        val info =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = metadata(),
                deviceName = "Other",
                tlvRecords = listOf(impostor),
            )
        assertThat(info.hasSuperDropMarker()).isFalse()
    }

    @Test
    fun `marker type avoids the known QR and vendor TLV types`() {
        assertThat(SuperDropPeerMarker.TYPE).isNotEqualTo(EndpointInfo.TLV_TYPE_QR_CODE)
        assertThat(SuperDropPeerMarker.TYPE).isNotEqualTo(EndpointInfo.TLV_TYPE_VENDOR_ID)
    }
}
