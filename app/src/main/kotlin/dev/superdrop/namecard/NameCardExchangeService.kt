/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.superdrop.R
import dev.superdrop.discovery.diagnostics.DiagnosticLog

/**
 * **Name Card exchange foreground service (card/server side).** Started from
 * [dev.superdrop.nfc.NameCardHceService] when this phone is tapped: it brings the
 * process up reliably (a tap can wake us cold) and runs [NameCardBleExchange] as
 * the GATT **server** — advertising the rendezvous token and serving our card —
 * so the tapping phone can read it. When the peer writes its card back, this
 * launches the full-screen [NameCardTransferActivity] to show it + Save.
 *
 * Reader/client side does NOT use this service — that runs from the foreground
 * [NameCardTransferActivity] directly (no service needed when already foreground).
 *
 * FGS type `connectedDevice` (Bluetooth); the manifest declares the type and the
 * app already holds the `connectedDevice` FGS-type gate permission. See
 * `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`.
 *
 * Status: compile-only here (no BT/NFC/2 phones); device-verified by a real tap.
 */
internal class NameCardExchangeService : Service() {
    private var exchange: NameCardBleExchange? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startInForeground()
        val token = intent?.getByteArrayExtra(EXTRA_TOKEN)
        if (token == null) {
            DiagnosticLog.w(TAG, "no token → stop")
            stopSelf()
            return START_NOT_STICKY
        }
        val localCard =
            NameCardResolver(
                storedCard = NameCardProfileStore.from(this)::load,
                deviceSources = AndroidDeviceContactSources(this),
            ).resolve()
        if (localCard == null) {
            // Nothing to share (no in-app card and no device fallback). The
            // Settings row's red dot nudges the user to set one up.
            DiagnosticLog.w(TAG, "no resolvable card to share → stop")
            stopSelf()
            return START_NOT_STICKY
        }

        val ble = NameCardBleExchange(this)
        exchange = ble
        val started =
            ble.startServer(localCard = localCard, token = token) { peerCard ->
                DiagnosticLog.w(TAG, "server: peer card received → launch transfer screen")
                startActivity(
                    NameCardTransferActivity.serverIntent(this, peerCard).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                ble.stop()
                stopSelf()
            }
        if (!started) {
            DiagnosticLog.w(TAG, "startServer failed → stop")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        exchange?.stop()
        exchange = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.name_card_exchange_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.name_card_exchange_notification))
                .setOngoing(true)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "NameCardExchangeSvc"
        private const val CHANNEL_ID = "name_card_exchange"
        private const val NOTIFICATION_ID = 4310
        private const val EXTRA_TOKEN = "token"

        /** Start the server-side exchange for [token] (from the HCE tap). */
        fun start(
            context: Context,
            token: ByteArray,
        ) {
            val intent =
                Intent(context, NameCardExchangeService::class.java).putExtra(EXTRA_TOKEN, token)
            context.startForegroundService(intent)
        }
    }
}
