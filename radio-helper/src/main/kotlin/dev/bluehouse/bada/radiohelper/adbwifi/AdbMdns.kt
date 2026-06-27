/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper.adbwifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WHAT THIS IS
 * ------------
 * `AdbMdns` — discovers the **randomized adbd port** that Android 11+ "Wireless
 * debugging" opens, via mDNS (`_adb-tls-connect._tcp`). Lives in the HELPER
 * module so the shared self-ADB engine ([AdbWifiRadio]) can find the port to
 * connect to.
 *
 * WHY IT EXISTS
 * -------------
 * After [AdbWifiManager.enableWirelessDebugging] the adbd port is different each
 * time, so it must be resolved before connecting. Mirrors the adb-auto-enable
 * reference.
 *
 * THREADING / STATUS
 * ------------------
 * Blocking — call OFF the main thread. NOT device-tested. `NsdManager.resolveService`
 * is deprecated on API 34 but still functional; a `registerServiceInfoCallback`
 * migration can come later.
 */
internal object AdbMdns {
    private const val TAG = "AdbWifi/mDNS"
    const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

    /**
     * The Android-11 "Pair device with pairing code" service. Advertised only
     * while the pairing dialog is open; discovering it gives us the transient
     * PAIRING port so the user only has to type the 6-digit code (not the port),
     * exactly like Brevent. Use with [discoverPort] (serviceType = this).
     */
    const val SERVICE_PAIRING = "_adb-tls-pairing._tcp"

    /** A resolved mDNS service endpoint (the device's own IP + the service port). */
    data class HostPort(
        val host: String,
        val port: Int,
    )

    /**
     * @return the resolved adbd port, or -1 if none found within [timeoutSeconds].
     * Loopback (127.0.0.1) self-connect works for the CONNECT service (verified:
     * LADB self-connects over localhost), so this port + 127.0.0.1 is enough for
     * the connect path. For PAIRING use [discoverHostPort] (the pairing server may
     * bind only to the Wi-Fi IP, not loopback).
     */
    fun discoverPort(
        context: Context,
        serviceType: String = SERVICE_CONNECT,
        timeoutSeconds: Long = 10,
    ): Int = discoverHostPort(context, serviceType, timeoutSeconds)?.port ?: -1

    /**
     * Discover a service and return BOTH the resolved host IP and port (or null).
     * Used for pairing, where connecting to the device's actual Wi-Fi IP is safer
     * than assuming the pairing server is on loopback. Blocking — off main thread.
     */
    @Suppress("DEPRECATION")
    fun discoverHostPort(
        context: Context,
        serviceType: String = SERVICE_CONNECT,
        timeoutSeconds: Long = 10,
    ): HostPort? {
        val nsd =
            context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
                ?: return null
        val result = arrayOfNulls<HostPort>(1)
        val latch = CountDownLatch(1)

        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    serviceInfo ?: return
                    nsd.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(
                                info: NsdServiceInfo?,
                                errorCode: Int,
                            ) {
                                Log.w(TAG, "resolve failed: $errorCode")
                            }

                            override fun onServiceResolved(info: NsdServiceInfo?) {
                                val p = info?.port ?: return
                                val h = info.host?.hostAddress ?: return
                                Log.i(TAG, "resolved ${info.serviceName} -> $h:$p")
                                // Record the latest; let the timeout end discovery.
                                result[0] = HostPort(h, p)
                            }
                        },
                    )
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}

                override fun onDiscoveryStopped(serviceType: String?) {}

                override fun onStartDiscoveryFailed(
                    serviceType: String?,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "discovery start failed: $errorCode")
                    latch.countDown()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String?,
                    errorCode: Int,
                ) {}
            }

        return runCatching {
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutSeconds, TimeUnit.SECONDS)
            runCatching { nsd.stopServiceDiscovery(listener) }
            result[0]
        }.getOrElse {
            Log.w(TAG, "discoverHostPort failed: ${it.message}")
            null
        }
    }
}
