/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.endpoint.NearbyServiceId
import dev.bluehouse.bada.protocol.nfc.QuickShareNfcCodec
import java.io.IOException
import java.net.InetAddress

/**
 * Sender-side **Quick Share NFC tap-to-share reader** (reader-mode). While
 * the send sheet is open (and the iPhone-link QR panel is NOT — the two
 * share one NFC controller and are mutually exclusive), [enable] puts the
 * adapter into reader-mode for ISO-DEP. On a tag, it transceives the Quick
 * Share APDU exchange against the peer's HCE (`F00000FE2C`):
 *
 *  1. SELECT `00 A4 04 00 05 F00000FE2C 00` -> expect `9000`.
 *  2. ADVERTISEMENT `80 01 00 00 <Lc> <hhww(serviceId="NearbySharing")> 00 FF`
 *     -> parse the `hhwv` response: `deym` NfcTag + Wi-Fi-LAN rxAdv.
 *
 * The parsed identity (endpointId, EndpointInfo) + Wi-Fi-LAN IP:port are
 * handed to [onPeerTapped] as a [TappedPeer], which [dev.bluehouse.bada.send.SendActivity]
 * turns into a discovered peer and auto-connects to over the same path a
 * tapped peer-icon uses.
 *
 * Public `android.nfc` APIs only. **NOT device-tested** (no NFC hardware
 * in the build environment).
 *
 * The APDU builders/parsers are inherently byte-index heavy, so the class
 * suppresses detekt's MagicNumber — the per-byte protocol comments document each
 * value better than a named constant per offset would.
 */
