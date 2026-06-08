/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.superdrop.radiohelper.adbwifi.AdbMdns
import dev.superdrop.radiohelper.adbwifi.AdbWifiManager
import dev.superdrop.radiohelper.adbwifi.AdbWifiRadio

/**
 * WHAT THIS IS
 * ------------
 * `AdbWifiTestActivity` — the on-device setup + test screen for the self-ADB
 * Wi-Fi path. Launcher label: **"Radio Helper: ADB-WiFi Setup"**. It performs the
 * ONE-TIME pairing the rest of the feature depends on, and lets you prove the
 * whole chain works on YOUR phone (esp. ColorOS) before relying on the boot
 * service + ladder — the anti-Potemkin step.
 *
 * WHY IT LIVES IN THE HELPER
 * --------------------------
 * The self-ADB engine was migrated out of the Super Drop app into `:radio-helper`
 * so every sharing app shares one helper; the pairing key/cert it creates here
 * live in the HELPER's `filesDir`, which is the same process that later runs the
 * boot warm-up ([AdbWifiBootService]) and the tap toggle (`RadioService`).
 *
 * SCREEN LAYOUT (top → bottom)
 * ----------------------------
 *  - statusText — multi-line text at the top showing Wi-Fi/paired state + the
 *    last action result.
 *  - pairPortField — number EditText, hint "pairing port…".
 *  - pairCodeField — number EditText, hint "6-digit pairing code".
 *  - pairButton — "1. Pair" — one-time pairing with Wireless debugging.
 *  - grantButton — "2. Self-grant WRITE_SECURE_SETTINGS" — over ADB, so the app
 *    can self-enable wireless debugging on boot afterwards.
 *  - toggleButton — "3. Toggle Wi-Fi (self-ADB)" — the real end-to-end test via
 *    the shared [AdbWifiRadio] engine (the same path the tap uses).
 *
 * STEPS (do once, in order):
 *  1. Settings → Developer options → Wireless debugging → "Pair device with
 *     pairing code". Type the PAIRING PORT + 6-digit CODE here → tap **1. Pair**.
 *  2. Tap **2. Self-grant WSS** — connects to adbd (port via mDNS) and runs
 *     `pm grant ... WRITE_SECURE_SETTINGS` so the helper can self-enable wireless
 *     debugging on every boot.
 *  3. Tap **3. Toggle Wi-Fi (self-ADB)** — runs [AdbWifiRadio.setWifi]
 *     (enable debugging → discover port → `svc wifi`). This is the real test.
 *
 * THREADING / STATUS
 * ------------------
 * All ADB ops run on a background thread (they block). NOT verified on a device
 * by the author — running this screen IS the verification step.
 */
internal class AdbWifiTestActivity : Activity() {
    // statusText — multi-line label, top of the screen; shows live Wi-Fi/paired
    // state plus the result of the last button tap.
    private lateinit var status: TextView
    private lateinit var portField: EditText
    private lateinit var codeField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        // pairPortField — number input, the pairing PORT from the Wireless
        // debugging "Pair device with pairing code" dialog.
        portField =
            EditText(this).apply {
                hint = "pairing port (from Wireless debugging dialog)"
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        // pairCodeField — number input, the 6-digit pairing CODE.
        codeField =
            EditText(this).apply {
                hint = "6-digit pairing code"
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        status = TextView(this).apply { setPadding(0, pad, 0, 0); textSize = 14f }

        root.addView(portField)
        root.addView(codeField)
        // pairButton — "1. Pair": one-time pairing with Wireless debugging.
        root.addView(
            Button(this).apply {
                text = "1. Pair"
                setOnClickListener {
                    val port = portField.text.toString().toIntOrNull()
                    val code = codeField.text.toString().trim()
                    if (port == null || code.isEmpty()) {
                        status.text = "Enter pairing port + code first"
                        return@setOnClickListener
                    }
                    bg("Pairing 127.0.0.1:$port…") {
                        if (AdbWifiManager.pair(this@AdbWifiTestActivity, "127.0.0.1", port, code)) {
                            "Paired OK (key stored)"
                        } else {
                            "Pair FAILED (check port/code; dialog still open?)"
                        }
                    }
                }
            },
        )
        // grantButton — "2. Self-grant WRITE_SECURE_SETTINGS" over ADB so the
        // helper can self-enable wireless debugging on each boot.
        root.addView(
            Button(this).apply {
                text = "2. Self-grant WRITE_SECURE_SETTINGS"
                setOnClickListener {
                    bg("Discovering port + granting…") {
                        val p = AdbMdns.discoverPort(this@AdbWifiTestActivity)
                        if (p < 0) {
                            "No adbd port via mDNS — is Wireless debugging ON?"
                        } else if (AdbWifiManager.selfGrantWriteSecureSettings(this@AdbWifiTestActivity, "127.0.0.1", p)) {
                            "WSS self-granted via ADB (port $p)"
                        } else {
                            "Grant FAILED (paired? port $p)"
                        }
                    }
                }
            },
        )
        // toggleButton — "3. Toggle Wi-Fi (self-ADB)": the real end-to-end test,
        // through the SAME shared engine the NFC tap uses ([AdbWifiRadio]).
        root.addView(
            Button(this).apply {
                text = "3. Toggle Wi-Fi (self-ADB)"
                setOnClickListener {
                    bg("AdbWifiRadio.setWifi…") {
                        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val target = !wm.isWifiEnabled
                        val ok = AdbWifiRadio.setWifi(this@AdbWifiTestActivity, target)
                        "AdbWifiRadio.setWifi(target=$target) ran=$ok " +
                            "(now ${if (wm.isWifiEnabled) "ON" else "OFF"})"
                    }
                }
            },
        )
        setContentView(root)
        status.text = "paired=${AdbWifiRadio.isPaired(this)} — do steps 1→2→3"
    }

    private fun bg(
        working: String,
        work: () -> String,
    ) {
        status.text = working
        Thread {
            val result = runCatching { work() }.getOrElse { "ERROR: ${it.message}" }
            runOnUiThread { status.text = result }
        }.start()
    }
}
