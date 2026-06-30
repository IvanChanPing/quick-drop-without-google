/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.superdrop.consent.ConsentDialogActivity
import dev.superdrop.consent.ConsentTrampolineActivity
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import dev.superdrop.service.receiver.ReceiverForegroundService
import dev.superdrop.update.UpdateCheckWorker
import java.util.concurrent.TimeUnit

/**
 * Application bootstrap that wires the `:app`-side activity classes
 * into the `:service-android` library at process start.
 *
 * The service module deliberately keeps no compile-time dependency on
 * `:app` — it would otherwise become a circular reference. Instead
 * the service exposes a pair of `@Volatile` `Class<*>` slots
 * ([ReceiverForegroundService.openAppTarget] and
 * [ReceiverForegroundService.consentTrampolineTarget]) that the host
 * application populates here, before any service `onCreate` runs.
 *
 * The wiring happens in `Application.onCreate`, which Android
 * guarantees to invoke before any other component (`Service`,
 * `BroadcastReceiver`, `Activity`) of the app, so by the time the
 * receiver service first tries to build a notification PendingIntent
 * the targets are already set.
 *
 * It also points [DiagnosticLog]'s on-disk sink at the app's external
 * files dir so BLE/discovery diagnostics persist into the bug report past
 * the 15-minute in-memory ring-buffer window (#201).
 */
class BadaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReceiverForegroundService.openAppTarget = MainActivity::class.java
        ReceiverForegroundService.consentTrampolineTarget = ConsentTrampolineActivity::class.java
        // In-app (foreground) consent surface = the original centered floating
        // dialog; external/background arrivals keep using the sheet trampoline.
        ReceiverForegroundService.consentDialogTarget = ConsentDialogActivity::class.java
        // Must match where BugReportCollector reads the log back from
        // (getExternalFilesDir(null)); a filesDir fallback would write logs
        // the collector never picks up.
        getExternalFilesDir(null)?.let { DiagnosticLog.configureFileSink(it) }

        scheduleAutomaticUpdateCheck()
    }

    /**
     * Enqueue the 6-hourly automatic GitHub update check ([UpdateCheckWorker]).
     *
     * Uses a UNIQUE PeriodicWork so re-running onCreate (every process start)
     * never stacks duplicate jobs, and `ExistingPeriodicWorkPolicy.UPDATE` so a
     * future interval/constraint change is picked up without losing the
     * persisted schedule. The CONNECTED network constraint means a run only
     * fires when there is connectivity to reach GitHub. WorkManager persists the
     * schedule across reboots, so the poll self-restarts on boot with no user
     * action.
     */
    private fun scheduleAutomaticUpdateCheck() {
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).build()
        WorkManager
            .getInstance(this)
            .enqueueUniquePeriodicWork(
                UpdateCheckWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }
}
