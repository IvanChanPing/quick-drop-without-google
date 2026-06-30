/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import android.Manifest
import android.animation.ObjectAnimator
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.superdrop.R
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import dev.superdrop.protocol.namecard.NameCard
import dev.superdrop.service.radio.RadioHelperClient
import dev.superdrop.service.radio.ShareRadioController

/**
 * **Name Card transfer screen** — the full-screen, NameDrop-style page shown when
 * two phones tap to swap contacts (see `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`).
 * A plain Activity (NO draw-over-other-apps overlay; the tap drags you into the
 * app and it exits when done) that only ever appears while unlocked.
 *
 * Two roles:
 *  - **CLIENT** (the phone that tapped / has the app foreground): runs the BLE
 *    client itself, shows a brief "Connecting…" then the received card with
 *    **Receive Only** / **Share** (Share also sends your card back). Launched by
 *    the reader via [clientIntent].
 *  - **SERVER** (the tapped, idle phone): its card was already served on the tap;
 *    this just shows the received card with **Save** / **Done**. Launched by
 *    [NameCardExchangeService] via [serverIntent] once the peer wrote its card.
 *
 * Saving uses [NameCardSaver] (ContactsContract — direct insert if WRITE_CONTACTS
 * is granted, else the system Add-contact screen), never a vCard import.
 *
 * UI (R.layout.activity_name_card_transfer):
 *  - `nameCardGlow` — a thin tinted bar across the top that fades in/out (tween
 *    loop) to evoke NameDrop's light beam.
 *  - `nameCardAvatar` — large circle with the contact's first initial.
 *  - `nameCardName` / `nameCardPhone` / `nameCardEmail` — the received fields.
 *  - `nameCardConnecting` — "Connecting…" line, shown until the card arrives (client).
 *  - `nameCardPrimary` / `nameCardSecondary` — the two action buttons (labels set per role).
 *
 * Status: compile-only here (no display / BLE / 2 phones). The look + the live
 * exchange are device-verified; the visual design is a first pass to iterate on-device.
 */
internal class NameCardTransferActivity : AppCompatActivity() {
    private var exchange: NameCardBleExchange? = null
    private var localCard: NameCard? = null
    private var peerReceived = false

    /** Forces Bluetooth on for the swap + the 5s helper heartbeat (client role); restored on destroy. */
    private val shareRadios by lazy { ShareRadioController(this, "NameCardTransfer") }
    private val btHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Card awaiting save once the WRITE_CONTACTS prompt returns. */
    private var pendingSaveCard: NameCard? = null

