/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
// BluetoothGattCharacteristic.value get/set + the no-value writeCharacteristic overload are
// deprecated on API 33+; we keep them for the minSdk-24 path and pass values explicitly where
// the new overloads exist. Suppressed file-wide to keep the BLE plumbing readable.
@file:Suppress("DEPRECATION")

package dev.superdrop.namecard

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import dev.superdrop.protocol.namecard.NameCard
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Name Card Bluetooth exchange** — carries the actual contact card between two
 * Super Drop phones AFTER an NFC tap has triggered them and shared a rendezvous
 * token ([NameCardBootstrap]). NFC is only the trigger; Bluetooth carries the card.
 *
 * The two phones meet by the 16-byte token from the tap, not a Bluetooth MAC
 * (Android hides the local MAC): the **server** advertises the token in BLE
 * service data; the **client** scans for exactly that token.
 *
 * Roles follow the NFC roles:
 *  - [startServer] — the CARD phone (the tapped, HCE side). Advertises the token
 *    and runs a GATT server with one READ|WRITE characteristic that serves our
 *    [NameCard] (read) and receives the peer's (write).
 *  - [startClient] — the READER phone (the initiator). Scans for the token,
 *    connects, READs the peer's card, then holds for the user's choice:
 *    [shareBack] WRITEs ours (Share) or [declineShare] closes (Receive Only).
 *
 * Both deliver the peer's card via [onPeerCard]. The reader then chooses Share
 * ([shareBack]) or Receive-Only ([declineShare]) on the held connection, driven by
 * [NameCardTransferActivity].
 *
 * Permissions (already declared): BLUETOOTH_ADVERTISE (server), BLUETOOTH_SCAN
 * (client), BLUETOOTH_CONNECT (both GATT). Runtime-checked; a missing one logs
 * and returns false rather than throwing.
 *
 * ## STATUS — COMPILE-ONLY / UNVERIFIED
 * There is no Bluetooth radio or second phone in the build env, so NONE of the
 * BLE path is exercised here. Standard Android BLE APIs; connect /
 * advertise / scan / MTU / long-read behaviour is device-verified only (on-device
 * test script). Driven by [NameCardExchangeService] (server) and
 * [NameCardTransferActivity] (client); a [ShareRadioController] in each forces
 * Bluetooth on (+ heartbeat) before this runs.
 */
