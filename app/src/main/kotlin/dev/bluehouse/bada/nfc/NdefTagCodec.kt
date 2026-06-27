/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import java.nio.charset.StandardCharsets

/**
 * Pure byte encoders/parsers for the NFC Forum Type-4 NDEF tag that
 * [BadaNdefApduService] serves to a tapped iPhone. Split out of the
 * `HostApduService` so the deterministic, `android.*`-free logic (Capability
 * Container, NDEF file framing, the Well-Known URI record, and the SELECT-by-AID
 * matcher) is unit-testable on a host JVM — the Android service body only handles
 * the APDU state machine and delegates the byte work here.
 *
 * The wire constants (CC layout, NDEF record header, fixed offsets/lengths, and
 * NFC Forum URI prefix codes) are inherently numeric, so MagicNumber is suppressed.
 */
@Suppress("MagicNumber")
internal object NdefTagCodec {
    /** NDEF file identifier (E1 04) advertised in the Capability Container. */
    val NDEF_FILE_ID: ByteArray = byteArrayOf(0xE1.toByte(), 0x04)

    /** True iff this SELECT-by-name APDU carries exactly [aid] in its data. */
    @Suppress("ReturnCount")
    fun apduSelectsAid(
        apdu: ByteArray,
        aid: ByteArray,
    ): Boolean {
        // 00 A4 04 00 <Lc> <AID...> [Le]
        if (apdu.size < 5 + aid.size) return false
        val lc = apdu[4].toInt() and 0xFF
        if (lc != aid.size) return false
        for (i in aid.indices) {
            if (apdu[5 + i] != aid[i]) return false
        }
        return true
    }

    /**
     * Build the 15-byte Capability Container per NFC Forum T4T:
     * ```
     * 00 0F  CCLEN = 15
     * 20     mapping version 2.0
     * 00 3B  MLe (max R-APDU data, 59)
     * 00 34  MLc (max C-APDU data, 52)
     * 04 06  NDEF File Control TLV: T=04, L=06
     *   E1 04            NDEF file id
     *   <max ndef len hi/lo>
     *   00               read access granted
     *   FF               write access denied (read-only tag)
     * ```
     */
    fun buildCapabilityContainer(ndefFileLen: Int): ByteArray =
        byteArrayOf(
            0x00,
            0x0F, // CCLEN = 15
            0x20, // mapping version 2.0
            0x00,
            0x3B, // MLe
            0x00,
            0x34, // MLc
            0x04,
            0x06, // NDEF File Control TLV (T=04,L=06)
            NDEF_FILE_ID[0],
            NDEF_FILE_ID[1],
            ((ndefFileLen shr 8) and 0xFF).toByte(),
            (ndefFileLen and 0xFF).toByte(),
            0x00, // read access granted
            0xFF.toByte(), // write access denied
        )

    /** NDEF file = 2-byte NLEN (big-endian message length) + message. */
    fun buildNdefFile(ndefMessage: ByteArray): ByteArray {
        val file = ByteArray(2 + ndefMessage.size)
        file[0] = ((ndefMessage.size shr 8) and 0xFF).toByte()
        file[1] = (ndefMessage.size and 0xFF).toByte()
        System.arraycopy(ndefMessage, 0, file, 2, ndefMessage.size)
        return file
    }

    /**
     * Build a single-record NDEF message containing one Well-Known URI record
     * (RTD-U).
     * ```
     * Record header byte = 0xD1 : MB=1 ME=1 CF=0 SR=1 IL=0 TNF=001(Well Known)
     * Type Length = 01
     * Payload Length = 1 (URI prefix code) + URI-body bytes (short record)
     * Type = 'U' (0x55)
     * Payload[0] = URI identifier code (prefix), Payload[1..] = URI body
     * ```
     * The longest matching NFC Forum URI prefix shortens the body; identifier
     * codes are fixed by the spec: 0x01 = http://www., 0x02 = https://www.,
     * 0x03 = http://, 0x04 = https://.
     */
    fun buildUriNdefMessage(uri: String): ByteArray {
        var prefixCode = 0x00
        var body = uri
        val prefixes =
            arrayOf(
                "https://www." to 0x02,
                "http://www." to 0x01,
                "https://" to 0x04,
                "http://" to 0x03,
            )
        for ((prefix, code) in prefixes) {
            if (uri.startsWith(prefix)) {
                prefixCode = code
                body = uri.substring(prefix.length)
                break
            }
        }
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val payloadLen = 1 + bodyBytes.size // +1 for the prefix code

        // Short record (SR=1): payload length fits in 1 byte. The pairing URL is
        // well under 255 bytes, so SR is valid.
        val rec = ByteArray(4 + payloadLen)
        rec[0] = 0xD1.toByte() // MB=1,ME=1,SR=1,TNF=Well-Known
        rec[1] = 0x01 // type length = 1 ('U')
        rec[2] = payloadLen.toByte()
        rec[3] = 0x55 // 'U'
        rec[4] = prefixCode.toByte()
        System.arraycopy(bodyBytes, 0, rec, 5, bodyBytes.size)
        return rec
    }
}
