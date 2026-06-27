/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.bluehouse.bada.radiohelper.adbwifi.AdbWifiManager
import dev.bluehouse.bada.radiohelper.adbwifi.AdbWifiRadio
import rikka.shizuku.Shizuku

/**
 * WHAT THIS IS
 * ------------
 * `SelfTestActivity` — the ONE and ONLY screen of the Radio Helper APK. Launcher
 * label: **"Bada Radio Helper"**. Everything lives here: the radio toggle
 * test buttons AND the one-time self-ADB Wi-Fi pairing setup. There is
 * deliberately NO second launcher icon — the user refused to hunt for a separate
 * "Setup" icon (2026-06-08), so the pairing controls are buttons on THIS screen.
 *
 * SCREEN LAYOUT (top → bottom), all inside a ScrollView:
 *  - statusText — live state: Wi-Fi / Bluetooth / self-ADB paired+lastStatus /
 *    Shizuku, plus the result of the last action.
 *  - "Toggle Bluetooth" — direct BluetoothAdapter.enable()/disable() (zero setup).
 *  - "Toggle Wi-Fi" — runs the FULL ladder (direct → self-ADB → Shizuku → panel)
 *    and prints every rung's outcome.
 *  - "Request Shizuku permission" — only visible if Shizuku is running but not
 *    granted.
 *  - --- Silent Wi-Fi setup (one-time) --- section header.
 *  - "Open Wireless debugging settings" — jumps to Developer options to get the
 *    pairing port + code.
 *  - pairPortField / pairCodeField — number inputs for the pairing dialog values.
 *  - "1. Pair" — one-time pairing with the device's own Wireless debugging.
 *  - "2. Self-grant WRITE_SECURE_SETTINGS" — over ADB, lets the helper re-enable
 *    wireless debugging on boot.
 *  - adbGrantHint + adbGrantCommand (selectable monospace) + "Copy ADB grant
 *    command" — the MANUAL fallback when self-grant (step 2) doesn't stick: the
 *    exact `adb shell pm grant <pkg> WRITE_SECURE_SETTINGS` for this install
 *    (pkg auto-resolves .debug/release). One-time, survives reboots.
 *  - "3. Test self-ADB Wi-Fi" — flips Wi-Fi through AdbWifiRadio only (the same
 *    engine the NFC tap / RadioService uses).
 *
 * WHY IT EXISTS
 * -------------
 * Self-ADB is the silent Wi-Fi path that needs no Shizuku and self-starts on
 * boot, but it requires a one-time pairing. Putting that pairing on the main
 * screen keeps it a single, supported, in-app flow (one-time setup is allowed;
 * recurring manual steps are not).
 *
 * THREADING / STATUS
 * ------------------
 * All ADB / Shizuku / ladder calls block → run on a background Thread, render on
 * the UI thread. Self-ADB on ColorOS is compile-only / device-UNVERIFIED; running
 * this screen's steps 1→2→3 IS the verification.
 *
 * The whole UI is built programmatically in onCreate (dp sizes, colors, and the
 * step-by-step button wiring), so this developer-only diagnostic screen suppresses
 * the length / complexity / magic-number rules that boilerplate inherently trips.
 */
@Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod")
internal class SelfTestActivity : Activity() {
    // statusText — multi-line label at the top; live radio + self-ADB + Shizuku
    // state plus the last action's result.
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

