/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

/**
 * WHAT THIS IS
 * ------------
 * `QuickShareWatchStatus` — a tiny in-memory status board for the Quick Share
 * auto-detect feature ([QuickShareWatcherService]). It records, for on-screen
 * display on [SelfTestActivity], the latest human-readable status line and the last
 * foreground window the watcher saw (so a mismatched class name is VISIBLE and
 * fixable without a decompile). Whether a restore is actually pending is sourced
 * separately from [ShareRadioSession.isSessionActive] (the persisted truth that
 * survives our process being killed), not held here.
 *
 * WHY IT EXISTS
 * -------------
 * Observability requirement: the detector runs invisibly in the background, so
 * without this the user could never tell whether Quick Share was detected, what
 * class GMS actually reported, or whether the radios were flipped. This makes the
 * whole path legible (the make-or-break "does the class match?" unknown).
 *
 * THREADING / SCOPE
 * -----------------
 * The accessibility service and [SelfTestActivity] run in the SAME app process,
 * so a process-local singleton is sufficient (no IPC / SharedPreferences needed
 * for display). Fields are @Volatile because the watcher writes from its event
 * callback / worker thread and the activity reads from the UI thread.
 *
 * STATUS: compile-only / device-UNVERIFIED — populated at runtime by the watcher.
 */
internal object QuickShareWatchStatus {
    /** Latest human-readable status line (e.g. "Quick Share detected → enabling radios"). */
    @Volatile
    var line: String = "not started — enable the accessibility service below"
        private set

    /** Last foreground window seen as "pkg / class" — for diagnosing class-name matches. */
    @Volatile
    var lastWindow: String = "—"
        private set

    fun update(status: String) {
        line = status
    }

    fun window(pkgCls: String) {
        lastWindow = pkgCls
    }
}
