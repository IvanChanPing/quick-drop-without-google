/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.SystemClock
import dev.superdrop.diag.DiagnosticUploader
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import dev.superdrop.protocol.endpoint.EndpointInfo
import dev.superdrop.protocol.endpoint.NearbyServiceId
import dev.superdrop.protocol.nfc.QuickShareNfcCodec
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
 * handed to [onPeerTapped] as a [TappedPeer], which [dev.superdrop.send.SendActivity]
 * turns into a discovered peer and auto-connects to over the same path a
 * tapped peer-icon uses.
 *
 * Public `android.nfc` APIs only. **NOT device-tested** (no NFC hardware
 * in the build environment).
 */
public class SuperDropTapReader(
    private val activity: Activity,
    private val onPeerTapped: (TappedPeer) -> Unit,
) {
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
        val tapped =
            try {
                isoDep.connect()
                exchange(isoDep)
            } catch (e: IOException) {
                DiagnosticLog.w(TAG, "tap exchange failed: ${e.message}")
                null
            } finally {
                runCatching { isoDep.close() }
            }
        // Auto-ship the tap diagnostics (SELECT/ADVERTISEMENT outcome) so a
        // failed send-tap is debuggable without adb. Runs on this binder thread's
        // caller via a background thread inside the uploader. Best-effort.
        DiagnosticUploader.upload(activity, reason = "nfc-send-tap")
        if (tapped != null) {
            onPeerTapped(tapped)
        }
    }

    @Suppress("ReturnCount")
    private fun exchange(isoDep: IsoDep): TappedPeer? {
        // 1. SELECT the Quick Share advertising application.
        val selectResp = isoDep.transceive(buildSelectApdu())
        if (!endsWithOk(selectResp)) {
            DiagnosticLog.w(TAG, "SELECT not OK (${selectResp.size}B)")
            return null
        }

        // 2. ADVERTISEMENT — RE-POLLED over a short window. A COLD receiver
        // (idle / not yet advertising) answers the first ADVERTISEMENT with an
        // empty tag AND wakes itself into receive (verified in GMS:
        // NfcAdvertisingChimeraService not-advertising branch -> djvf.f ->
        // PendingIntent.send launches the receive flow). Once it is advertising,
        // its HCE returns a real tag — so we re-send the ADVERTISEMENT on the
        // same (sustained-tap) IsoDep connection until we get a usable tag or the
        // window expires. This is what lets us send to a cold native/Super Drop
        // receiver by tap (mirrors native↔native). onTag runs on a binder thread
        // (off the main thread), so the loop + sleep cannot ANR; if the tag
        // leaves the field, transceive throws IOException which ends the loop via
        // the caller's catch.
        val hhww =
            QuickShareNfcCodec.encodeHhwwRequest(
                QuickShareNfcCodec.HhwwRequest(serviceId = NearbyServiceId.VALUE),
            )
        val advApdu = buildAdvertisementApdu(hhww)
        val deadline = SystemClock.elapsedRealtime() + TAP_RETRY_WINDOW_MS
        var attempt = 0
        while (true) {
            attempt++
            readAdvertisement(isoDep, advApdu, attempt)?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) {
                DiagnosticLog.w(
                    TAG,
                    "ADVERTISEMENT yielded no usable tag after $attempt attempt(s) over ${TAP_RETRY_WINDOW_MS}ms",
                )
                return null
            }
            try {
                Thread.sleep(TAP_RETRY_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                DiagnosticLog.w(TAG, "tap re-poll interrupted after $attempt attempt(s)")
                return null
            }
        }
    }

    /**
     * One ADVERTISEMENT round-trip + parse. Returns the resolved peer, or `null`
     * when the receiver answered empty / not-yet-advertising (the caller retries
     * within the re-poll window). Throws on an IsoDep I/O error (tag left field).
     */
    @Suppress("ReturnCount")
    private fun readAdvertisement(
        isoDep: IsoDep,
        advApdu: ByteArray,
        attempt: Int,
    ): TappedPeer? {
        val advResp = isoDep.transceive(advApdu)
        if (!endsWithOk(advResp) || advResp.size <= STATUS_LEN) {
            DiagnosticLog.w(TAG, "ADVERTISEMENT empty/not-OK (attempt $attempt, ${advResp.size}B)")
            return null
        }
        val body = advResp.copyOfRange(0, advResp.size - STATUS_LEN)

        val response = QuickShareNfcCodec.parseHhwvResponse(body) ?: return null
        if (response.nfcTag.isEmpty()) {
            DiagnosticLog.w(TAG, "hhwv carried no NfcTag yet (attempt $attempt)")
            return null
        }
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
                "name=${endpointInfo.deviceName} (attempt $attempt)",
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
        private const val TAG = "SuperDropTapReader"
        private const val STATUS_LEN = 2

        /**
         * How long to keep re-sending the ADVERTISEMENT on one sustained tap
         * while a cold receiver wakes + starts advertising (see [exchange]).
         */
        private const val TAP_RETRY_WINDOW_MS = 2500L

        /** Pause between ADVERTISEMENT re-polls within [TAP_RETRY_WINDOW_MS]. */
        private const val TAP_RETRY_INTERVAL_MS = 250L
    }
}