        // --- Radio toggles ---
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
                    // Off the UI thread: self-ADB (socket/mDNS) + Shizuku (8s
                    // bind) block → ANR if on main. Render hops back to UI.
                    Thread {
                        val target = !RadioToggler.isWifiOn(ctx)
                        val result = RadioToggler.setWifiWithDiagnostics(ctx, target)
                        runOnUiThread {
                            render(
                                "Toggle Wi-Fi (target=${if (target) "ON" else "OFF"})\n" +
                                    "won by: ${result.path} (silent=${result.success})\n\n" +
                                    result.steps.joinToString("\n"),
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

        // --- Quick Share auto-detect (AccessibilityService) ---
        // quickShareSectionHeader — small ~13sp text divider reading
        // "— Quick Share auto-detect (one-time) —", padded above so it separates
        // visually. Sits in the vertical list BELOW the Shizuku button and ABOVE the
        // "Silent Wi-Fi setup" header, introducing this feature's group.
        root.addView(
            TextView(this).apply {
                text = "— Quick Share auto-detect (one-time) —"
                setPadding(0, pad, 0, 0)
                textSize = 13f
            },
        )
        // quickShareHelp — small ~12sp grey paragraph directly under the header,
        // explaining the feature + that the enable is one-time (re-binds on boot, no
        // per-reboot step).
        root.addView(
            TextView(this).apply {
                text =
                    "Auto-turns Wi‑Fi/Bluetooth ON when Google Quick Share opens, and " +
                    "restores them after. Tap below and enable \"Bada Quick Share " +
                    "auto-detect\" under Accessibility (one-time; survives reboots)."
                setPadding(0, pad / 2, 0, pad / 2)
                textSize = 12f
            },
        )
        // enableQuickShareDetectButton — full-width default-style button labelled
        // "Enable Quick Share auto-detect (Accessibility)", directly under
        // quickShareHelp. On tap opens the system Accessibility settings list so the
        // user can flip QuickShareWatcherService on. Live state (enabled?, restore
        // pending, last GMS window, last action) shows in the top status block.
        root.addView(
            Button(this).apply {
                text = "Enable Quick Share auto-detect (Accessibility)"
                setOnClickListener {
                    val opened =
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            true
                        }.getOrDefault(false)
                    render(
                        if (opened) {
                            "Accessibility settings opened — enable \"Bada Quick Share auto-detect\"."
                        } else {
                            "Couldn't open Accessibility settings — open Settings > Accessibility manually."
                        },
                    )
                }
            },
        )

        // --- Silent Wi-Fi setup (one-time, self-ADB) ---
        // sectionHeader — plain bold-ish divider label introducing the pairing
        // controls so they read as a distinct one-time-setup group.
        root.addView(
            TextView(this).apply {
                text = "— Silent Wi-Fi setup (one-time) —"
                setPadding(0, pad, 0, 0)
                textSize = 13f
            },
        )
        // setupInstructions — help text for the NOTIFICATION-based pairing (the
        // Brevent trick). The Wireless-debugging "Pair device with pairing code"
        // dialog closes if you switch apps, and Settings can't go split-screen on
        // this device — so instead you reply to a NOTIFICATION with the code, which
        // does NOT switch apps, so the dialog stays open. mDNS finds the port; you
        // type only the 6-digit code.
        root.addView(
            TextView(this).apply {
                text =
                    "Pairing is done via a NOTIFICATION so the pairing dialog stays open:\n" +
                    "1. Tap 'Open Wireless debugging settings' and turn Wireless debugging ON.\n" +
                    "2. Tap '1. Start pairing' — it posts a notification with a reply box.\n" +
                    "3. In Settings tap 'Pair device with pairing code' (a 6-digit code appears).\n" +
                    "4. Pull DOWN the notification shade, type the 6 digits into the notification " +
                    "reply, and send. Do NOT close the dialog. The result shows in the notification."
                setPadding(0, pad / 2, 0, pad / 2)
                textSize = 12f
            },
        )
        root.addView(
            Button(this).apply {
                text = "Open Wireless debugging settings"
                setOnClickListener {
                    // Public action only opens Developer options; ColorOS has no
                    // public deep-link to the pairing dialog, so the user taps
                    // "Wireless debugging → Pair device with pairing code" there.
                    val opened =
                        runCatching {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            true
                        }.getOrDefault(false)
                    if (!opened) render("Couldn't open Developer options — open it manually")
                }
            },
        )
        // startPairingButton — posts the inline-reply pairing notification
        // (PairingNotifier). The actual pairing runs in PairingReplyReceiver when
        // you send the code from the notification; results show in the notification.
        root.addView(
            Button(this).apply {
                text = "1. Start pairing (notification)"
                setOnClickListener {
                    PairingNotifier.show(this@SelfTestActivity)
                    render(
                        "Pairing notification posted. Now: open the pairing dialog in Settings, " +
                            "pull down the shade, and type the 6-digit code into the notification reply.",
                    )
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = "2. Self-grant WRITE_SECURE_SETTINGS"
                setOnClickListener {
                    val ctx = this@SelfTestActivity
                    render("Connecting (autoConnect) + granting…")
                    Thread {
                        // autoConnect (inside selfGrant) does mDNS + TLS itself.
                        AdbWifiManager.enableWirelessDebugging(ctx)
                        val granted = AdbWifiManager.selfGrantWriteSecureSettings(ctx)
                        val msg =
                            if (granted) {
                                "WSS self-granted + verified HELD"
                            } else {
                                val out = AdbWifiManager.lastGrantOutput
                                when {
                                    out == null ->
                                        "Grant FAILED — shell didn't connect: " +
                                            (AdbWifiManager.lastError ?: "paired? wireless debugging on?")
                                    out.isBlank() ->
                                        "pm grant ran with NO error but WSS is still NOT held — " +
                                            "run the ADB command below from a PC."
                                    else ->
                                        "Grant FAILED — pm grant said: ${out.trim()}"
                                }
                            }
                        runOnUiThread { render(msg) }
                    }.start()
                }
            },
        )
        // adbGrantHint — small help line under step 2 explaining the MANUAL
        // fallback when self-grant fails (the user's case: self-grant ran but the
        // permission wasn't actually held). It's a ONE-TIME PC command that
        // persists across reboots, so it never becomes a per-boot manual step.
        root.addView(
            TextView(this).apply {
                text =
                    "If step 2 doesn't stick: run this ONCE from a PC with the phone " +
                    "connected over ADB. WRITE_SECURE_SETTINGS persists across reboots " +
                    "— no per-boot step:"
                setPadding(0, pad, 0, pad / 4)
                textSize = 12f
            },
        )
        // adbGrantCommand — selectable monospace box with the EXACT pm grant
        // command for THIS install (packageName auto-resolves .debug vs release).
        // Long-press to select/copy, or tap the Copy button below.
        val grantCommand = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        root.addView(
            TextView(this).apply {
                text = grantCommand
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setTextIsSelectable(true)
                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            },
        )
        // copyGrantButton — copies the pm grant command to the clipboard so the
        // user can paste it into a PC terminal without retyping the package id.
        root.addView(
            Button(this).apply {
                text = "Copy ADB grant command"
                setOnClickListener {
                    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("adb grant command", grantCommand))
                    render("ADB grant command copied to clipboard")
                }
            },
        )

        root.addView(
            Button(this).apply {
                text = "3. Test self-ADB Wi-Fi"
                setOnClickListener {
                    val ctx = this@SelfTestActivity
                    render("AdbWifiRadio.setWifi…")
                    Thread {
                        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val target = !wm.isWifiEnabled
                        val ran = AdbWifiRadio.setWifi(ctx, target)
                        runOnUiThread {
                            render(
                                "self-ADB setWifi(target=${if (target) "ON" else "OFF"}) ran=$ran\n" +
                                    "status: ${AdbWifiRadio.lastStatus}\n" +
                                    "Wi-Fi now ${if (wm.isWifiEnabled) "ON" else "OFF"}",
                            )
                        }
                    }.start()
                }
            },
        )

        // Wrap in a ScrollView — the setup section makes the screen taller than a
        // phone viewport.
        setContentView(ScrollView(this).apply { addView(root) })
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
        val selfAdb =
            if (AdbWifiRadio.isPaired(this)) {
                "PAIRED — last: ${AdbWifiRadio.lastStatus}"
            } else {
                "NOT PAIRED (use the setup buttons below)"
            }
        // wss — live ground-truth of WRITE_SECURE_SETTINGS so a self-grant that
        // "ran but didn't stick" is visible at a glance (not just in a button toast).
        val wss = if (AdbWifiManager.hasWriteSecureSettings(this)) "HELD" else "NOT held"
        // qsDetect — live state of the Quick Share auto-detect accessibility
        // service: whether it's enabled, the last GMS window it saw (to diagnose a
        // class-name mismatch), and its last action line.
        val qsEnabled = if (isQuickShareWatcherEnabled()) "ENABLED" else "DISABLED"
        // restorePending — persisted ground-truth (ShareRadioSession.isSessionActive)
        // of whether a radio WE turned on is still waiting to be restored. Shown so a
        // "restore never fired" can be told apart from "restore ran but toggle-off
        // failed": after leaving Quick Share + the grace window, this should flip to NO
        // and Wi-Fi/BT above should return to their pre-share state.
        val restorePending = if (ShareRadioSession.isSessionActive(this)) "YES (radio held on)" else "no"
        shizukuButton.visibility = if (ShizukuRadio.needsPermission) Button.VISIBLE else Button.GONE
        status.text =
            "Wi-Fi: $wifi    Bluetooth: $bt\n" +
            "WRITE_SECURE_SETTINGS: $wss\n" +
            "self-ADB: $selfAdb\n" +
            "Shizuku: $shizuku\n" +
            "Quick Share auto-detect: $qsEnabled\n" +
            "  restore pending: $restorePending\n" +
            "  last GMS window: ${QuickShareWatchStatus.lastWindow}\n" +
            "  status: ${QuickShareWatchStatus.line}\n\n$message"
    }

    /**
     * True if [QuickShareWatcherService] is currently enabled in
     * Settings > Accessibility. Read from the canonical
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (a ':'-separated list of
     * "pkg/class" component names) — the same source the framework uses to decide
     * which services to bind. Matches on our component so a differently-named
     * service can't false-positive.
     */
    private fun isQuickShareWatcherEnabled(): Boolean {
        val enabled =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
        val me = ComponentName(this, QuickShareWatcherService::class.java)
        return enabled.split(':').any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c.packageName == me.packageName && c.className == me.className
        }
    }

    private companion object {
        const val SHIZUKU_REQUEST = 1001
    }
}
