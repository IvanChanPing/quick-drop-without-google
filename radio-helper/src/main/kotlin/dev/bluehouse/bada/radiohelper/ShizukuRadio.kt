/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Silent Wi-Fi fallback via Shizuku: bind the [RadioShellService] user
 * service (runs as shell UID) and ask it to flip Wi-Fi with `svc wifi`.
 * Works on OEMs (e.g. ColorOS) that clamp `setWifiEnabled()` even with
 * WRITE_SECURE_SETTINGS, and needs no fragile hidden-AIDL binding.
 *
 * [isAvailable] is true only when Shizuku is running AND has granted us
 * permission; otherwise callers fall back to the Wi-Fi settings panel.
 */
internal object ShizukuRadio {
    private const val TAG = "ShizukuRadio"
    private const val BIND_TIMEOUT_SECONDS = 8L

    @Volatile
    private var service: IRadioShell? = null

    init {
        // Make binder availability reliable regardless of startup timing, and
        // drop a cached service if Shizuku dies. Best-effort: if the Shizuku
        // provider isn't initialised these throw — swallowed.
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { /* availability re-checked live */ }
            Shizuku.addBinderDeadListener { service = null }
        }
    }

    /** Human-readable result of the last [trySetWifi] attempt (diagnostics). */
    @Volatile
    var lastStatus: String = "not attempted"
        private set

    val isAvailable: Boolean
        get() =
            runCatching {
                Shizuku.pingBinder() &&
                    !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

    /** True if Shizuku is running but we don't yet hold its permission. */
    val needsPermission: Boolean
        get() =
            runCatching {
                Shizuku.pingBinder() &&
                    !Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

    /**
     * Flip Wi-Fi via the Shizuku user service. Returns `true` only when the
     * shell command reported success; `false` when Shizuku is unavailable,
     * the bind times out, or the command failed (→ caller shows the panel).
     */
    @Suppress("ReturnCount") // one early return per unavailability/failure rung.
    fun trySetWifi(
        context: Context,
        on: Boolean,
    ): Boolean {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) {
            lastStatus = "Shizuku not running"
            return false
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            lastStatus = "Shizuku too old (pre-v11)"
            return false
        }
        val granted =
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        if (!granted) {
            lastStatus = "Shizuku permission NOT granted"
            return false
        }
        val svc = boundService(context)
        if (svc == null) {
            lastStatus = "user-service bind failed/timeout"
            return false
        }
        return runCatching { svc.setWifiEnabled(on) }
            .onSuccess { lastStatus = if (it) "svc wifi OK" else "svc wifi returned false" }
            .getOrElse {
                lastStatus = "svc call threw: ${it.message}"
                Log.w(TAG, "setWifiEnabled via Shizuku failed: ${it.message}")
                service = null // drop a dead binder so the next call rebinds
                false
            }
    }

    @Synchronized
    private fun boundService(context: Context): IRadioShell? {
        service?.let { if (it.asBinder().isBinderAlive) return it }
        val latch = CountDownLatch(1)
        val args =
            Shizuku
                .UserServiceArgs(
                    ComponentName(context.packageName, RadioShellService::class.java.name),
                ).daemon(false)
                .processNameSuffix("radioshell")
                .debuggable(BuildConfig.DEBUG)
                .version(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    binder: IBinder?,
                ) {
                    service =
                        if (binder != null && binder.pingBinder()) {
                            IRadioShell.Stub.asInterface(binder)
                        } else {
                            null
                        }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }
        return runCatching {
            Shizuku.bindUserService(args, connection)
            latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            service
        }.getOrElse {
            Log.w(TAG, "bindUserService failed: ${it.message}")
            null
        }
    }
}
