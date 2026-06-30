/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import dev.bluehouse.bada.R

/**
 * WHAT THIS IS
 * -----------
 * `UpdateInstallActivity` — an invisible (transparent, no-UI) trampoline
 * Activity launched by the **"Download & install"** action on the
 * [UpdateNotifier] update notification. It exists only because the
 * "install unknown apps" permission flow needs an Activity context.
 *
 * WHAT IT DOES (no visible screen of its own)
 * -------------------------------------------
 * 1. Clears the "update available" notification.
 * 2. If this app may not yet install packages (`!canInstallPackages`) → opens
 *    the system "allow this source to install apps" settings page (ONE-TIME
 *    grant, not per-boot), shows a short toast, and finishes.
 * 3. Otherwise → kicks off [UpdateDownloadInstaller.installFromUrl] (download
 *    on a worker thread → system installer) and finishes immediately.
 *
 * INVOKED BY: PendingIntent in [UpdateNotifier.downloadAndInstallIntent].
 * Manifest: registered transparent, `exported=false`, `excludeFromRecents`,
 * `noHistory` so it never lingers in the task list. Needs the
 * `REQUEST_INSTALL_PACKAGES` manifest permission.
 *
 * STATUS: compile-only / DEVICE-UNVERIFIED — the grant gate + install launch
 * need an on-device run.
 */
internal class UpdateInstallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkUrl = intent?.getStringExtra(EXTRA_APK_URL)
        val version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()

        // Dismiss the alert that launched us so it does not linger after action.
        UpdateNotifier.cancel(this)

        if (apkUrl.isNullOrBlank()) {
            finish()
            return
        }

        if (!canInstallPackages()) {
            // One-time grant: send the user to enable "install unknown apps",
            // then have them tap Download again.
            runCatching { startActivity(unknownSourcesSettingsIntent()) }
            Toast
                .makeText(this, R.string.update_install_need_unknown_sources, Toast.LENGTH_LONG)
                .show()
            finish()
            return
        }

        UpdateDownloadInstaller.installFromUrl(applicationContext, apkUrl, version)
        Toast.makeText(this, R.string.update_install_started, Toast.LENGTH_SHORT).show()
        finish()
    }

    /**
     * `true` when this app may install APKs without bouncing the user to the
     * "install unknown apps" settings page first. Always true below API 26.
     */
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            packageManager.canRequestPackageInstalls()

    /**
     * Intent that opens the system "allow this source to install apps" screen
     * for this app. The grant is ONE-TIME (it persists; not per-boot).
     */
    private fun unknownSourcesSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName"),
        )

    internal companion object {
        const val EXTRA_APK_URL = "dev.bluehouse.bada.update.extra.APK_URL"
        const val EXTRA_VERSION = "dev.bluehouse.bada.update.extra.VERSION"
    }
}
