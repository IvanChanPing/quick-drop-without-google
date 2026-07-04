/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.namecard

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * **Bada Name Card** — the contact descriptor exchanged by the
 * NameDrop-style "tap two phones to swap contacts" feature (see
 * the Name Card design notes).
 *
 * ## What it is / what it's called
 * A small, self-describing binary blob carrying a person's shareable contact
 * details (name, phone, email, plus room to grow). It is the **payload** the
 * two apps swap **after** an NFC tap bootstraps a Bluetooth (or Wi-Fi-LAN)
 * link — the NFC tap itself never carries this; it is only the trigger.
 *
 * ## Why a dedicated wire format (not vCard on the wire)
 * Both ends are our own app, so we control the bytes. A compact
 * type-length-value (TLV) layout keeps the blob tiny (well under a Bluetooth
 * MTU's worth for name+number+email) and **forward compatible**: a newer app
 * can add fields (a photo, an organisation, a second number) and an older app
 * round-trips the unknown TLVs verbatim instead of corrupting them. We only
 * convert to/from an actual Android contact (vCard / ContactsContract) at the
 * UI edges (profile setup on send; "Save contact" on receive).
 *
 * ## Wire format
 * ```
 * +---------+---------------------------------------------+
 * | byte 0  |              zero or more TLV records        |
 * | version |  type(1) | length(2, big-endian) | value(n) |
 * +---------+---------------------------------------------+
 * ```
 * - `version` (1 byte, 0..255): bumped on incompatible changes; [CURRENT_VERSION] today.
 * - Each TLV: 1-byte `type`, 2-byte big-endian `length` (0..65535), then `length` value bytes.
 *   The 2-byte length leaves headroom for a future small photo field.
 * - Known string field types are [TYPE_DISPLAY_NAME], [TYPE_PHONE], [TYPE_EMAIL]
 *   (UTF-8). Unknown types are preserved in [extraFields] across a round trip.
 *
 * ## Invariants
 * A card must carry at least one of name / phone / email (an empty card is
 * meaningless). String fields must fit in the 2-byte length when UTF-8 encoded.
 *
 * Pure-JVM, unit-tested in `NameCardTest` (round-trip, optional fields,
 * unknown-TLV preservation, malformed → null, strict UTF-8). The NFC/BLE
 * transport and the profile/save UI that produce and consume it are separate.
 *
 * @see dev.bluehouse.bada.protocol.endpoint.EndpointInfo for the sibling TLV codec this mirrors.
 */
public data class NameCard(
    /** Wire format version. [CURRENT_VERSION] for cards this build creates. */
    val version: Int = CURRENT_VERSION,
    /** Display name (UTF-8), or `null` if the sender shares only a number. */
    val displayName: String? = null,
    /** Phone number as free text (kept verbatim; formatting is the UI's job). */
    val phoneNumber: String? = null,
    /** Email address, or `null`. */
    val email: String? = null,
    /**
     * Richer typed contact fields beyond the primary name/phone/email —
     * organization, job title, postal address, website, birthday, note,
     * nickname, and any *additional* phones/emails. Each is a [NameCardEntry]
     * (kind + UTF-8 value); the list is repeatable (two phone entries = two
     * numbers) and its order is preserved on the wire. Normally empty for a
     * bare name+number card.
     */
    val entries: List<NameCardEntry> = emptyList(),
    /**
     * TLV records whose `type` this build does not recognise, preserved
     * verbatim so a round trip through an older app never drops a newer app's
     * fields. Normally empty.
     */
    val extraFields: List<NameCardField> = emptyList(),
) {
    init {
        require(version in 0..MAX_VERSION) {
            "version must fit in 1 byte (0..$MAX_VERSION), got $version"
        }
        require(displayName != null || phoneNumber != null || email != null || entries.isNotEmpty()) {
            "a NameCard must carry at least one field (name / phone / email / a typed entry)"
        }
        requireFits(TYPE_DISPLAY_NAME, displayName)
        requireFits(TYPE_PHONE, phoneNumber)
        requireFits(TYPE_EMAIL, email)
        for (entry in entries) {
            require(entry.value.toByteArray(StandardCharsets.UTF_8).size <= MAX_FIELD_LEN) {
                "entry ${entry.kind} value must fit in 2 bytes (<= $MAX_FIELD_LEN)"
            }
        }
        for (field in extraFields) {
            require(field.value.size <= MAX_FIELD_LEN) {
                "extra field type ${field.type} value must fit in 2 bytes (<= $MAX_FIELD_LEN)"
            }
        }
    }

    /**
     * Encode this card into the canonical wire format. Freshly allocated;
     * mutating the result never affects this instance. Known fields are
     * emitted in a stable order (name, phone, email) followed by [extraFields].
     */
    public fun serialize(): ByteArray {
        val known =
            buildList {
                displayName?.let { add(NameCardField(TYPE_DISPLAY_NAME, it.toUtf8())) }
                phoneNumber?.let { add(NameCardField(TYPE_PHONE, it.toUtf8())) }
                email?.let { add(NameCardField(TYPE_EMAIL, it.toUtf8())) }
            }
        val typed = entries.map { NameCardField(typeForKind(it.kind), it.value.toUtf8()) }
        val all = known + typed + extraFields

        val total = HEADER_LEN + all.sumOf { TLV_HEADER_LEN + it.value.size }
        val out = ByteArray(total)
        out[0] = version.toByte()

        var offset = HEADER_LEN
        for (field in all) {
            out[offset] = field.type.toByte()
            val len = field.value.size
            out[offset + 1] = ((len ushr BITS_PER_BYTE) and UNSIGNED_BYTE_MASK).toByte()
            out[offset + 2] = (len and UNSIGNED_BYTE_MASK).toByte()
            offset += TLV_HEADER_LEN
            field.value.copyInto(out, destinationOffset = offset)
            offset += len
        }
        check(offset == total) { "serializer offset drift: wrote $offset, expected $total" }
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NameCard) return false
        return version == other.version &&
            displayName == other.displayName &&
            phoneNumber == other.phoneNumber &&
            email == other.email &&
            entries == other.entries &&
            extraFields == other.extraFields
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (phoneNumber?.hashCode() ?: 0)
        result = 31 * result + (email?.hashCode() ?: 0)
        result = 31 * result + entries.hashCode()
        result = 31 * result + extraFields.hashCode()
        return result
    }

    public companion object {
        /** Wire version this build emits. */
        public const val CURRENT_VERSION: Int = 1

        /** Max value of the 1-byte version field. */
        public const val MAX_VERSION: Int = 0xFF

        /** Length of the version header byte. */
        public const val HEADER_LEN: Int = 1

        /** TLV header = type(1) + length(2). */
        public const val TLV_HEADER_LEN: Int = 3

        /** Max value-length a TLV can carry (2-byte big-endian length). */
        public const val MAX_FIELD_LEN: Int = 0xFFFF

        /** TLV type: UTF-8 display name. */
        public const val TYPE_DISPLAY_NAME: Int = 1

        /** TLV type: UTF-8 phone number. */
        public const val TYPE_PHONE: Int = 2

        /** TLV type: UTF-8 email address. */
        public const val TYPE_EMAIL: Int = 3

        // TLV types for the richer [NameCardEntry] fields. New types are forward-compatible: an older
        // build that doesn't know them keeps them verbatim in [extraFields] and round-trips them.
        public const val TYPE_COMPANY: Int = 4
        public const val TYPE_TITLE: Int = 5
        public const val TYPE_ADDRESS: Int = 6
        public const val TYPE_WEBSITE: Int = 7
        public const val TYPE_BIRTHDAY: Int = 8
        public const val TYPE_NOTE: Int = 9
        public const val TYPE_NICKNAME: Int = 10
        public const val TYPE_PHONE_EXTRA: Int = 11
        public const val TYPE_EMAIL_EXTRA: Int = 12

        /** Wire TLV type for a [NameCardEntry] kind. */
        internal fun typeForKind(kind: NameCardEntryKind): Int =
            when (kind) {
                NameCardEntryKind.COMPANY -> TYPE_COMPANY
                NameCardEntryKind.TITLE -> TYPE_TITLE
                NameCardEntryKind.ADDRESS -> TYPE_ADDRESS
                NameCardEntryKind.WEBSITE -> TYPE_WEBSITE
                NameCardEntryKind.BIRTHDAY -> TYPE_BIRTHDAY
                NameCardEntryKind.NOTE -> TYPE_NOTE
                NameCardEntryKind.NICKNAME -> TYPE_NICKNAME
                NameCardEntryKind.PHONE -> TYPE_PHONE_EXTRA
                NameCardEntryKind.EMAIL -> TYPE_EMAIL_EXTRA
            }

        /** The [NameCardEntry] kind for a wire TLV type, or `null` if [type] is not an entry type. */
        internal fun kindForType(type: Int): NameCardEntryKind? =
            when (type) {
                TYPE_COMPANY -> NameCardEntryKind.COMPANY
                TYPE_TITLE -> NameCardEntryKind.TITLE
                TYPE_ADDRESS -> NameCardEntryKind.ADDRESS
                TYPE_WEBSITE -> NameCardEntryKind.WEBSITE
                TYPE_BIRTHDAY -> NameCardEntryKind.BIRTHDAY
                TYPE_NOTE -> NameCardEntryKind.NOTE
                TYPE_NICKNAME -> NameCardEntryKind.NICKNAME
                TYPE_PHONE_EXTRA -> NameCardEntryKind.PHONE
                TYPE_EMAIL_EXTRA -> NameCardEntryKind.EMAIL
                else -> null
            }

        private const val UNSIGNED_BYTE_MASK: Int = 0xFF
        private const val BITS_PER_BYTE: Int = 8

        /**
         * Parse a card from the wire format, or `null` for any malformed input
         * (truncated header/TLV, length past the buffer, invalid UTF-8 in a
         * known string field, or a blob with no name/phone/email). Callers
         * treat `null` as "ignore this card" — never a hard error — because the
         * peer is another build we don't fully control.
         *
         * The first occurrence of each known type wins; later duplicates of a
         * known type are dropped. Unknown types are collected into [extraFields].
         */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        public fun parse(bytes: ByteArray): NameCard? {
            if (bytes.size < HEADER_LEN) return null
            val version = bytes[0].toInt() and UNSIGNED_BYTE_MASK

            var displayName: String? = null
            var phoneNumber: String? = null
            var email: String? = null
            val typedEntries = mutableListOf<NameCardEntry>()
            val extras = mutableListOf<NameCardField>()

            var offset = HEADER_LEN
            while (offset < bytes.size) {
                if (offset + TLV_HEADER_LEN > bytes.size) return null
                val type = bytes[offset].toInt() and UNSIGNED_BYTE_MASK
                val len =
                    ((bytes[offset + 1].toInt() and UNSIGNED_BYTE_MASK) shl BITS_PER_BYTE) or
                        (bytes[offset + 2].toInt() and UNSIGNED_BYTE_MASK)
                offset += TLV_HEADER_LEN
                if (offset + len > bytes.size) return null
                val value = bytes.copyOfRange(offset, offset + len)
                offset += len

                when (type) {
                    TYPE_DISPLAY_NAME ->
                        if (displayName == null) displayName = decodeUtf8Strict(value) ?: return null
                    TYPE_PHONE ->
                        if (phoneNumber == null) phoneNumber = decodeUtf8Strict(value) ?: return null
                    TYPE_EMAIL ->
                        if (email == null) email = decodeUtf8Strict(value) ?: return null
                    else -> {
                        val kind = kindForType(type)
                        if (kind != null) {
                            typedEntries += NameCardEntry(kind, decodeUtf8Strict(value) ?: return null)
                        } else {
                            extras += NameCardField(type, value)
                        }
                    }
                }
            }

            if (displayName == null && phoneNumber == null && email == null && typedEntries.isEmpty()) {
                return null
            }
            return NameCard(
                version = version,
                displayName = displayName,
                phoneNumber = phoneNumber,
                email = email,
                entries = typedEntries.toList(),
                extraFields = extras.toList(),
            )
        }

        private fun requireFits(
            type: Int,
            value: String?,
        ) {
            if (value == null) return
            val size = value.toUtf8().size
            require(size <= MAX_FIELD_LEN) {
                "field type $type UTF-8 length must fit in 2 bytes (<= $MAX_FIELD_LEN), got $size"
            }
        }

        private fun String.toUtf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)

        /** Strict UTF-8 decode; `null` on any malformed/unmappable byte sequence. */
        private fun decodeUtf8Strict(bytes: ByteArray): String? =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
    }
}

