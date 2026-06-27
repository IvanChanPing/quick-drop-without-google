/*
 * Copyright 2026 Bada contributors. Apache-2.0.
 *
 * ════════════════════════════════════════════════════════════════════════════
 *  RadioHelperClient — DROP-IN CLIENT for the universal Bada Radio Helper
 * ════════════════════════════════════════════════════════════════════════════
 *
 * WHAT THIS IS
 *   Bada's copy of the canonical universal-radio-helper client
 *   (`radio-helper/client/RadioHelperClient.kt`, repackaged here). Routes
 *   Wi-Fi/Bluetooth toggling through the ONE installed `radio-helper` APK
 *   (pkg `dev.bluehouse.bada.radiohelper`) instead of re-implementing the
 *   targetSdk-28 legacy toggle / self-ADB / Shizuku ladder in this app.
 *
 * WHO CALLS IT (in Bada)
 *   [dev.bluehouse.bada.service.receiver.ReceiverForegroundService] on an NFC
 *   tap-to-receive wake (ACTION_NFC_WAKE): `connect { prepareForShare(RADIO_BOTH) }`
 *   to force Wi-Fi + Bluetooth on for the transfer, and `transferFinished()` +
 *   `disconnect()` when the receiver session tears down (helper restores only
 *   what it turned on).
 *
 * SESSION MODE (helper owns the state)
 *   The HELPER captures the user's original Wi-Fi/BT state, enables what's off,
 *   and restores it on transferFinished — persisted helper-side
 *   (`ShareRadioSession`) so it survives a process kill between the two calls.
 *   This app tracks nothing.
 *
 * REQUIREMENTS (see /radio-helper-integration skill)
 *   - Manifest: `<uses-permission android:name="dev.bluehouse.bada.radiohelper.permission.BIND_RADIO"/>`
 *     + `<queries><package android:name="dev.bluehouse.bada.radiohelper"/></queries>`.
 *   - Bada must be signed with the SAME key as the helper (BIND_RADIO is a
 *     signature permission). Debug builds share the debug key → OK for testing.
 *
 * THREADING / STATUS
 *   bindService + Messenger.send are async (non-blocking) → safe on the main
 *   thread, NO ANR. Callbacks are delivered on the main looper → CALL ON THE
 *   MAIN THREAD (the callback queues aren't synchronized). Compile-only here
 *   until the real NFC-tap share path is exercised on a device.
 */
package dev.bluehouse.bada.service.radio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.util.ArrayDeque

