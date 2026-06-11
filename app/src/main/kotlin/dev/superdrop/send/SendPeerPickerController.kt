/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.send

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.superdrop.R
import dev.superdrop.databinding.ActivitySendBinding
import dev.superdrop.discovery.NearbyPeer
import dev.superdrop.ui.sheet.DeviceIconView
import dev.superdrop.discovery.NearbyPeerDiscovery
import dev.superdrop.discovery.NearbyPeerEvent
import dev.superdrop.discovery.NearbyPeerRoute
import dev.superdrop.discovery.ble.BleAdvertiseHandle
import dev.superdrop.discovery.ble.BleAdvertiser
import dev.superdrop.service.receiver.ReceiverAdvertisementStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.superdrop.discovery.diagnostics.DiagnosticLog as Log

@Suppress("LongParameterList") // Every collaborator (UI, lifecycle, callbacks, sender id) is needed.
internal class SendPeerPickerController(
    private val context: Context,
    private val binding: ActivitySendBinding,
    private val lifecycle: Lifecycle,
    private val scope: CoroutineScope,
    private val onPeerSelected: (NearbyPeer) -> Unit,
    /**
     * Invoked after every discovery update with the current resolved-peer
     * snapshot. The QR-code/link share path (#28) uses it to match peers
     * against the active QR session and auto-connect; the normal picker
     * flow ignores it. Default no-op so non-QR callers need not supply it.
     */
    private val onPeersResolved: (List<NearbyPeer>) -> Unit = {},
    private val logDiagnostic: (String) -> Unit,
    /**
     * Sender's 4-byte endpoint slug. Threaded into the BLE FastInitiation
     * pulse's `secret_id_hash` so stock GMS receivers classify the pulse
     * as an active `type=NOTIFY` share instead of an all-zero-hash
     * `type=SILENT` pulse.
     */
    private val senderEndpointId: String,
) {
    private val peers: MutableList<NearbyPeer> = mutableListOf()

    private var outboundPresenceJob: Job? = null
    private var discoveryJob: Job? = null
    private var emptyPeerHintJob: Job? = null

    private val emptyPeerHintTimer: EmptyPeerHintTimer = EmptyPeerHintTimer()
    private var bleAdvertiser: BleAdvertiser? = null
    private var bleAdvertiseHandle: BleAdvertiseHandle? = null

    /**
     * Snapshot of the row contents the picker was last rendered with —
     * one entry per visible peer in the order they were drawn, capturing
     * only the fields the row actually displays (stableId for identity,
     * title for the primary line, subtitle for the secondary line).
     *
     * Used to short-circuit [renderPeerList] when a discovery event
     * carries no display-relevant change. Without this gate, every BLE
     * fast-advertisement observation (which includes a fresh `rssi:Int?`
     * value, making the data-class equality on [NearbyPeer] flip every
     * few hundred milliseconds) would trigger a full
     * `container.removeAllViews()` + re-inflate cycle. With three or
     * more peers in the list, that churn lands inside roughly 10% of
     * tap windows and the user has to double-tap to register a click —
     * exactly the symptom reported on multi-peer environments.
     */
    private var lastRenderedRowSnapshot: List<RenderedRowSnapshot> = emptyList()

    private data class RenderedRowSnapshot(
        val stableId: String,
        val title: String,
        val subtitle: String,
    )

    fun start() {
        outboundPresenceJob?.cancel()
        outboundPresenceJob =
            scope.launch {
                val receiverWasAdvertising = ReceiverAdvertisementStateHolder.isAdvertising
                if (receiverWasAdvertising) {
                    logDiagnostic("discovery: waiting for receiver mDNS unpublish before browse")
                }

                val unpublishObserved = ReceiverAdvertisementStateHolder.awaitNotAdvertising()
                if (!unpublishObserved) {
                    logDiagnostic("discovery: receiver mDNS unpublish wait timed out; starting browse")
                } else if (receiverWasAdvertising) {
                    logDiagnostic("discovery: receiver mDNS unpublish observed")
                }

                if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@launch
                startDiscovery()
                startEmptyPeerHintTimer()
                startBleAdvertise()
            }
    }

    fun stop() {
        outboundPresenceJob?.cancel()
        discoveryJob?.cancel()
        emptyPeerHintJob?.cancel()
        stopBleAdvertise()
    }

    fun suspendPicker() {
        discoveryJob?.cancel()
        discoveryJob = null
        emptyPeerHintJob?.cancel()
        emptyPeerHintJob = null
        binding.sendNetworkHint.visibility = View.GONE
    }

    fun stopBleAdvertise() {
        bleAdvertiseHandle?.close()
        bleAdvertiseHandle = null
        bleAdvertiser = null
    }

    fun onHintDismissed() {
        emptyPeerHintTimer.markDismissed()
        binding.sendNetworkHint.visibility = View.GONE
    }

    /** Current resolved peers (snapshot) — used by the NFC tap-wake auto-connect. */
    fun resolvedPeers(): List<NearbyPeer> = peers.toList()

    fun peerLabel(peer: NearbyPeer): String = peer.displayName()

    fun peerSubtitle(peer: NearbyPeer): String = planFor(peer).subtitle

    fun peerFailureReason(peer: NearbyPeer): String = planFor(peer).failureReason ?: "no usable initial route"

    fun formatPeerSnapshot(
        peer: NearbyPeer,
        chosenRoute: NearbyPeerRoute? = null,
    ): String {
        val plan = planFor(peer)
        val endpointId = peer.endpointId ?: "<none>"
        val addressList =
            peer.lanEndpoint
                ?.addresses
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(",") { it.hostAddress ?: "<unresolved>" }
                ?: "<none>"
        val info = peer.endpointInfo
        val infoSummary =
            if (info == null) {
                "<none>"
            } else {
                buildString {
                    append("v=").append(info.version)
                    append(" hidden=").append(info.hidden)
                    append(" type=").append(info.deviceType.name)
                    append(" name=")
                    append(info.deviceName.toQuotedLogValue(nullToken = "<null>"))
                    if (info.tlvRecords.isNotEmpty()) {
                        append(" tlv=").append(
                            info.tlvRecords.joinToString(",") { tlv ->
                                val valueHex = tlv.value.joinToString("") { "%02x".format(it) }
                                "${tlv.type}:$valueHex"
                            },
                        )
                    }
                }
            }
        val routeSummary =
            when (chosenRoute) {
                is NearbyPeerRoute.Lan -> "lan=${chosenRoute.address.hostAddress}:${chosenRoute.port}"
                is NearbyPeerRoute.BluetoothClassic -> "bluetooth=${chosenRoute.macAddress}"
                is NearbyPeerRoute.BleL2cap -> "ble-l2cap=${chosenRoute.macAddress}:${chosenRoute.psm}"
                is NearbyPeerRoute.BleGatt -> "ble-gatt=${chosenRoute.macAddress}"
                null -> plan.action.diagnosticLabel
            }
        val bleIdentitySummary =
            formatBleIdentitySnapshot(peer)
        return "peer=${peer.stableId} endpointId=$endpointId mediums=${peer.candidateMediums} " +
            "addrs=[$addressList] route=$routeSummary displayName=${peer.displayName().toQuotedLogValue()} " +
            "displayNameSource=${peer.displayNameSource()} bootstrap={${plan.diagnosticSummary()}}" +
            "$bleIdentitySummary endpointInfo={$infoSummary}"
    }

    private fun formatBleIdentitySnapshot(peer: NearbyPeer): String {
        val ble = peer.bleAdvertisement ?: return ""
        val displayName = ble.displayName.toQuotedLogValue()
        val displayNameSource = ble.displayNameSource ?: "<none>"
        return " bleName=$displayName bleNameSource=$displayNameSource"
    }

    private fun startDiscovery() {
        val discovery = NearbyPeerDiscovery(context.applicationContext)
        logDiagnostic("discovery: start")
        discoveryJob =
            scope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    logDiagnostic("discovery: browse collector start")
                    try {
                        discovery.browse().collect { event -> onDiscoveryEvent(event) }
                    } finally {
                        logDiagnostic("discovery: browse collector stop")
                    }
                }
            }
    }

    private fun onDiscoveryEvent(event: NearbyPeerEvent) {
        val before = peers.size
        when (event) {
            is NearbyPeerEvent.Resolved -> {
                upsertResolvedPeer(event.peer)
                logDiagnostic(
                    "discovery: resolved ${formatPeerSnapshot(event.peer)} " +
                        "before=$before after=${peers.size} rows=${formatPeerRows()}",
                )
            }
            is NearbyPeerEvent.Lost -> {
                val removed = peers.removeAll { it.stableId == event.stableId }
                logDiagnostic(
                    "discovery: lost peer=${event.stableId} removed=$removed " +
                        "before=$before after=${peers.size} rows=${formatPeerRows()}",
                )
            }
        }
        renderPeerList()
        onPeersResolved(peers.toList())
    }

    private fun upsertResolvedPeer(incoming: NearbyPeer) {
        if (!planFor(incoming).isConnectable) {
            peers.removeAll { it.stableId == incoming.stableId }
            return
        }
        val existingIndex = peers.indexOfFirst { it.stableId == incoming.stableId }
        if (existingIndex >= 0) {
            peers[existingIndex] = incoming
        } else {
            peers.add(incoming)
        }
        peers.sortWith(
            compareByDescending<NearbyPeer> { planFor(it).isConnectable }
                .thenBy { it.displayName().lowercase() },
        )
    }

    private fun renderPeerList() {
        val container = binding.sendPeerList

        // Build the target row payloads up-front so we can compare
        // against the last rendered snapshot before deciding whether
        // the row container needs a rebuild.
        //
        // OShare-style presentation (Phase 1): each connectable peer is
        // drawn as a circular [DeviceIconView] in a horizontal row, and
        // peers are DEDUPED BY DISPLAY NAME — a receiver's BLE MAC
        // rotates for privacy, so the same phone is otherwise seen under
        // several stableIds and would show as several identical circles
        // (the "two CPH2583" duplicate). We collapse to one chip per name
        // and keep the first peer seen under that name as the chip's tap
        // target; the snapshot picks up a later peer for the same name on
        // the next render if the first is lost.
        data class TargetRow(
            val peer: NearbyPeer,
            val title: String,
            val subtitle: String,
        )
        val seenNames = HashSet<String>()
        val targetRows =
            peers.mapNotNull { peer ->
                val plan = planFor(peer)
                if (!plan.isConnectable) return@mapNotNull null
                val label = peerLabel(peer)
                if (!seenNames.add(label)) return@mapNotNull null
                TargetRow(peer, label, plan.subtitle)
            }
        val targetSnapshot =
            targetRows.map { row ->
                RenderedRowSnapshot(row.peer.stableId, row.title, row.subtitle)
            }

        // Subtitle ("Looking for nearby devices…" vs "Pick a device")
        // is cheap and depends only on whether peers exist, so we
        // refresh it unconditionally — re-applying the same string is
        // a no-op at the TextView layer.
        binding.sendSubtitle.setText(
            when {
                peers.isEmpty() -> R.string.send_subtitle_discovering
                else -> R.string.send_subtitle_pick_peer
            },
        )

        if (targetSnapshot == lastRenderedRowSnapshot) {
            // No display-relevant change. Skip the row rebuild so a
            // tap that just landed on an existing row keeps its click
            // handler attached, instead of being silently dropped by a
            // `removeAllViews()` + re-inflate cycle that would have run
            // for every BLE RSSI tick. The empty-state TextView is
            // still gated on the timer, not on peer-list churn, so we
            // call its updater below regardless.
            updateEmptyPeerHintVisibility()
            return
        }
        lastRenderedRowSnapshot = targetSnapshot

        container.removeAllViews()
        for (target in targetRows) {
            val stableId = target.peer.stableId
            val icon = DeviceIconView(context, stableId, target.title)
            icon.isEnabled = true
            icon.alpha = 1f
            icon.setOnClickListener {
                // Acknowledge the tap with the OShare bounce, then route
                // through the SAME selection path the old row click used
                // (resolve a current peer by stableId so a rotated-MAC
                // re-resolve picks the freshest route). Discovery /
                // OutboundConnection wiring is unchanged.
                icon.bounce()
                peers.firstOrNull { it.stableId == stableId }?.let(onPeerSelected)
            }
            container.addView(icon)
        }
        // Empty-state visibility is gated on [EmptyPeerHintTimer] inside
        // [updateEmptyPeerHintVisibility] so the "no devices nearby yet"
        // helper text only surfaces after a short discovery window has
        // elapsed. Pinning it true the instant the activity opens
        // produced a confusing pair of conflicting messages — the
        // subtitle would say "Looking for nearby devices…" while the
        // body told the user there were none — even though no scan
        // had actually had time to land its first result.
        updateEmptyPeerHintVisibility()
    }

    private fun startEmptyPeerHintTimer() {
        emptyPeerHintTimer.start(System.currentTimeMillis())
        emptyPeerHintJob =
            scope.launch {
                delay(EmptyPeerHintTimer.DEFAULT_DELAY_MILLIS)
                updateEmptyPeerHintVisibility()
            }
    }

    private fun updateEmptyPeerHintVisibility() {
        // Empty-state TextView ("no devices nearby yet…") is gated on
        // the same delay window as the same-Wi-Fi hint card, but the
        // dismiss latch does NOT apply — the body text is purely
        // informational, not a banner the user can close. The result
        // is a clean two-phase render: subtitle alone for the first
        // discovery window, subtitle + helper text once the window
        // expires with no peers found.
        val now = System.currentTimeMillis()
        val isEmpty = peers.isEmpty()
        binding.sendEmptyState.visibility =
            if (emptyPeerHintTimer.shouldShowEmptyState(now, isEmpty)) {
                View.VISIBLE
            } else {
                View.GONE
            }

        // The "Same Wi-Fi network required" inline card is intentionally
        // disabled in favour of the help link + bottom-sheet flow added
        // alongside `send_help_link`. The two surfaces were colliding
        // visually whenever the peer list stayed empty long enough to
        // pop the inline card — the link below it overlapped the
        // dismiss button on the card. The bottom sheet covers the same
        // guidance (and adds the QR fallback section), so the inline
        // card is kept in the layout for now but never raised.
        binding.sendNetworkHint.visibility = View.GONE
    }

    @Suppress("MissingPermission")
    private fun startBleAdvertise() {
        val advertiser = BleAdvertiser(context.applicationContext, senderEndpointId)
        bleAdvertiser = advertiser
        bleAdvertiseHandle = advertiser.start()
        if (bleAdvertiseHandle == null) {
            logDiagnostic("ble: pulse not started; falling back to mDNS-only discovery")
            Log.i(BLE_TAG, "BLE pulse not started - falling back to mDNS-only discovery")
        } else {
            logDiagnostic("ble: pulse started")
            Log.i(BLE_TAG, "BLE pulse started")
        }
    }

    private fun formatPeerRows(): String =
        if (peers.isEmpty()) {
            "<empty>"
        } else {
            peers.joinToString(";") { peer ->
                val plan = planFor(peer)
                val route =
                    when (val action = plan.action) {
                        is SendBootstrapPlan.Action.Direct ->
                            when (val route = action.route) {
                                is NearbyPeerRoute.Lan -> "${route.address.hostAddress}:${route.port}"
                                is NearbyPeerRoute.BluetoothClassic -> route.macAddress
                                is NearbyPeerRoute.BleL2cap -> "${route.macAddress}:${route.psm}"
                                is NearbyPeerRoute.BleGatt -> route.macAddress
                            }
                        SendBootstrapPlan.Action.Unavailable -> "<none>"
                    }
                "${peer.stableId}/${peer.endpointId ?: "<none>"}/$route/" +
                    "${peer.displayName().toSanitizedLogValue()}(${peer.displayNameSource()})"
            }
        }

    private fun planFor(peer: NearbyPeer): SendBootstrapPlan = SendBootstrapPlan.resolve(peer = peer)

    private companion object {
        private const val BLE_TAG: String = "BadaDiscovery"
    }
}

internal fun String?.toQuotedLogValue(nullToken: String = "<none>"): String =
    this
        ?.toSanitizedLogValue()
        ?.let { "\"$it\"" }
        ?: nullToken

internal fun String.toSanitizedLogValue(): String =
    buildString(length) {
        this@toSanitizedLogValue.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