    /**
     * WRITE_CONTACTS request fired on Accept so the card can be saved DIRECTLY
     * (auto, no extra screen). On grant → direct insert; on denial → fall back to
     * the system Add-contact screen (no permission). Then finish either way.
     */
    private val writeContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val card = pendingSaveCard ?: return@registerForActivityResult
            pendingSaveCard = null
            persistCard(card, granted)
            finish()
        }

    private val glow by lazy { findViewById<View>(R.id.nameCardGlow) }
    private val avatar by lazy { findViewById<TextView>(R.id.nameCardAvatar) }
    private val nameView by lazy { findViewById<TextView>(R.id.nameCardName) }
    private val phoneView by lazy { findViewById<TextView>(R.id.nameCardPhone) }
    private val emailView by lazy { findViewById<TextView>(R.id.nameCardEmail) }
    private val connecting by lazy { findViewById<TextView>(R.id.nameCardConnecting) }
    private val cardPanel by lazy { findViewById<View>(R.id.nameCardPanel) }
    private val primary by lazy { findViewById<Button>(R.id.nameCardPrimary) }
    private val secondary by lazy { findViewById<Button>(R.id.nameCardSecondary) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-screen NameDrop look: no action bar / title chrome.
        supportActionBar?.hide()
        setContentView(R.layout.activity_name_card_transfer)
        startGlowLoop()

        when (intent.getStringExtra(EXTRA_ROLE)) {
            ROLE_SERVER -> setupServer()
            else -> setupClient()
        }
    }

    /** SERVER role: the peer card already arrived; just show it + Save/Done. */
    private fun setupServer() {
        val peer = intent.getByteArrayExtra(EXTRA_PEER_CARD)?.let { NameCard.parse(it) }
        if (peer == null) {
            DiagnosticLog.w(TAG, "server screen: no peer card → finish")
            finish()
            return
        }
        showCard(peer)
        primary.text = getString(R.string.name_card_transfer_save)
        primary.setOnClickListener { saveAndFinish(peer) }
        secondary.text = getString(R.string.name_card_transfer_done)
        secondary.setOnClickListener { finish() }
    }

    /** CLIENT role: run the exchange, show Connecting…, then the card + Receive Only / Share. */
    private fun setupClient() {
        val token = intent.getByteArrayExtra(EXTRA_TOKEN)
        if (token == null) {
            finish()
            return
        }
        localCard =
            NameCardResolver(
                storedCard = NameCardProfileStore.from(this)::load,
                deviceSources = AndroidDeviceContactSources(this),
            ).resolve()

        connecting.visibility = View.VISIBLE
        cardPanel.visibility = View.INVISIBLE

        // Force Bluetooth on via the helper (+ 5s heartbeat); start scanning once BT is
        // actually on (immediately if already on, else after a grace for the helper toggle).
        shareRadios.requestRadiosOn(RadioHelperClient.RADIO_BT)
        startClientWhenBtReady(token, attempt = 0)

        // Don't sit on "Connecting…" forever if no peer is found / BLE never connects.
        connecting.postDelayed({
            if (!peerReceived && !isFinishing) {
                DiagnosticLog.w(TAG, "client: connect timeout → no peer")
                connecting.text = getString(R.string.name_card_transfer_failed)
                exchange?.stop()
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private fun startClientWhenBtReady(
        token: ByteArray,
        attempt: Int,
    ) {
        if (isFinishing) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true && attempt < MAX_BT_WAIT_ATTEMPTS) {
            DiagnosticLog.w(TAG, "client: BT not on yet (attempt $attempt) → waiting for helper")
            btHandler.postDelayed({ startClientWhenBtReady(token, attempt + 1) }, BT_GRACE_MS)
            return
        }
        val ble = NameCardBleExchange(this)
        exchange = ble
        val started = ble.startClient(token) { peer -> runOnUiThread { onPeerCardReceived(peer) } }
        if (!started) {
            connecting.text = getString(R.string.name_card_transfer_failed)
        }
    }

    private fun onPeerCardReceived(peer: NameCard) {
        peerReceived = true
        connecting.visibility = View.GONE
        showCard(peer)
        // Share = send my card back too; Receive Only = take theirs only. Both save theirs.
        primary.text = getString(R.string.name_card_transfer_share)
        primary.setOnClickListener {
            localCard?.let { exchange?.shareBack(it) } ?: exchange?.declineShare()
            saveAndFinish(peer)
        }
        secondary.text = getString(R.string.name_card_transfer_receive_only)
        secondary.setOnClickListener {
            exchange?.declineShare()
            saveAndFinish(peer)
        }
    }

    private fun saveAndFinish(card: NameCard) {
        if (NameCardSaver.hasWritePermission(this)) {
            persistCard(card, granted = true)
            finish()
        } else {
            // Ask for WRITE_CONTACTS so we can save directly (auto). The result handler
            // persists + finishes; on denial it falls back to the system Add-contact screen.
            pendingSaveCard = card
            writeContactsPermission.launch(Manifest.permission.WRITE_CONTACTS)
        }
    }

    /** Save [card]: direct ContactsContract insert (off the UI thread) if [granted], else the
     *  system Add-contact screen. */
    private fun persistCard(
        card: NameCard,
        granted: Boolean,
    ) {
        if (granted) {
            // ContactsProvider insert is IPC — off the UI thread (no ANR). applicationContext
            // so it survives this Activity finishing immediately.
            val appCtx = applicationContext
            Thread { NameCardSaver.saveDirect(appCtx, card) }.start()
        } else {
            runCatching { startActivity(NameCardSaver.systemInsertIntent(card)) }
        }
    }

    /** Bind [card] into the panel and play the entrance tween. */
    private fun showCard(card: NameCard) {
        cardPanel.visibility = View.VISIBLE
        avatar.text = (card.displayName ?: card.phoneNumber ?: "?").trim().take(1).uppercase()
        nameView.text = card.displayName ?: getString(R.string.name_card_transfer_no_name)
        bindOptional(phoneView, card.phoneNumber)
        bindOptional(emailView, card.email)
        playEntrance(cardPanel)
    }

    private fun bindOptional(
        view: TextView,
        value: String?,
    ) {
        if (value.isNullOrBlank()) {
            view.visibility = View.GONE
        } else {
            view.visibility = View.VISIBLE
            view.text = value
        }
    }

    /**
     * Entrance = fade + slight rise with an OVERSHOOT easing curve (control point
     * y>1) for a gentle settle — a tween, NOT a physics animation.
     */
    private fun playEntrance(target: View) {
        target.alpha = 0f
        target.translationY = ENTRANCE_RISE_PX
        val overshoot = PathInterpolator(0.2f, 0.9f, 0.3f, OVERSHOOT_Y)
        ObjectAnimator.ofFloat(target, View.ALPHA, 0f, 1f).apply {
            duration = ENTRANCE_MS
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, ENTRANCE_RISE_PX, 0f).apply {
            duration = ENTRANCE_MS
            interpolator = overshoot
            start()
        }
    }

    /** Top "light beam" glow: a looping fade in/out via a tween (no physics spec). */
    private fun startGlowLoop() {
        ObjectAnimator.ofFloat(glow, View.ALPHA, GLOW_MIN, GLOW_MAX).apply {
            duration = GLOW_MS
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    override fun onDestroy() {
        btHandler.removeCallbacksAndMessages(null)
        exchange?.stop()
        exchange = null
        // Stop the heartbeat + restore radios the helper turned on for this swap.
        shareRadios.restoreRadios()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NameCardTransfer"
        private const val EXTRA_ROLE = "role"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PEER_CARD = "peer_card"
        private const val ROLE_CLIENT = "client"
        private const val ROLE_SERVER = "server"

        private const val ENTRANCE_MS = 320L
        private const val ENTRANCE_RISE_PX = 64f
        private const val OVERSHOOT_Y = 1.12f
        private const val GLOW_MS = 1100L
        private const val GLOW_MIN = 0.35f
        private const val GLOW_MAX = 1.0f
        private const val CONNECT_TIMEOUT_MS = 18_000L
        private const val BT_GRACE_MS = 1_500L
        private const val MAX_BT_WAIT_ATTEMPTS = 2

        /** Reader side: open the screen to run the BLE client for [token]. */
        fun clientIntent(
            context: Context,
            token: ByteArray,
        ): Intent =
            Intent(context, NameCardTransferActivity::class.java)
                .putExtra(EXTRA_ROLE, ROLE_CLIENT)
                .putExtra(EXTRA_TOKEN, token)

        /** Card side: open the screen to show the [peerCard] the tapper sent back. */
        fun serverIntent(
            context: Context,
            peerCard: NameCard,
        ): Intent =
            Intent(context, NameCardTransferActivity::class.java)
                .putExtra(EXTRA_ROLE, ROLE_SERVER)
                .putExtra(EXTRA_PEER_CARD, peerCard.serialize())
    }
}
