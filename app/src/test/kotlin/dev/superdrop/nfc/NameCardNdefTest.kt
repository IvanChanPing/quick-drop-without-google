/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Pure-JVM tests for [NameCardNdef] (Name Card v2 raw NDEF codec — plan Appendix A2/A6).
 * No android.nfc / no Robolectric — the codec is deliberately framework-free so it runs
 * under the repo's plain junit4 test setup.
 */
class NameCardNdefTest {
    private val token = ByteArray(16) { (it + 1).toByte() }
    private val pkg = "dev.superdrop.debug"

    @Test
    fun `build then parseToken round-trips the token`() {
        val ndef = NameCardNdef.build(token, pkg)
        assertArrayEquals(token, NameCardNdef.parseToken(ndef))
    }

    @Test
    fun `message frames external record first then AAR last`() {
        val ndef = NameCardNdef.build(token, pkg)
        // Record 1: MB=1, ME=0, SR=1, TNF=external -> 0x80|0x10|0x04 = 0x94.
        assertTrue("rec1 MB set", (ndef[0].toInt() and 0x80) != 0)
        assertTrue("rec1 ME clear", (ndef[0].toInt() and 0x40) == 0)
        assertTrue("rec1 external TNF", (ndef[0].toInt() and 0x07) == 0x04)
        // The external type string must be present verbatim (lowercase, matches manifest).
        val typeStr = "superdrop.dev:namecard".toByteArray(StandardCharsets.US_ASCII)
        assertTrue("ext type present", indexOf(ndef, typeStr) >= 0)
        // AAR type + our package must be present (so the reader launches us from closed).
        assertTrue("aar type present", indexOf(ndef, "android.com:pkg".toByteArray(StandardCharsets.US_ASCII)) >= 0)
        assertTrue("aar payload = pkg", indexOf(ndef, pkg.toByteArray(StandardCharsets.US_ASCII)) >= 0)
        // Last record has ME set.
        assertTrue("some record has ME", ndef.any { (it.toInt() and 0x40) != 0 })
    }

    @Test
    fun `parseToken ignores an AAR-only message`() {
        // Hand-build a lone AAR record (external TNF, type android.com:pkg) -> must be null.
        val aarType = "android.com:pkg".toByteArray(StandardCharsets.US_ASCII)
        val aarPayload = pkg.toByteArray(StandardCharsets.US_ASCII)
        val rec = byteArrayOf((0x80 or 0x40 or 0x10 or 0x04).toByte(), aarType.size.toByte(), aarPayload.size.toByte()) +
            aarType + aarPayload
        assertNull(NameCardNdef.parseToken(rec))
    }

    @Test
    fun `parseToken rejects a foreign external type`() {
        val foreignType = "evil.example:namecard".toByteArray(StandardCharsets.US_ASCII)
        val payload = ByteArray(17).also { it[0] = 0x01 }
        val rec = byteArrayOf((0x80 or 0x40 or 0x10 or 0x04).toByte(), foreignType.size.toByte(), payload.size.toByte()) +
            foreignType + payload
        assertNull(NameCardNdef.parseToken(rec))
    }

    @Test
    fun `parseToken rejects wrong version`() {
        // Same framing as ours but version byte 0x02.
        val type = "superdrop.dev:namecard".toByteArray(StandardCharsets.US_ASCII)
        val payload = ByteArray(17).also { it[0] = 0x02 }
        val rec = byteArrayOf((0x80 or 0x40 or 0x10 or 0x04).toByte(), type.size.toByte(), payload.size.toByte()) +
            type + payload
        assertNull(NameCardNdef.parseToken(rec))
    }

    @Test
    fun `parseToken returns null on truncated garbage`() {
        assertNull(NameCardNdef.parseToken(byteArrayOf(0x94.toByte(), 0x16, 0x11, 0x00)))
        assertNull(NameCardNdef.parseToken(ByteArray(0)))
    }

    @Test
    fun `build rejects a wrong-length token`() {
        var threw = false
        try {
            NameCardNdef.build(ByteArray(8), pkg)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
