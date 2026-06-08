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

/**
 * Standalone on-device test for the targetSdk-28 radio-toggle capability.
 * Install ONLY this APK, launch it, and tap the buttons — if Wi-Fi /
 * Bluetooth actually flip (and the call returns `true`), the OEM honours the
 * legacy capability and the companion-helper approach is viable on this
 * device. If a toggle does nothing / returns `false`, this OEM (e.g. some
 * ColorOS builds) has clamped it and we fall back to system prompts / Shizuku.
 *
 * Pure programmatic UI so the module needs no resources/AndroidX.
 */
internal class SelfTestActivity : Activity() {
    private lateinit var status: TextView

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
                textSize = 16f
                setPadding(0, 0, 0, pad)
            }
        root.addView(status)
        root.addView(
            Button(this).apply {
                text = "Toggle Wi-Fi"
                setOnClickListener {
                    val target = !RadioToggler.isWifiOn(this@SelfTestActivity)
                    val result = RadioToggler.setWifi(this@SelfTestActivity, target)
                    render("setWifiEnabled($target) returned $result")
                }
            },
        )
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
        setContentView(root)
        render("ready — targetSdk 28 helper")
    }

    override fun onResume() {
        super.onResume()
        render("resumed")
    }

    private fun render(message: String) {
        val wifi = if (RadioToggler.isWifiOn(this)) "ON" else "OFF"
        val bt = if (RadioToggler.isBluetoothOn()) "ON" else "OFF"
        status.text = "Wi-Fi: $wifi\nBluetooth: $bt\n\n$message"
    }
}
