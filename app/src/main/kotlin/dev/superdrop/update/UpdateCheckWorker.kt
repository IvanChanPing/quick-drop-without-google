/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.superdrop.BuildConfig

/**
 * WHAT THIS IS
 * -----------
 * `UpdateCheckWorker` — the background job behind Super Drop's AUTOMATIC update
 * check. A WorkManager [CoroutineWorker] scheduled by
 * [dev.superdrop.BadaApplication] as a unique PeriodicWork ("bada-update-check",
 * every 6 hours, network-connected). Each run polls GitHub Releases and, when a
 * newer version exists, posts the [UpdateNotifier] "update available"
 * notification.
 *
 * WHAT IT DOES (one run)
 * ----------------------
 * 1. If the user disabled auto-check ([UpdatePreferences.autoCheckEnabled] false)
 *    → no-op success.
 * 2. Fetch `releases/latest` via the shared [UpdateChecker].
 * 3. If the release version is strictly newer than `BuildConfig.VERSION_NAME`
 *    (lexicographic on the project's fixed `YYYYMMDD.NN` scheme) AND we have not
 *    already notified for that exact version → notify + remember it (so a later
 *    poll does not re-alert for the same release). The notification adapts to
 *    whether the release carries a downloadable `.apk` asset.
 * 4. Persist the latest-release snapshot so the in-app red-dot badge stays in
 *    sync with the toolbar overflow ([UpdateRepository] reads the same prefs).
 *
 * FAILURE HANDLING
 * ----------------
 * A fetch failure (offline, HTTP 404 when no release is published yet, JSON
 * mismatch) returns [Result.success] — NOT retry — so WorkManager does not
 * back-off-spam GitHub; the next scheduled 6-hourly run simply tries again.
 *
 * WHY WorkManager
 * ---------------
 * PeriodicWork survives reboots with zero user action (the schedule is persisted
 * and re-enqueued by the OS), satisfying the "no per-boot manual setup" rule —
 * no BootReceiver needed.
 *
 * STATUS: compile-only / DEVICE-UNVERIFIED — the periodic trigger + notification
 * post need an on-device run. The version-comparison + dedup logic is plain and
 * unit-testable.
 */
internal class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = UpdatePreferences.from(applicationContext)
        if (!prefs.autoCheckEnabled()) return Result.success()

        val release =
            UpdateChecker.fetchLatestRelease().getOrElse {
                // Transient/absent release: try again on the next periodic run.
                return Result.success()
            }

        // Keep the red-dot badge source-of-truth current regardless of notify.
        prefs.saveLatestRelease(release.version, release.releaseUrl)

        if (isNewer(release.version, BuildConfig.VERSION_NAME) &&
            release.version != prefs.lastNotifiedVersion()
        ) {
            UpdateNotifier.notifyUpdateAvailable(
                context = applicationContext,
                version = release.version,
                releaseUrl = release.releaseUrl,
                apkAssetUrl = release.apkAssetUrl,
            )
            prefs.saveNotifiedVersion(release.version)
        }

        return Result.success()
    }

    /**
     * Strict-greater comparison. Works for the project's fixed `YYYYMMDD.NN`
     * versionName because every field is zero-padded to a fixed width, so a
     * plain lexicographic compare is monotonic with release order. Matches
     * [UpdateRepository]'s identical helper.
     */
    private fun isNewer(
        candidate: String,
        installed: String,
    ): Boolean = candidate > installed

    internal companion object {
        /** Unique name for the periodic work (see BadaApplication). */
        const val UNIQUE_WORK_NAME = "bada-update-check"
    }
}
