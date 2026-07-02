/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.nfc

import android.app.KeyguardManager
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import dev.superdrop.namecard.NameCardExchangeService
import dev.superdrop.namecard.NameCardPreferences
import java.nio.charset.StandardCharsets

/**
 * Host Card Emulation service that exposes the current Super Drop pairing
 * link (the QR URL) as an NFC Forum **Type-4 Tag** carrying a single NDEF
 * **URI** record. When an iPhone (XS+/iOS 13+, screen-on/unlocked) is
 * tapped to the back of this phone, iOS CoreNFC background-reads the NDEF
 * URI and offers to open it — and because the URI is an ordinary
 * `https://quickshare.google/qrcode#…` web link (not an App Store /
 * universal link), iOS opens it **in Safari**, not the App Store.
 *
 * Ported from `oshare-nfc-tap`'s `NdefAppStoreApduService`, which emulated
 * the OnePlus Share iOS tap with a *static* App-Store universal link. The
 * Type-4 state machine (SELECT AID / SELECT CC / READ CC / SELECT NDEF /
 * READ BINARY) is identical; the only change is that the served URI is
 * **dynamic** — read from [NfcLinkHolder.currentUrl] at the moment the
 * reader SELECTs the NDEF application, so each tap broadcasts whatever
 * pairing link the Send screen is currently showing. When the holder is
 * `null` (no QR session active) we serve an empty NDEF message (NLEN = 0)
 * so a stray tap does nothing rather than opening a stale link.
 *
 * Implemented with only public `android.nfc.cardemulation` APIs + the
 * auto-granted `BIND_NFC_SERVICE` permission — no OEM privilege.
 *
 * ## Type-4 Tag protocol (NFC Forum T4T Operation + ISO 7816-4)
 * A Type-4 reader (the iPhone) runs, and we answer, this sequence:
 *
 *  1. SELECT by name, NDEF Tag App AID `D2760000850101` -> `90 00`.
 *     We snapshot [NfcLinkHolder.currentUrl] here and build the NDEF +
 *     CC files for this read session.
 *  2. SELECT (by file id) the Capability Container `E103` -> `90 00`.
 *  3. READ_BINARY the CC -> 15-byte CC describing the NDEF file
 *     (id `E104`, max read, max NDEF len) + `90 00`.
 *  4. SELECT the NDEF file `E104` -> `90 00`.
 *  5. READ_BINARY offset 0, len 2 -> the 2-byte NLEN.
 *  6. READ_BINARY offset 2.. -> the NDEF message bytes.
 *
 * VERIFIED-vs-ASSUMED: the NDEF byte layout is built per the public NFC
 * Forum Type-4 Tag + NDEF URI RTD specs, and the AID is the NFC Forum NDEF
 * Tag Application AID. Whether a real iPhone tapping a real device performs
 * exactly this T4T read sequence and follows the URI into Safari is NOT
 * proven here (needs an actual iPhone + NFC hardware to confirm).
 */
public class SuperDropNdefApduService : HostApduService() {
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
    private var ndefFile: ByteArray = buildNdefFile(EMPTY_NDEF_MESSAGE)
    private var ccFile: ByteArray = buildCapabilityContainer(ndefFile.size)

    /**
     * Name Card v2: the token minted for the at-rest Name Card tap of THIS read session, or null
     * when this session is not a Name Card tap (QR link armed / feature off / locked). Set in
     * [refreshNdefForCurrentLink], consumed once by [maybeStartNameCardServer] on the first NDEF read.
     */
    private var pendingNameCardToken: ByteArray? = null

