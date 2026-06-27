/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper.adbwifi

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * WHAT THIS IS
 * ------------
 * `AdbWifiManager` — the low-level `libadb-android` connection manager for the
 * **Bada Radio Helper's self-ADB Wi-Fi** path. Lives in the HELPER module
 * (`:radio-helper`), NOT the main app, so the app reaches it through the one
 * helper instead of embedding its own copy.
 *
 * WHAT IT DOES
 * ------------
 * The helper pairs ONCE with the device's own Android-11 "Wireless debugging",
 * then connects to localhost `adbd` (which runs as the shell UID) to run
 * `svc wifi enable/disable` — flipping Wi-Fi silently even on OEMs (ColorOS)
 * that clamp `WifiManager.setWifiEnabled()`.
 *
 * Mirrors the proven `adb-auto-enable` reference (SimpleAdbManager): a persistent
 * RSA keypair + self-signed cert in `filesDir` (`adb_key` / `adb_key.pub` /
 * `adb_cert`) so a pairing survives reboots without re-pairing. Each operation
 * builds a fresh manager (load keys → do op → close), matching the reference.
 *
 * WHO CALLS IT
 * ------------
 * - [AdbWifiRadio] — the shared engine that wraps "enable → discover → toggle".
 *   Boot-time readiness (re-enabling wireless debugging after a reboot) is driven
 *   by the boot service via [AdbWifiRadio.ensureReady]; the tap-time toggle is
 *   driven by `RadioService` via [AdbWifiRadio.setWifi]. Callers never use this
 *   class directly except the on-device pairing test (`AdbWifiTestActivity`).
 *
 * THREADING / STATUS
 * ------------------
 * All calls are blocking (socket I/O) — callers MUST run them OFF the main thread
 * (no UI-thread blocking → ANR). NOT device-tested; ColorOS `adb_wifi_enabled`
 * write + key-persistence across reboots is UNVERIFIED.
 *
 * The ADB pairing/connect wire constants (mDNS service-type bytes, packet field
 * offsets) are inherently numeric, so the class suppresses detekt's MagicNumber —
 * a named constant per offset would not read more clearly than the protocol comments.
 */
