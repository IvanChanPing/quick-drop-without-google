/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.superdrop.R
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import dev.superdrop.nfc.NameCardNdef
import dev.superdrop.protocol.namecard.NameCard
import dev.superdrop.service.radio.RadioHelperClient
import dev.superdrop.service.radio.ShareRadioController

/**
 * **Name Card transfer screen** — the full-screen, NameDrop-style page shown when
 * two phones tap to swap contacts (see `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`).
 *
 * The card look + choreography here is the design that was perfected in the
 * standalone `namecard-tester` app and dropped in verbatim (minus that tester's
 * live-tuning TUNE button / sliders — the tuned values are baked as the constants
 * in [Anim] below). Nothing about the tap→BLE→save engine changed; only the
 * entrance / exit / ripple visuals were swapped in.
 *
 * **Window:** launched under `Theme.SuperDrop.NameCardTransfer` (translucent, no dim,
 * no window animation) + [overrideOpenTransition] so the card floats OVER whatever
 * screen you were on (home / another app) and the ONLY motion is the view-level
 * animation below — exactly like the tester over the home screen.
 *
 * **The card = one unit.** `nameCardCard` (R.layout.activity_name_card_transfer)
 * wraps the avatar + name + phone + email AND the two action buttons, so they
 * descend + expand together on entry and (on Share) fly up + shrink together.
 *
 * **Choreography:**
 *  - PRE-ENTRANCE RIPPLE ([playTriggerRipple]) — the AirDrop glow/wave shader on a
 *    transparent full-screen layer over the previous screen, as the "tap happened"
 *    cue (API 33+; skipped otherwise). Then the card enters.
 *  - ENTRANCE ([twoPhaseEntrance]) — the small card DESCENDS (decelerate) to a stop,
 *    then EXPANDS in place (accelerate) around a near-top pivot.
 *  - SHARE = reverse-exit ([reverseExit]) — the card RISES + SHRINKS off the top,
 *    then a glow ripple, then the contact opens.
 *  - RECEIVE ONLY / SAVE = send ripple ([playSendRipple]) — the AirDrop "suck the
 *    card up into the island" effect on a flipped snapshot, then the contact opens.
 *
 * Two roles:
 *  - **CLIENT** (the phone that tapped / has the app foreground): runs the BLE
 *    client, shows "Connecting…" then the received card with **Receive Only** /
 *    **Share** (Share also sends your card back). Launched via [clientIntent].
 *  - **SERVER** (the tapped, idle phone): its card was already served on the tap;
 *    shows the received card with **Save** / **Done**. Launched by
 *    [NameCardExchangeService] via [serverIntent] once the peer wrote its card.
 *
 * Saving uses [NameCardSaver] (ContactsContract — direct insert if WRITE_CONTACTS is
 * granted, else the system Add-contact screen), never a vCard import.
 *
 * Status: compile-verified on this box; the live BLE exchange + contact save are
 * device-verified from the prior build, but the NEW animation/ripple visuals are
 * GPU/UI-UNVERIFIED here (no display / device) — the user device-tests the look.
 */
internal class NameCardTransferActivity : AppCompatActivity() {
    private var exchange: NameCardBleExchange? = null
    private var localCard: NameCard? = null
    private var peerReceived = false

    /** Guards against a second Share/Receive/Save tap re-running the exit + save. */
    private var committed = false

    // ---- Name Card v2 (symmetric consent) ----

    /** The live consent session (from [NameCardLinkHolder]); non-null only in a v2 session. */
    private var v2Session: NameCardLinkHolder.Session? = null

    /** The peer's card once it arrives, so [NameCardConsentMachine.Effect.SaveCardAndRipple] can save it. */
    private var v2PeerCard: NameCard? = null

    /** True once a terminal §8 state has been rendered, so effects/back-taps don't re-render it. */
    private var v2TerminalShown = false

    /** True while the consent heads-up notification is posted (so it's cancelled exactly once). */
    private var v2NotificationShown = false

    /**
     * Bridges the live [NameCardLinkHolder.Session] to this screen. The holder already delivers on the
     * main thread; the extra [runOnUiThread] is a cheap safety net.
     */
    private val v2Observer =
        object : NameCardLinkHolder.UiObserver {
            override fun onReady() = runOnUiThread { onV2Ready() }

            override fun onConsentEffect(effect: NameCardConsentMachine.Effect) =
                runOnUiThread { onV2Effect(effect) }

            override fun onPeerCard(card: NameCard) = runOnUiThread { v2PeerCard = card }

            override fun onLegacyPeer() = runOnUiThread { onV2LegacyPeer() }
        }

    /** Forces Bluetooth on for the swap + the 5s helper heartbeat (client role); restored on destroy. */
    private val shareRadios by lazy { ShareRadioController(this, "NameCardTransfer") }
    private val btHandler = Handler(Looper.getMainLooper())
    private val ui = Handler(Looper.getMainLooper())

