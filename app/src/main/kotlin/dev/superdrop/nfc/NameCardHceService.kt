/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.nfc

import android.app.KeyguardManager
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import dev.superdrop.namecard.NameCardPreferences
import dev.superdrop.protocol.namecard.NameCardBootstrap

/**
 * **Name Card tap — card (HCE) side.** Host Card Emulation service for the Super
 * Drop **Name Card** proprietary AID [NAME_CARD_AID]. When another Super Drop
 * phone (in reader-mode) taps this phone, this service answers the bootstrap
 * exchange so BOTH apps wake and learn a shared rendezvous token; the actual
 * contact swap happens afterwards over Bluetooth (P4). See
 * `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`.
 *
 * The tap is only a TRIGGER — this never carries the contact card, only a tiny
 * [NameCardBootstrap] (version + 16-byte token via [NameCardBootstrapHolder]).
 *
 * APDU exchange (both ends are our app, so this is a private protocol distinct
 * from the iPhone NDEF AID `D2760000850101` and the Quick Share AID `F00000FE2C`):
 *  1. SELECT by name `00 A4 04 00 <Lc> F0534443415244` → `9000` (else `6A82`).
 *  2. EXCHANGE `80 10 00 00 00` → `<bootstrap(17B)> 9000`.
 *
 * **Unlock gate (privacy, user requirement):** the card answers SELECT/EXCHANGE
 * only while the device is UNLOCKED ([KeyguardManager.isDeviceLocked] false).
 * Locked → `6982` (security status not satisfied) and no token is emitted, so a
 * locked/lost phone never leaks anything.
 *
 * Being invoked here also wakes our process (the platform binds the service on a
 * tap even from cold); on EXCHANGE it starts
 * [dev.superdrop.namecard.NameCardExchangeService] with the minted token to run
 * the Bluetooth rendezvous (advertise + serve the card).
 *
 * Public `android.nfc.cardemulation` APIs + the auto-granted `BIND_NFC_SERVICE`.
 * Status: compile-only here (no NFC hardware / second phone) — device-verified by
 * a real two-phone tap. The bootstrap codec is unit-tested.
 */
public class NameCardHceService : HostApduService() {
    private val keyguard: KeyguardManager? by lazy {
        getSystemService(KeyguardManager::class.java)
    }

    override fun processCommandApdu(
        apdu: ByteArray?,
        extras: Bundle?,
    ): ByteArray {
        if (apdu == null || apdu.size < MIN_APDU_LEN) return SW_WRONG_LENGTH

        // Master switch: when the user has turned the feature off, decline every
        // tap (no token, nothing served) so this phone is not tappable as a card.
        if (!NameCardPreferences.from(this).isEnabled()) {
            DiagnosticLog.w(TAG, "tap ignored — Name Card feature disabled in settings")
            return SW_FILE_NOT_FOUND
        }

        // SELECT by name → match our Name Card AID.
        if (apdu[1] == INS_SELECT && apdu[2] == P1_SELECT_BY_NAME) {
            if (!apduSelectsAid(apdu, NAME_CARD_AID)) return SW_FILE_NOT_FOUND
            if (isLocked()) {
                DiagnosticLog.w(TAG, "SELECT while locked → 6982 (no card shared from a locked phone)")
                return SW_LOCKED
            }
            DiagnosticLog.w(TAG, "SELECT Name Card AID → 9000")
            return SW_OK
        }

        // EXCHANGE → mint + return a fresh bootstrap token (unlocked only).
        if (apdu[0] == CLA_PROPRIETARY && apdu[1] == INS_EXCHANGE) {
            if (isLocked()) {
                DiagnosticLog.w(TAG, "EXCHANGE while locked → 6982")
                return SW_LOCKED
            }
            val bootstrap = NameCardBootstrapHolder.newSession()
            // Wake the card-side Bluetooth server (foreground service) so the tapping
            // phone can read our card over BLE using this token.
            runCatching {
                dev.superdrop.namecard.NameCardExchangeService.start(this, bootstrap.token)
            }.onFailure { DiagnosticLog.w(TAG, "EXCHANGE: start exchange service failed: ${it.message}") }
            DiagnosticLog.w(TAG, "Name Card EXCHANGE → bootstrap(${bootstrap.serialize().size}B) + server FGS")
            return bootstrap.serialize() + SW_OK
        }

        return SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        // No per-session APDU state to reset; the token lives in the holder for
        // the Bluetooth layer to pick up.
    }

    private fun isLocked(): Boolean = keyguard?.isDeviceLocked == true

    public companion object {
        private const val TAG = "NameCardHce"

        private const val MIN_APDU_LEN = 4
        private const val P1_SELECT_BY_NAME: Byte = 0x04
        private const val INS_SELECT: Byte = 0xA4.toByte()
        private const val CLA_PROPRIETARY: Byte = 0x80.toByte()
        private const val INS_EXCHANGE: Byte = 0x10

        /**
         * Proprietary Name Card AID = `F0` (proprietary range) + ASCII "SDCARD".
         * Distinct from the iPhone NDEF AID and the Quick Share AID so all three
         * HCE services coexist on the one controller. Declared in
         * `res/xml/superdrop_namecard_apduservice.xml`.
         */
        public val NAME_CARD_AID: ByteArray =
            byteArrayOf(0xF0.toByte(), 0x53, 0x44, 0x43, 0x41, 0x52, 0x44)

        private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_LOCKED = byteArrayOf(0x69.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00)

        /** True iff this SELECT-by-name APDU carries exactly [aid]. */
        internal fun apduSelectsAid(
            apdu: ByteArray,
            aid: ByteArray,
        ): Boolean {
            if (apdu.size < 5 + aid.size) return false
            val lc = apdu[4].toInt() and 0xFF
            if (lc != aid.size) return false
            for (i in aid.indices) {
                if (apdu[5 + i] != aid[i]) return false
            }
            return true
        }

        /** Build the SELECT-by-name APDU for the Name Card AID (used by the reader). */
        internal fun buildSelectApdu(): ByteArray {
            val aid = NAME_CARD_AID
            val apdu = ByteArray(5 + aid.size + 1)
            apdu[1] = INS_SELECT
            apdu[2] = P1_SELECT_BY_NAME
            apdu[4] = aid.size.toByte()
            System.arraycopy(aid, 0, apdu, 5, aid.size)
            apdu[apdu.size - 1] = 0x00 // Le
            return apdu
        }

        /** Build the EXCHANGE APDU `80 10 00 00 00` (used by the reader). */
        internal fun buildExchangeApdu(): ByteArray =
            byteArrayOf(CLA_PROPRIETARY, INS_EXCHANGE, 0x00, 0x00, 0x00)

        /** Whether [resp] ends with the `9000` success status word. */
        internal fun endsWithOk(resp: ByteArray): Boolean =
            resp.size >= 2 && resp[resp.size - 2] == SW_OK[0] && resp[resp.size - 1] == SW_OK[1]
    }
}
