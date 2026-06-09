/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.service.radio

import android.content.Context
import dev.superdrop.discovery.diagnostics.DiagnosticLog

/**
 * ShareRadioController — the ONE place that forces Wi-Fi + Bluetooth on for a
 * share and restores them after, on behalf of every share entry point.
 *
 * ### Why this exists
 *
 * Three flows need the radios on for a transfer: the SENDER
 * ([dev.superdrop.send.SendActivity]), the cold NFC tap-to-receive wake, and
 * the Quick Settings tile (both via
 * [dev.superdrop.service.receiver.ReceiverForegroundService]). Each used to
 * re-implement the identical dance — create a [RadioHelperClient], `connect`,
 * `prepareForShare(RADIO_BOTH)`, track a "prepared" flag, then on teardown
 * `transferFinished` + `disconnect`. This class holds that logic once so the
 * call sites just say [requestRadiosOn] at share start and [restoreRadios] at
 * the terminal.
 *
 * The actual capture-original / enable-only-off / restore-only-ours logic lives
 * in the `:radio-helper` app's SESSION mode (the helper owns it and persists it
 * across a process kill); this controller is only the client-side lease.
 *
 * ### Contract / behaviour (preserved verbatim from the old call sites)
 *
 *  - [requestRadiosOn] is best-effort and async (binds on the caller's thread,
 *    work happens on the helper's): if the helper isn't installed / has the
 *    wrong signing key / is force-stopped / times out, the radios are left
 *    as-is and the caller's own fallback applies. No ANR.
 *  - [restoreRadios] tells the helper the share is over so it restores ONLY the
 *    radios it turned on, then unbinds. `finishSession` lets a caller unbind
 *    WITHOUT ending the helper session — the sender uses this on a
 *    config-change recreate (`isFinishing == false`): drop this dead instance's
 *    binding but leave the persisted, re-entrant helper session for the
 *    recreated instance to re-prepare. The receiver always ends the session.
 *  - [logTag] is the logcat tag for observability; pass `null` for none.
 *
 * Holds at most one client at a time; not thread-safe — call from a single
 * thread per instance (the main thread, as both existing call sites do).
 */
public class ShareRadioController(
    private val context: Context,
    private val logTag: String? = null,
) {
    private var client: RadioHelperClient? = null

    /** `true` once the helper has acknowledged a [requestRadiosOn] prepare. */
    public var isPrepared: Boolean = false
        private set

    /**
     * Bind the `:radio-helper` and force [radios] ON for the share. Idempotent
     * against repeat calls on the same instance (reuses the existing client).
     */
    public fun requestRadiosOn(radios: Int = RadioHelperClient.RADIO_BOTH) {
        val c = client ?: RadioHelperClient(context).also { client = it }
        log("requesting radios (bitmask=$radios) via radio-helper")
        c.connect { connected ->
            if (!connected) {
                log("radio-helper unavailable (not installed / wrong signing key / force-stopped) -> radios left as-is")
                return@connect
            }
            c.prepareForShare(radios) { nowOn ->
                isPrepared = true
                log("radio-helper prepared radios, now-on bitmask=$nowOn")
            }
        }
    }

    /**
     * Release the radio lease. When [finishSession] is true (the default —
     * a real terminal: transfer done / declined / cancelled / sheet closed)
     * the helper restores only what it turned on. When false (config-change
     * recreate) the session is left intact and we only unbind.
     */
    public fun restoreRadios(finishSession: Boolean = true) {
        val c = client ?: return
        if (finishSession && isPrepared) {
            log("share finished -> radio-helper restores the radios it enabled")
            c.transferFinished()
            isPrepared = false
        }
        c.disconnect()
        client = null
    }

    private fun log(message: String) {
        if (logTag != null) DiagnosticLog.w(logTag, message)
    }
}