@Suppress("MagicNumber")
internal class AdbWifiManager private constructor(
    context: Context,
) : AbsAdbConnectionManager() {
    private val keyFile = File(context.filesDir, "adb_key")
    private val pubKeyFile = File(context.filesDir, "adb_key.pub")
    private val certFile = File(context.filesDir, "adb_cert")

    private lateinit var privKey: PrivateKey
    private lateinit var cert: X509Certificate

    init {
        // Tell libadb which protocol/auth scheme to use (TLS pairing on API 30+).
        setApi(Build.VERSION.SDK_INT)
        loadOrGenerateKeyPair()
    }

    override fun getPrivateKey(): PrivateKey = privKey

    override fun getCertificate(): Certificate = cert

    override fun getDeviceName(): String = "BadaRadioHelper"

    private fun loadOrGenerateKeyPair() {
        if (keyFile.exists() && pubKeyFile.exists() && certFile.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                privKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert =
                    CertificateFactory
                        .getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                return
            }.onFailure { Log.w(TAG, "key load failed, regenerating: ${it.message}") }
        }
        generateKeyPairAndCert()
    }

    private fun generateKeyPairAndCert() {
        val keyPair =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048, SecureRandom()) }
                .generateKeyPair()
        privKey = keyPair.private
        cert = selfSignedCert(keyPair)
        keyFile.writeBytes(keyPair.private.encoded)
        pubKeyFile.writeBytes(keyPair.public.encoded)
        certFile.writeBytes(cert.encoded)
    }

    private fun selfSignedCert(keyPair: KeyPair): X509Certificate {
        val issuer = X500Name("CN=BadaRadioHelper")
        val now = System.currentTimeMillis()
        val builder =
            JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(now),
                Date(now - 24L * 60 * 60 * 1000),
                Date(now + 365L * 24 * 60 * 60 * 1000),
                issuer,
                keyPair.public,
            )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    internal companion object {
        private const val TAG = "AdbWifi"

        /**
         * The actual exception from the last [runShell]/[connect] attempt (class +
         * message), or null on success. Surfaced up through [AdbWifiRadio.lastStatus]
         * so a connect failure says WHY ("ConnectException: Connection refused" =
         * wrong host/port not listening; an SSL/handshake error = key not trusted)
         * instead of a generic "adbd unreachable". Diagnostics only.
         */
        @Volatile
        var lastError: String? = null
            private set

        /**
         * The raw stdout of the last `pm grant` self-grant attempt: empty string
         * when `pm grant` succeeded silently, an error string when it failed but
         * still "ran", or null when the shell connection never opened. Surfaced on
         * [dev.bluehouse.bada.radiohelper.SelfTestActivity] so a grant that "ran but did
         * not stick" is visible instead of a false "success". Diagnostics only.
         */
        @Volatile
        var lastGrantOutput: String? = null
            private set

        @Volatile
        private var providersReady = false

        @Synchronized
        private fun ensureProviders() {
            if (providersReady) return
            runCatching {
                // libadb 3.1.1's SslUtils instantiates org.conscrypt.OpenSSLProvider
                // itself for TLSv1.3 — we do NOT insert Conscrypt as a global provider
                // (that was needed for 1.0.1 and could interfere). We only ensure the
                // BouncyCastle provider for our self-signed cert generation.
                if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            }.onFailure { Log.w(TAG, "security providers: ${it.message}") }
            providersReady = true
        }

        private fun manager(context: Context): AdbWifiManager {
            ensureProviders()
            return AdbWifiManager(context.applicationContext)
        }

        /**
         * Marker file written ONLY after a genuinely successful [pair]. NOT the
         * cert file: the cert/key is generated on the first manager creation (even
         * a FAILED pair attempt), so its existence is a false-positive for
         * "paired". This marker reflects a real, accepted 6-digit pairing.
         */
        private fun pairedMarker(context: Context) = File(context.filesDir, "adb_paired")

        /** True only after a successful 6-digit pairing (key trusted by adbd). */
        fun isPaired(context: Context): Boolean = pairedMarker(context).exists()

        /**
         * One-time pairing with the device's Wireless Debugging "pair with code".
         * MUST be done with this app and the Wireless-debugging "Pair device with
         * pairing code" dialog side-by-side in SPLIT SCREEN — leaving either one
         * closes the dialog and the code/port change (verified: this is the
         * documented LADB workaround). Blocking. @return true on success; on
         * success writes [pairedMarker] so [isPaired] is truthful.
         */
        fun pair(
            context: Context,
            host: String,
            port: Int,
            code: String,
        ): Boolean =
            runCatching {
                manager(context).use { it.pair(host, port, code) }
            }.onSuccess { ok ->
                Log.i(TAG, "pair($host:$port) result=$ok")
                if (ok) runCatching { pairedMarker(context).writeText("1") }
            }.getOrElse {
                Log.w(TAG, "pair failed: ${it.message}")
                false
            }

        /** mDNS discovery + connect budget for [autoConnect] (per attempt). */
        private const val AUTOCONNECT_TIMEOUT_MS = 10_000L

        /**
         * Connect to the device's OWN adbd via libadb's `autoConnect` (mDNS
         * discovery of `_adb-tls-connect._tcp` + TLSv1.3, the library's intended
         * path as of 3.1.1), run a shell command, return its stdout (null on
         * failure). Replaces the old manual `connect(host, port)` that hit
         * IOException on ColorOS. Requires wireless debugging to be ON (the caller
         * runs [enableWirelessDebugging] first) and a prior successful [pair].
         * Blocking — call off the main thread. Records [lastError] on failure
         * (incl. `AdbPairingRequiredException` when the key isn't trusted).
         */
        fun runShell(
            context: Context,
            command: String,
        ): String? =
            runCatching {
                manager(context).use { mgr ->
                    if (!mgr.autoConnect(context.applicationContext, AUTOCONNECT_TIMEOUT_MS)) {
                        throw java.io.IOException("autoConnect returned false")
                    }
                    mgr.openStream("shell:$command").use { stream ->
                        stream.openInputStream().bufferedReader().readText()
                    }
                }
            }.onSuccess { lastError = null }
                .getOrElse {
                    lastError = "${it.javaClass.simpleName}: ${it.message}"
                    Log.w(TAG, "runShell '$command' failed: $lastError")
                    null
                }

        /**
         * Enable Android-11 Wireless Debugging by writing the secure setting
         * (needs WRITE_SECURE_SETTINGS, which we self-grant after the first
         * pairing). @return true if the write succeeded. After this, adbd comes up
         * on a RANDOM port → [runShell]'s autoConnect discovers it via mDNS.
         */
        fun enableWirelessDebugging(context: Context): Boolean =
            runCatching {
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
                true
            }.getOrElse {
                Log.w(TAG, "enable wireless debugging failed (WSS not granted?): ${it.message}")
                false
            }

        /**
         * Self-grant WRITE_SECURE_SETTINGS over the self-ADB shell (one-time, after
         * pairing), then VERIFY the permission is actually held.
         *
         * @return `true` ONLY when the app actually holds WRITE_SECURE_SETTINGS
         *   afterward — NOT merely when the `pm grant` command ran. The old version
         *   returned `runShell(...) != null`, which is non-null even when `pm grant`
         *   prints an error and the grant silently no-ops (runShell reads stdout
         *   only); that produced the false "WSS self-granted" while the permission
         *   was never held — the bug the user hit. The command's raw output is
         *   recorded in [lastGrantOutput] (empty = silent success, error text =
         *   ran-but-failed, null = shell never connected) for on-screen diagnostics.
         */
        fun selfGrantWriteSecureSettings(context: Context): Boolean {
            val output =
                runShell(
                    context,
                    "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
                )
            lastGrantOutput = output
            val held = hasWriteSecureSettings(context)
            when {
                output == null ->
                    Log.w(TAG, "self-grant: shell never connected ($lastError)")
                !held ->
                    Log.w(TAG, "self-grant: pm grant ran but WSS still DENIED. output='${output.trim()}'")
                else ->
                    Log.i(TAG, "self-grant: WSS granted + verified held")
            }
            return held
        }

        /**
         * Whether this app currently HOLDS `WRITE_SECURE_SETTINGS`. Ground-truth
         * check via `PackageManager.checkPermission` (all-API; reflects a
         * just-applied `pm grant`). Used to verify [selfGrantWriteSecureSettings]
         * and to show live WSS state on the helper's main screen.
         */
        fun hasWriteSecureSettings(context: Context): Boolean =
            context.packageManager.checkPermission(
                "android.permission.WRITE_SECURE_SETTINGS",
                context.packageName,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        /**
         * Flip Wi-Fi via `svc wifi` over the ADB shell (shell UID → works even
         * where setWifiEnabled is clamped). @return true if the command ran
         * (connection succeeded); caller should verify the radio state.
         */
        fun setWifi(
            context: Context,
            on: Boolean,
        ): Boolean = runShell(context, "svc wifi ${if (on) "enable" else "disable"}") != null
    }
}

/** Kotlin `use` for libadb's AutoCloseable-ish manager + stream. */
private inline fun <T : AbsAdbConnectionManager, R> T.use(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        runCatching { close() }
    }

private inline fun <R> AdbStream.use(block: (AdbStream) -> R): R =
    try {
        block(this)
    } finally {
        runCatching { close() }
    }
