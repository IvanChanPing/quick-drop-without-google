/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.namecard

/**
 * **Name Card NFC bootstrap** — the tiny blob the NFC tap carries (see
 * `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`). The tap is only a TRIGGER: it
 * does NOT carry the contact card. It carries a fresh random **session token**
 * so the two woken apps can find *each other* (and only each other) over
 * Bluetooth — both filter BLE advertisements by this token, then swap the actual
 * [NameCard]s over that link.
 *
 * Why a token instead of a Bluetooth MAC: Android does not expose the local
 * Bluetooth MAC to apps, so we cannot put an address in the tap. A shared random
 * token is the rendezvous key instead — whichever phone reads the other's token
 * advertises/scans for it, so a third nearby Bada phone can't hijack the
 * pairing.
 *
 * Wire format (fixed [SIZE] = 17 bytes): `version(1) | token(16)`.
 *
 * Pure-JVM, unit-tested in `NameCardBootstrapTest`. Emitted/parsed by the NFC
 * HCE/reader and consumed by the BLE rendezvous.
 */
public data class NameCardBootstrap(
    /** Format version; [CURRENT_VERSION] today. */
    val version: Int,
    /** Random rendezvous token, exactly [TOKEN_LEN] bytes. */
    val token: ByteArray,
) {
    init {
        require(version in 0..MAX_VERSION) {
            "version must fit in 1 byte (0..$MAX_VERSION), got $version"
        }
        require(token.size == TOKEN_LEN) {
            "token must be exactly $TOKEN_LEN bytes, got ${token.size}"
        }
    }

    /** Encode to the fixed [SIZE]-byte wire form. Freshly allocated. */
    public fun serialize(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = version.toByte()
        token.copyInto(out, destinationOffset = HEADER_LEN)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NameCardBootstrap) return false
        return version == other.version && token.contentEquals(other.token)
    }

    override fun hashCode(): Int = 31 * version + token.contentHashCode()

    public companion object {
        /** Format version this build emits. */
        public const val CURRENT_VERSION: Int = 1

        /** Max value of the 1-byte version field. */
        public const val MAX_VERSION: Int = 0xFF

        /** Length of the version header byte. */
        public const val HEADER_LEN: Int = 1

        /** Length of the rendezvous token. */
        public const val TOKEN_LEN: Int = 16

        /** Total fixed wire size. */
        public const val SIZE: Int = HEADER_LEN + TOKEN_LEN

        private const val UNSIGNED_BYTE_MASK: Int = 0xFF

        /**
         * Parse a bootstrap from the wire form, or `null` if it is not exactly
         * [SIZE] bytes (truncated / wrong length). Callers treat `null` as "this
         * was not a Name Card tap" and ignore it.
         */
        public fun parse(bytes: ByteArray): NameCardBootstrap? {
            if (bytes.size != SIZE) return null
            return NameCardBootstrap(
                version = bytes[0].toInt() and UNSIGNED_BYTE_MASK,
                token = bytes.copyOfRange(HEADER_LEN, SIZE),
            )
        }
    }
}
