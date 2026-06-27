/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.service.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.protocol.endpoint.NearbyServiceId
import dev.bluehouse.bada.protocol.nfc.NfcTapLinkHolder
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket

/**
 * `NfcColdReceiverPrimer` — makes a COLD NFC tap behave like a WARM one (single
 * tap, no second tap, no button) for the Quick Share tap-to-receive path.
 *
 * ## What it is / what it's called
 * Invoked as `NfcColdReceiverPrimer.prime(context)` from
 * [dev.bluehouse.bada.nfc.BadaTapHceService]'s `processCommandApdu` ADVERTISEMENT
 * branch when the receiver is NOT already live ([NfcTapLinkHolder.current] ==
 * null). There is no UI; it runs inside the HCE binder callback that the OS
 * starts on the tap (even from a dead process).
 *
 * ## What it does (the warm-parity trick)
 * A WARM receiver answers the very first ADVERTISEMENT with a real
 * `deym(PCP=3) + Wi-Fi-LAN rxAdv(IP:port)` because its TCP listener is already
 * bound and [NfcTapLinkHolder] is populated. A COLD receiver normally has
 * neither, so it used to answer an EMPTY tag and rely on the sender re-scanning.
 * This primer removes that dependency:
 *
 *  1. Reads the device's Wi-Fi-LAN IPv4 (fast, in-memory `ConnectivityManager`).
 *  2. Binds a [ServerSocket] on `0.0.0.0:0` SYNCHRONOUSLY (fast local syscall) —
 *     the SAME shape the production [TcpServerFactory.default] would bind. Even
 *     before any accept loop runs, a bound socket completes the TCP handshake
 *     and QUEUES the sender's connect in the kernel backlog, so the port we
 *     advertise is immediately connectable.
 *  3. Builds the live [NfcTapLinkHolder.Link] (same identity the session will
 *     advertise: [BleEndpointIdHolder] id, [NearbyServiceId.hashPrefix],
 *     [EndpointIdentityHolder] endpoint-info, the Wi-Fi IP, the bound port) and
 *     publishes it so the HCE answers a REAL tag on the FIRST tap.
 *  4. Stashes the bound socket for the about-to-start [ReceiverSession] to ADOPT
 *     (via [TcpServerFactory.default] → [takePreBoundSocket]) so the accept loop
 *     drains the kernel backlog on the IDENTICAL port the tag advertised.
 *
 * The HCE still fires the foreground-service wake; the session comes up, adopts
 * this socket, advertises (BLE + mDNS), and accepts the queued connection.
 *
 * ## Preconditions / failure paths
 * - **No Wi-Fi-LAN IPv4** (Wi-Fi off / still connecting) → [prime] returns
 *   `null`; the HCE answers an empty tag for that round and relies on the wake +
 *   the radio-helper turning Wi-Fi on + BLE discovery. (The radios are forced on
 *   by the wake in [ReceiverForegroundService] via the radio-helper.)
 * - **Foreground-service wake refused** (e.g. ColorOS background-FGS-start
 *   limit) → the session never starts and never adopts the socket; the kernel
 *   queues the connect but nothing drains it → it times out. The next tap
 *   re-primes: [prime] CLOSES any prior unadopted socket first, so the leak is
 *   bounded to one socket. [ReceiverForegroundService] also calls
 *   [discardUnadopted] on teardown.
 *
 * ## Threading
 * [prime] runs on the HCE main/binder thread, so it does only fast local work
 * (a `ConnectivityManager` read + a `ServerSocket(0)` bind). The one
 * potentially-heavier step is building the endpoint identity the FIRST time the
 * process is cold ([AdvertisedDeviceNames.createEndpointInfo] reads the device
 * name); we reuse the cached [EndpointIdentityHolder] snapshot when present to
 * avoid it.
 *
 * ## Status
 * Compile-only / device-UNVERIFIED — there is no NFC hardware in the build env.
 * The kernel-backlog + socket-adoption mechanism is unit-tested in
 * `TcpReceiverServerTest` ("start adopts a pre-bound ServerSocket…"); the real
 * single-cold-tap transfer is the on-device test.
 */
object NfcColdReceiverPrimer {
    private const val TAG = "BadaNfcColdPrime"

    /** Matches `TcpReceiverServer.ACCEPT_BACKLOG` so the adopted socket is identical. */
    private const val ACCEPT_BACKLOG = 8

    private val socketLock = Any()

    @Volatile
    private var preBoundSocket: ServerSocket? = null