@Suppress("TooManyFunctions", "ReturnCount", "MagicNumber")
internal class NameCardBleExchange(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)

    /** Safety-timeout handler: auto-[stop] a session that never completes (battery backstop). */
    private val mainHandler = Handler(Looper.getMainLooper())

    // Server-side handles.
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var gattServer: BluetoothGattServer? = null

    // Client-side handles.
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var clientGatt: BluetoothGatt? = null

    // Held between READ (peer card surfaced) and the user's Share/Receive-Only choice.
    private var pendingGatt: BluetoothGatt? = null
    private var pendingCharacteristic: BluetoothGattCharacteristic? = null

    // ---- Name Card v2 (symmetric consent) state ----

    /** Non-null only in a v2 session; the coordinator we raise peer events to (main-thread). */
    private var consentListener: ConsentBleListener? = null

    /** This session's own card, kept so a machine `TransmitCard` effect can send/serve it. */
    private var v2LocalCard: NameCard? = null

    // @Volatile on the fields written on one thread (main / a binder callback) and read on another,
    // so the CARD-read gate and notify target are never read stale.

    /** Server: whether OUR user tapped Share — the gate the CARD read handler consults (plan D2). */
    @Volatile private var v2LocalSharing = false

    /** Server: has the connected peer sent HELLO? Read-before-HELLO ⇒ legacy v1 client (plan D3). */
    @Volatile private var v2PeerHelloSeen = false

    /** Server handles for the CONSENT characteristic + the device that subscribed to its notifies. */
    private var v2ServerConsentChar: BluetoothGattCharacteristic? = null

    @Volatile private var v2SubscribedDevice: BluetoothDevice? = null

    /** Client handles: the peer's CONSENT + CARD characteristics once services are discovered. */
    @Volatile private var v2ClientConsentChar: BluetoothGattCharacteristic? = null

    @Volatile private var v2ClientCardChar: BluetoothGattCharacteristic? = null

    @Volatile private var v2ClientGatt: BluetoothGatt? = null

    /**
     * Client single-GATT-op serializer (plan B3 trap): Android allows one outstanding GATT operation
     * per connection; a second before the prior callback SILENTLY fails. Every client GATT op goes
     * through [enqueueClientOp]; each completion callback calls [clientOpDone] to flush the next.
     */
    private var v2OpInFlight = false
    private val v2OpQueue = ArrayDeque<() -> Unit>()

    /** Client: true while the HELLO write is outstanding, so its ack becomes the link-ready signal. */
    @Volatile private var v2AwaitingHelloAck = false

    /** Fire [ConsentBleListener.onLinkReady] exactly once per session. */
    @Volatile private var v2ReadyFired = false

    /**
     * Card side: advertise [token] and serve [localCard] over GATT. Calls
     * [onPeerCard] when the connecting peer writes its card. Returns false if BLE
     * is unavailable / permissions missing.
     */
    fun startServer(
        localCard: NameCard,
        token: ByteArray,
        onPeerCard: (NameCard) -> Unit,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        if (!hasPermission(advertisePermission()) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            DiagnosticLog.w(TAG, "server: missing BLE permission → skip")
            running.set(false)
            return false
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DiagnosticLog.w(TAG, "server: Bluetooth off/unavailable → skip")
            running.set(false)
            return false
        }

        val cardBytes = localCard.serialize()
        val server =
            manager.openGattServer(
                appContext,
                serverCallback(cardBytes, onPeerCard),
            ) ?: run {
                DiagnosticLog.w(TAG, "server: openGattServer null → skip")
                running.set(false)
                return false
            }
        gattServer = server
        val characteristic =
            BluetoothGattCharacteristic(
                CARD_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
            ).also { it.value = cardBytes }
        val service =
            BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                .also { it.addCharacteristic(characteristic) }
        server.addService(service)

        val advertiser =
            adapter.bluetoothLeAdvertiser ?: run {
                DiagnosticLog.w(TAG, "server: no advertiser → skip")
                stop()
                return false
            }
        this.advertiser = advertiser
        val cb = advertiseCallbackImpl()
        advertiseCallback = cb
        return try {
            advertiser.startAdvertising(advertiseSettings(), advertiseData(token), cb)
            mainHandler.postDelayed({ stop() }, MAX_SESSION_MS)
            DiagnosticLog.w(TAG, "server: advertising token + GATT serving card(${cardBytes.size}B)")
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            DiagnosticLog.w(TAG, "server: startAdvertising threw: ${t.message}")
            stop()
            false
        }
    }

    /**
     * Reader side: scan for [token], connect, and READ the peer card, delivering
     * it via [onPeerCard]. The connection is then HELD open for the user's choice:
     * [shareBack] writes our card back (Share) or [declineShare] closes it
     * (Receive Only). A [MAX_SESSION_MS] backstop auto-closes if neither is called.
     */
    fun startClient(
        token: ByteArray,
        onPeerCard: (NameCard) -> Unit,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        if (!hasPermission(scanPermission()) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            DiagnosticLog.w(TAG, "client: missing BLE permission → skip")
            running.set(false)
            return false
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DiagnosticLog.w(TAG, "client: Bluetooth off/unavailable → skip")
            running.set(false)
            return false
        }
        val scanner =
            adapter.bluetoothLeScanner ?: run {
                DiagnosticLog.w(TAG, "client: no scanner → skip")
                running.set(false)
                return false
            }
        this.scanner = scanner
        val filter =
            ScanFilter
                .Builder()
                .setServiceData(ParcelUuid(SERVICE_DATA_UUID), token)
                .build()
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        val cb = scanCallbackImpl(onPeerCard)
        scanCallback = cb
        return try {
            scanner.startScan(listOf(filter), settings, cb)
            mainHandler.postDelayed({ stop() }, MAX_SESSION_MS)
            DiagnosticLog.w(TAG, "client: scanning for token")
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            DiagnosticLog.w(TAG, "client: startScan threw: ${t.message}")
            stop()
            false
        }
    }

    /**
     * Reader side, after the user taps **Share**: write our [localCard] to the
     * held connection so the peer (server) receives it too. The connection is
     * torn down once the write completes ([onCharacteristicWrite] → [stop]).
     */
    fun shareBack(localCard: NameCard) {
        val gatt = pendingGatt
        val characteristic = pendingCharacteristic
        if (gatt == null || characteristic == null) {
            DiagnosticLog.w(TAG, "shareBack: no held connection → stop")
            stop()
            return
        }
        characteristic.value = localCard.serialize()
        if (!gatt.writeCharacteristic(characteristic)) {
            DiagnosticLog.w(TAG, "shareBack: writeCharacteristic returned false → stop")
            stop()
        } else {
            DiagnosticLog.w(TAG, "client: Share → writing our card")
        }
    }

    /** Reader side, **Receive Only**: don't send our card; close the connection. */
    fun declineShare() {
        DiagnosticLog.w(TAG, "client: Receive Only → not sharing our card")
        stop()
    }

    /** Tear down all BLE handles. Idempotent. */
    fun stop() {
        running.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        pendingGatt = null
        pendingCharacteristic = null
        // v2 session state.
        consentListener = null
        v2LocalCard = null
        v2LocalSharing = false
        v2PeerHelloSeen = false
        v2LegacyReported = false
        v2ServerConsentChar = null
        v2SubscribedDevice = null
        v2ClientConsentChar = null
        v2ClientCardChar = null
        v2OpInFlight = false
        v2OpQueue.clear()
        v2AwaitingHelloAck = false
        v2ReadyFired = false
        runCatching { v2ClientGatt?.disconnect() }
        runCatching { v2ClientGatt?.close() }
        v2ClientGatt = null
        runCatching { scanCallback?.let { scanner?.stopScan(it) } }
        scanner = null
        scanCallback = null
        runCatching { clientGatt?.disconnect() }
        runCatching { clientGatt?.close() }
        clientGatt = null
        runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        advertiser = null
        advertiseCallback = null
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
    }

    // ---- server callbacks ----

    private fun serverCallback(
        cardBytes: ByteArray,
        onPeerCard: (NameCard) -> Unit,
    ): BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                // Offset-aware so a card larger than one MTU is read in chunks.
                val slice =
                    if (offset in 0..cardBytes.size) cardBytes.copyOfRange(offset, cardBytes.size) else ByteArray(0)
                val status =
                    if (offset in 0..cardBytes.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET
                gattServer?.sendResponse(device, requestId, status, offset, slice)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                val peer = NameCard.parse(value)
                if (peer != null) {
                    DiagnosticLog.w(TAG, "server: received peer card (${value.size}B)")
                    onPeerCard(peer)
                } else {
                    DiagnosticLog.w(TAG, "server: peer wrote unparseable card (${value.size}B)")
                }
            }
        }

    // ---- client callbacks ----

    private fun scanCallbackImpl(onPeerCard: (NameCard) -> Unit): ScanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                if (!running.get()) return
                // First match wins; stop scanning and connect.
                runCatching { scanCallback?.let { scanner?.stopScan(it) } }
                DiagnosticLog.w(TAG, "client: token match ${result.device.address?.takeLast(5)} → connecting")
                clientGatt = result.device.connectGatt(appContext, false, gattClientCallback(onPeerCard))
            }

            override fun onScanFailed(errorCode: Int) {
                DiagnosticLog.w(TAG, "client: scan failed code=$errorCode")
            }
        }

    private fun gattClientCallback(onPeerCard: (NameCard) -> Unit): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    DiagnosticLog.w(TAG, "client: connected → requestMtu")
                    if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    DiagnosticLog.w(TAG, "client: disconnected status=$status")
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                DiagnosticLog.w(TAG, "client: mtu=$mtu → discoverServices")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CARD_CHARACTERISTIC_UUID)
                if (ch == null) {
                    DiagnosticLog.w(TAG, "client: Name Card characteristic not found")
                    stop()
                    return
                }
                gatt.readCharacteristic(ch)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                // Hold the connection open for the user's Share / Receive-Only choice.
                pendingGatt = gatt
                pendingCharacteristic = characteristic
                val peer = characteristic.value?.let { NameCard.parse(it) }
                if (peer != null) {
                    DiagnosticLog.w(TAG, "client: read peer card → awaiting Share/Receive-Only")
                    onPeerCard(peer)
                } else {
                    DiagnosticLog.w(TAG, "client: peer card unreadable")
                    stop()
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                DiagnosticLog.w(TAG, "client: wrote our card status=$status → exchange done")
                stop()
            }
        }

    // ================= Name Card v2 (symmetric consent) =================
    // These run alongside the v1 methods above; a caller uses EITHER the v1 startServer/startClient
    // OR the v2 startServerV2/startClientV2 for a session (never both). v1 stays byte-identical.
    // Effects from NameCardConsentMachine map here: SendChoice→sendLocalChoice, TransmitCard→
    // transmitCard, CloseLink→sendByeAndClose. Peer events go out via [ConsentBleListener] (main).

    /** True in a v2 session once we know our role — server advertises+serves, client scans+connects. */
    private var v2IsServer = false

    /** Whether a legacy-peer fallback has already been reported for this session (fire once). */
    private var v2LegacyReported = false

    /**
     * Card side (v2): advertise [token] + serve a GATT service holding BOTH the CARD characteristic
     * (gated read — served only once OUR user taps Share, plan D2) and the CONSENT characteristic
     * (WRITE+NOTIFY). Peer events reach [listener] on the main thread. Returns false if BLE is
     * unavailable. Mirrors [startServer] but adds the consent plumbing; v1 [startServer] is untouched.
     */
    fun startServerV2(
        localCard: NameCard,
        token: ByteArray,
        listener: ConsentBleListener,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        if (!hasPermission(advertisePermission()) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            DiagnosticLog.w(TAG, "serverV2: missing BLE permission → skip")
            running.set(false)
            return false
        }
        val manager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DiagnosticLog.w(TAG, "serverV2: Bluetooth off/unavailable → skip")
            running.set(false)
            return false
        }
        v2IsServer = true
        consentListener = listener
        v2LocalCard = localCard
        val cardBytes = localCard.serialize()
        val server =
            manager.openGattServer(appContext, serverCallbackV2(cardBytes)) ?: run {
                DiagnosticLog.w(TAG, "serverV2: openGattServer null → skip")
                running.set(false)
                return false
            }
        gattServer = server
        // CARD: READ (gated in the handler) + WRITE (peer's card back). NO value bake — the handler
        // is the single source, and it gates on v2LocalSharing (plan D2).
        val cardChar =
            BluetoothGattCharacteristic(
                CARD_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        // CONSENT: WRITE (client→server messages) + NOTIFY (server→client), with the CCCD.
        val consentChar =
            BluetoothGattCharacteristic(
                CONSENT_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ).also {
                it.addDescriptor(
                    BluetoothGattDescriptor(
                        CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            }
        v2ServerConsentChar = consentChar
        val service =
            BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).also {
                it.addCharacteristic(cardChar)
                it.addCharacteristic(consentChar)
            }
        server.addService(service)

        val advertiser =
            adapter.bluetoothLeAdvertiser ?: run {
                DiagnosticLog.w(TAG, "serverV2: no advertiser → skip")
                stop()
                return false
            }
        this.advertiser = advertiser
        val cb = advertiseCallbackImpl()
        advertiseCallback = cb
        return try {
            advertiser.startAdvertising(advertiseSettings(), advertiseData(token), cb)
            mainHandler.postDelayed({ stop() }, MAX_SESSION_MS_V2)
            DiagnosticLog.w(TAG, "serverV2: advertising token + serving CARD(gated)+CONSENT")
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            DiagnosticLog.w(TAG, "serverV2: startAdvertising threw: ${t.message}")
            stop()
            false
        }
    }

    /**
     * Reader side (v2): scan for [token], connect, discover services, and — if the peer exposes the
     * CONSENT characteristic — subscribe + send HELLO and drive the consent flow. If CONSENT is
     * ABSENT the peer is a legacy v1 server: [ConsentBleListener.onLegacyPeer] fires and we read the
     * card the v1 way. Peer events reach [listener] on the main thread.
     */
    fun startClientV2(
        token: ByteArray,
        listener: ConsentBleListener,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false
        if (!hasPermission(scanPermission()) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            DiagnosticLog.w(TAG, "clientV2: missing BLE permission → skip")
            running.set(false)
            return false
        }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            DiagnosticLog.w(TAG, "clientV2: Bluetooth off/unavailable → skip")
            running.set(false)
            return false
        }
        val scanner =
            adapter.bluetoothLeScanner ?: run {
                DiagnosticLog.w(TAG, "clientV2: no scanner → skip")
                running.set(false)
                return false
            }
        v2IsServer = false
        consentListener = listener
        this.scanner = scanner
        val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_DATA_UUID), token).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    if (!running.get()) return
                    runCatching { scanCallback?.let { scanner.stopScan(it) } }
                    DiagnosticLog.w(TAG, "clientV2: token match → connecting")
                    v2ClientGatt = result.device.connectGatt(appContext, false, gattClientCallbackV2())
                }

                override fun onScanFailed(errorCode: Int) {
                    DiagnosticLog.w(TAG, "clientV2: scan failed code=$errorCode")
                }
            }
        scanCallback = cb
        return try {
            scanner.startScan(listOf(filter), settings, cb)
            mainHandler.postDelayed({ stop() }, MAX_SESSION_MS_V2)
            DiagnosticLog.w(TAG, "clientV2: scanning for token")
            true
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            DiagnosticLog.w(TAG, "clientV2: startScan threw: ${t.message}")
            stop()
            false
        }
    }

    /** Machine `SendChoice` effect: tell the peer my choice (server notifies; client writes). */
    fun sendLocalChoice(share: Boolean) {
        val bytes =
            NameCardConsentCodec.encode(
                if (share) ConsentMessage.ChoiceShare else ConsentMessage.ChoiceReceiveOnly,
            )
        if (v2IsServer) notifyConsent(bytes) else enqueueClientOp { writeConsent(bytes) }
    }

    /**
     * Machine `TransmitCard` effect: send my card. Server opens its gated CARD read (sets
     * [v2LocalSharing]; the client reads it after our CHOICE_SHARE notify). Client WRITES its card to
     * the peer's CARD characteristic (the v1 `shareBack` path, reused).
     */
    fun transmitCard(localCard: NameCard) {
        v2LocalCard = localCard
        if (v2IsServer) {
            v2LocalSharing = true
            DiagnosticLog.w(TAG, "serverV2: TransmitCard → CARD read gate open")
        } else {
            enqueueClientOp { writeCard(localCard.serialize()) }
        }
    }

    /**
     * Machine `CloseLink` effect: send BYE (so the peer knows it's a clean finish, not a drop), then
     * tear down after a short grace so any final in-flight card read/write drains (plan D5/B3).
     */
    fun sendByeAndClose() {
        val bye = NameCardConsentCodec.encode(ConsentMessage.Bye)
        if (v2IsServer) notifyConsent(bye) else enqueueClientOp { writeConsent(bye) }
        mainHandler.postDelayed({ stop() }, SESSION_CLOSE_GRACE_MS)
    }

    // ---- v2 server GATT callback ----

    private fun serverCallbackV2(cardBytes: ByteArray): BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    DiagnosticLog.w(TAG, "serverV2: peer disconnected status=$status")
                    notifyDisconnected()
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != CARD_CHARACTERISTIC_UUID) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }
                // Read-before-HELLO ⇒ legacy v1 client: serve unconditionally (plan D3).
                if (!v2PeerHelloSeen) {
                    if (!v2LegacyReported) {
                        v2LegacyReported = true
                        DiagnosticLog.w(TAG, "serverV2: CARD read before HELLO → legacy v1 peer")
                        notifyLegacyPeer()
                    }
                    serveCard(device, requestId, offset, cardBytes)
                    return
                }
                // v2 peer: serve only once OUR user tapped Share (plan D2).
                if (!v2LocalSharing) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                    return
                }
                serveCard(device, requestId, offset, cardBytes)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                when (characteristic.uuid) {
                    CARD_CHARACTERISTIC_UUID -> {
                        val peer = NameCard.parse(value)
                        if (peer != null) {
                            DiagnosticLog.w(TAG, "serverV2: peer card written (${value.size}B)")
                            notifyPeerCard(peer)
                        } else {
                            DiagnosticLog.w(TAG, "serverV2: peer wrote unparseable card (${value.size}B)")
                        }
                    }
                    CONSENT_CHARACTERISTIC_UUID -> handlePeerConsent(value)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                // TRAP (plan B3): ALWAYS respond or the client's subscribe stalls forever.
                v2SubscribedDevice = device
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                // A subscriber exists → the server can now notify choices.
                notifyLinkReady()
            }
        }

    /** Server: answer a CARD read with the offset-aware slice (long-read across MTU). */
    private fun serveCard(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        cardBytes: ByteArray,
    ) {
        val ok = offset in 0..cardBytes.size
        val slice = if (ok) cardBytes.copyOfRange(offset, cardBytes.size) else ByteArray(0)
        val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET
        gattServer?.sendResponse(device, requestId, status, offset, slice)
    }

    // ---- v2 client GATT callback ----

    private fun gattClientCallbackV2(): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    DiagnosticLog.w(TAG, "clientV2: disconnected status=$status")
                    notifyDisconnected()
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                val service = gatt.getService(SERVICE_UUID)
                val consent = service?.getCharacteristic(CONSENT_CHARACTERISTIC_UUID)
                val cardChar = service?.getCharacteristic(CARD_CHARACTERISTIC_UUID)
                if (consent == null) {
                    // Legacy v1 server: no consent channel → read the card the old way (plan D3).
                    DiagnosticLog.w(TAG, "clientV2: no CONSENT char → legacy v1 server")
                    notifyLegacyPeer()
                    if (cardChar != null) enqueueClientOp { gatt.readCharacteristic(cardChar) } else stop()
                    return
                }
                v2ClientConsentChar = consent
                v2ClientCardChar = cardChar
                v2ClientGatt = gatt
                gatt.setCharacteristicNotification(consent, true)
                val cccd = consent.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    DiagnosticLog.w(TAG, "clientV2: CONSENT has no CCCD → cannot subscribe")
                    stop()
                    return
                }
                enqueueClientOp { writeCccdEnable(gatt, cccd) }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                clientOpDone()
                // Subscribed → announce ourselves with HELLO; its write-ack becomes link-ready.
                v2AwaitingHelloAck = true
                enqueueClientOp { writeConsent(NameCardConsentCodec.helloBytes()) }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                clientOpDone()
                if (v2AwaitingHelloAck) {
                    v2AwaitingHelloAck = false
                    notifyLinkReady()
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid == CONSENT_CHARACTERISTIC_UUID) handlePeerConsent(characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == CONSENT_CHARACTERISTIC_UUID) handlePeerConsent(value)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                onClientCardRead(characteristic.value)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                onClientCardRead(value)
            }
        }

    /** Client: a CARD read completed — parse + surface the peer's card, then free the op slot. */
    private fun onClientCardRead(value: ByteArray?) {
        clientOpDone()
        val peer = value?.let { NameCard.parse(it) }
        if (peer != null) {
            DiagnosticLog.w(TAG, "clientV2: read peer card (${value.size}B)")
            notifyPeerCard(peer)
        } else {
            DiagnosticLog.w(TAG, "clientV2: peer card unreadable")
        }
    }

    /** Decode a peer CONSENT message (server-received write / client-received notify) → listener. */
    private fun handlePeerConsent(value: ByteArray?) {
        val msg = value?.let { NameCardConsentCodec.decode(it) }
        when (msg) {
            is ConsentMessage.Hello -> {
                v2PeerHelloSeen = true
                notifyPeerHello()
            }
            ConsentMessage.ChoiceShare -> {
                notifyPeerChoice(true)
                // Client: peer shared ⇒ its card is now readable — go read it.
                if (!v2IsServer) {
                    val gatt = v2ClientGatt
                    val card = v2ClientCardChar
                    if (gatt != null && card != null) enqueueClientOp { gatt.readCharacteristic(card) }
                }
            }
            ConsentMessage.ChoiceReceiveOnly -> notifyPeerChoice(false)
            ConsentMessage.Bye -> notifyDisconnected()
            null -> DiagnosticLog.w(TAG, "v2: undecodable consent message (${value?.size ?: 0}B)")
        }
    }

    // ---- v2 client single-op serializer (main-thread) ----

    private fun enqueueClientOp(op: () -> Unit) =
        runOnMain {
            if (v2OpInFlight) {
                v2OpQueue.addLast(op)
            } else {
                v2OpInFlight = true
                op()
            }
        }

    private fun clientOpDone() =
        runOnMain {
            v2OpInFlight = false
            val next = v2OpQueue.removeFirstOrNull() ?: return@runOnMain
            v2OpInFlight = true
            next()
        }

    private fun writeCccdEnable(
        gatt: BluetoothGatt,
        cccd: BluetoothGattDescriptor,
    ) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }.onFailure {
            DiagnosticLog.w(TAG, "clientV2: writeDescriptor threw ${it.message}")
            clientOpDone()
        }
    }

    private fun writeConsent(bytes: ByteArray) {
        val gatt = v2ClientGatt
        val ch = v2ClientConsentChar
        if (gatt == null || ch == null) {
            clientOpDone()
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                ch.value = bytes
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(ch)
            }
        }.onFailure {
            DiagnosticLog.w(TAG, "clientV2: writeConsent threw ${it.message}")
            clientOpDone()
        }
    }

    private fun writeCard(bytes: ByteArray) {
        val gatt = v2ClientGatt
        val ch = v2ClientCardChar
        if (gatt == null || ch == null) {
            clientOpDone()
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                ch.value = bytes
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(ch)
            }
        }.onFailure {
            DiagnosticLog.w(TAG, "clientV2: writeCard threw ${it.message}")
            clientOpDone()
        }
    }

    private fun notifyConsent(bytes: ByteArray) {
        val dev = v2SubscribedDevice
        val ch = v2ServerConsentChar
        val server = gattServer
        if (dev == null || ch == null || server == null) {
            DiagnosticLog.w(TAG, "serverV2: notify skipped (no subscriber yet)")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(dev, ch, false, bytes)
            } else {
                ch.value = bytes
                server.notifyCharacteristicChanged(dev, ch, false)
            }
        }.onFailure { DiagnosticLog.w(TAG, "serverV2: notify threw ${it.message}") }
    }

    // ---- v2 listener marshaling (binder thread → main) ----

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun notifyLinkReady() =
        runOnMain {
            if (!v2ReadyFired) {
                v2ReadyFired = true
                consentListener?.onLinkReady()
            }
        }

    private fun notifyPeerHello() = runOnMain { consentListener?.onPeerHello() }

    private fun notifyPeerChoice(share: Boolean) = runOnMain { consentListener?.onPeerChoice(share) }

    private fun notifyPeerCard(card: NameCard) = runOnMain { consentListener?.onPeerCardArrived(card) }

    private fun notifyLegacyPeer() = runOnMain { consentListener?.onLegacyPeer() }

    private fun notifyDisconnected() = runOnMain { consentListener?.onDisconnected() }

    // ---- advertising helpers (idiom from BleAdvertiser) ----

    private fun advertiseSettings(): AdvertiseSettings =
        AdvertiseSettings
            .Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

    private fun advertiseData(token: ByteArray): AdvertiseData =
        AdvertiseData
            .Builder()
            .addServiceData(ParcelUuid(SERVICE_DATA_UUID), token)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

    private fun advertiseCallbackImpl(): AdvertiseCallback =
        object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                DiagnosticLog.w(TAG, "server: advertise onStartFailure code=$errorCode")
            }
        }

    // ---- permission helpers ----

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun advertisePermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            Manifest.permission.BLUETOOTH
        }

    private fun scanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.BLUETOOTH
        }

    companion object {
        private const val TAG = "NameCardBle"

        /** Default ATT MTU to request so a ~200-byte card writes in one go. */
        private const val REQUESTED_MTU = 247

        /** Battery backstop: auto-stop a session (advertise/scan/GATT) that never completes. */
        private const val MAX_SESSION_MS = 30_000L

        /** GATT service holding the single Name Card read/write characteristic. */
        val SERVICE_UUID: UUID = UUID.fromString("f0534443-0001-4000-8000-534443415244")

        /** The one characteristic: READ serves our card, WRITE receives the peer's. */
        val CARD_CHARACTERISTIC_UUID: UUID = UUID.fromString("f0534443-0002-4000-8000-534443415244")

        /** 16-bit-style UUID under which the rendezvous token rides in adv service data. */
        val SERVICE_DATA_UUID: UUID = UUID.fromString("0000fe2d-0000-1000-8000-00805f9b34fb")

        // ---- Name Card v2 (symmetric consent) ----

        /**
         * v2 CONSENT characteristic (WRITE + NOTIFY): the client WRITES its
         * [NameCardConsentCodec] messages here; the server NOTIFIES its own back. Added to the SAME
         * [SERVICE_UUID] service as [CARD_CHARACTERISTIC_UUID]. Its absence on the server is how a v2
         * client detects a legacy v1 peer (plan D3).
         */
        val CONSENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("7b2fdd3e-9a41-4e2c-b7a4-5c1e6f3d0a11")

        /** Standard Client Characteristic Configuration Descriptor (enables NOTIFY on CONSENT). */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** v2 session backstop (plan D5): longer than v1's 30s so the 30s UX timer resolves first. */
        private const val MAX_SESSION_MS_V2 = 60_000L

        /**
         * Grace after a v2 `CloseLink`/BYE before the actual radio teardown, so any in-flight final
         * card read (server side, which has no read-completion callback) or write drains first. A
         * heuristic — TODO-DEVICE: tune against real two-phone timing.
         */
        private const val SESSION_CLOSE_GRACE_MS = 1_500L
    }
}

/**
 * Callback surface the BLE layer raises to the v2 consent coordinator (the transfer activity via
 * [NameCardLinkHolder]). All methods are delivered on the MAIN thread (the exchange marshals from the
 * binder-thread GATT callbacks). See [NameCardBleExchange] + plan B3.
 */
internal interface ConsentBleListener {
    /**
     * The link can now carry a choice: server has a subscriber / client finished its HELLO write.
     * The UI keeps the Share/Receive-Only buttons disabled ("Connecting…") until this fires so a
     * fast tap is never lost before the transport is ready.
     */
    fun onLinkReady()

    /** The peer sent HELLO — it speaks v2. */
    fun onPeerHello()

    /** The peer reported its choice ([share] = Share, else Receive Only). */
    fun onPeerChoice(share: Boolean)

    /** The peer's card BYTES arrived and parsed. */
    fun onPeerCardArrived(card: NameCard)

    /** The peer only speaks v1 (no CONSENT characteristic / read-before-HELLO) — fall back. */
    fun onLegacyPeer()

    /** The link dropped or the peer sent BYE. */
    fun onDisconnected()
}
