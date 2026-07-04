/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.superdrop.R
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import dev.superdrop.protocol.namecard.NameCard
import dev.superdrop.service.radio.RadioHelperClient
import dev.superdrop.service.radio.ShareRadioController

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
@Suppress("ReturnCount")
internal class NameCardExchangeService : Service() {
    private var exchange: NameCardBleExchange? = null
    private val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Forces Bluetooth on for the swap + runs the 5s helper heartbeat; restored on stop. */
    private val shareRadios by lazy { ShareRadioController(this, TAG) }

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

        // Force Bluetooth on via the helper (+ start the 5s keep-alive heartbeat so a
        // crash mid-swap restores radios ~20s after the last beat, not minutes later).
        shareRadios.requestRadiosOn(RadioHelperClient.RADIO_BT)

        if (NameCardPreferences.from(this).isV2Enabled()) {
            // Name Card v2 (symmetric consent): serve the gated card + CONSENT channel and launch the
            // transfer screen immediately so BOTH phones show the card at tap (plan B4). The service
            // stays foreground for the WHOLE session — it does NOT stop on peer-card-arrived.
            startServerWhenBtReadyV2(localCard, token, attempt = 0)
            timeoutHandler.postDelayed({
                DiagnosticLog.w(TAG, "serverV2: session timeout → stop")
                stopSelf()
            }, SERVE_TIMEOUT_MS_V2)
            return START_NOT_STICKY
        }

        // ---- v1 (asymmetric, unchanged) ----
        // The helper enables BT asynchronously; start serving once BT is actually on
        // (immediately if already on, else after a short grace for the helper toggle).
        startServerWhenBtReady(localCard, token, attempt = 0)

        // Backstop: stop if the tapping phone never connects/exchanges (e.g. Receive-Only,
        // which never reaches onPeerCard) so we don't advertise + foreground forever.
        timeoutHandler.postDelayed({
            DiagnosticLog.w(TAG, "server: exchange timeout → stop")
            stopSelf()
        }, SERVE_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    private fun startServerWhenBtReadyV2(
        localCard: NameCard,
        token: ByteArray,
        attempt: Int,
    ) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled == true || attempt >= MAX_BT_WAIT_ATTEMPTS) {
            startServerNowV2(localCard, token)
            return
        }
        DiagnosticLog.w(TAG, "serverV2: BT not on yet (attempt $attempt) → waiting for helper")
        timeoutHandler.postDelayed({ startServerWhenBtReadyV2(localCard, token, attempt + 1) }, BT_GRACE_MS)
    }

    private fun startServerNowV2(
        localCard: NameCard,
        token: ByteArray,
    ) {
        val ble = NameCardBleExchange(this)
        exchange = ble
        val session = NameCardLinkHolder.startSession(ble, NameCardLinkHolder.Role.SERVER, localCard)
        session.onClosed = { stopSelf() }
        val started = ble.startServerV2(localCard = localCard, token = token, listener = session)
        if (!started) {
            DiagnosticLog.w(TAG, "startServerV2 failed (BT off / no perm) → stop")
            stopSelf()
            return
        }
        // Both screens open at tap: launch the symmetric consent UI now (peer card arrives later via
        // the holder, not an Intent extra). Keep the FGS + radios alive until the session closes.
        DiagnosticLog.w(TAG, "serverV2: serving → launch transfer screen (SERVER-v2)")
        startActivity(
            NameCardTransferActivity.serverV2Intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun startServerWhenBtReady(
        localCard: NameCard,
        token: ByteArray,
        attempt: Int,
    ) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled == true || attempt >= MAX_BT_WAIT_ATTEMPTS) {
            startServerNow(localCard, token)
            return
        }
        DiagnosticLog.w(TAG, "server: BT not on yet (attempt $attempt) → waiting for helper")
        timeoutHandler.postDelayed({ startServerWhenBtReady(localCard, token, attempt + 1) }, BT_GRACE_MS)
    }

    private fun startServerNow(
        localCard: NameCard,
        token: ByteArray,
    ) {
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
            DiagnosticLog.w(TAG, "startServer failed (BT off / no perm) → stop")
            stopSelf()
        }
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null)
        exchange?.stop()
        exchange = null
        // Stop the heartbeat + restore the radios the helper turned on.
        shareRadios.restoreRadios()
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
            NotificationCompat
                .Builder(this, CHANNEL_ID)
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

        /** Stop the service if no exchange completes (> the BLE manager's own backstop). */
        private const val SERVE_TIMEOUT_MS = 33_000L

        /** v2 session backstop: > the exchange's 60s v2 backstop, so the UX timer resolves first (plan B4). */
        private const val SERVE_TIMEOUT_MS_V2 = 65_000L

        /** Grace between retries while the helper turns Bluetooth on, and the cap. */
        private const val BT_GRACE_MS = 1_500L
        private const val MAX_BT_WAIT_ATTEMPTS = 2

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
