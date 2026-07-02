/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [NameCardNdef] (Name Card v2 NDEF codec — plan Appendix A2/A6). Runs under Robolectric
 * because it exercises the real android.nfc NdefMessage/NdefRecord framework classes. Executed by the
 * dedicated `:app:robolectricDebugUnitTest` Gradle task (offline android-all SDK).
 *
 * Pinned to SDK 35 (Android 15): that matches the cached `android-all-15-robolectric` jar and runs on
 * Java 17. Without the pin Robolectric picks :app's compileSdk (36), which requires Java 21.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NameCardNdefTest {
    private val token = ByteArray(16) { (it + 1).toByte() }
    private val pkg = "dev.superdrop.debug"

    @Test
    fun `build then parseToken round-trips the token`() {
        val msg = NameCardNdef.build(token, pkg)
        assertArrayEquals(token, NameCardNdef.parseToken(msg))
    }

    @Test
    fun `build emits external record first then AAR last`() {
        val msg = NameCardNdef.build(token, pkg)
        assertEquals(2, msg.records.size)
        val ext = msg.records[0]
        assertEquals(NdefRecord.TNF_EXTERNAL_TYPE, ext.tnf)
        assertArrayEquals("superdrop.dev:namecard".toByteArray(Charsets.US_ASCII), ext.type)
        assertEquals(17, ext.payload.size)
        assertEquals(0x01.toByte(), ext.payload[0])
        // Record 2 is the AAR for our package (must be last so nothing shadows it in dispatch).
        assertArrayEquals(
            NdefRecord.createApplicationRecord(pkg).toByteArray(),
            msg.records[1].toByteArray(),
        )
    }

    @Test
    fun `parseToken ignores the AAR record even though it is external-type`() {
        val onlyAar = NdefMessage(arrayOf(NdefRecord.createApplicationRecord(pkg)))
        assertNull(NameCardNdef.parseToken(onlyAar))
    }

    @Test
    fun `parseToken rejects a foreign external type`() {
        val payload = ByteArray(17).also { it[0] = 0x01 }
        val foreign = NdefMessage(arrayOf(NdefRecord.createExternal("evil.example", "namecard", payload)))
        assertNull(NameCardNdef.parseToken(foreign))
    }

    @Test
    fun `parseToken rejects wrong version and wrong payload size`() {
        val badVersion = ByteArray(17).also { it[0] = 0x02 }
        val short = ByteArray(5).also { it[0] = 0x01 }
        assertNull(
            NameCardNdef.parseToken(
                NdefMessage(arrayOf(NdefRecord.createExternal("superdrop.dev", "namecard", badVersion))),
            ),
        )
        assertNull(
            NameCardNdef.parseToken(
                NdefMessage(arrayOf(NdefRecord.createExternal("superdrop.dev", "namecard", short))),
            ),
        )
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
}
