/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.update

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import dev.superdrop.R
import dev.superdrop.helper.HelperInstaller

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
 * 2. If this app may not yet install packages
 *    (`!HelperInstaller.canInstallPackages`) → opens the system
 *    "allow this source to install apps" settings page (ONE-TIME grant, not
 *    per-boot), shows a short toast telling the user to grant it then tap
 *    Download again, and finishes.
 * 3. Otherwise → kicks off [UpdateDownloadInstaller.installFromUrl] (download
 *    on a worker thread → system installer) and finishes immediately.
 *
 * WHY REUSE HelperInstaller
 * -------------------------
 * The radio-helper first-run install solved the identical
 * unknown-sources-gate + PackageInstaller problem; reusing its
 * [HelperInstaller.canInstallPackages] / [HelperInstaller.unknownSourcesSettingsIntent]
 * keeps one gating implementation.
 *
 * INVOKED BY: PendingIntent in [UpdateNotifier.downloadAndInstallIntent].
 * Manifest: registered transparent, `exported=false`, `excludeFromRecents`,
 * `noHistory` so it never lingers in the task list.
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

        if (!HelperInstaller.canInstallPackages(this)) {
            // One-time grant: send the user to enable "install unknown apps" for
            // Super Drop, then have them tap Download again.
            runCatching { startActivity(HelperInstaller.unknownSourcesSettingsIntent(this)) }
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

    internal companion object {
        const val EXTRA_APK_URL = "dev.superdrop.update.extra.APK_URL"
        const val EXTRA_VERSION = "dev.superdrop.update.extra.VERSION"
    }
}