@Suppress("MagicNumber")
public class BadaTapReader(
    private val activity: Activity,
    private val onPeerTapped: (TappedPeer) -> Unit,
    private val onTapWake: () -> Unit = {},
    /**
     * One-line human-readable summary of EVERY tap (resolved / woke / failed) with the
     * raw SELECT/ADVERTISEMENT bytes, so the send UI can surface it on-screen (Toast) —
     * the on-device, no-internet observability path. Called on a binder thread; the
     * activity marshals to the UI thread.
     */
    private val onTapDiagnostic: (String) -> Unit = {},
) {
    /** Raw SELECT/ADVERTISEMENT bytes of the in-flight exchange, for [onTapDiagnostic]. */
    private var lastExchangeSummary: String = ""

    /**
     * A peer discovered via an NFC tap, ready to be injected into the send
     * flow.
     *
     * @property endpointId the peer's 4-byte endpoint id (ASCII).
     * @property endpointInfo the parsed Nearby EndpointInfo (device name etc.).
     * @property address the peer's Wi-Fi-LAN address.
     * @property port the peer's TCP port.
     */
    public data class TappedPeer(
        val endpointId: String,
        val endpointInfo: EndpointInfo,
        val address: InetAddress,
        val port: Int,
    )

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /**
     * Enter reader-mode. No-op when the device has no NFC adapter. Safe to
     * call repeatedly (the platform replaces the prior reader-mode
     * registration). Callers MUST ensure the iPhone-link NDEF HCE is not
     * the intended NFC owner at the same time (reader-mode suppresses our
     * own HCE while active anyway, but the QR panel should be closed).
     */
    public fun enable() {
        val adapter = nfcAdapter ?: return
        val flags =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        runCatching {
            adapter.enableReaderMode(activity, ::onTag, flags, null)
        }.onFailure { DiagnosticLog.w(TAG, "enableReaderMode failed: ${it.message}") }
    }

    /** Leave reader-mode. No-op when the device has no NFC adapter. */
    public fun disable() {
        val adapter = nfcAdapter ?: return
        runCatching { adapter.disableReaderMode(activity) }
    }

    /**
     * Reader-mode tag callback (runs on a binder thread). Drives the APDU
     * exchange and, on success, posts the parsed peer back to the activity
     * via [onPeerTapped]. The activity is responsible for marshalling onto
     * the UI thread.
     */
    private fun onTag(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        lastExchangeSummary = "no IsoDep exchange"
        val result =
            try {
                isoDep.connect()
                exchange(isoDep)
            } catch (e: IOException) {
                DiagnosticLog.w(TAG, "tap exchange failed: ${e.message}")
                lastExchangeSummary += " IO=${e.message}"
                TapResult.Failed
            } finally {
                runCatching { isoDep.close() }
            }
        // On-screen, no-internet observability: surface EVERY tap outcome to the UI.
        val outcome =
            when (result) {
                is TapResult.Resolved -> "RESOLVED ${result.peer.endpointId}"
                TapResult.Woke -> "WOKE (receiver idle → handing off to discovery)"
                TapResult.Failed -> "FAILED (no QS HCE / tag lost)"
            }
        DiagnosticLog.w(TAG, "tap outcome=$outcome | $lastExchangeSummary")
        onTapDiagnostic("NFC tap: $outcome | $lastExchangeSummary")
        when (result) {
            // The HCE returned a real Quick Share advertisement tag (the receiver was
            // already advertising) -> connect to its Wi-Fi-LAN endpoint directly.
            is TapResult.Resolved -> onPeerTapped(result.peer)
            // SELECT was accepted (it IS a Quick Share receiver) but the ADVERTISEMENT
            // came back empty -> the receiver was idle and its HCE just fired the wake
            // (djvf.f opens Quick Share). Exactly as stock Quick Share's reader does, we
            // do NOT re-poll the NFC; we hand off to the already-running discovery so the
            // now-waking receiver is found and connected over the normal Nearby channel.
            TapResult.Woke -> {
                DiagnosticLog.w(TAG, "tap: receiver idle, HCE woke it -> handing off to discovery")
                onTapWake()
            }
            // SELECT failed / link error: not a Quick Share receiver, or the tag left the
            // field. Nothing to do; reader stays armed for the next tap.
            TapResult.Failed -> Unit
        }
    }

    /**
     * Outcome of one NFC tap exchange, mirroring stock Quick Share's one-shot read.
     *
     * @property Resolved the HCE returned a usable advertisement tag (receiver was
     *   already advertising) -> connect to it.
     * @property Woke SELECT succeeded but ADVERTISEMENT was empty -> the receiver was
     *   idle and its HCE fired the wake; hand off to discovery (matches Quick Share).
     * @property Failed SELECT rejected (not a QS receiver) or an IsoDep I/O error.
     */
    private sealed interface TapResult {
        data class Resolved(
            val peer: TappedPeer,
        ) : TapResult

        object Woke : TapResult

        object Failed : TapResult
    }

    /**
     * One-shot tap exchange, matching stock Quick Share's reader (`djkb.c`): SELECT,
     * then ONE ADVERTISEMENT — NO re-poll loop. Quick Share verified (GMS 26.18.33)
     * sends the ADVERTISEMENT exactly once per tag and relies on its concurrent Nearby
     * discovery to finish the connection; re-polling the same IsoDep is unlike the
     * original and just races the receiver's NFC reset on wake ("Tag was lost").
     *
     * - SELECT rejected -> [TapResult.Failed] (not a Quick Share HCE).
     * - ADVERTISEMENT returns a usable tag (receiver already advertising) -> [TapResult.Resolved].
     * - ADVERTISEMENT empty / `0000` (receiver idle; its HCE fired the wake) -> [TapResult.Woke].
     */
    @Suppress("ReturnCount")
    private fun exchange(isoDep: IsoDep): TapResult {
        // 1. SELECT the Quick Share advertising application.
        val selectApdu = buildSelectApdu()
        val selectResp = isoDep.transceive(selectApdu)
        lastExchangeSummary = "SELECT=${hex(selectResp)}"
        DiagnosticLog.w(TAG, "SELECT apdu=${hex(selectApdu)} resp=${hex(selectResp)}")
        if (!endsWithOk(selectResp)) {
            DiagnosticLog.w(TAG, "SELECT not OK (${selectResp.size}B) — not a Quick Share HCE")
            return TapResult.Failed
        }

        // 2. ADVERTISEMENT — sent ONCE (Quick Share does not re-poll). If the HCE
        // returns a real tag the receiver was already advertising; if it returns the
        // empty `djvb.a()` (`0000`) the receiver was idle and its HCE just fired the
        // wake (djvf.f -> opens Quick Share). SELECT having succeeded means this IS a
        // Quick Share receiver, so an empty ADVERTISEMENT means "woke it" -> hand off
        // to discovery rather than re-polling the NFC link.
        val hhww =
            QuickShareNfcCodec.encodeHhwwRequest(
                QuickShareNfcCodec.HhwwRequest(serviceId = NearbyServiceId.VALUE),
            )
        val advApdu = buildAdvertisementApdu(hhww)
        val advResp = isoDep.transceive(advApdu)
        lastExchangeSummary += " ADV=${hex(advResp)}"
        DiagnosticLog.w(TAG, "ADV apdu=${hex(advApdu)} resp=${hex(advResp)}")

        val peer = parseTappedPeer(advResp)
        return if (peer != null) TapResult.Resolved(peer) else TapResult.Woke
    }

    /**
     * Parse an ADVERTISEMENT response into a [TappedPeer], or `null` if it is empty /
     * `0000` / not a usable Wi-Fi-LAN tag (i.e. the receiver was idle, not advertising).
     */
    @Suppress("ReturnCount")
    private fun parseTappedPeer(advResp: ByteArray): TappedPeer? {
        if (!endsWithOk(advResp) || advResp.size <= STATUS_LEN) return null
        val body = advResp.copyOfRange(0, advResp.size - STATUS_LEN)

        val response = QuickShareNfcCodec.parseHhwvResponse(body) ?: return null
        if (response.nfcTag.isEmpty()) return null
        val nfcTag = QuickShareNfcCodec.parseNfcTag(response.nfcTag) ?: return null
        val endpointInfo = EndpointInfo.parse(nfcTag.endpointInfo) ?: return null

        // The Wi-Fi-LAN IP:port comes from the rxAdv. Without it we cannot
        // connect (BT-Classic fallback is out of scope for the tap path).
        val rxAdv = response.rxAdv ?: return null
        val lan = QuickShareNfcCodec.parseWifiLanEndpoint(rxAdv) ?: return null

        val endpointId = String(nfcTag.endpointId, Charsets.US_ASCII)
        DiagnosticLog.w(
            TAG,
            "tap resolved endpointId=$endpointId ${lan.address.hostAddress}:${lan.port} " +
                "name=${endpointInfo.deviceName}",
        )
        return TappedPeer(
            endpointId = endpointId,
            endpointInfo = endpointInfo,
            address = lan.address,
            port = lan.port,
        )
    }

    private fun buildSelectApdu(): ByteArray {
        // 00 A4 04 00 Lc <AID> 00
        val aid = QuickShareNfcCodec.ADVERTISING_AID
        val apdu = ByteArray(5 + aid.size + 1)
        apdu[0] = 0x00
        apdu[1] = QuickShareNfcCodec.INS_SELECT
        apdu[2] = 0x04 // P1 = select by name
        apdu[3] = 0x00
        apdu[4] = aid.size.toByte()
        System.arraycopy(aid, 0, apdu, 5, aid.size)
        apdu[apdu.size - 1] = 0x00 // Le
        return apdu
    }

    private fun buildAdvertisementApdu(hhww: ByteArray): ByteArray {
        // 80 01 00 00 Lc <hhww> 00  (Le)
        val apdu = ByteArray(5 + hhww.size + 1)
        apdu[0] = QuickShareNfcCodec.CLA_PROPRIETARY
        apdu[1] = QuickShareNfcCodec.INS_ADVERTISEMENT
        apdu[2] = 0x00 // P1
        apdu[3] = 0x00 // P2
        apdu[4] = hhww.size.toByte()
        System.arraycopy(hhww, 0, apdu, 5, hhww.size)
        apdu[apdu.size - 1] = 0x00 // Le
        return apdu
    }

    private fun endsWithOk(resp: ByteArray): Boolean {
        if (resp.size < STATUS_LEN) return false
        return resp[resp.size - 2] == QuickShareNfcCodec.SW_OK[0] &&
            resp[resp.size - 1] == QuickShareNfcCodec.SW_OK[1]
    }

    private companion object {
        private const val TAG = "BadaTapReader"
        private const val STATUS_LEN = 2

        /** Max bytes hex-dumped per diagnostic line (keeps uploads bounded). */
        private const val HEX_DUMP_MAX = 80

        /**
         * `hex` — bounded uppercase hex dump of an APDU / response for the NFC-tap
         * diagnostics (Round-2 instrumentation). Truncates past [HEX_DUMP_MAX] bytes
         * with a `…(NB)` suffix so a stray large frame can't bloat the upload.
         */
        private fun hex(bytes: ByteArray): String {
            val n = minOf(bytes.size, HEX_DUMP_MAX)
            val sb = StringBuilder(n * 2 + 8)
            for (i in 0 until n) sb.append("%02X".format(bytes[i].toInt() and 0xFF))
            if (bytes.size > HEX_DUMP_MAX) sb.append("…(${bytes.size}B)")
            return sb.toString()
        }
    }
}