    /** Name Card v2: guards the one-shot BLE-server start so it fires on the first NDEF read only. */
    private var nameCardServerStarted = false

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
                if (apduSelectsAid(apdu, NDEF_TAG_APP_AID)) {
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
                if (hi == NDEF_FILE_ID[0] && lo == NDEF_FILE_ID[1]) {
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

            // Name Card v2: the reader is actually pulling our NDEF file → this is a real tap, not a
            // stray SELECT. Bring up the BLE GATT server (once) so the tapping phone can read our card
            // over Bluetooth using the token embedded in the NDEF. Mirrors NameCardHceService's EXCHANGE
            // side-effect. No-op unless this session minted a Name Card token (pendingNameCardToken).
            if (selected == Selected.NDEF) {
                maybeStartNameCardServer()
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
        // Name Card v2: end the read session so the next tap mints a fresh token + can restart the server.
        pendingNameCardToken = null
        nameCardServerStarted = false
    }

    /**
     * Name Card v2: bring up the BLE GATT server ONCE per read session, using the token embedded in
     * the NDEF we're serving. No-op unless this session is a Name Card tap ([pendingNameCardToken] set).
     * Best-effort — a failed FGS start must never break the NFC response.
     */
    private fun maybeStartNameCardServer() {
        val token = pendingNameCardToken ?: return
        if (nameCardServerStarted) return
        nameCardServerStarted = true
        runCatching {
            NameCardExchangeService.start(this, token)
        }.onFailure { Log.w(TAG, "Name Card NDEF read: start exchange service failed: ${it.message}") }
        Log.d(TAG, "Name Card NDEF read → server FGS (token ${token.size}B)")
    }

    /**
     * Name Card v2: is this at-rest tap a Name Card tap? True only when the master switch is ON, the
     * v2 symmetric gate is ON, AND the device is UNLOCKED (privacy — a locked/lost phone shares nothing).
     */
    private fun nameCardActive(): Boolean {
        val prefs = NameCardPreferences.from(this)
        if (!prefs.isEnabled() || !prefs.isV2Enabled()) return false
        val keyguard = getSystemService(KeyguardManager::class.java)
        return keyguard?.isDeviceLocked != true
    }

    /**
     * Snapshot [NfcLinkHolder.currentUrl] and (re)build the NDEF + CC
     * files for this read session. A non-null URL becomes a URI record; a
     * null/blank URL becomes the empty NDEF message (NLEN = 0) so the tap
     * is a no-op rather than opening a stale link.
     */
    private fun refreshNdefForCurrentLink() {
        // Reset the per-session Name Card state; only the Name Card branch below re-arms it.
        pendingNameCardToken = null
        nameCardServerStarted = false

        val url = NfcLinkHolder.currentUrl
        val message =
            when {
                // Feature 1 (iPhone/QR-link tap): a pairing link is armed (QR panel open) → serve it.
                // This ALWAYS wins over Name Card, matching NameDrop's mid-share-vs-at-rest priority.
                !url.isNullOrBlank() -> {
                    Log.d(TAG, "SELECT NDEF app AID -> OK; will serve $url")
                    buildUriNdefMessage(url)
                }
                // Feature 3 (Name Card v2): at rest + enabled + unlocked → serve the Name Card NDEF
                // (external record with a fresh rendezvous token + our AAR so the reading phone launches
                // us from closed). This is the always-on default whenever features 1/2 aren't active.
                nameCardActive() -> {
                    val bootstrap = NameCardBootstrapHolder.newSession()
                    pendingNameCardToken = bootstrap.token
                    Log.d(TAG, "SELECT NDEF app AID -> OK; serving Name Card NDEF (v2 tap)")
                    NameCardNdef.build(bootstrap.token, packageName).toByteArray()
                }
                // Otherwise (feature off / locked / no link): empty NDEF = a deliberate dead tap.
                else -> {
                    Log.d(TAG, "SELECT NDEF app AID -> OK; no link + no Name Card, serving empty NDEF")
                    EMPTY_NDEF_MESSAGE
                }
            }
        ndefFile = buildNdefFile(message)
        ccFile = buildCapabilityContainer(ndefFile.size)
    }

    public companion object {
        private const val TAG = "SuperDropNfc"

        // ---- Status words (ISO 7816-4) ----
        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

        // ---- File identifiers ----
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)

        // NDEF Tag Application AID (NFC Forum). The same AID an iPhone
        // SELECTs to read a Type-4 NDEF tag.
        private val NDEF_TAG_APP_AID =
            byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)

        // READ_BINARY INS.
        private const val INS_READ_BINARY: Byte = 0xB0.toByte()

        /** A valid empty NDEF message (single empty record, TNF=0x00). */
        private val EMPTY_NDEF_MESSAGE = byteArrayOf(0xD0.toByte(), 0x00, 0x00)

        /** True iff this SELECT-by-name APDU carries exactly [aid] in its data. */
        internal fun apduSelectsAid(
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
        internal fun buildCapabilityContainer(ndefFileLen: Int): ByteArray =
            byteArrayOf(
                0x00, 0x0F, // CCLEN = 15
                0x20, // mapping version 2.0
                0x00, 0x3B, // MLe
                0x00, 0x34, // MLc
                0x04, 0x06, // NDEF File Control TLV (T=04,L=06)
                NDEF_FILE_ID[0], NDEF_FILE_ID[1],
                ((ndefFileLen shr 8) and 0xFF).toByte(),
                (ndefFileLen and 0xFF).toByte(),
                0x00, // read access granted
                0xFF.toByte(), // write access denied
            )

        /** NDEF file = 2-byte NLEN (big-endian message length) + message. */
        internal fun buildNdefFile(ndefMessage: ByteArray): ByteArray {
            val file = ByteArray(2 + ndefMessage.size)
            file[0] = ((ndefMessage.size shr 8) and 0xFF).toByte()
            file[1] = (ndefMessage.size and 0xFF).toByte()
            System.arraycopy(ndefMessage, 0, file, 2, ndefMessage.size)
            return file
        }

        /**
         * Build a single-record NDEF message containing one Well-Known URI
         * record (RTD-U).
         * ```
         * Record header byte = 0xD1 : MB=1 ME=1 CF=0 SR=1 IL=0 TNF=001(Well Known)
         * Type Length = 01
         * Payload Length = 1 (URI prefix code) + URI-body bytes (short record)
         * Type = 'U' (0x55)
         * Payload[0] = URI identifier code (prefix), Payload[1..] = URI body
         * ```
         * We pick the longest matching NFC Forum URI prefix to shorten the
         * body; `https://` maps to identifier code 0x04. The Super Drop
         * pairing link is a normal `https://quickshare.google/qrcode#…`
         * web URL, so iOS opens it in Safari rather than the App Store.
         */
        internal fun buildUriNdefMessage(uri: String): ByteArray {
            var prefixCode = 0x00
            var body = uri
            // Longest-prefix match against the NFC Forum URI prefix table (subset).
            val prefixes =
                arrayOf(
                    "https://www." to 0x01,
                    "http://www." to 0x02,
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

            // Short record (SR=1): payload length fits in 1 byte. The
            // pairing URL is well under 255 bytes, so SR is valid.
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
}
