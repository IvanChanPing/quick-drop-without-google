/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * Host Card Emulation service that exposes the current Bada pairing link
 * (the QR URL) as an NFC Forum **Type-4 Tag** carrying a single NDEF **URI**
 * record. When a phone is tapped to the back of this device, it
 * background-reads the NDEF URI and offers to open it — so a phone that does
 * not run the app can open the pairing link by tapping, without scanning the
 * QR code.
 *
 * The served URI is read from [NfcLinkHolder.currentUrl] at the moment the
 * reader SELECTs the NDEF application, so each tap serves whatever pairing
 * link the Send/QR screen is currently showing. When the holder is `null`
 * (no QR on screen) an empty NDEF message (NLEN = 0) is served, so a stray
 * tap does nothing rather than opening a stale link.
 *
 * Implemented with only public `android.nfc.cardemulation` APIs + the
 * auto-granted `BIND_NFC_SERVICE` permission — no OEM privilege.
 *
 * ## Type-4 Tag protocol (NFC Forum T4T Operation + ISO 7816-4)
 * The reader runs, and this service answers, the standard T4T sequence:
 *
 *  1. SELECT by name, NDEF Tag App AID `D2760000850101` -> `90 00`
 *     (the URL is snapshotted here and the NDEF + CC files built for this
 *     read session).
 *  2. SELECT (by file id) the Capability Container `E103` -> `90 00`.
 *  3. READ_BINARY the CC -> 15-byte CC describing the NDEF file
 *     (id `E104`, max read, max NDEF len) + `90 00`.
 *  4. SELECT the NDEF file `E104` -> `90 00`.
 *  5. READ_BINARY offset 0, len 2 -> the 2-byte NLEN.
 *  6. READ_BINARY offset 2.. -> the NDEF message bytes.
 *
 * The APDU state machine is inherently byte-index heavy and branches per
 * command/file, so MagicNumber / ReturnCount / CyclomaticComplexMethod are
 * suppressed; the pure byte encoders live in [NdefTagCodec].
 */
@Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod")
public class BadaNdefApduService : HostApduService() {
    /** Which file the reader currently has selected. */
    private enum class Selected { NONE, CC, NDEF }

    private var selected: Selected = Selected.NONE

    /**
     * The NDEF file (NLEN prefix + message) and CC for the *current* read
     * session. Rebuilt on each SELECT-AID from [NfcLinkHolder.currentUrl]
     * so a tap always serves the link on screen at tap time. Initialised
     * to the empty-NDEF form for the case where a reader issues a
     * READ_BINARY before any SELECT-AID (defensive — a compliant reader
     * always selects first).
     */
    private var ndefFile: ByteArray = NdefTagCodec.buildNdefFile(EMPTY_NDEF_MESSAGE)
    private var ccFile: ByteArray = NdefTagCodec.buildCapabilityContainer(ndefFile.size)

    override fun processCommandApdu(
        apdu: ByteArray?,
        extras: Bundle?,
    ): ByteArray {
        if (apdu == null || apdu.size < 4) {
            return SW_WRONG_LENGTH
        }

        // SELECT (INS A4).
        if (apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte()) {
            // SELECT by name (P1=04): match the NDEF Tag Application AID.
            if (apdu[2] == 0x04.toByte()) {
                if (NdefTagCodec.apduSelectsAid(apdu, NDEF_TAG_APP_AID)) {
                    refreshNdefForCurrentLink()
                    selected = Selected.NONE // app selected; no file selected yet
                    return SW_OK
                }
                return SW_FILE_NOT_FOUND
            }
            // SELECT by file id (P1=00, P2=0C, Lc=02, fid).
            if (apdu[2] == 0x00.toByte() && apdu.size >= 7) {
                val hi = apdu[5]
                val lo = apdu[6]
                if (hi == CC_FILE_ID[0] && lo == CC_FILE_ID[1]) {
                    selected = Selected.CC
                    return SW_OK
                }
                if (hi == NdefTagCodec.NDEF_FILE_ID[0] && lo == NdefTagCodec.NDEF_FILE_ID[1]) {
                    selected = Selected.NDEF
                    return SW_OK
                }
                return SW_FILE_NOT_FOUND
            }
            return SW_FILE_NOT_FOUND
        }

        // READ_BINARY (INS B0): P1P2 = offset, Le (last byte) = length.
        if (apdu[1] == INS_READ_BINARY) {
            val offset = ((apdu[2].toInt() and 0xFF) shl 8) or (apdu[3].toInt() and 0xFF)
            var le = if (apdu.size >= 5) apdu[4].toInt() and 0xFF else 0
            if (le == 0) le = 256 // Le=00 means 256 in short form

            val file =
                when (selected) {
                    Selected.CC -> ccFile
                    Selected.NDEF -> ndefFile
                    Selected.NONE -> return SW_FILE_NOT_FOUND
                }

            if (offset > file.size) {
                return SW_WRONG_LENGTH
            }
            val len = minOf(le, file.size - offset)
            val resp = ByteArray(len + 2)
            System.arraycopy(file, offset, resp, 0, len)
            resp[len] = SW_OK[0]
            resp[len + 1] = SW_OK[1]
            return resp
        }

        return SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        selected = Selected.NONE
    }

    /**
     * Snapshot [NfcLinkHolder.currentUrl] and (re)build the NDEF + CC
     * files for this read session. A non-null URL becomes a URI record; a
     * null/blank URL becomes the empty NDEF message (NLEN = 0) so the tap
     * is a no-op rather than opening a stale link.
     */
    private fun refreshNdefForCurrentLink() {
        val url = NfcLinkHolder.currentUrl
        val message =
            if (url.isNullOrBlank()) {
                Log.d(TAG, "SELECT NDEF app AID -> OK; no current link, serving empty NDEF")
                EMPTY_NDEF_MESSAGE
            } else {
                Log.d(TAG, "SELECT NDEF app AID -> OK; will serve $url")
                NdefTagCodec.buildUriNdefMessage(url)
            }
        ndefFile = NdefTagCodec.buildNdefFile(message)
        ccFile = NdefTagCodec.buildCapabilityContainer(ndefFile.size)
    }

    public companion object {
        private const val TAG = "BadaNfc"

        // ---- Status words (ISO 7816-4) ----
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

        // ---- File identifiers ----
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)

        // NDEF Tag Application AID (NFC Forum). The same AID an iPhone
        // SELECTs to read a Type-4 NDEF tag.
        private val NDEF_TAG_APP_AID =
            byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)

        // READ_BINARY INS.
        private const val INS_READ_BINARY: Byte = 0xB0.toByte()

        /** A valid empty NDEF message (single empty record, TNF=0x00). */
        private val EMPTY_NDEF_MESSAGE = byteArrayOf(0xD0.toByte(), 0x00, 0x00)
    }
}
