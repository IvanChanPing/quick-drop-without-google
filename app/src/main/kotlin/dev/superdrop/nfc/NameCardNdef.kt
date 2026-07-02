/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.nfc

import java.nio.charset.StandardCharsets

/**
 * **Name Card v2 NDEF codec** — builds and parses the raw NDEF message that carries the
 * Name Card rendezvous token over the both-background NFC tap (symmetric NameDrop-style
 * trigger; docs/NAMECARD_V2_EXECUTOR_PLAN.md §5–§6, Appendix A2).
 *
 * HAND-ROLLED RAW BYTES (no `android.nfc` dependency) on purpose, matching the existing
 * [SuperDropNdefApduService.buildUriNdefMessage] house style, because:
 *  1. the CARD side needs raw bytes anyway (feeds [SuperDropNdefApduService] `buildNdefFile`),
 *  2. it makes the codec a PURE object that is JVM-unit-testable with plain junit4 (the repo
 *     has NO Robolectric), and
 *  3. it round-trips against its own parser in [NameCardNdefTest].
 *
 * MESSAGE = two NFC-Forum NDEF records (both short-record, TNF_EXTERNAL_TYPE = 0x04):
 *  - Record 1 (MB=1,ME=0): external type `superdrop.dev:namecard`, payload `[0x01 ver][16B token]`.
 *  - Record 2 (MB=0,ME=1): the **AAR** — external type `android.com:pkg`, payload = our package
 *    name. This is exactly what `NdefRecord.createApplicationRecord(pkg)` emits, and it makes the
 *    READING phone's OS launch our app from CLOSED after the tap.
 *
 * READER side: the OS delivers an `android.nfc.NdefMessage`; the caller converts it with
 * `msg.toByteArray()` and passes that here to [parseToken] — one pure parser, framework-free.
 *
 * Domain+type are ALL-LOWERCASE, matching the manifest `pathPrefix="/superdrop.dev:namecard"`
 * so Android's external-type URI mapping (`vnd.android.nfc://ext/<domain>:<type>`) never
 * misses on case. Status: compile+unit-test verified on the box; on-device tap UNVERIFIED.
 */
internal object NameCardNdef {
    /** External-type name for our record — MUST stay lowercase + in sync with the manifest filter. */
    const val EXT_TYPE: String = "superdrop.dev:namecard"

    /** First payload byte; bump only with a manifest/parse compatibility plan. */
    const val PAYLOAD_VERSION: Byte = 0x01

    private const val TOKEN_LEN = 16
    private const val AAR_TYPE = "android.com:pkg"

    private const val TNF_EXTERNAL_TYPE = 0x04
    private const val FLAG_MB = 0x80
    private const val FLAG_ME = 0x40
    private const val FLAG_CF = 0x20
    private const val FLAG_SR = 0x10
    private const val FLAG_IL = 0x08

    private val EXT_TYPE_BYTES = EXT_TYPE.toByteArray(StandardCharsets.US_ASCII)

    /**
     * Build the raw NDEF message bytes (NOT NLEN-prefixed — the card side wraps it via
     * `buildNdefFile`). [packageName] must be `context.packageName` / `BuildConfig.APPLICATION_ID`,
     * never a literal (debug vs release differ).
     */
    fun build(
        token: ByteArray,
        packageName: String,
    ): ByteArray {
        require(token.size == TOKEN_LEN) { "token must be $TOKEN_LEN bytes, got ${token.size}" }
        val extPayload = ByteArray(1 + TOKEN_LEN)
        extPayload[0] = PAYLOAD_VERSION
        System.arraycopy(token, 0, extPayload, 1, TOKEN_LEN)
        val aarPayload = packageName.toByteArray(StandardCharsets.US_ASCII)

        val rec1 = shortRecord(FLAG_MB, EXT_TYPE_BYTES, extPayload) // MB=1, ME=0
        val rec2 = shortRecord(FLAG_ME, AAR_TYPE.toByteArray(StandardCharsets.US_ASCII), aarPayload) // ME=1
        return rec1 + rec2
    }

    /**
     * Extract the rendezvous token from a raw NDEF message, or null when it carries no
     * well-formed `superdrop.dev:namecard` record (someone else's tag, stale version,
     * truncated). Never throws — malformed input returns null so a stray/foreign tap is a no-op.
     */
    fun parseToken(ndef: ByteArray): ByteArray? {
        try {
            var i = 0
            while (i < ndef.size) {
                val flags = ndef[i].toInt() and 0xFF
                i += 1
                val tnf = flags and 0x07
                val sr = flags and FLAG_SR != 0
                val il = flags and FLAG_IL != 0
                val cf = flags and FLAG_CF != 0

                val typeLen = ndef[i].toInt() and 0xFF
                i += 1
                val payloadLen =
                    if (sr) {
                        (ndef[i].toInt() and 0xFF).also { i += 1 }
                    } else {
                        val len =
                            ((ndef[i].toInt() and 0xFF) shl 24) or
                                ((ndef[i + 1].toInt() and 0xFF) shl 16) or
                                ((ndef[i + 2].toInt() and 0xFF) shl 8) or
                                (ndef[i + 3].toInt() and 0xFF)
                        i += 4
                        len
                    }
                val idLen = if (il) (ndef[i].toInt() and 0xFF).also { i += 1 } else 0

                val type = ndef.copyOfRange(i, i + typeLen)
                i += typeLen
                i += idLen // skip ID field if present
                val payloadStart = i
                i += payloadLen

                // Chunked records are not something we emit; skip matching but keep walking.
                if (!cf &&
                    tnf == TNF_EXTERNAL_TYPE &&
                    type.contentEquals(EXT_TYPE_BYTES) &&
                    payloadLen == 1 + TOKEN_LEN &&
                    ndef[payloadStart] == PAYLOAD_VERSION
                ) {
                    return ndef.copyOfRange(payloadStart + 1, payloadStart + 1 + TOKEN_LEN)
                }

                if (flags and FLAG_ME != 0) break // last record
            }
        } catch (e: IndexOutOfBoundsException) {
            return null // malformed → not our tap
        }
        return null
    }

    /** One short (SR=1, IL=0) NDEF record: [flags|SR|TNF_EXTERNAL] [typeLen] [payloadLen] type payload. */
    private fun shortRecord(
        mbMeFlag: Int,
        type: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size <= 0xFF) { "short record payload must be <=255 bytes, got ${payload.size}" }
        require(type.size <= 0xFF) { "type must be <=255 bytes" }
        val header = byteArrayOf(
            (mbMeFlag or FLAG_SR or TNF_EXTERNAL_TYPE).toByte(),
            type.size.toByte(),
            payload.size.toByte(),
        )
        return header + type + payload
    }
}