class RadioHelperClient(
    context: Context,
) {
    private val appContext = context.applicationContext

    // Outgoing messenger to the helper's RadioService (set on bind).
    @Volatile
    private var outgoing: Messenger? = null

    // Per-message-type FIFO of pending callbacks. Requests are normally issued
    // sequentially (prepare→finished), so FIFO correlation by `what` is correct.
    private val wifiCbs = ArrayDeque<(Boolean) -> Unit>()
    private val btCbs = ArrayDeque<(Boolean) -> Unit>()
    private val queryCbs = ArrayDeque<(Boolean, Boolean) -> Unit>()
    private val prepareCbs = ArrayDeque<(Int) -> Unit>()
    private val finishedCbs = ArrayDeque<() -> Unit>()

    private val incoming =
        Messenger(
            Handler(Looper.getMainLooper()) { msg ->
                when (msg.what) {
                    MSG_SET_WIFI -> wifiCbs.poll()?.invoke(msg.arg1 == 1)
                    MSG_SET_BLUETOOTH -> btCbs.poll()?.invoke(msg.arg1 == 1)
                    MSG_QUERY -> queryCbs.poll()?.invoke(msg.arg1 == 1, msg.arg2 == 1)
                    MSG_PREPARE_SHARE -> prepareCbs.poll()?.invoke(msg.arg1)
                    MSG_TRANSFER_FINISHED -> finishedCbs.poll()?.invoke()
                }
                true
            },
        )

    // Explicit type: connection and connectTimeout reference each other, which
    // makes Kotlin's type inference recurse if either is left to inference.
    private val connection: ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                mainHandler.removeCallbacks(connectTimeout)
                outgoing = service?.let { Messenger(it) }
                pendingOnConnected?.invoke(outgoing != null)
                pendingOnConnected = null
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                outgoing = null
            }
        }

    private var pendingOnConnected: ((Boolean) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Fallback if a bind that returned true never actually connects (e.g. a sticky
    // OEM force-stop where the helper process can't be started): without this the
    // caller's connect() callback would hang forever. Fires connect(false) + unbinds.
    private val connectTimeout: Runnable =
        Runnable {
            if (outgoing == null) {
                val cb = pendingOnConnected
                pendingOnConnected = null
                runCatching { appContext.unbindService(connection) }
                cb?.invoke(false)
            }
        }

    /**
     * Bind the helper's RadioService. [onConnected] is called with `true` when
     * bound, or `false` if the helper isn't installed / bind denied (wrong
     * signing key) — in which case the caller should use its own fallback
     * (system Wi-Fi panel / BT ACTION_REQUEST_ENABLE).
     */
    fun connect(onConnected: (Boolean) -> Unit) {
        if (outgoing != null) {
            onConnected(true)
            return
        }
        pendingOnConnected = onConnected
        val bound =
            HELPER_PACKAGES.any { pkg ->
                runCatching {
                    appContext.bindService(
                        // FLAG_INCLUDE_STOPPED_PACKAGES: an explicit bind to a Service
                        // is not subject to the broadcast-receiver "stopped-state"
                        // exclusion, and this flag FORCES matching a force-stopped /
                        // never-opened helper — so our app can WAKE the helper even
                        // after the user force-stopped it. (Aggressive OEM force-stop,
                        // e.g. ColorOS, may still need one manual open — then connect()
                        // returns false and the caller falls back.)
                        Intent()
                            .setComponent(ComponentName(pkg, HELPER_SERVICE))
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }.getOrDefault(false)
            }
        if (!bound) {
            pendingOnConnected = null
            Log.w(TAG, "helper not installed / bind denied (same signing key?)")
            onConnected(false)
        } else {
            // Guard against a bind that returns true but never connects.
            mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        }
    }

    /** Toggle Wi-Fi. [cb] gets true if a SILENT path succeeded; on false the
     *  caller should open the system Wi-Fi panel itself (foreground). */
    fun setWifi(
        on: Boolean,
        cb: (Boolean) -> Unit,
    ) = send(MSG_SET_WIFI, on, wifiCbs, cb)

    /** Toggle Bluetooth. [cb] gets the helper's enable()/disable() result. */
    fun setBluetooth(
        on: Boolean,
        cb: (Boolean) -> Unit,
    ) = send(MSG_SET_BLUETOOTH, on, btCbs, cb)

    /** Query current state: cb(wifiOn, btOn). */
    fun queryState(cb: (Boolean, Boolean) -> Unit) {
        val m = outgoing
        if (m == null) {
            cb(false, false)
            return
        }
        queryCbs.add(cb)
        runCatching {
            m.send(Message.obtain(null, MSG_QUERY).also { it.replyTo = incoming })
        }.onFailure {
            queryCbs.remove(cb)
            cb(false, false)
        }
    }

    private fun send(
        what: Int,
        on: Boolean,
        cbs: ArrayDeque<(Boolean) -> Unit>,
        cb: (Boolean) -> Unit,
    ) {
        val m = outgoing
        if (m == null) {
            cb(false)
            return
        }
        cbs.add(cb)
        runCatching {
            m.send(
                Message.obtain(null, what).also {
                    it.arg1 = if (on) 1 else 0
                    it.replyTo = incoming
                },
            )
        }.onFailure {
            cbs.remove(cb)
            cb(false)
        }
    }

    /**
     * Transfer START — ONE call. The HELPER captures the user's original Wi-Fi/BT
     * state, enables whichever requested radios are OFF, and remembers what it
     * turned on (persisted helper-side). The app does NOT query, track, or restore
     * anything itself — that's all in the helper (`ShareRadioSession`).
     * @param radios which radios the transfer needs ([RADIO_BOTH] default).
     * @param done optional: bitmask of radios now ON (for diagnostics; the app may
     *   ignore it). Call [connect] (and act on its false result) before this.
     */
    fun prepareForShare(
        radios: Int = RADIO_BOTH,
        done: (radiosNowOn: Int) -> Unit = {},
    ) {
        val m = outgoing
        if (m == null) {
            done(0)
            return
        }
        prepareCbs.add(done)
        runCatching {
            m.send(
                Message.obtain(null, MSG_PREPARE_SHARE).also {
                    it.arg1 = radios
                    it.replyTo = incoming
                },
            )
        }.onFailure {
            prepareCbs.remove(done)
            done(0)
        }
    }

    /**
     * Transfer FINISHED — ONE call (complete / declined / timeout). The HELPER
     * restores ONLY the radios it turned on, back to the user's original state.
     */
    fun transferFinished(done: () -> Unit = {}) {
        val m = outgoing
        if (m == null) {
            done()
            return
        }
        finishedCbs.add(done)
        runCatching {
            m.send(Message.obtain(null, MSG_TRANSFER_FINISHED).also { it.replyTo = incoming })
        }.onFailure {
            finishedCbs.remove(done)
            done()
        }
    }

    /**
     * Transfer HEARTBEAT — "my transfer is still running." Fire-and-forget keep-alive
     * (no reply tracked): each call pushes the helper's restore out, so if this app
     * crashes / is killed mid-transfer (never calls [transferFinished]) the radios are
     * restored ~20 s after the LAST beat instead of waiting for the helper's 20-min
     * watchdog. Send it every few seconds for the life of the transfer. No-op if not
     * connected. Used by [ShareRadioController]'s heartbeat ticker.
     */
    fun heartbeat() {
        val m = outgoing ?: return
        runCatching { m.send(Message.obtain(null, MSG_TRANSFER_HEARTBEAT)) }
    }

    /** Unbind. Call when the app no longer needs the helper. */
    fun disconnect() {
        mainHandler.removeCallbacks(connectTimeout)
        if (outgoing != null) {
            runCatching { appContext.unbindService(connection) }
            outgoing = null
        }
    }

    companion object {
        private const val TAG = "RadioHelperClient"

        // The helper APK. Release first, then the debug build (handy in testing).
        private val HELPER_PACKAGES = listOf("dev.bluehouse.bada.radiohelper", "dev.bluehouse.bada.radiohelper.debug")
        private const val HELPER_SERVICE = "dev.bluehouse.bada.radiohelper.RadioService"
        private const val CONNECT_TIMEOUT_MS = 5_000L

        // MUST match RadioService in the helper.
        private const val MSG_SET_WIFI = 1
        private const val MSG_SET_BLUETOOTH = 2
        private const val MSG_QUERY = 3
        private const val MSG_PREPARE_SHARE = 4
        private const val MSG_TRANSFER_FINISHED = 5
        private const val MSG_TRANSFER_HEARTBEAT = 6

        /** Radio bitmask for [prepareForShare] (matches ShareRadioSession). */
        const val RADIO_WIFI = 1
        const val RADIO_BT = 2
        const val RADIO_BOTH = RADIO_WIFI or RADIO_BT
    }
}
