/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * WHAT THIS IS
 * ------------
 * `PairingNotifier` — posts the **inline-reply pairing notification**. This is the
 * mechanism (lifted from Brevent, verified from its strings) that makes Android-11
 * wireless-debugging pairing POSSIBLE on devices where it otherwise isn't:
 *
 * WHY IT EXISTS
 * -------------
 * The "Pair device with pairing code" dialog in Settings CLOSES the moment you
 * switch apps (and Settings can't be put in split screen on this device), so you
 * can't read the code, open our app, and type it. But replying to a NOTIFICATION
 * does NOT switch the foreground app — the pairing dialog stays alive. So the user
 * pulls down the shade and types the 6-digit code into THIS notification's reply
 * field while the dialog is still open. Exactly Brevent's "reply Brevent's
 * notification with the six digit code".
 *
 * WHAT IT'S CALLED / HOW INVOKED
 * ------------------------------
 * Posted by the "Start pairing (notification)" button on SelfTestActivity via
 * [show]. The reply is delivered to [PairingReplyReceiver], which discovers the
 * pairing port via mDNS and calls the pairing. [update] re-posts the same
 * notification with a result/status line (and the reply field again, to retry).
 *
 * THE NOTIFICATION (what it looks like)
 * -------------------------------------
 * Channel "ADB Wi-Fi pairing"; title "ADB Wi-Fi pairing"; body = the current
 * instruction/status; one action button labelled "Pair" that opens an inline
 * text reply ("6-digit pairing code"). Small icon = the system info icon.
 *
 * STATUS: compile-only / device-UNVERIFIED. The premise (inline reply keeps the
 * pairing dialog alive on this ColorOS) is Brevent-verified in general but not
 * tested by us on this device.
 */
internal object PairingNotifier {
    /** RemoteInput result key carrying the typed 6-digit code. */
    const val KEY_CODE = "dev.bluehouse.bada.radiohelper.PAIRING_CODE"

    private const val CHANNEL_ID = "adb_pairing"
    private const val NOTIF_ID = 4711
    private const val ACTION_REPLY = "dev.bluehouse.bada.radiohelper.action.PAIRING_REPLY"

    /** Post the pairing notification with its inline reply field. */
    fun show(context: Context) {
        notify(
            context,
            "Open Settings ▸ Developer options ▸ Wireless debugging ▸ Pair device with " +
                "pairing code, then pull down here and type the 6 digits into the reply ▸ " +
                "(do NOT close the dialog).",
        )
    }

    /** Re-post with a status/result line (keeps the reply field for retries). */
    fun update(
        context: Context,
        status: String,
    ) {
        notify(context, status)
    }

    private fun notify(
        context: Context,
        body: String,
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        // replyAction — the "Pair" action button that expands into an inline text
        // field; the typed code is delivered to PairingReplyReceiver as RemoteInput.
        val remoteInput =
            RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val replyIntent =
            Intent(context, PairingReplyReceiver::class.java).setAction(ACTION_REPLY)
        val piFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(context, 0, replyIntent, piFlags)
        val replyAction =
            Notification.Action
                .Builder(android.R.drawable.ic_menu_send, "Pair", pi)
                .addRemoteInput(remoteInput)
                .build()

        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
        val notification =
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("ADB Wi-Fi pairing")
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setOngoing(false)
                .setAutoCancel(false)
                .addAction(replyAction)
                .build()
        nm.notify(NOTIF_ID, notification)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ADB Wi-Fi pairing",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "One-time wireless-debugging pairing for silent Wi-Fi toggling." },
        )
    }
}
