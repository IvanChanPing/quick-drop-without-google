/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * On-device test for the radio-toggle ladder. Install ONLY this APK and tap:
 *  - **Toggle Bluetooth** — direct `BluetoothAdapter.enable()` (zero setup).
 *  - **Toggle Wi-Fi** — runs the ladder: direct `setWifiEnabled` (works only on
 *    lenient OEMs, NOT ColorOS) → Shizuku (silent, optional) → Wi-Fi panel
 *    pop-up (one tap, the default). The status line shows the Shizuku state.
 *  - **Request Shizuku permission** — appears when Shizuku is running but not
 *    yet granted to this app.
 *
 * Pure programmatic UI so the module needs no resources.
 */
internal class SelfTestActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var shizukuButton: Button

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> render("Shizuku permission result") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        status =
            TextView(this).apply {
                textSize = 15f
                setPadding(0, 0, 0, pad)
            }
        root.addView(status)
        root.addView(
            Button(this).apply {
                text = "Toggle Bluetooth"
                setOnClickListener {
                    val target = !RadioToggler.isBluetoothOn()
                    val result = RadioToggler.setBluetooth(target)
                    render("BluetoothAdapter.${if (target) "enable" else "disable"}() returned $result")
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Toggle Wi-Fi"
                setOnClickListener {
                    val ctx = this@SelfTestActivity
                    render("working…")
                    // MUST run off the UI thread: the Shizuku bind waits up to
                    // 8s, which would ANR/crash the app if done on the main
                    // thread. Render + panel-launch hop back to the UI thread.
                    Thread {
                        val target = !RadioToggler.isWifiOn(ctx)
                        val direct = RadioToggler.setWifi(ctx, target)
                        val shiz = if (direct) false else ShizukuRadio.trySetWifi(ctx, target)
                        runOnUiThread {
                            val outcome =
                                when {
                                    direct -> "direct setWifiEnabled OK (silent)"
                                    shiz -> "Shizuku OK (silent)"
                                    else -> "no silent path -> panel opened=${RadioToggler.openWifiPanel(ctx)}"
                                }
                            render(
                                "target=$target\n" +
                                    "direct setWifiEnabled returned=$direct\n" +
                                    "Shizuku: ${ShizukuRadio.lastStatus}\n" +
                                    "=> $outcome",
                            )
                        }
                    }.start()
                }
            },
        )
        shizukuButton =
            Button(this).apply {
                text = "Request Shizuku permission"
                setOnClickListener {
                    runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST) }
                        .onFailure { render("Shizuku request failed: ${it.message}") }
                }
            }
        root.addView(shizukuButton)
        setContentView(root)
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
        render("ready")
    }

    override fun onResume() {
        super.onResume()
        render("resumed")
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }

    private fun render(message: String) {
        val wifi = if (RadioToggler.isWifiOn(this)) "ON" else "OFF"
        val bt = if (RadioToggler.isBluetoothOn()) "ON" else "OFF"
        val shizuku =
            when {
                ShizukuRadio.isAvailable -> "available"
                ShizukuRadio.needsPermission -> "running, permission NOT granted"
                else -> "not available"
            }
        shizukuButton.visibility = if (ShizukuRadio.needsPermission) Button.VISIBLE else Button.GONE
        status.text =
            "Wi-Fi: $wifi    Bluetooth: $bt\n" +
            "Shizuku: $shizuku\n\n$message"
    }

    private companion object {
        const val SHIZUKU_REQUEST = 1001
    }
}
