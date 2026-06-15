/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

/**
 * Shizuku **user service** implementation. Shizuku spawns this class in a
 * separate process running as the **shell UID** (or root), so the shell
 * commands below have the privilege to flip Wi-Fi that a normal app lacks —
 * this is the optional silent path when `setWifiEnabled()` is clamped by the
 * OEM (e.g. ColorOS) — no app permission can unlock it (the gate is the
 * signature-level NETWORK_SETTINGS), only running as shell/root.
 *
 * Not a normal Android Service: Shizuku instantiates it via `app_process`,
 * so it needs a no-arg (or Context) constructor and must NOT be declared in
 * the manifest. We avoid binding the hidden `IWifiManager` (whose AIDL
 * transaction codes shift between versions) by shelling out to `svc wifi`,
 * which is stable.
 */
internal class RadioShellService : IRadioShell.Stub() {
    override fun setWifiEnabled(enabled: Boolean): Boolean {
        val state = if (enabled) "enable" else "disable"
        // `svc wifi enable` is the long-standing path; `cmd wifi` is the
        // newer one. Try svc first, fall back to cmd.
        if (exec("svc wifi $state")) return true
        val cmdState = if (enabled) "enabled" else "disabled"
        return exec("cmd -w wifi set-wifi-enabled $cmdState")
    }

    override fun getWifiState(): Int =
        runCatching {
            val out = capture("settings get global wifi_on").trim()
            when (out) {
                "1" -> 1
                "0" -> 0
                else -> -1
            }
        }.getOrDefault(-1)

    override fun destroy() {
        // Nothing to release; the process is torn down by Shizuku.
    }

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
}
