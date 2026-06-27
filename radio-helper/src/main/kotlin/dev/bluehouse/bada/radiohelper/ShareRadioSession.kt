/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
    const val ACTION_WATCHDOG = "dev.bluehouse.bada.radiohelper.action.SHARE_WATCHDOG"

    /** Radio bitmask used in the prepare request/result (matches the client). */
    const val RADIO_WIFI = 1
    const val RADIO_BT = 2
    const val RADIO_BOTH = RADIO_WIFI or RADIO_BT

    /**
     * The pure, Android-free decision for [prepare]: given what's wanted, the
     * current radio states, and the prior persisted session, decide which radios
     * to ATTEMPT to enable and the cumulative "what WE turned on" flags to persist.
     * Extracted so the safety-critical bitmask logic (enable-only-what's-off,
     * restore-only-ours, re-entrant seed) is unit-testable on a host JVM.
     *
     * @property attemptWifi call the silent Wi-Fi enable ladder.
     * @property attemptBt call the Bluetooth enable.
     * @property enabledWifi cumulative "we turned Wi-Fi on" to persist (incl. prior).
     * @property enabledBt cumulative "we turned BT on" to persist (incl. prior).
     * @property alreadyOn bitmask of wanted radios already ON (no toggle needed).
     */
    internal data class PrepareDecision(
        val attemptWifi: Boolean,
        val attemptBt: Boolean,
        val enabledWifi: Boolean,
        val enabledBt: Boolean,
        val alreadyOn: Int,
    )

    internal fun decidePrepare(
        radios: Int,
        wifiOn: Boolean,
        btOn: Boolean,
        priorEnabledWifi: Boolean,
        priorEnabledBt: Boolean,
    ): PrepareDecision {
        val want = if (radios == 0) RADIO_BOTH else radios
        val attemptWifi = want and RADIO_WIFI != 0 && !wifiOn
        val attemptBt = want and RADIO_BT != 0 && !btOn
        var alreadyOn = 0
        if (want and RADIO_WIFI != 0 && wifiOn) alreadyOn = alreadyOn or RADIO_WIFI
        if (want and RADIO_BT != 0 && btOn) alreadyOn = alreadyOn or RADIO_BT
        return PrepareDecision(
            attemptWifi = attemptWifi,
            attemptBt = attemptBt,
            // RE-ENTRANT: a prior session's true is never reset to false, so a SECOND
            // prepare (rotation, repeated wakes) ADDS to what we enabled. Without this
            // a 2nd prepare would see the radio we already turned on as "already on",
            // record false, and finish() would strand it ON.
            enabledWifi = priorEnabledWifi || attemptWifi,
            enabledBt = priorEnabledBt || attemptBt,
            alreadyOn = alreadyOn,
        )
    }

    /**
     * Transfer START. For each requested radio that is currently OFF, turn it ON
     * (silent ladder) and remember we did so (persisted). Radios already ON are
     * left untouched and NOT recorded (so [finish] won't turn them off).
     * @param radios bitmask of radios the transfer needs (0 → both).
     * @return bitmask of radios that are ON after this call (Wi-Fi bit set only if
     *         a SILENT path actually enabled it; the caller may ignore this).
     */
    @Synchronized
    fun prepare(
        context: Context,
        radios: Int,
    ): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val decision =
            decidePrepare(
                radios = radios,
                wifiOn = RadioToggler.isWifiOn(context),
                btOn = RadioToggler.isBluetoothOn(),
                priorEnabledWifi = prefs.getBoolean(KEY_ENABLED_WIFI, false),
                priorEnabledBt = prefs.getBoolean(KEY_ENABLED_BT, false),
            )

        // Persist the intent synchronously and arm the watchdog BEFORE touching any
        // radio, so a process kill in the window between flipping a radio ON and the
        // flush landing can NEVER strand the user's Wi-Fi/BT on: finish() (or the
        // watchdog) still turns off whatever we recorded, and disabling an already-off
        // radio is a harmless no-op. The safe direction — a spurious restore beats a
        // stranded radio. (Reviewer M1.)
        if (decision.enabledWifi || decision.enabledBt) {
            persistEnabled(prefs, decision.enabledWifi, decision.enabledBt)
            scheduleWatchdog(context)
        } else {
            // Nothing to turn on (both already on) — back out any stale watchdog.
            cancelWatchdog(context)
        }

        var nowOn = decision.alreadyOn
        if (decision.attemptWifi) {
            if (RadioToggler.setWifiSilent(context, true)) {
                nowOn = nowOn or RADIO_WIFI
            } else {
                Log.w(TAG, "prepare: Wi-Fi could not be enabled silently (${RadioToggler.javaClass.simpleName})")
            }
        }
        if (decision.attemptBt && RadioToggler.setBluetooth(true)) {
            nowOn = nowOn or RADIO_BT
        }

        Log.i(
            TAG,
            "prepare(radios=$radios): enabledWifi=${decision.enabledWifi} " +
                "enabledBt=${decision.enabledBt} nowOn=$nowOn",
        )
        return nowOn
    }

    /**
     * Persist "what WE turned on" SYNCHRONOUSLY (commit, not apply) so the flags
     * survive a process kill that lands before an async flush would have. Safe to
     * block here: every caller runs on RadioService's background HandlerThread or
     * QuickShareWatcherService's worker, never the main thread.
     */
    private fun persistEnabled(
        prefs: SharedPreferences,
        enabledWifi: Boolean,
        enabledBt: Boolean,
    ) {
        prefs
            .edit()
            .putBoolean(KEY_ENABLED_WIFI, enabledWifi)
            .putBoolean(KEY_ENABLED_BT, enabledBt)
            .commit()
    }

    /**
     * Transfer TERMINAL (complete / declined / timeout). Turn back OFF only the
     * radios WE turned on in [prepare], restoring the user's original state. Clears
     * the persisted session.
     */
    @Synchronized
    fun finish(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabledWifi = prefs.getBoolean(KEY_ENABLED_WIFI, false)
        val enabledBt = prefs.getBoolean(KEY_ENABLED_BT, false)
        if (enabledWifi) RadioToggler.setWifiSilent(context, false)
        if (enabledBt) RadioToggler.setBluetooth(false)
        // commit (not apply): clear the session synchronously so a concurrent
        // prepare/restore can't observe a half-cleared state after we return.
        prefs.edit().clear().commit()
        cancelWatchdog(context)
        Log.i(TAG, "finish: restored wifi=$enabledWifi bt=$enabledBt")
    }

    /**
     * BOOT recovery. AlarmManager watchdogs are cleared on reboot, so if the device
     * rebooted mid-transfer a session can be left persisted (and Android remembers
     * Wi-Fi as the ON state WE set). Called by the boot service to restore the
     * user's original state. No-op if no session is pending. Blocking — off main.
     */
    @Synchronized
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
