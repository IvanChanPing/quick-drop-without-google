/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.adbwifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Discovers the **randomized adbd port** that Android 11+ Wireless Debugging
 * opens, via mDNS (`_adb-tls-connect._tcp`). After [AdbWifiManager.enableWirelessDebugging]
 * the port is different each time, so we must resolve it before connecting.
 * Mirrors the adb-auto-enable reference. Blocking — call OFF the main thread.
 *
 * NOT device-tested. `NsdManager.resolveService` is deprecated on API 34 but
 * still functional; a `registerServiceInfoCallback` migration can come later.
 */
internal object AdbMdns {
    private const val TAG = "AdbWifi/mDNS"
    const val SERVICE_CONNECT = "_adb-tls-connect._tcp"

    /**
     * @return the resolved adbd port, or -1 if none found within [timeoutSeconds].
     * Waits the full timeout to capture the latest advertised port (the port can
     * be re-advertised), matching the reference.
     */
    @Suppress("DEPRECATION")
    fun discoverPort(
        context: Context,
        serviceType: String = SERVICE_CONNECT,
        timeoutSeconds: Long = 10,
    ): Int {
        val nsd =
            context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
                ?: return -1
        val port = intArrayOf(-1)
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
                                Log.i(TAG, "resolved ${info.serviceName} -> port $p")
                                // Record the latest; let the timeout end discovery.
                                port[0] = p
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
            port[0]
        }.getOrElse {
            Log.w(TAG, "discoverPort failed: ${it.message}")
            -1
        }
    }
}
