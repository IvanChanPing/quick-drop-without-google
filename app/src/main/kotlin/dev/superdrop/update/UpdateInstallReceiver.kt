/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import dev.superdrop.R

/**
 * WHAT THIS IS
 * -----------
 * `UpdateInstallReceiver` — the [PackageInstaller] status sink for the
 * self-update install started by [UpdateDownloadInstaller.installFromUrl].
 * Manifest-registered, NOT exported (only the app's own session
 * PendingIntent targets it). Mirror of
 * [dev.superdrop.helper.HelperInstallReceiver].
 *
 * WHAT IT DOES
 * ------------
 * - `STATUS_PENDING_USER_ACTION` → launches the system install-confirm dialog
 *   (the only step the user sees) with FLAG_ACTIVITY_NEW_TASK.
 * - `STATUS_SUCCESS` → brief "Super Drop updated" toast (may not render if the
 *   process is replaced by the new APK — best-effort).
 * - anything else → "Update install failed" toast + logs the reason.
 *
 * STATUS: compile-only / DEVICE-UNVERIFIED — the confirm-dialog launch and the
 * success/failure path need an on-device run.
 */
internal class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != UpdateDownloadInstaller.ACTION_INSTALL_STATUS) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = extractConfirmIntent(intent)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Log.e(TAG, "Could not launch install confirm dialog", it) }
                } else {
                    Log.e(TAG, "PENDING_USER_ACTION with no confirm intent")
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, R.string.update_install_success, Toast.LENGTH_SHORT).show()

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Update install failed: status=$status message=$message")
                Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractConfirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        private const val TAG = "UpdateInstallReceiver"
    }
}
