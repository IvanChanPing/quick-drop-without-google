/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * WHAT THIS IS
 * ------------
 * `ShareRadioSession` — the HELPER-side orchestration for "force the needed radios
 * ON for a transfer, then restore them to their ORIGINAL state when it finishes."
 * This logic lives in the HELPER (not the client app) by design: the client apps
 * are dumb — they only call [prepare] at transfer/NFC-tap start and [finish] at the
 * terminal. The helper decides what was off, turns it on, and undoes ONLY what it
 * turned on.
 *
 * WHY IN THE HELPER
 * -----------------
 * The user's rule (2026-06-09): "the helper should be the one to determine whether
 * Wi-Fi/Bluetooth were already off and turn them on and then set them back to their
 * original. The only thing our app should do is call to it and tell it the transfer
 * finished." So state capture + restore is server-side here, never duplicated per app.
 *
 * PROCESS-DEATH ROBUSTNESS
 * ------------------------
 * The "what we turned on" flags are persisted to SharedPreferences, so if the helper
 * process is killed between [prepare] and [finish], the restore still undoes exactly
 * the radios we enabled (no leaving the user's Wi-Fi/BT on against their original).
 *
 * HOW IT FITS
 * -----------
 * Called by `RadioService` on MSG_PREPARE_SHARE / MSG_TRANSFER_FINISHED. Uses
 * [RadioToggler] for the actual silent toggle ladder. Runs on RadioService's
 * background HandlerThread (the silent Wi-Fi path can block) — never the main thread.
 *
 * STATUS: compile-only / device-UNVERIFIED end-to-end (the underlying toggle ladder
 * was validated separately).
 */
internal object ShareRadioSession {
    private const val TAG = "ShareRadioSession"
    private const val PREFS = "share_radio_session"
    private const val KEY_ENABLED_WIFI = "enabledWifi"
    private const val KEY_ENABLED_BT = "enabledBt"

    // SAFETY watchdog: if the app never calls finish (crash / force-kill mid-
    // transfer), restore the radios anyway after this long so the user's Wi-Fi/BT
    // are never stranded ON. The PRIMARY restore is still the app's
    // transferFinished (fired on ANY terminal: success/fail/cancel/closed); this
    // is only the backstop. Generous so it can't cut a legitimately long transfer
    // where the app is alive (that path restores via transferFinished on its own).
    private const val WATCHDOG_MS = 20L * 60 * 1000
    const val ACTION_WATCHDOG = "dev.superdrop.radiohelper.action.SHARE_WATCHDOG"

    /** Radio bitmask used in the prepare request/result (matches the client). */
    const val RADIO_WIFI = 1
    const val RADIO_BT = 2
    const val RADIO_BOTH = RADIO_WIFI or RADIO_BT

    /**
     * Transfer START. For each requested radio that is currently OFF, turn it ON
     * (silent ladder) and remember we did so (persisted). Radios already ON are
     * left untouched and NOT recorded (so [finish] won't turn them off).
     * @param radios bitmask of radios the transfer needs (0 → both).
     * @return bitmask of radios that are ON after this call (Wi-Fi bit set only if
     *         a SILENT path actually enabled it; the caller may ignore this).
     */
    fun prepare(
        context: Context,
        radios: Int,
    ): Int {
        val want = if (radios == 0) RADIO_BOTH else radios
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // RE-ENTRANT: seed from any prior un-finished prepare so a SECOND prepare
        // (activity recreated on rotation, or repeated wakes) ADDS to what we
        // enabled and never resets a true→false. Without this, a 2nd prepare would
        // see the radio we already turned on as "already on", record enabled=false,
        // and finish() would then leave it stranded ON. finish() clears the prefs,
        // so a genuinely new share still starts from a clean (false) capture.
        var enabledWifi = prefs.getBoolean(KEY_ENABLED_WIFI, false)
        var enabledBt = prefs.getBoolean(KEY_ENABLED_BT, false)
        var nowOn = 0

        if (want and RADIO_WIFI != 0) {
            if (RadioToggler.isWifiOn(context)) {
                nowOn = nowOn or RADIO_WIFI
            } else if (RadioToggler.setWifiSilent(context, true)) {
                enabledWifi = true
                nowOn = nowOn or RADIO_WIFI
            } else {
                Log.w(TAG, "prepare: Wi-Fi could not be enabled silently (${RadioToggler.javaClass.simpleName})")
            }
        }
        if (want and RADIO_BT != 0) {
            if (RadioToggler.isBluetoothOn()) {
                nowOn = nowOn or RADIO_BT
            } else if (RadioToggler.setBluetooth(true)) {
                enabledBt = true
                nowOn = nowOn or RADIO_BT
            }
        }

        // Persist what WE turned on so finish() restores it even after a process kill.
        prefs.edit()
            .putBoolean(KEY_ENABLED_WIFI, enabledWifi)
            .putBoolean(KEY_ENABLED_BT, enabledBt)
            .apply()
        // Arm the safety watchdog only if there's something to restore; if nothing
        // was turned on (both were already on), there's no session to back out.
        if (enabledWifi || enabledBt) scheduleWatchdog(context) else cancelWatchdog(context)
        Log.i(TAG, "prepare(want=$want): enabledWifi=$enabledWifi enabledBt=$enabledBt nowOn=$nowOn")
        return nowOn
    }

    /**
     * Transfer TERMINAL (complete / declined / timeout). Turn back OFF only the
     * radios WE turned on in [prepare], restoring the user's original state. Clears
     * the persisted session.
     */
    fun finish(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabledWifi = prefs.getBoolean(KEY_ENABLED_WIFI, false)
        val enabledBt = prefs.getBoolean(KEY_ENABLED_BT, false)
        if (enabledWifi) RadioToggler.setWifiSilent(context, false)
        if (enabledBt) RadioToggler.setBluetooth(false)
        prefs.edit().clear().apply()
        cancelWatchdog(context)
        Log.i(TAG, "finish: restored wifi=$enabledWifi bt=$enabledBt")
    }

    /**
     * BOOT recovery. AlarmManager watchdogs are cleared on reboot, so if the device
     * rebooted mid-transfer a session can be left persisted (and Android remembers
     * Wi-Fi as the ON state WE set). Called by the boot service to restore the
     * user's original state. No-op if no session is pending. Blocking — off main.
     */
    fun restoreStaleOnBoot(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ENABLED_WIFI, false) || prefs.getBoolean(KEY_ENABLED_BT, false)) {
            Log.i(TAG, "restoreStaleOnBoot: found pending session — restoring")
            finish(context)
        }
    }

    private fun watchdogPendingIntent(context: Context): PendingIntent {
        val intent =
            Intent(context.applicationContext, ShareWatchdogReceiver::class.java).setAction(ACTION_WATCHDOG)
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context.applicationContext, 0, intent, flags)
    }

    /** Arm the safety alarm at now+WATCHDOG_MS (backstop for a missed finish). */
    private fun scheduleWatchdog(context: Context) = scheduleRestoreIn(context, WATCHDOG_MS)

    /**
     * (Re)arm the restore alarm to fire [delayMs] from now, REPLACING any prior
     * alarm (same PendingIntent). DURABLE by design: AlarmManager re-launches
     * [ShareWatchdogReceiver] (→ [finish]) even if our process was frozen or killed
     * in between — unlike an in-process `Handler.postDelayed`, which dies with the
     * process. Exact + allow-while-idle: this APK targets API 28, so exact alarms
     * need no SCHEDULE_EXACT_ALARM permission. Used by [QuickShareWatcherService] to
     * schedule the post-Quick-Share restore on a short grace, and to push it back
     * out to the full watchdog while Quick Share is in the foreground.
     */
    fun scheduleRestoreIn(
        context: Context,
        delayMs: Long,
    ) {
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + delayMs
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogPendingIntent(context))
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, watchdogPendingIntent(context))
            }
        }.onFailure { Log.w(TAG, "scheduleRestoreIn($delayMs) failed: ${it.message}") }
    }

    /**
     * True while a prepared session is pending restore (we turned a radio ON and
     * haven't restored yet). Read from the persisted flags so it's correct even
     * after the alarm-driven [finish] ran in another process. For status/UI.
     */
    fun isSessionActive(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED_WIFI, false) || prefs.getBoolean(KEY_ENABLED_BT, false)
    }

    private fun cancelWatchdog(context: Context) {
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(watchdogPendingIntent(context)) }
    }
}
