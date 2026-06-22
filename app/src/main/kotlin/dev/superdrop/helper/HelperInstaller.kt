/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.helper

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * WHAT THIS IS
 * -----------
 * `HelperInstaller` — the first-run "Install Radio Helper" support. Checks
 * whether the universal Radio Helper app ([helperPackage]) is installed and,
 * if not, installs the copy BUNDLED inside Super Drop's assets
 * (`assets/radio-helper.apk`, embedded at build time by the `:app`
 * `bundleRadioHelperDebug` Gradle task) via [PackageInstaller] — with NO
 * browser and NO download.
 *
 * WHY IT EXISTS
 * -------------
 * Super Drop silently toggles Wi-Fi/Bluetooth by binding the helper's
 * `RadioService` (signature-permission `BIND_RADIO`). The helper must be
 * installed for that to work, and asking the user to find/download an APK in a
 * browser is friction. This installs it in-app on first launch.
 *
 * WHO CALLS IT
 * ------------
 * [dev.superdrop.MainActivity.maybePromptHelperInstall] (first-run dialog →
 * "Install" button). Install result is surfaced by [HelperInstallReceiver].
 *
 * SIGNING / PACKAGE
 * -----------------
 * The bundled helper is the DEBUG-variant helper (`dev.superdrop.radiohelper.
 * debug`) signed with the same family key as the debug app (`dev.superdrop.
 * debug`), so the installed helper satisfies the signature permission. The
 * helper package is derived from this app's id suffix (see [helperPackage]).
 *
 * STATUS: compile-only / DEVICE-UNVERIFIED — no device in the build env. The
 * PackageInstaller session + unknown-sources gating need an on-device run.
 */
object HelperInstaller {
    private const val TAG = "HelperInstaller"
    private const val ASSET_NAME = "radio-helper.apk"
    private const val HELPER_BASE = "dev.superdrop.radiohelper"

    /** Broadcast action used by the PackageInstaller status [PendingIntent]. */
    const val ACTION_INSTALL_STATUS = "dev.superdrop.helper.INSTALL_STATUS"

    /**
     * The Radio Helper package this build pairs with: the `.debug` helper for
     * the `.debug` app, the release helper otherwise — so the variant we bind
     * to is the one we check for and install.
     */
    fun helperPackage(context: Context): String =
        if (context.packageName.endsWith(".debug")) "$HELPER_BASE.debug" else HELPER_BASE

    /** `true` when the matching Radio Helper package is already installed. */
    fun isHelperInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(helperPackage(context), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /**
     * `true` when this app may install APKs without bouncing the user to the
     * "install unknown apps" settings page first. Always true below API 26.
     */
    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /**
     * Intent that opens the system "allow this source to install apps" screen
     * for Super Drop. The grant is ONE-TIME (it persists; not per-boot).
     */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Install the bundled helper APK via a [PackageInstaller] session. Streams
     * the asset on a worker thread (the APK is ~15 MB — never touch the main
     * thread → no ANR). The session's status callback is delivered to
     * [HelperInstallReceiver], which launches the system confirm dialog and
     * reports success/failure. Safe to call only after [canInstallPackages].
     */
    fun installBundledHelper(context: Context) {
        val appContext = context.applicationContext
        Thread({
            runCatching {
                val installer = appContext.packageManager.packageInstaller
                val params =
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    appContext.assets.open(ASSET_NAME).use { input ->
                        session.openWrite("radio-helper", 0, -1).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val statusIntent =
                        Intent(appContext, HelperInstallReceiver::class.java)
                            .setAction(ACTION_INSTALL_STATUS)
                            .setPackage(appContext.packageName)
                    // MUTABLE: the platform fills in EXTRA_INTENT (the confirm
                    // dialog) on API 31+, which requires a mutable PendingIntent.
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
            }.onFailure { Log.e(TAG, "Bundled helper install failed to start", it) }
        }, "helper-install").start()
    }
}
