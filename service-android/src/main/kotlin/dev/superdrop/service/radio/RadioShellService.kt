/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.service.radio

import android.util.Log

/**
 * RadioShellService — the IN-APP (Path B) Shizuku **user service**.
 *
 * WHAT: a faithful copy of the radio-helper's `RadioShellService`, moved into the
 * main app (`:service-android`, so it lands in the app APK) and EXTENDED with
 * Bluetooth. Shizuku spawns this class via `app_process` in a separate process
 * running as the **shell UID**, so the `svc`/`cmd` shell commands below have the
 * privilege to flip Wi-Fi/Bluetooth that the modern-targetSdk main app lacks.
 *
 * WHY: when the user has Shizuku, the app does everything the radio-helper APK did
 * WITHOUT that second APK (see [[SHIZUKU_PREFERRED_PATH_PLAN]] — "2 apps, not 3").
 * This is the privileged executor for the in-app copy of the toggle ladder; it
 * replaces the helper's self-ADB rung with Shizuku.
 *
 * HOW IT'S USED: bound by the in-app Shizuku wrapper (added in a later phase) via
 * `Shizuku.bindUserService(UserServiceArgs(ComponentName(pkg, RadioShellService)))`.
 * NOT a normal Android Service — it needs a no-arg constructor and must **NOT** be
 * declared in the manifest (Shizuku instantiates it directly). We avoid binding the
 * hidden `IWifiManager`/Bluetooth AIDL (transaction codes shift between versions) by
 * shelling out to the stable `svc`/`cmd` commands.
 *
 * DIFFERENCE FROM THE HELPER COPY: Bluetooth methods are added here. The exact
 * Bluetooth shell command is **device-verified only** (see [setBluetoothEnabled]) —
 * we try both known forms and log which one succeeded so a device run makes the
 * winning command LEGIBLE instead of a silent failure.
 *
 * STATUS: compile-only. The Shizuku bind + the shell commands (esp. Bluetooth) are
 * device-UNVERIFIED until exercised on a phone with Shizuku running.
 */
internal class RadioShellService : IRadioShell.Stub() {
    override fun setWifiEnabled(enabled: Boolean): Boolean {
        val state = if (enabled) "enable" else "disable"
        // `svc wifi enable` is the long-standing path; `cmd wifi` is the newer
        // one. Try svc first, fall back to cmd. (Verbatim from the helper.)
        if (exec("svc wifi $state")) {
            Log.i(TAG, "wifi $state via `svc wifi` OK")
            return true
        }
        val cmdState = if (enabled) "enabled" else "disabled"
        val ok = exec("cmd -w wifi set-wifi-enabled $cmdState")
        Log.i(TAG, "wifi $state via `cmd -w wifi` = $ok")
        return ok
    }

    override fun getWifiState(): Int = readGlobalFlag("wifi_on")

    /**
     * Flip Bluetooth from the shell UID. The main app can't use
     * `BluetoothAdapter.enable()` (no-op at targetSdk 36), so this is the only
     * in-app silent BT path. The exact command is **device-verified only**: we try
     * `cmd bluetooth_manager` (the modern manager shell, present on recent Android)
     * first, then `svc bluetooth` (older AOSP), and LOG which one exited 0 so the
     * winning form is visible on a real device rather than failing silently.
     */
    override fun setBluetoothEnabled(enabled: Boolean): Boolean {
        val mgrState = if (enabled) "enable" else "disable"
        if (exec("cmd bluetooth_manager $mgrState")) {
            Log.i(TAG, "bt $mgrState via `cmd bluetooth_manager` OK")
            return true
        }
        val ok = exec("svc bluetooth $mgrState")
        Log.i(TAG, "bt $mgrState via `svc bluetooth` = $ok (cmd bluetooth_manager failed first)")
        return ok
    }

    override fun getBluetoothState(): Int = readGlobalFlag("bluetooth_on")

    override fun destroy() {
        // Nothing to release; the process is torn down by Shizuku.
    }

    /** Read a `settings get global <flag>` boolean as 1/0, or -1 if unknown. */
    private fun readGlobalFlag(flag: String): Int =
        runCatching {
            when (capture("settings get global $flag").trim()) {
                "1" -> 1
                "0" -> 0
                else -> -1
            }
        }.getOrDefault(-1)

    private fun exec(command: String): Boolean =
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            p.waitFor() == 0
        }.getOrDefault(false)

    private fun capture(command: String): String =
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }.getOrDefault("")

    private companion object {
        const val TAG = "RadioShellService"
    }
}
