/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.protocol.nfc.NfcTapLinkHolder
import dev.bluehouse.bada.protocol.nfc.QuickShareNfcCodec
import dev.bluehouse.bada.service.receiver.NfcColdReceiverPrimer
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService
import java.security.SecureRandom

/**
 * Host Card Emulation service implementing the **Google Quick Share NFC
 * tap-to-share** advertiser/receiver role on AID `F00000FE2C`.
 *
 * A stock Quick Share SENDER in reader-mode (or our own
 * [dev.bluehouse.bada.send.SendActivity] reader-mode), tapped to the back of
 * this phone while we are receiving, runs:
 *
 *  1. SELECT by name, AID `F00000FE2C` -> `9000`.
 *  2. ADVERTISEMENT `80 01 00 00 <Lc> <hhww> 00 FF` -> we answer an
 *     `hhwv` carrying our `deym` NfcTag + a Wi-Fi-LAN `rxInstantConnectionAdv`
 *     advertising our live [dev.bluehouse.bada.protocol.server.TcpReceiverServer]
 *     IP:port, then `9000`.
 *
 * The reader then injects us as a discovered peer (`onEndpointFound`) and
 * connects over Wi-Fi-LAN — the transport we already host. NFC carries
 * only the bootstrap identity + connectivity (verified
 * `docs/NFC_INTEROP_BYTEMAP.md` §2).
 *
 * **Liveness gating.** The HCE only emits a real tag while
 * [NfcTapLinkHolder.current] is non-null — i.e. the receiver service is up
 * and advertising. The receiver service drives that holder per the user's
 * [NfcTapSharePreferences] mode (SHEET_OPEN / APP_FOREGROUND / BACKGROUND),
 * exactly like [NfcLinkHolder] gates the iPhone-link HCE. When the holder
 * is null we still answer SELECT (so the reader's protocol does not error)
 * but return an empty `hhwv` so a stray tap is a no-op.
 *
 * Separate AID from [BadaNdefApduService] (`D2760000850101`, the
 * iPhone NDEF link), so the two HCE services coexist on the one controller.
 *
 * Public `android.nfc.cardemulation` APIs only + the auto-granted
 * `BIND_NFC_SERVICE` permission. **NOT device-tested** (no NFC hardware in
 * the build environment); the byte formats are smali-verified but the
 * end-to-end tap is unvalidated.
 *
 * APDU parsing is inherently byte-index heavy and uses one guard-return per
 * malformed-input case, so the class suppresses detekt's MagicNumber / ReturnCount.
 */
@Suppress("MagicNumber", "ReturnCount")
public class BadaTapHceService : HostApduService() {
    private val secureRandom = SecureRandom()

    override fun processCommandApdu(
        apdu: ByteArray?,
        extras: Bundle?,
    ): ByteArray {
        if (apdu == null || apdu.size < MIN_APDU_LEN) {
            return QuickShareNfcCodec.SW_WRONG_LENGTH
        }

        // SELECT by name (00 A4 04 00 <Lc> <AID>).
        if (apdu[1] == QuickShareNfcCodec.INS_SELECT && apdu[2] == P1_SELECT_BY_NAME) {
            return if (apduSelectsAid(apdu, QuickShareNfcCodec.ADVERTISING_AID)) {
                DiagnosticLog.w(TAG, "SELECT F00000FE2C -> 9000")
                QuickShareNfcCodec.SW_OK
            } else {
                QuickShareNfcCodec.SW_FILE_NOT_FOUND
            }
        }

        // ADVERTISEMENT (80 01 ..) — return our static tag.
        if (apdu[0] == QuickShareNfcCodec.CLA_PROPRIETARY &&
            apdu[1] == QuickShareNfcCodec.INS_ADVERTISEMENT
        ) {
            return handleAdvertisement(apdu)
        }

        return QuickShareNfcCodec.SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        // No per-session state to reset; the tag is rebuilt per ADVERTISEMENT.
    }

    /**
     * Cold tap-to-receive wake. Android starts this [HostApduService] on a
     * tap even if our process was dead, so this is the one cold-wake path a
     * non-privileged app has (a BLE-scan wake needs the app already
     * running). We ask [ReceiverForegroundService] to come up and open a
     * bounded visibility window ([ReceiverForegroundService.ACTION_NFC_WAKE]);
     * the tapping sender then connects and the normal inbound path posts the
     * Accept consent notification — no app UI is forced to the foreground.
     *
     * Best-effort: a background foreground-service start can be refused on
     * some OEMs/API levels (logged, not fatal). NOT device-verified.
     */
    private fun fireReceiveWake() {
        runCatching {
            val intent =
                Intent(this, ReceiverForegroundService::class.java).apply {
                    action = ReceiverForegroundService.ACTION_NFC_WAKE
                }
            startForegroundService(intent)
        }.onFailure { DiagnosticLog.w(TAG, "receive wake failed: ${it.message}") }
    }