/**
 * One type-length-value record. Used for forward-compatible fields whose
 * `type` an older build does not recognise — kept verbatim so a round trip
 * never loses a newer build's data. See [NameCard].
 */
public data class NameCardField(
    /** 1-byte TLV type (0..255). */
    val type: Int,
    /** Raw value bytes (interpretation depends on [type]). */
    val value: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NameCardField) return false
        return type == other.type && value.contentEquals(other.value)
    }

    override fun hashCode(): Int = 31 * type + value.contentHashCode()
}

/**
 * A richer, typed contact field carried by a [NameCard] beyond the primary
 * name/phone/email — an organization, job title, postal address, website,
 * birthday, note, nickname, or an *additional* phone/email. Repeatable within a
 * card (two [NameCardEntryKind.PHONE] entries = two numbers). Its [value] is
 * free UTF-8 text; the receiver maps [kind] to the right ContactsContract row
 * when saving.
 */
public data class NameCardEntry(
    /** Which kind of contact field this is. */
    val kind: NameCardEntryKind,
    /** The field's UTF-8 text value (a number, address, URL, date, note, …). */
    val value: String,
)

/** The kinds of richer contact field a [NameCardEntry] can carry. */
public enum class NameCardEntryKind {
    COMPANY,
    TITLE,
    ADDRESS,
    WEBSITE,
    BIRTHDAY,
    NOTE,
    NICKNAME,
    PHONE,
    EMAIL,
}
