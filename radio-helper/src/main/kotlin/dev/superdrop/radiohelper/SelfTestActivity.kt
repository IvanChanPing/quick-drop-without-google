/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.radiohelper

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

/**
 * On-device test for the radio-toggle ladder. Install ONLY this APK and tap:
 *  - **Toggle Bluetooth** — direct `BluetoothAdapter.enable()` (zero setup).
 *  - **Toggle Wi-Fi** — runs the ladder: direct `setWifiEnabled` (needs the
 *    ADB WRITE_SECURE_SETTINGS grant) → Shizuku (silent) → Wi-Fi panel pop-up
 *    (one tap). The status line shows which prerequisites are present.
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
                    val target = !RadioToggler.isWifiOn(ctx)
                    val wss =
                        checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                            PackageManager.PERMISSION_GRANTED
                    // Run the ladder STEP BY STEP so we can see which one fired.
                    val direct = RadioToggler.setWifi(ctx, target)
                    val shiz = if (direct) false else ShizukuRadio.trySetWifi(ctx, target)
                    val outcome =
                        when {
                            direct -> "direct setWifiEnabled OK (silent)"
                            shiz -> "Shizuku OK (silent)"
                            else -> "no silent path -> panel opened=${RadioToggler.openWifiPanel(ctx)}"
                        }
                    render(
                        "target=$target\n" +
                            "WRITE_SECURE_SETTINGS granted=$wss\n" +
                            "direct setWifiEnabled returned=$direct\n" +
                            "Shizuku: ${ShizukuRadio.lastStatus}\n" +
                            "=> $outcome",
                    )
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
        val wss =
            checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        val shizuku =
            when {
                ShizukuRadio.isAvailable -> "available"
                ShizukuRadio.needsPermission -> "running, permission NOT granted"
                else -> "not available"
            }
        shizukuButton.visibility = if (ShizukuRadio.needsPermission) Button.VISIBLE else Button.GONE
        status.text =
            "Wi-Fi: $wifi    Bluetooth: $bt\n" +
            "WRITE_SECURE_SETTINGS granted: $wss\n" +
            "Shizuku: $shizuku\n\n$message"
    }

    private companion object {
        const val SHIZUKU_REQUEST = 1001
    }
}
