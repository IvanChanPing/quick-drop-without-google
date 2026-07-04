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
 * Pure-JVM tests for [NameCardResolver]'s per-field precedence: each field prefers
 * the in-app card and falls back to the device Contacts profile (then SIM for phone).
 */
class NameCardResolverTest {
    private fun sources(
        name: String? = null,
        number: String? = null,
        profilePhone: String? = null,
        email: String? = null,
    ) = object : DeviceContactSources {
        override fun profileDisplayName(): String? = name

        override fun profilePhoneNumber(): String? = profilePhone

        override fun profileEmail(): String? = email

        override fun simPhoneNumber(): String? = number
    }

    @Test
    fun `in-app card wins over device sources`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111")
        val resolver = NameCardResolver({ card }, sources(name = "Device Owner", number = "999"))
        assertEquals(card, resolver.resolve())
    }

    @Test
    fun `in-app fields win per field and the contacts profile fills the blanks`() {
        val card = NameCard(displayName = "Mike") // name only; no phone/email in-app
        val resolver =
            NameCardResolver(
                { card },
                sources(name = "Device Owner", profilePhone = "222", email = "mike@work.com", number = "999"),
            )
        val resolved = resolver.resolve()!!
        assertEquals("Mike", resolved.displayName) // in-app name wins
        assertEquals("222", resolved.phoneNumber) // profile phone fills the blank (before SIM)
        assertEquals("mike@work.com", resolved.email) // profile email fills the blank
    }

    @Test
    fun `profile phone is preferred over the SIM number when filling a blank`() {
        val resolver = NameCardResolver({ null }, sources(profilePhone = "222", number = "999"))
        assertEquals("222", resolver.resolve()!!.phoneNumber)
    }

    @Test
    fun `share selection drops the unchecked fields`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111", email = "m@e.com")
        val resolver = NameCardResolver({ card }, sources(), shareSelection = { setOf("name", "email") })
        val resolved = resolver.resolve()!!
        assertEquals("Mike", resolved.displayName)
        assertNull(resolved.phoneNumber) // "phone" unchecked -> dropped
        assertEquals("m@e.com", resolved.email)
    }

    @Test
    fun `null share selection shares every present field`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111")
        val resolver = NameCardResolver({ card }, sources(), shareSelection = { null })
        assertEquals(card, resolver.resolve())
    }

    @Test
    fun `an empty share selection resolves to null`() {
        val card = NameCard(displayName = "Mike", phoneNumber = "111")
        val resolver = NameCardResolver({ card }, sources(), shareSelection = { emptySet() })
        assertNull(resolver.resolve())
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
