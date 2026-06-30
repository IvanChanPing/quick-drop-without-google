/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import dev.superdrop.protocol.namecard.NameCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [NameCardResolver]'s fallback precedence: in-app card →
 * device "Me"/SIM → bare number → nothing.
 */
class NameCardResolverTest {
    private fun sources(
        name: String? = null,
        number: String? = null,
    ) = object : DeviceContactSources {
        override fun profileDisplayName(): String? = name

        override fun simPhoneNumber(): String? = number
    }

    @Test
    fun `in-app card wins over device sources`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111")
        val resolver = NameCardResolver({ card }, sources(name = "Device Owner", number = "999"))
        assertEquals(card, resolver.resolve())
    }

    @Test
    fun `falls back to device name and SIM number when no in-app card`() {
        val resolver = NameCardResolver({ null }, sources(name = "Device Owner", number = "999"))
        assertEquals(NameCard(displayName = "Device Owner", phoneNumber = "999"), resolver.resolve())
    }

    @Test
    fun `falls back to a bare-number card when only the SIM number is known`() {
        val resolver = NameCardResolver({ null }, sources(name = null, number = "5550199"))
        val resolved = resolver.resolve()
        assertEquals("5550199", resolved!!.phoneNumber)
        assertNull(resolved.displayName)
    }

    @Test
    fun `falls back to a name-only card when only the device name is known`() {
        val resolver = NameCardResolver({ null }, sources(name = "Device Owner", number = null))
        val resolved = resolver.resolve()
        assertEquals("Device Owner", resolved!!.displayName)
        assertNull(resolved.phoneNumber)
    }

    @Test
    fun `resolves to null when nothing is available`() {
        val resolver = NameCardResolver({ null }, sources())
        assertNull(resolver.resolve())
        assertFalse(resolver.canResolve())
    }

    @Test
    fun `blank device strings are treated as absent`() {
        val resolver = NameCardResolver({ null }, sources(name = "   ", number = ""))
        assertNull(resolver.resolve())
    }

    @Test
    fun `canResolve is true when a device fallback exists`() {
        val resolver = NameCardResolver({ null }, sources(number = "999"))
        assertTrue(resolver.canResolve())
    }
}