    /**
     * Bind a listener + publish the live [NfcTapLinkHolder.Link] so a cold tap
     * answers a real, connectable tag on the FIRST round. Returns the link, or
     * `null` when there is no Wi-Fi-LAN IPv4 to advertise yet.
     */
    fun prime(context: Context): NfcTapLinkHolder.Link? {
        val ip = firstWifiLanIpv4(context)
        if (ip == null) {
            DiagnosticLog.w(TAG, "prime: no Wi-Fi-LAN IPv4 yet -> empty tag this round (wake + radios + BLE)")
            return null
        }

        val socket =
            synchronized(socketLock) {
                // Close any prior primed-but-never-adopted socket (e.g. a previous
                // tap whose FGS wake was refused) so the leak is bounded to one.
                preBoundSocket?.let { runCatching { it.close() } }
                ServerSocket(0, ACCEPT_BACKLOG).also { preBoundSocket = it }
            }

        val link = buildLink(context, ip, socket.localPort)
        NfcTapLinkHolder.set(link)
        DiagnosticLog.w(TAG, "prime: live tag ready ${ip.hostAddress}:${socket.localPort} (cold tap == warm)")
        return link
    }

    /**
     * Publish the live receiver link for a WARM session — one already running with
     * its TCP listener bound on [port] — so an NFC tap answers a real
     * `deym + Wi-Fi-LAN rxAdv` tag without cold-priming a second socket. Called by
     * [ReceiverForegroundService] right after the session binds. Returns the
     * published link, or `null` when there is no Wi-Fi-LAN IPv4 to advertise (the
     * receiver isn't reachable over Wi-Fi-LAN, so we leave the tag empty).
     */
    fun publishWarmLink(
        context: Context,
        port: Int,
    ): NfcTapLinkHolder.Link? {
        val ip = firstWifiLanIpv4(context) ?: return null
        val link = buildLink(context, ip, port)
        NfcTapLinkHolder.set(link)
        DiagnosticLog.w(TAG, "publishWarmLink: live tag ${ip.hostAddress}:$port")
        return link
    }

    /**
     * Build the [NfcTapLinkHolder.Link] advertised over NFC for the given live
     * [ip]/[port], reusing the cached [EndpointIdentityHolder] snapshot so we
     * don't re-read device-name settings on the HCE thread when possible.
     */
    private fun buildLink(
        context: Context,
        ip: Inet4Address,
        port: Int,
    ): NfcTapLinkHolder.Link {
        val identity =
            EndpointIdentityHolder.snapshot.get()
                ?: AdvertisedDeviceNames.createEndpointInfo(context).also {
                    EndpointIdentityHolder.snapshot.compareAndSet(null, it)
                }
        val effectiveIdentity = EndpointIdentityHolder.snapshot.get() ?: identity
        return NfcTapLinkHolder.Link(
            endpointId = BleEndpointIdHolder.bytesFor(),
            serviceIdHash = NearbyServiceId.hashPrefix,
            endpointInfo = effectiveIdentity.serialize(),
            address = ip,
            port = port,
        )
    }

    /**
     * Hand the primed listener to the about-to-start [ReceiverSession] (called
     * once by [TcpServerFactory.default]). Returns `null` when no cold tap
     * primed a socket — production then binds a fresh ephemeral one as before.
     */
    fun takePreBoundSocket(): ServerSocket? =
        synchronized(socketLock) {
            val s = preBoundSocket
            preBoundSocket = null
            s
        }

    /**
     * Release a primed-but-unadopted socket (the FGS wake never brought the
     * session up). Called from [ReceiverForegroundService] teardown so a refused
     * cold wake does not leak the listener until the next tap.
     */
    fun discardUnadopted() {
        synchronized(socketLock) {
            preBoundSocket?.let { runCatching { it.close() } }
            preBoundSocket = null
        }
    }

    /**
     * First non-local Wi-Fi-LAN IPv4, via `ConnectivityManager.LinkProperties`
     * (mirrors `ReceiverForegroundService.firstWifiLanIpv4`). Uses
     * `LinkProperties`, NOT the deprecated `WifiManager.connectionInfo`, which
     * returns sentinel `0.0.0.0` on API 31+ without precise-location permission.
     */
    private fun firstWifiLanIpv4(context: Context): Inet4Address? {
        val cm =
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return null
        return cm.allNetworks
            .asSequence()
            .filter { network ->
                cm
                    .getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.mapNotNull { network -> cm.getLinkProperties(network) }
            .flatMap { linkProperties -> linkProperties.linkAddresses.asSequence() }
            .map { linkAddress -> linkAddress.address }
            .filterIsInstance<Inet4Address>()
            .filterNot { addr: InetAddress ->
                addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
            }.sortedBy(InetAddress::getHostAddress)
            .firstOrNull()
    }
}
