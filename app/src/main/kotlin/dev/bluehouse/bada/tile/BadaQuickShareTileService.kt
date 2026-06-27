/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.bluehouse.bada.MainActivity
import dev.bluehouse.bada.R
import dev.bluehouse.bada.consent.ConsentTrampolineActivity
import dev.bluehouse.bada.onboarding.PermissionRequirements
import dev.bluehouse.bada.service.receiver.MdnsVisibilityOverrideHolder
import dev.bluehouse.bada.service.receiver.ReceiverForegroundService
import dev.bluehouse.bada.service.receiver.TileVisibilityElevationHolder
import dev.bluehouse.bada.service.receiver.consent.ConsentIntents

/**
 * Quick Settings tile that opens the **receive bottom sheet**
 * straight from the system Quick Settings panel, making the
 * device discoverable to nearby Quick Share senders for the duration of
 * the sheet without permanently flipping any switch.
 *
 * ### Behaviour (replaces the old persistent on/off toggle)
 *
 * On click the tile:
 *
 *  1. Captures the current receiver visibility from
 *     [MdnsVisibilityOverrideHolder.isActive].
 *  2. If visibility is below "visible" (override off), raises it to
 *     visible AND starts [ReceiverForegroundService] — but only
 *     temporarily, recording the prior state in
 *     [TileVisibilityElevationHolder] so it can be restored.
 *  3. Launches [ConsentTrampolineActivity] in waiting mode (the receive
 *     sheet) as a foreground activity.
 *  4. When the sheet is dismissed / finished, the activity calls
 *     [TileVisibilityElevationHolder.restoreIfArmed] which restores the
 *     exact prior visibility (and stops the service if the tile started
 *     it). If visibility was ALREADY active when the tile fired, nothing
 *     is changed and nothing is restored.
 *
 * The tile is therefore momentary in effect: it never leaves the
 * receiver discoverable after the user closes the sheet (unless it was
 * already discoverable before, which it leaves untouched).
 *
 * ### Tile visual state
 *
 * The tile still mirrors [MdnsVisibilityOverrideHolder.isActive] in
 * [syncTile] so that, while the sheet is open and visibility is bumped,
 * the tile reads ACTIVE; once the sheet is dismissed and visibility
 * restored, the next [onStartListening] re-syncs it back to INACTIVE.
 * The override lives in memory only and resets on process death.
 */
