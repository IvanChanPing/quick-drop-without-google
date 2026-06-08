/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger

/**
 * Bound [Messenger] service the main Super Drop app calls to flip radios on an
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
            val result =
                when (msg.what) {
                    MSG_SET_WIFI -> RadioToggler.setWifiSilent(this, on)
                    MSG_SET_BLUETOOTH -> RadioToggler.setBluetooth(on)
                    MSG_QUERY ->
                        return@Handler replyState(msg)
                    else -> return@Handler false
                }
            replyResult(msg, result)
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
    ): Boolean {
        val reply = request.replyTo ?: return true
        val out = Message.obtain(null, request.what).apply { arg1 = if (result) 1 else 0 }
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
    }
}
