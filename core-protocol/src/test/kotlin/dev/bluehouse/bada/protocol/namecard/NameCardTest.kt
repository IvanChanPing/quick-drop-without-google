/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.namecard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Pure-JVM tests for [NameCard] — the contact blob swapped by the tap-to-share
 * Name Card feature. Covers round-trip fidelity, optional fields (the
 * "number-only" fallback), forward-compat unknown-TLV preservation, and that
 * malformed input parses to `null` rather than throwing.
 */
class NameCardTest {
    @Test
    fun `round-trips a full card`() {
        val card =
            NameCard(
                displayName = "Mike Peskoff",
                phoneNumber = "+1 415 555 0199",
                email = "mike@example.com",
            )
        assertThat(NameCard.parse(card.serialize())).isEqualTo(card)
    }

    @Test
    fun `round-trips a name-only card`() {
        val card = NameCard(displayName = "Mike")
        val parsed = NameCard.parse(card.serialize())
        assertThat(parsed).isEqualTo(card)
        assertThat(parsed!!.phoneNumber).isNull()
        assertThat(parsed.email).isNull()
    }

    @Test
    fun `round-trips a number-only card (the no-profile fallback)`() {
        val card = NameCard(phoneNumber = "5550199")
        val parsed = NameCard.parse(card.serialize())
        assertThat(parsed).isEqualTo(card)
        assertThat(parsed!!.displayName).isNull()
    }

    @Test
    fun `round-trips non-ASCII UTF-8 names`() {
        val card = NameCard(displayName = "Михаил 日本語 😀", phoneNumber = "123")
        assertThat(NameCard.parse(card.serialize())).isEqualTo(card)
    }

    @Test
    fun `preserves unknown TLV fields across a round trip (forward compat)`() {
        val future = NameCardField(type = 99, value = byteArrayOf(1, 2, 3, 4))
        val card = NameCard(displayName = "Mike", extraFields = listOf(future))
        val parsed = NameCard.parse(card.serialize())
        assertThat(parsed).isEqualTo(card)
        assertThat(parsed!!.extraFields).containsExactly(future)
    }

    @Test
    fun `round-trips richer typed entries (company, address, multiple phones)`() {
        val card =
            NameCard(
                displayName = "Mike Peskoff",
                phoneNumber = "+1 415 555 0199",
                email = "mike@example.com",
                entries =
                    listOf(
                        NameCardEntry(NameCardEntryKind.COMPANY, "ContactTap"),
                        NameCardEntry(NameCardEntryKind.TITLE, "Engineer"),
                        NameCardEntry(NameCardEntryKind.ADDRESS, "123 Main St, City"),
                        NameCardEntry(NameCardEntryKind.WEBSITE, "https://example.com"),
                        NameCardEntry(NameCardEntryKind.BIRTHDAY, "1990-01-01"),
                        NameCardEntry(NameCardEntryKind.NOTE, "met at a conference"),
                        NameCardEntry(NameCardEntryKind.NICKNAME, "Mikey"),
                        NameCardEntry(NameCardEntryKind.PHONE, "+1 650 555 0123"),
                        NameCardEntry(NameCardEntryKind.EMAIL, "mike@work.com"),
                    ),
            )
        val parsed = NameCard.parse(card.serialize())
        assertThat(parsed).isEqualTo(card)
        assertThat(parsed!!.entries).isEqualTo(card.entries)
    }

    @Test
    fun `round-trips a card carrying only a typed entry (no name or number)`() {
        val card = NameCard(entries = listOf(NameCardEntry(NameCardEntryKind.WEBSITE, "https://x.dev")))
        assertThat(NameCard.parse(card.serialize())).isEqualTo(card)
    }

    @Test
    fun `entry order is preserved (two phones stay in order)`() {
        val card =
            NameCard(
                phoneNumber = "111",
                entries =
                    listOf(
                        NameCardEntry(NameCardEntryKind.PHONE, "222"),
                        NameCardEntry(NameCardEntryKind.PHONE, "333"),
                    ),
            )
        val parsed = NameCard.parse(card.serialize())!!
        assertThat(parsed.entries.map { it.value }).containsExactly("222", "333").inOrder()
    }

    @Test
    fun `preserves the version byte`() {
        val card = NameCard(version = 7, displayName = "Mike")
        assertThat(card.serialize()[0].toInt()).isEqualTo(7)
        assertThat(NameCard.parse(card.serialize())!!.version).isEqualTo(7)
    }

    @Test
    fun `parse returns null on empty input`() {
        assertThat(NameCard.parse(ByteArray(0))).isNull()
    }

    @Test
    fun `parse returns null on a card with no fields`() {
        // Just a version byte, no TLVs → meaningless card.
        assertThat(NameCard.parse(byteArrayOf(NameCard.CURRENT_VERSION.toByte()))).isNull()
    }

    @Test
    fun `parse returns null on a truncated TLV header`() {
        // version + a single type byte but no length bytes.
        assertThat(NameCard.parse(byteArrayOf(1, NameCard.TYPE_PHONE.toByte()))).isNull()
    }

    @Test
    fun `parse returns null when a TLV length runs past the buffer`() {
        // version=1, type=phone, length=0x000A (10) but only 2 value bytes present.
        val blob = byteArrayOf(1, NameCard.TYPE_PHONE.toByte(), 0x00, 0x0A, 0x35, 0x35)
        assertThat(NameCard.parse(blob)).isNull()
    }

    @Test
    fun `parse returns null on invalid UTF-8 in a known string field`() {
        // version=1, type=name, length=1, value=0xFF (not valid UTF-8).
        val blob = byteArrayOf(1, NameCard.TYPE_DISPLAY_NAME.toByte(), 0x00, 0x01, 0xFF.toByte())
        assertThat(NameCard.parse(blob)).isNull()
    }

    @Test
    fun `duplicate known field types keep the first occurrence`() {
        // Two name TLVs: "A" then "B" → parser keeps "A".
        val blob =
            byteArrayOf(
                1,
                NameCard.TYPE_DISPLAY_NAME.toByte(),
                0x00,
                0x01,
                'A'.code.toByte(),
                NameCard.TYPE_DISPLAY_NAME.toByte(),
                0x00,
                0x01,
                'B'.code.toByte(),
            )
        assertThat(NameCard.parse(blob)!!.displayName).isEqualTo("A")
    }

    @Test
    fun `constructing an empty card is rejected`() {
        assertThrows<IllegalArgumentException> { NameCard() }
    }

    @Test
    fun `serialized size is compact for a typical card`() {
        // header(1) + 3 TLVs: name(3+12) + phone(3+15) + email(3+16) = 1 + 15 + 18 + 19 = 53 bytes.
        val card =
            NameCard(
                displayName = "Mike Peskoff",
                phoneNumber = "+1 415 555 0199",
                email = "mike@example.com",
            )
        assertThat(card.serialize().size).isLessThan(256)
    }
}
