/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide one-shot record of a **temporary** receiver-visibility
 * elevation performed by the Quick Settings tile (Phase 2).
 *
 * ### Why
 *
 * The Phase 2 tile no longer toggles the persistent "always visible"
 * override. Instead a tap opens the receive bottom sheet
 * ([dev.bluehouse.bada.consent.ConsentTrampolineActivity]) and bumps the
 * receiver to visible **only for the lifetime of that sheet**. When the
 * sheet is dismissed the prior state must be restored exactly:
 *
 *  - if the override was already active (the device was already
 *    discoverable), the tile changes nothing and there is nothing to
 *    restore;
 *  - if the override was off and no receiver service was running, the
 *    tile turns the override on + starts the service, and on dismissal
 *    turns the override back off + stops the service it started.
 *
 * The sheet activity lives in `:app` and the tile in `:app`, but the
 * restore must survive the activity reporting its own dismissal back
 * without either side holding a reference to the other. This holder is
 * that rendezvous: the tile [arm]s it with the captured prior state at
 * elevation time, and the activity calls [restoreIfArmed] from its
 * `finish` path. It is also disarmed by the receiver service's own
 * teardown so a process that dies with the sheet open does not leave a
 * stale armed flag that would later stop a legitimately-running service.
 *
 * ### Robustness to process death
 *
 * The record is in-memory only. If the process is killed while the sheet
 * is open, the [MdnsVisibilityOverrideHolder] override (also in-memory)
 * resets to `false` on the next process start anyway, so the elevation
 * does not persist — the system is in the same place a fresh process
 * would be. Best-effort by design.
 */
public object TileVisibilityElevationHolder {
    /** `true` between [arm] and [restoreIfArmed] / [disarm]. */
    private val armed = AtomicBoolean(false)

    /**
     * Snapshot of [MdnsVisibilityOverrideHolder.isActive] captured at
     * [arm] time. Only meaningful while [armed] is `true`.
     */
    @Volatile
    private var priorOverrideActive: Boolean = false

    /**
     * `true` when [arm] actually started the receiver service (i.e. the
     * override was off when the tile fired). Drives whether
     * [restoreIfArmed] stops the service. Only meaningful while [armed].
     */
    @Volatile
    private var elevatedService: Boolean = false

    /**
     * Record a tile elevation. [priorOverride] is the override state the
     * tile observed *before* it bumped visibility; [startedService] is
     * whether the tile started the receiver service as part of the bump.
     *
     * Idempotent against a double-arm (e.g. a rapid double tap): the
     * first armed snapshot wins so the restore still targets the true
     * pre-elevation state.
     */
    public fun arm(
        priorOverride: Boolean,
        startedService: Boolean,
    ) {
        if (armed.compareAndSet(false, true)) {
            priorOverrideActive = priorOverride
            elevatedService = startedService
        }
    }

    /** `true` while an elevation is outstanding. */
    public val isArmed: Boolean
        get() = armed.get()

    /**
     * Restore the pre-elevation receiver state if an elevation is
     * outstanding, then disarm. No-op when not armed (so a duplicate
     * dismissal, or a dismissal of a sheet that was opened by an
     * incoming transfer rather than the tile, does nothing).
     *
     * When the prior override was already active, nothing is changed —
     * the device was discoverable before the tile fired and stays so.
     * Otherwise the override is set back to `false`; if the tile also
     * started the service, the service is stopped.
     */
    public fun restoreIfArmed(context: Context) {
        if (!armed.compareAndSet(true, false)) return
        if (priorOverrideActive) {
            // Was already visible before the tile — leave everything be.
            return
        }
        MdnsVisibilityOverrideHolder.setAlwaysVisible(false)
        if (elevatedService) {
            ReceiverForegroundService.stop(context)
        }
    }

    /**
     * Clear the armed flag without restoring. Used by the receiver
     * service's own teardown so a later sheet dismissal cannot stop /
     * re-toggle a service the user has since controlled by other means.
     */
    public fun disarm() {
        armed.set(false)
    }
}
