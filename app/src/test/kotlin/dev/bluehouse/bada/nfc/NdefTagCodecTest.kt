/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Pure-JVM tests for [NdefTagCodec]'s byte encoders. The encoders are
 * deterministic companion functions with no Android dependency, so they run on a
 * host JVM without Robolectric.
 */
class NdefTagCodecTest {
    @Test
    fun `buildUriNdefMessage uses the correct NFC Forum URI prefix codes`() {
        // NFC Forum URI RTD identifier codes: 0x01=http://www., 0x02=https://www.,
        // 0x03=http://, 0x04=https://. The prefix code is the first payload byte.
        assertEquals(0x04, prefixCodeOf("https://quickshare.google/qrcode#key=abc"))
        assertEquals(0x03, prefixCodeOf("http://example.com/x"))
        assertEquals(0x02, prefixCodeOf("https://www.example.com/x"))
        assertEquals(0x01, prefixCodeOf("http://www.example.com/x"))
    }

    @Test
    fun `buildUriNdefMessage strips the matched prefix from the body`() {
        val url = "https://quickshare.google/qrcode#key=abc"
        val msg = NdefTagCodec.buildUriNdefMessage(url)
        // 0x04 = https://, so the body is the URL minus the "https://" prefix.
        assertEquals("quickshare.google/qrcode#key=abc", uriBodyOf(msg))
    }

    @Test
    fun `buildUriNdefMessage emits a well-formed short well-known U record`() {
        val msg = NdefTagCodec.buildUriNdefMessage("https://a")
        // Record header: MB=1,ME=1,SR=1,TNF=Well-Known(0x01) -> 0xD1.
        assertEquals(0xD1.toByte(), msg[0])
        // Type length = 1, then payload length, then type 'U' (0x55).
        assertEquals(0x01.toByte(), msg[1])
        assertEquals('U'.code.toByte(), msg[3])
        // Payload length byte == 1 (prefix code) + body bytes == total minus 4-byte header.
        assertEquals(msg.size - 4, msg[2].toInt() and 0xFF)
    }

    @Test
    fun `apduSelectsAid matches the NDEF Tag Application AID and rejects others`() {
        val ndefAid = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val select = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, ndefAid.size.toByte()) + ndefAid
        assertTrue(NdefTagCodec.apduSelectsAid(select, ndefAid))

        val wrong = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x02, 0x11, 0x22)
        assertFalse(NdefTagCodec.apduSelectsAid(wrong, ndefAid))
    }

    private fun prefixCodeOf(uri: String): Int {
        // Layout: [hdr][typeLen][payloadLen]['U'][prefixCode][body...]
        return NdefTagCodec.buildUriNdefMessage(uri)[4].toInt() and 0xFF
    }

    private fun uriBodyOf(msg: ByteArray): String = String(msg.copyOfRange(5, msg.size), StandardCharsets.UTF_8)
}
