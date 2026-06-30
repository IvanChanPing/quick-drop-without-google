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
 * token ([NameCardBootstrap]). See `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`.
 *
 * Mirrors Google's gestureexchange shape (NFC = trigger, Bluetooth = payload).
 * The two phones meet by the 16-byte token from the tap, not a Bluetooth MAC
 * (Android hides the local MAC): the **server** advertises the token in BLE
 * service data; the **client** scans for exactly that token.
 *
 * Roles follow the NFC roles:
 *  - [startServer] — the CARD phone (the tapped, HCE side). Advertises the token
 *    and runs a GATT server with one READ|WRITE characteristic that serves our
 *    [NameCard] (read) and receives the peer's (write).
 *  - [startClient] — the READER phone (the initiator). Scans for the token,
 *    connects, READs the peer's card, then (if [sendMine]) WRITEs ours.
 *
 * Both deliver the peer's card via [onPeerCard]. `sendMine`/the read-only choice
 * is the Receive-Only vs Share decision (driven by the P5 UI).
 *
 * Permissions (already declared): BLUETOOTH_ADVERTISE (server), BLUETOOTH_SCAN
 * (client), BLUETOOTH_CONNECT (both GATT). Runtime-checked; a missing one logs
 * and returns false rather than throwing.
 *
 * ## STATUS — COMPILE-ONLY / UNVERIFIED
 * There is no Bluetooth radio or second phone in the build env, so NONE of the
 * BLE path is exercised here. The Android BLE API usage mirrors the repo's
 * verified `BleAdvertiser` + `BleGattInitialControlServer` idioms, but connect /
 * advertise / scan / MTU / long-read behaviour is device-verified only (P7 test
 * script). Also: this manager is not yet CALLED — arming it from the NFC trigger
 * + the consent UI + a foreground-service host is P5.
 */
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

        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
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
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            DiagnosticLog.w(TAG, "server: startAdvertising threw: ${t.message}")
            stop()
            false
        }
    }

    /**
     * Reader side: scan for [token], connect, READ the peer card, then WRITE
     * [localCard] iff [sendMine]. Calls [onPeerCard] with the read peer card.
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
        val scanner = adapter.bluetoothLeScanner ?: run {
            DiagnosticLog.w(TAG, "client: no scanner → skip")
            running.set(false)
            return false
        }
        this.scanner = scanner
        val filter =
            ScanFilter.Builder()
                .setServiceData(ParcelUuid(SERVICE_DATA_UUID), token)
                .build()
        val settings =
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        val cb = scanCallbackImpl(onPeerCard)
        scanCallback = cb
        return try {
            scanner.startScan(listOf(filter), settings, cb)
            mainHandler.postDelayed({ stop() }, MAX_SESSION_MS)
            DiagnosticLog.w(TAG, "client: scanning for token")
            true
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
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
                val slice = if (offset in 0..cardBytes.size) cardBytes.copyOfRange(offset, cardBytes.size) else ByteArray(0)
                val status = if (offset in 0..cardBytes.size) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET
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

    private fun scanCallbackImpl(
        onPeerCard: (NameCard) -> Unit,
    ): ScanCallback =
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

    private fun gattClientCallback(
        onPeerCard: (NameCard) -> Unit,
    ): BluetoothGattCallback =
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

    // ---- advertising helpers (idiom from BleAdvertiser) ----

    private fun advertiseSettings(): AdvertiseSettings =
        AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

    private fun advertiseData(token: ByteArray): AdvertiseData =
        AdvertiseData.Builder()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_ADVERTISE else Manifest.permission.BLUETOOTH

    private fun scanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.BLUETOOTH

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
    }
}