    /** Card awaiting save once the WRITE_CONTACTS prompt returns. */
    private var pendingSaveCard: NameCard? = null

    /**
     * Contacts permissions fired on Accept: WRITE_CONTACTS to save directly (auto,
     * no extra screen) + READ_CONTACTS so the saved contact can be opened in the
     * Contacts app afterwards.
     */
    private val contactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val card = pendingSaveCard ?: return@registerForActivityResult
            pendingSaveCard = null
            persistCard(card, granted = result[Manifest.permission.WRITE_CONTACTS] == true)
        }

    private val root by lazy { findViewById<FrameLayout>(R.id.nameCardRoot) }

    /** nameCardCard — the whole card (avatar/name/fields + buttons); the single animated unit. */
    private val card by lazy { findViewById<View>(R.id.nameCardCard) }
    private val glow by lazy { findViewById<View>(R.id.nameCardGlow) }
    private val avatar by lazy { findViewById<TextView>(R.id.nameCardAvatar) }
    private val nameView by lazy { findViewById<TextView>(R.id.nameCardName) }
    private val phoneView by lazy { findViewById<TextView>(R.id.nameCardPhone) }
    private val emailView by lazy { findViewById<TextView>(R.id.nameCardEmail) }
    private val connecting by lazy { findViewById<TextView>(R.id.nameCardConnecting) }
    private val primary by lazy { findViewById<Button>(R.id.nameCardPrimary) }
    private val secondary by lazy { findViewById<Button>(R.id.nameCardSecondary) }

    /** nameCardDone — full-width blue pill at the bottom, top-level sibling of the card; shown only on
     *  a v2 terminal state (declined / no response / failed) and closes the screen. */
    private val done by lazy { findViewById<Button>(R.id.nameCardDone) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overrideOpenTransition() // kill the OEM window-open anim; only our view entrance plays
        // Full-screen NameDrop look: no action bar / title chrome.
        supportActionBar?.hide()
        setContentView(R.layout.activity_name_card_transfer)
        startGlowLoop()

        val v2 = NameCardPreferences.from(this).isV2Enabled()
        when {
            // v2 server: launched by the service at tap; attach to the live consent session.
            intent.getStringExtra(EXTRA_ROLE) == ROLE_SERVER_V2 -> setupServerV2()
            // v2 reader-side wake: AAR-launched with the token in the NDEF → run the consent client.
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED && v2 -> setupClientV2FromNdef()
            // Legacy reader-side wake (v2 off): the token is inside the NDEF, not an Intent extra.
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED -> setupClientFromNdef()
            intent.getStringExtra(EXTRA_ROLE) == ROLE_SERVER -> setupServer()
            else -> setupClient()
        }
    }

    /**
     * Reader-side wake path (Name Card v2). Extract the rendezvous token from the AAR-carrying NDEF
     * the OS delivered, then run the exact same BLE client exchange as the legacy foreground-reader
     * path. Null token (someone else's tag / malformed) → finish, logged (never silent).
     */
    private fun setupClientFromNdef() {
        val token = readNameCardTokenFromIntent()
        if (token == null) {
            DiagnosticLog.w(TAG, "NDEF launch: no Name Card token in message → finish")
            finish()
            return
        }
        DiagnosticLog.w(TAG, "NDEF launch: Name Card token (${token.size}B) → client exchange")
        setupClientWithToken(token)
    }

    /** Pull the first Name Card rendezvous token out of the OS-delivered NDEF messages, or null. */
    private fun readNameCardTokenFromIntent(): ByteArray? {
        @Suppress("DEPRECATION")
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        for (parcelable in raw) {
            val msg = parcelable as? NdefMessage ?: continue
            NameCardNdef.parseToken(msg)?.let { return it }
        }
        return null
    }

    /**
     * Suppress the OS/OEM window open animation (e.g. OxygenOS launcher zoom) so the
     * ONLY motion is the view-level [twoPhaseEntrance]. Belt-and-suspenders with the
     * theme's windowAnimationStyle=@null.
     */
    private fun overrideOpenTransition() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    /** SERVER role: the peer card already arrived; play the tap-cue ripple, show it + Save/Done. */
    private fun setupServer() {
        val peer = intent.getByteArrayExtra(EXTRA_PEER_CARD)?.let { NameCard.parse(it) }
        if (peer == null) {
            DiagnosticLog.w(TAG, "server screen: no peer card → finish")
            finish()
            return
        }
        bindCard(peer)
        primary.text = getString(R.string.name_card_transfer_save)
        // SAVE = take their card. Send ripple (card sucked up = saved), then open the contact.
        primary.setOnClickListener { commitWithSendRipple(primary) { saveAndFinish(peer) } }
        secondary.text = getString(R.string.name_card_transfer_done)
        // DONE = dismiss without saving. Reverse-exit the card, then close.
        secondary.setOnClickListener { commitWithReverseExit(secondary) { finish() } }
        // Tap-cue ripple over the previous screen, THEN the card enters.
        playTriggerRipple { revealAndEnter() }
    }

    /** CLIENT role (legacy foreground-reader path): token arrives as an Intent extra. */
    private fun setupClient() {
        val token = intent.getByteArrayExtra(EXTRA_TOKEN)
        if (token == null) {
            finish()
            return
        }
        setupClientWithToken(token)
    }

    /**
     * Run the BLE client exchange for [token]: resolve our own card, show Connecting…, scan+connect
     * once Bluetooth is on, and time out gracefully. Shared by both client entry points — the legacy
     * foreground-reader ([setupClient]) and the Name Card v2 NDEF wake ([setupClientFromNdef]).
     */
    private fun setupClientWithToken(token: ByteArray) {
        localCard =
            NameCardResolver(
                storedCard = NameCardProfileStore.from(this)::load,
                deviceSources = AndroidDeviceContactSources(this),
            ).resolve()

        connecting.visibility = View.VISIBLE
        card.visibility = View.INVISIBLE

        // Force Bluetooth on via the helper (+ 5s heartbeat); start scanning once BT is on.
        shareRadios.requestRadiosOn(RadioHelperClient.RADIO_BT)
        startClientWhenBtReady(token, attempt = 0)

        // Play the tap-cue ripple over the previous screen while we connect (fire-and-forget;
        // the card's own entrance plays when the peer card actually arrives).
        playTriggerRipple {}

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
        bindCard(peer)
        revealAndEnter()
        // Share = send my card back too; Receive Only = take theirs only. Both save theirs.
        primary.text = getString(R.string.name_card_transfer_share)
        primary.setOnClickListener {
            // Fire the BLE share-back immediately (don't wait on the exit animation), then
            // reverse-exit the card → glow ripple → open the saved contact (the tester's Share flow).
            localCard?.let { exchange?.shareBack(it) } ?: exchange?.declineShare()
            commitWithReverseExit(primary) { playTriggerRipple { saveAndFinish(peer) } }
        }
        secondary.text = getString(R.string.name_card_transfer_receive_only)
        secondary.setOnClickListener {
            exchange?.declineShare()
            commitWithSendRipple(secondary) { saveAndFinish(peer) }
        }
    }

    // ================= Name Card v2 (symmetric consent) UI =================
    // Reuses the SAME screen + animations as v1. Both v2 roles show OWN card + [Share][Receive Only];
    // the buttons stay disabled ("connecting") until the link is ready. Effects from the shared
    // NameCardConsentMachine (via NameCardLinkHolder) drive the waiting / declined / no-response
    // states on the EXISTING views — no new layout. See docs/NAMECARD_V2_EXECUTOR_PLAN.md B5.

    /** v2 SERVER: attach to the live session the service started at tap and render the consent screen. */
    private fun setupServerV2() {
        val session = NameCardLinkHolder.current()
        if (session == null) {
            DiagnosticLog.w(TAG, "serverV2 screen: no live session → finish")
            finish()
            return
        }
        v2Session = session
        session.peerCard?.let { v2PeerCard = it }
        localCard = session.localCard
        attachAndShowV2()
    }

    /** v2 CLIENT (AAR wake): start the consent client for the NDEF token, then render the same screen. */
    private fun setupClientV2FromNdef() {
        val token = readNameCardTokenFromIntent()
        if (token == null) {
            DiagnosticLog.w(TAG, "NDEF v2: no Name Card token → finish")
            finish()
            return
        }
        localCard =
            NameCardResolver(
                storedCard = NameCardProfileStore.from(this)::load,
                deviceSources = AndroidDeviceContactSources(this),
            ).resolve()
        val ble = NameCardBleExchange(this)
        exchange = ble
        v2Session = NameCardLinkHolder.startSession(ble, NameCardLinkHolder.Role.CLIENT, localCard)
        attachAndShowV2()
        shareRadios.requestRadiosOn(RadioHelperClient.RADIO_BT)
        startClientWhenBtReadyV2(ble, token, attempt = 0)
    }

    private fun startClientWhenBtReadyV2(
        ble: NameCardBleExchange,
        token: ByteArray,
        attempt: Int,
    ) {
        if (isFinishing) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true && attempt < MAX_BT_WAIT_ATTEMPTS) {
            btHandler.postDelayed({ startClientWhenBtReadyV2(ble, token, attempt + 1) }, BT_GRACE_MS)
            return
        }
        val session = v2Session ?: return
        if (!ble.startClientV2(token, session)) {
            v2ShowTerminal(getString(R.string.name_card_transfer_failed))
        }
    }

    /** Shared v2 screen setup: bind our card, wire the two buttons (disabled until ready), start the 30s timer. */
    private fun attachAndShowV2() {
        v2Session?.uiObserver = v2Observer
        localCard?.let { bindCard(it) }
        connecting.visibility = View.GONE
        // nameCardPrimary → "Share"; nameCardSecondary → "Receive Only". Disabled until onV2Ready().
        primary.text = getString(R.string.name_card_transfer_share)
        secondary.text = getString(R.string.name_card_transfer_receive_only)
        primary.isEnabled = false
        secondary.isEnabled = false
        primary.setOnClickListener { onV2LocalChoice(share = true) }
        secondary.setOnClickListener { onV2LocalChoice(share = false) }
        playTriggerRipple { revealAndEnter() }
        // 30s no-response timer (the machine has none of its own).
        ui.postDelayed({ v2Session?.onTimeout() }, V2_TIMEOUT_MS)
    }

    /** Link is ready to carry a choice → enable the buttons. */
    private fun onV2Ready() {
        if (v2TerminalShown) return
        primary.isEnabled = true
        secondary.isEnabled = true
        DiagnosticLog.w(TAG, "v2: link ready → buttons enabled")
    }

    /** A choice was tapped: pop the button, disable both, feed the machine. */
    private fun onV2LocalChoice(share: Boolean) {
        if (committed) return
        committed = true
        primary.isEnabled = false
        secondary.isEnabled = false
        pressAnim(if (share) primary else secondary)
        val session = v2Session ?: return
        if (share) session.onLocalShare() else session.onLocalReceiveOnly()
    }

    /** Render a machine effect on the existing views. BLE effects (SendChoice/TransmitCard) are the holder's. */
    private fun onV2Effect(effect: NameCardConsentMachine.Effect) {
        DiagnosticLog.w(TAG, "v2 effect: $effect")
        when (effect) {
            // Buttons already disabled after the tap; waiting is conveyed by that + the heads-up.
            NameCardConsentMachine.Effect.ShowWaiting -> Unit
            NameCardConsentMachine.Effect.ShowHeadsUpWaiting ->
                v2HeadsUp(getString(R.string.name_card_consent_headsup_waiting))
            NameCardConsentMachine.Effect.SaveCardAndRipple -> v2SaveReceived()
            NameCardConsentMachine.Effect.UpdateHeadsUpDeclined -> {
                v2HeadsUp(getString(R.string.name_card_consent_headsup_declined))
                v2ShowTerminal(getString(R.string.name_card_transfer_declined))
            }
            NameCardConsentMachine.Effect.FadeToDeclined ->
                v2ShowTerminal(getString(R.string.name_card_transfer_declined))
            NameCardConsentMachine.Effect.ShowNoResponse ->
                v2ShowTerminal(getString(R.string.name_card_transfer_no_response))
            NameCardConsentMachine.Effect.CloseLink -> v2CancelHeadsUp()
            else -> Unit // SendChoice / TransmitCard handled by the holder → BLE
        }
    }

    /** Peer shared: their card arrived — play the receive ripple, save it, open the contact. */
    private fun v2SaveReceived() {
        v2CancelHeadsUp()
        val peer = v2PeerCard
        if (peer == null) {
            DiagnosticLog.w(TAG, "v2: SaveCardAndRipple but no peer card yet")
            return
        }
        if (v2TerminalShown) return
        v2TerminalShown = true
        playSendRipple()
        ui.postDelayed({ if (!isFinishing) saveAndFinish(peer) }, Anim.SENT_RIPPLE_MS + Anim.AUTO_OPEN_DELAY_MS)
    }

    /**
     * Terminal §8 state: fade the whole card out (300ms tween), show [message] centered
     * (nameCardConnecting), and raise the full-width Done (nameCardDone). Both the message and Done
     * are top-level siblings of the card, so they render even if the card was never revealed.
     */
    private fun v2ShowTerminal(message: String) {
        if (v2TerminalShown) return
        v2TerminalShown = true
        v2CancelHeadsUp()
        // Fade the whole card out and raise the terminal overlay — the centered message
        // (nameCardConnecting) and the full-width Done (nameCardDone) are BOTH top-level siblings of
        // the card, so they render regardless of whether the card entrance ever ran (e.g. a
        // connect-failure terminal before reveal).
        if (card.visibility == View.VISIBLE) {
            card.animate()
                .alpha(0f)
                .setDuration(DECLINE_FADE_MS)
                .withEndAction { card.visibility = View.INVISIBLE }
                .start()
        } else {
            card.visibility = View.INVISIBLE
        }
        connecting.text = message
        connecting.visibility = View.VISIBLE
        done.visibility = View.VISIBLE
        done.setOnClickListener { finish() }
    }

    /** Post / update the consent heads-up ("Waiting for the other person…" → "They declined…"). */
    private fun v2HeadsUp(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            DiagnosticLog.w(TAG, "v2: POST_NOTIFICATIONS not granted → skip heads-up (in-app state still shows)")
            return
        }
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CONSENT_CHANNEL_ID,
                    getString(R.string.name_card_consent_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val notification =
            NotificationCompat.Builder(this, CONSENT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.nfc_namecard_hce_service_description))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .build()
        mgr.notify(CONSENT_NOTIFICATION_ID, notification)
        v2NotificationShown = true
    }

    private fun v2CancelHeadsUp() {
        if (!v2NotificationShown) return
        getSystemService(NotificationManager::class.java)?.cancel(CONSENT_NOTIFICATION_ID)
        v2NotificationShown = false
    }

    /** Peer only speaks v1 — log; the receive still works (its card write/read reaches the machine). */
    private fun onV2LegacyPeer() {
        DiagnosticLog.w(TAG, "v2: legacy peer — falling back to a plain receive (device-tuned)")
    }

    // ---- entrance / exit / ripple (ported from namecard-tester, values baked in [Anim]) ----

    /** Make the card visible and play the two-phase entrance once it has been laid out. */
    private fun revealAndEnter() {
        card.visibility = View.VISIBLE
        card.post { twoPhaseEntrance(card) }
    }

    /**
     * TWO-PHASE entrance: the small card DESCENDS (decelerate) from above to a stop
     * point, then EXPANDS in place from the start scale to full size (accelerate,
     * factor [Anim.EXPAND_EASE]) around a near-top pivot ([Anim.EXPAND_PIVOT_Y_FRAC]).
     * No overlap between the two phases. A tween, NOT a physics bounce.
     */
    private fun twoPhaseEntrance(v: View) {
        val screenH = v.rootView.height
        val h = v.height.toFloat()
        val restCenterY = h / 2f
        val fromY = Anim.START_CENTER_Y_FRAC * screenH - restCenterY // start above the top
        val stopY = Anim.STOP_FRAC * screenH - restCenterY // phase-1 stop (0.5 → 0 = centered rest)
        val s0 = Anim.SCALE_FROM
        v.pivotX = v.width * 0.5f
        v.pivotY = h * Anim.EXPAND_PIVOT_Y_FRAC
        v.scaleX = s0
        v.scaleY = s0
        v.translationY = fromY
        v.alpha = 1f
        // PHASE 1 — descend and slow to a stop (no scaling yet).
        ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, fromY, stopY).apply {
            duration = Anim.DESCENT_MS
            interpolator = DecelerateInterpolator()
            start()
        }
        // PHASE 2 — after the descent, expand in place from s0 to full size.
        val ease = AccelerateInterpolator(Anim.EXPAND_EASE.coerceAtLeast(0.1f))
        ObjectAnimator.ofFloat(v, View.SCALE_X, s0, 1f).apply {
            startDelay = Anim.DESCENT_MS
            duration = Anim.EXPAND_MS
            interpolator = ease
            start()
        }
        ObjectAnimator.ofFloat(v, View.SCALE_Y, s0, 1f).apply {
            startDelay = Anim.DESCENT_MS
            duration = Anim.EXPAND_MS
            interpolator = ease
            start()
        }
    }

    /**
     * SHARE reverse-exit: the card RISES UP and SHRINKS to a point off the top of the
     * screen (retraces the entrance start pos+scale) over [Anim.SHARE_EXIT_MS] with an
     * accelerate (leave-fast) easing; on end hides the card and runs [onEnd].
     */
    private fun reverseExit(
        v: View,
        onEnd: () -> Unit,
    ) {
        val screenH = v.rootView.height
        val h = v.height.toFloat()
        val restCenterY = h / 2f
        val toY = Anim.START_CENTER_Y_FRAC * screenH - restCenterY
        val s1 = Anim.SCALE_FROM
        v.pivotX = v.width * 0.5f
        v.pivotY = h * 0.5f
        val ease = AccelerateInterpolator(1.5f)
        ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, v.translationY, toY).apply {
            duration = Anim.SHARE_EXIT_MS
            interpolator = ease
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        v.visibility = View.INVISIBLE
                        onEnd()
                    }
                },
            )
            start()
        }
        ObjectAnimator.ofFloat(v, View.SCALE_X, v.scaleX, s1).apply {
            duration = Anim.SHARE_EXIT_MS
            interpolator = ease
            start()
        }
        ObjectAnimator.ofFloat(v, View.SCALE_Y, v.scaleY, s1).apply {
            duration = Anim.SHARE_EXIT_MS
            interpolator = ease
            start()
        }
    }

    /** Commit path A: [tapped] button pop → reverse-exit the card → [after]. Guarded against double taps. */
    private fun commitWithReverseExit(
        tapped: View,
        after: () -> Unit,
    ) {
        if (committed) return
        committed = true
        pressAnim(tapped)
        ui.postDelayed({
            if (!isFinishing) reverseExit(card) { if (!isFinishing) after() }
        }, Anim.BUTTON_PRESS_MS)
    }

    /** Commit path B: [tapped] button pop → send (suck-up) ripple → [after]. Guarded against double taps. */
    private fun commitWithSendRipple(
        tapped: View,
        after: () -> Unit,
    ) {
        if (committed) return
        committed = true
        pressAnim(tapped)
        ui.postDelayed({
            if (isFinishing) return@postDelayed
            playSendRipple()
            ui.postDelayed({ if (!isFinishing) after() }, Anim.SENT_RIPPLE_MS + Anim.AUTO_OPEN_DELAY_MS)
        }, Anim.BUTTON_PRESS_MS)
    }

    /**
     * PRE-ENTRANCE ripple (INSTANCE 1) — the AirDrop glow/wave shader drawn on a
     * TRANSPARENT full-screen layer over the previous screen (bgAlpha=0 → only the
     * additive glow shows; the screen behind stays visible). Always calls [onDone]
     * whether or not the ripple actually plays (API 33+), so the flow never stalls.
     */
    private fun playTriggerRipple(onDone: () -> Unit) {
        var started = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sh = RuntimeShader(AGSL)
                applyRippleUniforms(sh)
                sh.setFloatUniform("bgAlpha", 0f)
                val transparentPx = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                sh.setInputShader(
                    "layer",
                    BitmapShader(transparentPx, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                )
                val rv = RippleBgView(this, sh)
                rv.layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                root.addView(rv)
                ValueAnimator.ofFloat(0f, 2f).apply {
                    duration = Anim.TRIGGER_GLOW_MS
                    addUpdateListener {
                        sh.setFloatUniform("t", it.animatedValue as Float)
                        rv.invalidate()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                root.removeView(rv)
                                onDone()
                            }
                        },
                    )
                    start()
                }
                started = true
            }
        }
        if (!started) onDone()
    }

    /**
     * SEND ripple (INSTANCE 2) — AirDrop "suck the card up into the island". NON-FLIPPING:
     * snapshot the card to a bitmap, flip it vertically (cancels the shader's own Y-flip),
     * run the shader on an ImageView of that bitmap; the real card is hidden while it plays.
     * API 33+; a no-op otherwise (the caller's [after] still runs on its timer).
     */
    private fun playSendRipple() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (card.width <= 0 || card.height <= 0) return
        runCatching {
            val w = card.width
            val h = card.height
            val snap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            card.draw(Canvas(snap))
            val flip = Matrix().apply { preScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(snap, 0, 0, w, h, flip, true)
            val iv = ImageView(this)
            iv.layoutParams =
                FrameLayout.LayoutParams(w, h).apply {
                    leftMargin = card.x.toInt()
                    topMargin = card.y.toInt()
                }
            iv.setImageBitmap(flipped)
            root.addView(iv)
            card.visibility = View.INVISIBLE
            val sh = RuntimeShader(AGSL)
            sh.setFloatUniform("viewSize", w.toFloat(), h.toFloat())
            applyRippleUniforms(sh)
            sh.setFloatUniform("bgAlpha", 1f) // opaque: acts on the card snapshot
            ValueAnimator.ofFloat(0f, 2f).apply {
                duration = Anim.SENT_RIPPLE_MS
                addUpdateListener {
                    sh.setFloatUniform("t", it.animatedValue as Float)
                    iv.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "layer"))
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            iv.setRenderEffect(null)
                            root.removeView(iv)
                        }
                    },
                )
                start()
            }
        }
    }

    /** Apply the (baked) AirDrop-ripple split uniforms shared by both ripple instances. */
    private fun applyRippleUniforms(sh: RuntimeShader) {
        sh.setFloatUniform("glowXBias", Anim.R_GLOW_X_BIAS)
        sh.setFloatUniform("stretchAmt", Anim.R_STRETCH)
        sh.setFloatUniform("waveYOffset", Anim.R_WAVE_Y)
        sh.setFloatUniform("waveXBias", Anim.R_WAVE_X)
        sh.setFloatUniform("glowMoveYShift", Anim.R_GLOW_MOVE_Y)
        sh.setFloatUniform("glowMoveSize", Anim.R_GLOW_MOVE_SIZE)
        sh.setFloatUniform("glowCircleAmt", Anim.R_GLOW_CIRCLE)
    }

    /** Button tap feedback: a quick pop-EXPAND (scale up past 1 and back). */
    private fun pressAnim(v: View) {
        val sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, Anim.BUTTON_PRESS_SCALE, 1f)
        val sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, Anim.BUTTON_PRESS_SCALE, 1f)
        ObjectAnimator.ofPropertyValuesHolder(v, sx, sy).apply {
            duration = Anim.BUTTON_PRESS_MS
            start()
        }
    }

    /**
     * Full-screen View that DRAWS the ripple RuntimeShader via a Paint (renders real
     * pixels over the translucent window; reads its own size each frame so there's no
     * first-frame size-gate). Used for the pre-entrance over-background ripple cue.
     */
    private class RippleBgView(context: Context, private val sh: RuntimeShader) : View(context) {
        private val paint =
            Paint().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shader = sh
            }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            sh.setFloatUniform("viewSize", w.toFloat(), h.toFloat())
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    private fun saveAndFinish(card: NameCard) {
        if (NameCardSaver.hasWritePermission(this)) {
            persistCard(card, granted = true)
        } else {
            pendingSaveCard = card
            contactsPermission.launch(
                arrayOf(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS),
            )
        }
    }

    /**
     * Save [card] and finish. With permission: a direct ContactsContract insert off
     * the UI thread, then OPEN the saved contact in the Contacts app. Without: the
     * system Add-contact screen. [persistCard] owns the finish in every branch.
     */
    private fun persistCard(
        card: NameCard,
        granted: Boolean,
    ) {
        if (!granted) {
            runCatching { startActivity(NameCardSaver.systemInsertIntent(card)) }
            finish()
            return
        }
        val appCtx = applicationContext
        Thread {
            val contactUri = NameCardSaver.saveDirect(appCtx, card)
            runOnUiThread {
                contactUri?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, it)) } }
                finish()
            }
        }.start()
    }

    /** Bind [card]'s fields into the panel (card left INVISIBLE until [revealAndEnter]). */
    private fun bindCard(card: NameCard) {
        avatar.text = (card.displayName ?: card.phoneNumber ?: "?").trim().take(1).uppercase()
        nameView.text = card.displayName ?: getString(R.string.name_card_transfer_no_name)
        bindOptional(phoneView, card.phoneNumber)
        bindOptional(emailView, card.email)
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
        ui.removeCallbacksAndMessages(null)
        v2CancelHeadsUp()
        // Detach from the live session so we don't leak the activity; the client-side exchange is
        // ours to stop (below), the server-side one is owned by NameCardExchangeService.
        v2Session?.uiObserver = null
        v2Session = null
        exchange?.stop()
        exchange = null
        shareRadios.restoreRadios()
        super.onDestroy()
    }

    /**
     * Baked animation constants — the values tuned in the `namecard-tester` app
     * (its live TUNE sliders are intentionally NOT ported). Times in ms.
     */
    private object Anim {
        // Two-phase entrance / reverse-exit (from the liked tester screenshot).
        const val START_CENTER_Y_FRAC = -0.3f // card center starts this frac of screen height (above top)
        const val SCALE_FROM = 0.09f // start scale (proportional miniature)
        const val STOP_FRAC = 0.5f // phase-1 stop: center at 0.5 → centered full-screen rest
        const val DESCENT_MS = 500L
        const val EXPAND_MS = 700L
        const val EXPAND_EASE = 1.3f // AccelerateInterpolator factor
        const val EXPAND_PIVOT_Y_FRAC = 0.08f // expansion origin near the TOP of the card
        const val SHARE_EXIT_MS = 500L

        // Ripples.
        const val TRIGGER_GLOW_MS = 1100L
        const val SENT_RIPPLE_MS = 1200L
        const val AUTO_OPEN_DELAY_MS = 200L

        // Split AirDrop-ripple uniforms (defaults = the verbatim iOS look).
        const val R_GLOW_X_BIAS = 0f
        const val R_STRETCH = 1f
        const val R_WAVE_Y = 0.46f
        const val R_WAVE_X = 0f
        const val R_GLOW_MOVE_Y = -0.55f
        const val R_GLOW_MOVE_SIZE = 1f
        const val R_GLOW_CIRCLE = 1f

        // Button feedback.
        const val BUTTON_PRESS_MS = 200L
        const val BUTTON_PRESS_SCALE = 1.1f
    }

    companion object {
        private const val TAG = "NameCardTransfer"
        private const val EXTRA_ROLE = "role"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_PEER_CARD = "peer_card"
        private const val ROLE_CLIENT = "client"
        private const val ROLE_SERVER = "server"
        private const val ROLE_SERVER_V2 = "server_v2"

        private const val GLOW_MS = 1100L
        private const val GLOW_MIN = 0.35f
        private const val GLOW_MAX = 1.0f
        private const val CONNECT_TIMEOUT_MS = 18_000L
        private const val BT_GRACE_MS = 1_500L
        private const val MAX_BT_WAIT_ATTEMPTS = 2

        // Name Card v2.
        private const val V2_TIMEOUT_MS = 30_000L
        private const val DECLINE_FADE_MS = 300L
        private const val CONSENT_CHANNEL_ID = "namecard_consent"
        private const val CONSENT_NOTIFICATION_ID = 4311

        /**
         * The AirDrop "suck into the Dynamic Island" ripple — VERBATIM AGSL from the
         * airdrop-ripple-demo / namecard-tester. `bgAlpha` 1 = opaque (on the card
         * snapshot); 0 = transparent except the glow (over the previous screen).
         */
        private val AGSL =
            """
            uniform shader layer;
            uniform float  t;
            uniform float2 viewSize;
            uniform float  glowXBias;
            uniform float  stretchAmt;
            uniform float  waveYOffset;
            uniform float  waveXBias;
            uniform float  glowMoveYShift;
            uniform float  glowMoveSize;
            uniform float  glowCircleAmt;
            uniform float  bgAlpha;

            half4 main(float2 position) {
                float2 position_yflip = float2(position.x, viewSize.y - position.y);
                float uv_y_dynamic_island_offset = 0.46;

                float t2 = pow(t, 2.0);
                float t3 = pow(t, 3.0);

                float2 uv = position_yflip / viewSize;
                float2 uv_stretch = float2(uv.x+((uv.x-0.5)*pow(uv.y,6.0)*t3*0.1*stretchAmt), uv.y * (uv.y * pow((1.0-(t2*0.01)), 8.0)) + (1.0-uv.y) * uv.y);
                uv_stretch = mix(uv, uv_stretch, smoothstep(1.1, 1.0, t));
                float4 color = float4(layer.eval(uv_stretch * viewSize));

                float2 bang_offset = float2(0.0);
                float bang_d = 0.0;
                if (t >= 1.0) {
                    float aT = t - 1.0;
                    float2 uv2 = uv;
                    uv2 -= 0.5;
                    uv2.x *= viewSize.x / viewSize.y;
                    uv2.x -= waveXBias;

                    float2 uv_bang = float2(uv2.x, uv2.y);
                    float2 uv_bang_origin = float2(uv_bang.x, uv_bang.y-waveYOffset);
                    bang_d = (aT*0.16)/length(uv_bang_origin);
                    bang_d = smoothstep(0.09, 0.05, bang_d) * smoothstep(0.04, 0.07, bang_d) * (uv.y+0.05);
                    bang_offset = float2(-8.0*bang_d*uv2.x, -4.0*bang_d*(uv2.y-0.4))*0.1;

                    float bang_d2 = ((aT-0.085) * 0.14)/length(uv_bang_origin);
                    bang_d2 = smoothstep(0.09, 0.05, bang_d2) * smoothstep(0.04, 0.07, bang_d2) * (uv.y+0.05);
                    bang_offset += float2(-8.0*bang_d2*uv2.x, -4.0*bang_d2*(uv2.y-0.4))*-0.02;
                }

                float2 uv_stretch_bang = uv_stretch+bang_offset;
                color = float4(layer.eval(uv_stretch_bang * viewSize));
                color += bang_d*500.0 * smoothstep(1.05, 1.1, t);

                const float Pi = 6.28318530718 * 2.0;
                const float Directions = 60.0;
                const float Quality = 10.0;
                float Radius = t2*0.1 * pow(uv.y, 6.0) * 0.5;
                Radius *= smoothstep(1.3, 0.9, t);
                Radius += bang_d*0.05;
                for ( float d=0.0; d<Pi; d+=Pi/Directions )
                {
                    for ( float i=1.0/Quality; i<=1.0; i+=1.0/Quality )
                    {
                        float2 blurPos = (uv_stretch_bang + float2(cos(d),sin(d))*Radius*i);
                        color += float4(layer.eval(blurPos*viewSize));
                    }
                }
                color /= Quality * Directions;

                uv -= 0.5;
                uv.x *= viewSize.x / viewSize.y;
                uv.x -= glowXBias;

                float2 lighten_uv = float2(uv.x*0.65, uv.y - t + 0.5 + glowMoveYShift);
                float d = smoothstep(0.0, 0.6, (0.1*glowMoveSize)/length(lighten_uv)-uv_y_dynamic_island_offset)*0.25;
                float t_smooth = smoothstep(0.0, 0.3, t);
                d *= t_smooth;
                color = color + float4(color.r*d, color.g*d, 0.0, 1.0);

                float2 lighten2_uv = float2(uv.x*0.4, uv.y-uv_y_dynamic_island_offset);
                float d2 = smoothstep(0.0, 0.5, pow(1.0-length(lighten2_uv), 28.0))*0.5;
                float t2_smooth = smoothstep(0.0, 1.0, t2)*1.0;
                d2 *= t2_smooth;
                d2 *= smoothstep(1.13, 1.0, t);
                d2 *= glowCircleAmt;
                color = float4(color.rgb*(1.0-d2), 1.0) + float4(float3(d2), 1.0);

                half4 outc = half4(color);
                float glowLum = clamp(max(outc.r, max(outc.g, outc.b)), 0.0, 1.0);
                outc.a = mix(outc.a, glowLum, 1.0 - bgAlpha);
                return outc;
            }
            """.trimIndent()

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

        /**
         * Name Card v2 server side: open the symmetric-consent screen at tap. No peer card yet — it
         * attaches to the live [NameCardLinkHolder] session the [NameCardExchangeService] started and
         * receives the peer card + consent effects through it.
         */
        fun serverV2Intent(context: Context): Intent =
            Intent(context, NameCardTransferActivity::class.java)
                .putExtra(EXTRA_ROLE, ROLE_SERVER_V2)
    }
}