internal class BadaQuickShareTileService : TileService() {
    /**
     * Fires each time the Quick Settings panel becomes visible. Re-read
     * the current override so the tile reflects state changed elsewhere
     * (the in-app pill, the receive sheet bumping/restoring visibility,
     * or a process restart that reset the override).
     */
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        openReceiveSheet()
        syncTile()
    }

    /**
     * Capture → bump → open the receive sheet. The restore half runs
     * later from [ConsentTrampolineActivity.finish] via
     * [TileVisibilityElevationHolder.restoreIfArmed].
     */
    private fun openReceiveSheet() {
        // Being discoverable needs the mandatory discovery permission
        // (NEARBY_WIFI_DEVICES on API 33+). If it isn't granted yet,
        // bumping visibility would light up a receiver that can never
        // actually advertise — so bounce the user into the app instead,
        // which routes to the permissions onboarding. This mirrors
        // MainActivity's own service-start gate.
        if (!PermissionRequirements.allGranted(this) &&
            !PermissionRequirements.onlyOptionalMissing(this)
        ) {
            openApp()
            return
        }

        // (a) Capture the prior visibility BEFORE touching anything.
        val priorOverrideActive = MdnsVisibilityOverrideHolder.isActive

        // (b) If below "visible", raise it AND start the service — only
        // for the duration of the sheet. When already visible, change
        // nothing (the user is in a persistent always-on state we must
        // not disturb).
        var startedService = false
        if (!priorOverrideActive) {
            MdnsVisibilityOverrideHolder.setAlwaysVisible(true)
            try {
                // startWithRadios (not start): opening the receive sheet from
                // the tile also forces Wi-Fi + Bluetooth on via the radio-helper
                // for the duration of the receive; they are restored when the
                // sheet closes and stops this service (TileVisibilityElevationHolder
                // -> ReceiverForegroundService.stop -> restoreRadiosAfterShare).
                ReceiverForegroundService.startWithRadios(this)
                startedService = true
            } catch (e: IllegalStateException) {
                // Android 12+ forbids most background foreground-service
                // starts; ForegroundServiceStartNotAllowedException
                // (API 31+) extends IllegalStateException, and a Quick
                // Settings tap is NOT one of the platform's documented
                // start exemptions. When Bada is fully backgrounded the
                // start can be rejected — roll back the override bump we
                // just made and bounce into the app, which starts the
                // receiver from a visible (exempt) context.
                Log.w(TAG, "Foreground-service start from tile rejected; opening app instead", e)
                MdnsVisibilityOverrideHolder.setAlwaysVisible(false)
                openApp()
                return
            }
        }

        // Record what we did so the sheet's dismissal restores it. Arm
        // even when nothing was bumped (priorOverrideActive=true) so the
        // activity's restore call is a clean no-op rather than acting on
        // a stale prior elevation.
        TileVisibilityElevationHolder.arm(
            priorOverride = priorOverrideActive,
            startedService = startedService,
        )

        // (c) Launch the receive sheet in waiting mode.
        try {
            launchReceiveSheet()
        } catch (e: IllegalStateException) {
            // Launching the activity from the tile failed (rare vendor
            // background-activity-launch restriction). Undo the bump so
            // we don't leave the receiver discoverable with no sheet to
            // dismiss it.
            Log.w(TAG, "Receive-sheet launch from tile failed; restoring visibility", e)
            TileVisibilityElevationHolder.restoreIfArmed(this)
        }
    }

    /**
     * Start [ConsentTrampolineActivity] in Phase 2 waiting mode. The
     * `ACTION_OPEN_RECEIVE_SHEET` action with no connection id tells the
     * activity to show the "waiting for sender" panel. The visibility bump and
     * its restore are tracked by [TileVisibilityElevationHolder], not by an
     * intent extra.
     *
     * `startActivityAndCollapse` collapses the Quick Settings shade as the
     * sheet rises. API 34 made the bare-`Intent` overload throw, so we use
     * the `PendingIntent` overload there and the `Intent` form below it.
     */
    private fun launchReceiveSheet() {
        val intent =
            Intent(this, ConsentTrampolineActivity::class.java).apply {
                action = ConsentIntents.ACTION_OPEN_RECEIVE_SHEET
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending =
                PendingIntent.getActivity(
                    this,
                    RECEIVE_SHEET_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    /**
     * Push the current override state onto the tile. Safe to call from
     * any callback; no-ops if the platform has not handed us a [Tile]
     * yet (e.g. before the service is fully bound).
     */
    private fun syncTile() {
        val tile = qsTile ?: return
        val active = MdnsVisibilityOverrideHolder.isActive
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_bada_visible)
        val statusRes = if (active) R.string.qs_tile_subtitle_on else R.string.qs_tile_subtitle_off
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(statusRes)
        }
        tile.contentDescription =
            getString(
                if (active) R.string.qs_tile_content_desc_on else R.string.qs_tile_content_desc_off,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = getString(statusRes)
        }
        tile.updateTile()
    }

    /**
     * Collapse the panel and bring up [MainActivity]. Used both when a
     * mandatory permission is missing (MainActivity routes to onboarding)
     * and as the fallback when a background foreground-service start is
     * rejected.
     *
     * API 34 made [startActivityAndCollapse] with a bare `Intent` throw;
     * the `PendingIntent` overload it added is the supported path there,
     * while older platforms still take the `Intent` form.
     */
    private fun openApp() {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending =
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val TAG = "BadaQsTile"

        /**
         * Stable PendingIntent request code for the receive-sheet launch
         * on API 34+. Distinct from the openApp PendingIntent's code so
         * FLAG_UPDATE_CURRENT updates the right cached intent.
         */
        const val RECEIVE_SHEET_REQUEST_CODE = 1
    }
}
