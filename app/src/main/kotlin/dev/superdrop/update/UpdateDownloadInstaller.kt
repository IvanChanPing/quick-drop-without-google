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
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.superdrop.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * WHAT THIS IS
 * -----------
 * `UpdateDownloadInstaller` — the "download directly" half of the auto-update
 * feature. Given a GitHub release `.apk` `browser_download_url`, it STREAMS the
 * APK over HTTP straight into a [PackageInstaller] session (no temp file, no
 * FileProvider) and commits it, producing a drop-in in-place update of Super
 * Drop. Modelled on [dev.superdrop.helper.HelperInstaller.installBundledHelper],
 * which does the same from a bundled asset instead of a URL.
 *
 * WHO CALLS IT
 * ------------
 * [UpdateInstallActivity] (the trampoline launched by the notification's
 * "Download & install" action), AFTER it has confirmed
 * `canRequestPackageInstalls()`.
 *
 * THREADING
 * ---------
 * Runs the network read + session write on a dedicated worker thread — the APK
 * is multiple MB, so it must never touch the main thread (ANR). The system
 * install-confirm dialog is launched later by [UpdateInstallReceiver] from the
 * session's status callback.
 *
 * OBSERVABILITY
 * -------------
 * Posts a quiet, indeterminate **"Downloading update…"** notification while the
 * stream is in flight, swaps it for a **"Download failed"** notification on any
 * IO error (and logs), and cancels it once the session is committed (the system
 * installer UI takes over from there). No silent failure path.
 *
 * STATUS: compile-only / DEVICE-UNVERIFIED — the HTTP stream → PackageInstaller
 * commit → confirm-dialog path needs an on-device run; same untested surface as
 * HelperInstaller.
 */
internal object UpdateDownloadInstaller {
    private const val TAG = "UpdateDownloadInstaller"
    private const val CHANNEL_ID = "app_update"
    private const val PROGRESS_NOTIFICATION_ID = 0x5544_4C44 // "UDLD"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Action used by the PackageInstaller status [PendingIntent]. */
    const val ACTION_INSTALL_STATUS = "dev.superdrop.update.INSTALL_STATUS"

    /**
     * Download [apkUrl] and hand it to the system installer. [version] is used
     * only for the progress notification text. Safe to call only after the
     * caller has verified `canRequestPackageInstalls()`.
     */
    fun installFromUrl(
        context: Context,
        apkUrl: String,
        version: String,
    ) {
        val appContext = context.applicationContext
        showDownloadingNotification(appContext, version)
        Thread({
            runCatching { streamInstall(appContext, apkUrl) }
                .onFailure { error ->
                    Log.e(TAG, "Update download/install failed", error)
                    showFailedNotification(appContext)
                }
            NotificationManagerCompat.from(appContext).cancel(PROGRESS_NOTIFICATION_ID)
        }, "update-install").start()
    }

    private fun streamInstall(
        appContext: Context,
        apkUrl: String,
    ) {
        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub release assets 302-redirect to a CDN host (same https
            // scheme); HttpURLConnection follows same-protocol redirects.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Bada-Android-UpdateInstaller")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("APK download failed: HTTP $responseCode")
            }
            val declaredLength = connection.contentLengthLong.takeIf { it > 0L } ?: -1L

            val installer = appContext.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        // This APK IS our own package — declaring it lets the
                        // installer treat the operation as an update of us.
                        setAppPackageName(appContext.packageName)
                    }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                connection.inputStream.use { input ->
                    session.openWrite("update", 0, declaredLength).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val statusIntent =
                    Intent(appContext, UpdateInstallReceiver::class.java)
                        .setAction(ACTION_INSTALL_STATUS)
                        .setPackage(appContext.packageName)
                // MUTABLE so the platform can fill in EXTRA_INTENT (the confirm
                // dialog) on API 31+ — same requirement as HelperInstaller.
                val flags =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                val pending =
                    PendingIntent.getBroadcast(appContext, sessionId, statusIntent, flags)
                session.commit(pending.intentSender)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun showDownloadingNotification(
        appContext: Context,
        version: String,
    ) {
        ensureChannel(appContext)
        val notification =
            NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(appContext.getString(R.string.update_downloading_title))
                .setContentText(
                    appContext.getString(R.string.update_downloading_text, version),
                ).setOngoing(true)
                .setProgress(0, 0, true) // indeterminate horizontal bar
                .build()
        NotificationManagerCompat.from(appContext).notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun showFailedNotification(appContext: Context) {
        ensureChannel(appContext)
        val notification =
            NotificationCompat
                .Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(appContext.getString(R.string.update_download_failed_title))
                .setContentText(appContext.getString(R.string.update_download_failed_text))
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(appContext).notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.update_notification_channel_desc) },
        )
    }
}
