/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.bluehouse.bada.radiohelper.adbwifi.AdbMdns
import dev.bluehouse.bada.radiohelper.adbwifi.AdbWifiManager
import dev.bluehouse.bada.radiohelper.adbwifi.AdbWifiRadio

/**
 * WHAT THIS IS
 * ------------
 * `PairingReplyReceiver` — receives the 6-digit code the user types into the
 * [PairingNotifier] inline-reply notification, and completes the Android-11
 * wireless-debugging pairing WITHOUT the user leaving the Settings pairing dialog.
 *
 * FLOW
 * ----
 * user types code into the notification reply → this receiver fires →
 * [AdbMdns.discoverHostPort] finds the transient `_adb-tls-pairing._tcp` endpoint
 * (so the user never types the port) → [AdbWifiManager.pair] pairs with that
 * host:port + code → on success, [AdbWifiRadio.ensureReady] warms the connection.
 * Every step updates the notification via [PairingNotifier.update] so the result
 * is visible right there (no hunting for logs).
 *
 * WHY A RECEIVER
 * --------------
 * RemoteInput replies are delivered to a PendingIntent; a BroadcastReceiver is the
 * lightest target. Not exported — only our own PendingIntent triggers it.
 *
 * THREADING / STATUS
 * ------------------
 * mDNS + pairing block, so the work runs on a background thread via [goAsync];
 * onReceive returns immediately. compile-only / device-UNVERIFIED.
 */
internal class PairingReplyReceiver : BroadcastReceiver() {
    // The background ADB/mDNS pairing work can throw a wide range of I/O and
    // reflection errors; a broadcast receiver must never crash, so it catches
    // Throwable and surfaces the message to the pairing notification instead.
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val code =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(PairingNotifier.KEY_CODE)
                ?.toString()
                ?.trim()
                .orEmpty()
        if (code.isEmpty()) {
            PairingNotifier.update(context, "No code entered — reopen the pairing dialog and reply with the 6 digits.")
            return
        }

        PairingNotifier.update(context, "Pairing… discovering pairing port via mDNS (keep the dialog open).")
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val hp = AdbMdns.discoverHostPort(appContext, AdbMdns.SERVICE_PAIRING)
                val msg =
                    when {
                        hp == null ->
                            "No pairing service found via mDNS. Is the 'Pair device with pairing code' " +
                                "dialog open? Try toggling Wireless debugging off/on, reopen the dialog, retry."
                        AdbWifiManager.pair(appContext, hp.host, hp.port, code) -> {
                            // Paired → re-enable wireless debugging + warm the connection.
                            val ready = AdbWifiRadio.ensureReady(appContext)
                            val state = if (ready) "ready" else "warm-up: ${AdbWifiRadio.lastStatus}"
                            "Paired OK at ${hp.host}:${hp.port}. self-ADB $state"
                        }
                        else ->
                            "Pair FAILED at ${hp.host}:${hp.port} — wrong code or it expired. " +
                                "Reopen the dialog (new code) and reply again."
                    }
                Log.i(TAG, msg)
                PairingNotifier.update(appContext, msg)
            } catch (t: Throwable) {
                Log.w(TAG, "pairing reply failed: ${t.message}")
                PairingNotifier.update(appContext, "Pairing error: ${t.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "AdbWifi/Pairing"
    }
}