    /**
     * Build the `hhwv` response for an ADVERTISEMENT APDU. Parses the
     * reader's `hhww` (best-effort; we do not require a particular
     * serviceId — Bada only advertises one service) and returns our
     * live tag, or an empty response (no NfcTag field) when we are not a
     * live receiver.
     */
    private fun handleAdvertisement(apdu: ByteArray): ByteArray {
        // Warm: a live receiver already published its tag → answer it directly.
        // Cold: PRIME a real, connectable tag synchronously NOW — bind a TCP
        // listener + read the Wi-Fi-LAN IP in this callback so the FIRST tap
        // answers exactly like a warm receiver (deym PCP=3 + Wi-Fi-LAN rxAdv),
        // then fire the FGS wake so the session comes up and ADOPTS that same
        // socket (its accept loop drains the kernel backlog on the identical
        // port). This is what makes a single cold tap == warm, no second tap.
        // If we cannot prime (no Wi-Fi-LAN IP yet — Wi-Fi off/connecting), fall
        // back to the empty-tag + wake behaviour (the wake forces Wi-Fi/BT on
        // via the radio-helper and BLE carries discovery while Wi-Fi settles).
        val link =
            NfcTapLinkHolder.current ?: run {
                val primed = NfcColdReceiverPrimer.prime(this)
                fireReceiveWake()
                if (primed == null) {
                    DiagnosticLog.w(TAG, "ADVERTISEMENT cold, no Wi-Fi IP -> wake + empty hhwv")
                    return QuickShareNfcCodec.encodeHhwvResponse(
                        QuickShareNfcCodec.HhwvResponse(nfcTag = ByteArray(0)),
                    ) + QuickShareNfcCodec.SW_OK
                }
                DiagnosticLog.w(TAG, "ADVERTISEMENT cold -> primed live tag + wake (cold == warm, single tap)")
                primed
            }

        // Parsing the request is informational; we always answer with our
        // own single advertised service regardless of the requested id.
        parseAdvertisementRequest(apdu)?.let { request ->
            DiagnosticLog.w(TAG, "ADVERTISEMENT serviceId=${request.serviceId}")
        }

        val nfcTag =
            QuickShareNfcCodec.encodeNfcTag(
                endpointId = link.endpointId,
                serviceIdHash = link.serviceIdHash,
                endpointInfo = link.endpointInfo,
                // BT MAC = all-zero sentinel: the Wi-Fi/LAN path needs no MAC.
                btMac = ByteArray(BT_MAC_LEN),
            )

        val encryptionKey = ByteArray(NC_ENCRYPTION_KEY_LEN).also { secureRandom.nextBytes(it) }
        val rxAdv =
            QuickShareNfcCodec.encodeWifiLanRxAdv(
                ip = link.address,
                port = link.port,
                encryptionKey = encryptionKey,
            )

        DiagnosticLog.w(
            TAG,
            "ADVERTISEMENT -> hhwv tag(${nfcTag.size}B) rxAdv(${rxAdv.size}B) " +
                "${link.address.hostAddress}:${link.port}",
        )
        return QuickShareNfcCodec.encodeHhwvResponse(
            QuickShareNfcCodec.HhwvResponse(nfcTag = nfcTag, rxAdv = rxAdv),
        ) + QuickShareNfcCodec.SW_OK
    }

    /**
     * Extract the `hhww` payload from an ADVERTISEMENT APDU
     * (`80 01 P1 P2 Lc <hhww> [00 FF trailer]`) and parse it. Returns
     * `null` when the APDU has no parseable body — the response is the same
     * either way, so this is purely diagnostic.
     */
    private fun parseAdvertisementRequest(apdu: ByteArray): QuickShareNfcCodec.HhwwRequest? {
        if (apdu.size < ADVERTISEMENT_HEADER_LEN) return null
        val lc = apdu[4].toInt() and 0xFF
        val bodyStart = ADVERTISEMENT_HEADER_LEN
        if (lc <= 0 || bodyStart + lc > apdu.size) return null
        val body = apdu.copyOfRange(bodyStart, bodyStart + lc)
        return QuickShareNfcCodec.parseHhwwRequest(body)
    }

    private companion object {
        private const val TAG = "BadaTapHce"

        private const val MIN_APDU_LEN = 4
        private const val P1_SELECT_BY_NAME: Byte = 0x04

        /** `80 01 P1 P2 Lc` before the hhww body. */
        private const val ADVERTISEMENT_HEADER_LEN = 5

        private const val NC_ENCRYPTION_KEY_LEN = 0x20
        private const val BT_MAC_LEN = 6

        /** True iff this SELECT-by-name APDU carries exactly [aid]. */
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
    }
}
