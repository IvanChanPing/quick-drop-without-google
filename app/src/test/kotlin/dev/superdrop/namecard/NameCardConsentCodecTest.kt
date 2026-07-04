/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [NameCardConsentCodec]: round-trip every opcode, every malformed case decodes
 * to null, and trailing bytes beyond a message are tolerated (forward-compat, plan B1).
 */
class NameCardConsentCodecTest {
    @Test
    fun `hello round-trips with version`() {
        val bytes = NameCardConsentCodec.encode(ConsentMessage.Hello(0x01.toByte()))
        assertArrayEquals(byteArrayOf(0x01, 0x01), bytes)
        assertEquals(ConsentMessage.Hello(0x01.toByte()), NameCardConsentCodec.decode(bytes))
    }

    @Test
    fun `helloBytes uses this build's protocol version`() {
        assertArrayEquals(byteArrayOf(0x01, 0x01), NameCardConsentCodec.helloBytes())
    }

    @Test
    fun `choice share round-trips`() {
        val bytes = NameCardConsentCodec.encode(ConsentMessage.ChoiceShare)
        assertArrayEquals(byteArrayOf(0x02), bytes)
        assertEquals(ConsentMessage.ChoiceShare, NameCardConsentCodec.decode(bytes))
    }

    @Test
    fun `choice receive-only round-trips`() {
        val bytes = NameCardConsentCodec.encode(ConsentMessage.ChoiceReceiveOnly)
        assertArrayEquals(byteArrayOf(0x03), bytes)
        assertEquals(ConsentMessage.ChoiceReceiveOnly, NameCardConsentCodec.decode(bytes))
    }

    @Test
    fun `bye round-trips`() {
        val bytes = NameCardConsentCodec.encode(ConsentMessage.Bye)
        assertArrayEquals(byteArrayOf(0x04), bytes)
        assertEquals(ConsentMessage.Bye, NameCardConsentCodec.decode(bytes))
    }

    @Test
    fun `empty array decodes to null`() {
        assertNull(NameCardConsentCodec.decode(ByteArray(0)))
    }

    @Test
    fun `unknown opcode decodes to null`() {
        assertNull(NameCardConsentCodec.decode(byteArrayOf(0x7f)))
        assertNull(NameCardConsentCodec.decode(byteArrayOf(0x00)))
    }

    @Test
    fun `hello shorter than two bytes decodes to null`() {
        assertNull(NameCardConsentCodec.decode(byteArrayOf(0x01)))
    }

    @Test
    fun `trailing bytes beyond a choice are tolerated`() {
        // A future build could append fields; we read only the known prefix.
        assertEquals(ConsentMessage.ChoiceShare, NameCardConsentCodec.decode(byteArrayOf(0x02, 0x55, 0x66)))
        assertEquals(ConsentMessage.Bye, NameCardConsentCodec.decode(byteArrayOf(0x04, 0x01)))
    }

    @Test
    fun `trailing bytes beyond a hello keep the version byte`() {
        assertEquals(
            ConsentMessage.Hello(0x01.toByte()),
            NameCardConsentCodec.decode(byteArrayOf(0x01, 0x01, 0x99.toByte())),
        )
    }
}
