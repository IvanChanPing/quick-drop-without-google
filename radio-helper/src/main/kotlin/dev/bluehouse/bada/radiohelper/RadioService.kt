/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger

/**
 * Bound [Messenger] service the main Bada app calls to flip radios on an
 * NFC tap. Guarded by the signature-level `BIND_RADIO` permission (manifest),
 * so only the same-key app can bind. Each command carries the desired state in
 * [Message.arg1] (1 = on, 0 = off); when [Message.replyTo] is set, the service
 * replies with the call's boolean result in `arg1`.
 */
internal class RadioService : Service() {
    // Handle requests OFF the main thread: the Wi-Fi silent path may bind a
    // Shizuku service (waits seconds), which must never run on the main looper.
    private val handlerThread = HandlerThread("radio-service").apply { start() }

    private val handler =
        Handler(handlerThread.looper) { msg ->
            val on = msg.arg1 == 1
            when (msg.what) {
                MSG_SET_WIFI -> replyResult(msg, RadioToggler.setWifiSilent(this, on))
                MSG_SET_BLUETOOTH -> replyResult(msg, RadioToggler.setBluetooth(on))
                MSG_QUERY -> replyState(msg)
                // Helper-OWNED share orchestration (apps stay dumb): prepare =
                // capture the user's original Wi-Fi/BT state + enable only the
                // needed radios that are OFF (msg.arg1 = radio bitmask, 0 = both);
                // reply arg1 = bitmask now ON. finished = restore ONLY what we
                // turned on. State is persisted in ShareRadioSession (survives a
                // process kill between the two calls).
                MSG_PREPARE_SHARE -> replyInt(msg, ShareRadioSession.prepare(this, msg.arg1))
                MSG_TRANSFER_FINISHED -> {
                    ShareRadioSession.finish(this)
                    replyResult(msg, true)
                }
                // Heartbeat from a client whose transfer is STILL running: push the
                // restore out so that if the client crashes / is killed mid-transfer
                // (never sends MSG_TRANSFER_FINISHED), the radios restore
                // ~HEARTBEAT_RESTORE_MS after the LAST beat — instead of staying on
                // until the 20-min safety watchdog. A live client keeps beating.
                MSG_TRANSFER_HEARTBEAT -> {
                    ShareRadioSession.scheduleRestoreIn(this, HEARTBEAT_RESTORE_MS)
                    replyResult(msg, true)
                }
            }
            true
        }

    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        handlerThread.quitSafely()
        super.onDestroy()
    }

    private fun replyResult(
        request: Message,
        result: Boolean,
    ): Boolean = replyInt(request, if (result) 1 else 0)

    /** Reply to [request] with an Int payload in arg1 (used by prepare's bitmask). */
    private fun replyInt(
        request: Message,
        value: Int,
    ): Boolean {
        val reply = request.replyTo ?: return true
        val out = Message.obtain(null, request.what).apply { arg1 = value }
        return runCatching { reply.send(out) }.isSuccess
    }

    private fun replyState(request: Message): Boolean {
        val reply = request.replyTo ?: return true
        val out =
            Message.obtain(null, MSG_QUERY).apply {
                arg1 = if (RadioToggler.isWifiOn(this@RadioService)) 1 else 0
                arg2 = if (RadioToggler.isBluetoothOn()) 1 else 0
            }
        return runCatching { reply.send(out) }.isSuccess
    }

    companion object {
        const val MSG_SET_WIFI = 1
        const val MSG_SET_BLUETOOTH = 2

        /** Reply carries Wi-Fi state in arg1, Bluetooth state in arg2. */
        const val MSG_QUERY = 3

        /**
         * Share START — the helper captures the original Wi-Fi/BT state and enables
         * the needed radios that are off. arg1 = radio bitmask (1=Wi-Fi, 2=BT,
         * 0=both). Reply arg1 = bitmask of radios now ON. The app calls this once;
         * it does NOT track or restore anything itself.
         */
        const val MSG_PREPARE_SHARE = 4

        /**
         * Transfer TERMINAL — the helper restores ONLY the radios it turned on in
         * MSG_PREPARE_SHARE, back to the user's original state. Reply arg1 = 1 (ack).
         */
        const val MSG_TRANSFER_FINISHED = 5

        /**
         * Transfer HEARTBEAT — "my transfer is still running." Each beat re-arms the
         * restore alarm [HEARTBEAT_RESTORE_MS] out, so a crashed/killed client (one
         * that stops beating without a MSG_TRANSFER_FINISHED) is restored ~20 s after
         * its last beat instead of waiting for the 20-min watchdog. Reply arg1 = 1.
         * The client (e.g. ShareRadioController) sends this every few seconds for the
         * life of the transfer; it is OPTIONAL (the prepare→finished path still works).
         */
        const val MSG_TRANSFER_HEARTBEAT = 6

        /** How long after the LAST heartbeat the helper restores the radios. */
        private const val HEARTBEAT_RESTORE_MS = 20_000L
    }
}
