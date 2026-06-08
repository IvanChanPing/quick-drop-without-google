/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.debug

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.superdrop.adbwifi.AdbMdns
import dev.superdrop.adbwifi.AdbWifiManager

/**
 * DEBUG-only on-device test for the self-ADB Wi-Fi loop (option 2). Lets you
 * prove the whole chain works on YOUR phone (esp. ColorOS) before the boot
 * service + ladder wiring is built — the anti-Potemkin step.
 *
 * Steps (do once, in order):
 *  1. Settings → Developer options → Wireless debugging → "Pair device with
 *     pairing code". Type the PAIRING PORT + 6-digit CODE here → tap **Pair**.
 *  2. Tap **Self-grant WSS** — connects to adbd (port via mDNS) and runs
 *     `pm grant ... WRITE_SECURE_SETTINGS` so the app can self-enable wireless
 *     debugging later.
 *  3. Tap **Toggle Wi-Fi (self-ADB)** — enables wireless debugging, finds the
 *     port via mDNS, and runs `svc wifi enable/disable`. This is the real test.
 *
 * All ADB ops run on a background thread (they block). NOT verified on a device
 * by the author.
 */
internal class AdbWifiTestActivity : Activity() {
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
        portField =
            EditText(this).apply {
                hint = "pairing port (from Wireless debugging dialog)"
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        codeField =
            EditText(this).apply {
                hint = "6-digit pairing code"
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        status = TextView(this).apply { setPadding(0, pad, 0, 0); textSize = 14f }

        root.addView(portField)
        root.addView(codeField)
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
        root.addView(
            Button(this).apply {
                text = "3. Toggle Wi-Fi (self-ADB)"
                setOnClickListener {
                    bg("Enable debug → discover → svc wifi…") {
                        val enabled = AdbWifiManager.enableWirelessDebugging(this@AdbWifiTestActivity)
                        val p = AdbMdns.discoverPort(this@AdbWifiTestActivity)
                        if (p < 0) {
                            "No adbd port via mDNS (enableWirelessDebugging=$enabled; WSS granted?)"
                        } else {
                            val wm = this@AdbWifiTestActivity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                            val target = !wm.isWifiEnabled
                            val ok = AdbWifiManager.setWifi(this@AdbWifiTestActivity, "127.0.0.1", p, target)
                            "svc wifi ${if (target) "enable" else "disable"} via ADB port $p → ran=$ok " +
                                "(now ${if (wm.isWifiEnabled) "ON" else "OFF"})"
                        }
                    }
                }
            },
        )
        setContentView(root)
        status.text = "paired=${AdbWifiManager.isPaired(this)} — do steps 1→2→3"
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
