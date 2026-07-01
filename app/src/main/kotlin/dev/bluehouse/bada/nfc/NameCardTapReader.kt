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
import dev.bluehouse.bada.protocol.namecard.NameCardBootstrap
import java.io.IOException

/**
 * **Name Card tap — reader side.** Puts the foreground [activity] into NFC
 * reader-mode and, on a tap to another Bada phone's [NameCardHceService],
 * runs the bootstrap exchange (SELECT Name Card AID → EXCHANGE) to read the
 * peer's rendezvous [NameCardBootstrap]. The parsed token is handed to
 * [onPeerBootstrap], which opens [NameCardTransferActivity] to connect over
 * Bluetooth by scanning for that token.
 *
 * Why a foreground reader: Android assigns NFC roles only when one side is an
 * explicit reader, and reader-mode requires a resumed Activity. The person
 * sharing has the app foreground; the other phone can be idle (its background
 * [NameCardHceService] answers). `enableReaderMode` also suppresses this phone's
 * own card so the roles are deterministic.
 *
 * Public `android.nfc` APIs only.
 */
internal class NameCardTapReader(
    private val activity: Activity,
    private val onPeerBootstrap: (NameCardBootstrap) -> Unit,
    /** One-line human-readable outcome of each tap, for on-screen diagnostics. */
    private val onTapDiagnostic: (String) -> Unit = {},
) {
    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /** Enter reader-mode. No-op without an NFC adapter. Safe to call repeatedly. */
    fun enable() {
        val adapter = nfcAdapter ?: return
        val flags =
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        runCatching {
            adapter.enableReaderMode(activity, ::onTag, flags, null)
        }.onFailure { DiagnosticLog.w(TAG, "enableReaderMode failed: ${it.message}") }
    }

    /** Leave reader-mode. */
    fun disable() {
        nfcAdapter?.let { runCatching { it.disableReaderMode(activity) } }
    }

    /** Reader-mode tag callback (binder thread). Marshalling to UI is the caller's job. */
    private fun onTag(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return
        val bootstrap =
            try {
                isoDep.connect()
                exchange(isoDep)
            } catch (e: IOException) {
                DiagnosticLog.w(TAG, "Name Card tap exchange failed: ${e.message}")
                null
            } finally {
                runCatching { isoDep.close() }
            }

        if (bootstrap == null) {
            onTapDiagnostic("Name Card tap: no Bada card (or tag lost)")
            return
        }
        DiagnosticLog.w(TAG, "Name Card tap resolved peer token (${bootstrap.token.size}B)")
        onTapDiagnostic("Name Card tap: peer resolved → connecting over Bluetooth")
        onPeerBootstrap(bootstrap)
    }

    /** SELECT then EXCHANGE; returns the parsed peer bootstrap, or null on any failure. */
    @Suppress("ReturnCount")
    private fun exchange(isoDep: IsoDep): NameCardBootstrap? {
        val selectResp = isoDep.transceive(NameCardHceService.buildSelectApdu())
        if (!NameCardHceService.endsWithOk(selectResp)) {
            DiagnosticLog.w(TAG, "SELECT not OK (${selectResp.size}B) — not a Name Card peer / locked")
            return null
        }
        val exchangeResp = isoDep.transceive(NameCardHceService.buildExchangeApdu())
        if (!NameCardHceService.endsWithOk(exchangeResp) || exchangeResp.size <= STATUS_LEN) return null
        val body = exchangeResp.copyOfRange(0, exchangeResp.size - STATUS_LEN)
        return NameCardBootstrap.parse(body)
    }

    private companion object {
        private const val TAG = "NameCardTapReader"
        private const val STATUS_LEN = 2
    }
}
