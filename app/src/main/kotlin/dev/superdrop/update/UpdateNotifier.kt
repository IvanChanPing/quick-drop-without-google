/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.superdrop.R

/**
 * WHAT THIS IS
 * -----------
 * `UpdateNotifier` — builds and posts the **"Update available"** status-bar
 * notification for Super Drop's automatic update check. Invoked by
 * [UpdateCheckWorker] when its 6-hourly GitHub poll finds a release newer than
 * the installed `BuildConfig.VERSION_NAME`.
 *
 * WHAT THE USER SEES (the notification)
 * -------------------------------------
 * - Small icon: the platform download glyph (`stat_sys_download`), matching the
 *   transfer notifications' family.
 * - Title "Super Drop update available", text "Version <v> is ready to install".
 * - Tapping the BODY opens the in-app [CheckForUpdatesActivity] (the existing
 *   "Check for updates" screen).
 * - Action button **"View on GitHub"** — always present — opens the release page
 *   (`html_url`) in a browser.
 * - Action button **"Download & install"** — present ONLY when the release has an
 *   `.apk` asset attached (i.e. GitHub's CI built and uploaded the installable
 *   APK). Launches [UpdateInstallActivity], which downloads that APK and fires
 *   the system installer for a drop-in update. When no APK is attached the
 *   notification offers GitHub-only (matches the requested behaviour: "if GitHub
 *   built the APK it can be pulled, otherwise it just takes you to GitHub").
 *
 * WHY IT EXISTS
 * -------------
 * The pre-existing update flow was MANUAL only (overflow-menu → screen). This
 * adds the proactive "we found a new version" alert so the user does not have to
 * remember to check.
 *
 * STATUS: compile-only / DEVICE-UNVERIFIED — no device in the build env. The
 * notification render, action taps, and POST_NOTIFICATIONS gating need an
 * on-device run. POST_NOTIFICATIONS (API 33+) is requested by the onboarding
 * flow; if denied the notification is silently dropped (degraded, by design).
 */
internal object UpdateNotifier {
    /** Notification channel id for update alerts (user-visible under app settings). */
    private const val CHANNEL_ID = "app_update"

    /** Stable id so a later poll REPLACES rather than stacks the alert. */
    private const val NOTIFICATION_ID = 0x5544_4154 // "UDAT"

    /**
     * Post (or refresh) the "update available" notification.
     *
     * @param version     the newer release version, e.g. `20260701.01`.
     * @param releaseUrl  GitHub release page — the "View on GitHub" target.
     * @param apkAssetUrl direct `.apk` download URL, or `null` when the release
     *                    has no APK attached (then no "Download & install" action).
     */
    fun notifyUpdateAvailable(
        context: Context,
        version: String,
        releaseUrl: String,
        apkAssetUrl: String?,
    ) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val builder =
            NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(appContext.getString(R.string.update_notification_title))
                .setContentText(
                    appContext.getString(R.string.update_notification_text, version),
                ).setAutoCancel(true)
                .setContentIntent(openUpdateScreenIntent(appContext))
                .addAction(
                    0,
                    appContext.getString(R.string.update_notification_action_github),
                    viewOnGitHubIntent(appContext, releaseUrl),
                )

        // Adaptive: only offer a direct install when GitHub actually hosts the APK.
        if (apkAssetUrl != null) {
            builder.addAction(
                0,
                appContext.getString(R.string.update_notification_action_download),
                downloadAndInstallIntent(appContext, apkAssetUrl, version),
            )
        }

        // notify() no-ops (returns) if POST_NOTIFICATIONS is not granted on 33+.
        NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
    }

    /** Clear the alert (e.g. once the user has acted on it from the trampoline). */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                // DEFAULT: informational, not as urgent as an incoming transfer.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_notification_channel_desc)
            }
        manager.createNotificationChannel(channel)
    }

    /** Body tap → the in-app "Check for updates" screen. */
    private fun openUpdateScreenIntent(context: Context): PendingIntent {
        val intent =
            Intent(context, CheckForUpdatesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQ_OPEN_SCREEN, intent, immutableFlags())
    }

    /** "View on GitHub" → release page in a browser. */
    private fun viewOnGitHubIntent(
        context: Context,
        releaseUrl: String,
    ): PendingIntent {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, REQ_VIEW_GITHUB, intent, immutableFlags())
    }

    /** "Download & install" → the trampoline that gates + starts the install. */
    private fun downloadAndInstallIntent(
        context: Context,
        apkAssetUrl: String,
        version: String,
    ): PendingIntent {
        val intent =
            Intent(context, UpdateInstallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(UpdateInstallActivity.EXTRA_APK_URL, apkAssetUrl)
                .putExtra(UpdateInstallActivity.EXTRA_VERSION, version)
        return PendingIntent.getActivity(context, REQ_DOWNLOAD, intent, immutableFlags())
    }

    private fun immutableFlags(): Int =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private const val REQ_OPEN_SCREEN = 1
    private const val REQ_VIEW_GITHUB = 2
    private const val REQ_DOWNLOAD = 3
}
