/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.adbwifi

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
import org.conscrypt.Conscrypt
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
import java.security.spec.X509EncodedKeySpec
import java.util.Date

/**
 * `libadb-android` connection manager for Super Drop's **self-ADB Wi-Fi** path:
 * the app pairs once with the device's own Android-11 Wireless Debugging, then
 * connects to localhost `adbd` (as the shell UID) to run `svc wifi enable`.
 *
 * Mirrors the proven `adb-auto-enable` reference (SimpleAdbManager): a persistent
 * RSA keypair + self-signed cert in `filesDir` (`adb_key` / `adb_key.pub` /
 * `adb_cert`) so a pairing survives reboots without re-pairing. Each operation
 * builds a fresh manager (load keys → do op → close), matching the reference.
 *
 * NOTE: all calls are blocking (socket I/O) — callers MUST run them OFF the main
 * thread (see [self-review-every-change]: no blocking on the UI thread → ANR).
 * NOT device-tested; ColorOS adb_wifi_enabled / key-persistence is unverified.
 */
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

    override fun getDeviceName(): String = "SuperDrop"

    private fun loadOrGenerateKeyPair() {
        if (keyFile.exists() && pubKeyFile.exists() && certFile.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                privKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert =
                    CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                return
            }.onFailure { Log.w(TAG, "key load failed, regenerating: ${it.message}") }
        }
        generateKeyPairAndCert()
    }

    private fun generateKeyPairAndCert() {
        val keyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
                .generateKeyPair()
        privKey = keyPair.private
        cert = selfSignedCert(keyPair)
        keyFile.writeBytes(keyPair.private.encoded)
        pubKeyFile.writeBytes(keyPair.public.encoded)
        certFile.writeBytes(cert.encoded)
    }

    private fun selfSignedCert(keyPair: KeyPair): X509Certificate {
        val issuer = X500Name("CN=SuperDrop")
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

        @Volatile
        private var providersReady = false

        @Synchronized
        private fun ensureProviders() {
            if (providersReady) return
            runCatching {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            }.onFailure { Log.w(TAG, "security providers: ${it.message}") }
            providersReady = true
        }

        private fun manager(context: Context): AdbWifiManager {
            ensureProviders()
            return AdbWifiManager(context.applicationContext)
        }

        /** True once a pairing key+cert has been generated/stored. */
        fun isPaired(context: Context): Boolean =
            File(context.filesDir, "adb_cert").exists()

        /**
         * One-time pairing with the device's Wireless Debugging "pair with code".
         * Blocking. @return true on success.
         */
        fun pair(
            context: Context,
            host: String,
            port: Int,
            code: String,
        ): Boolean =
            runCatching {
                manager(context).use { it.pair(host, port, code) }
            }.getOrElse {
                Log.w(TAG, "pair failed: ${it.message}")
                false
            }

        /**
         * Connect to adbd at host:port, run a shell command, return its stdout
         * (empty string on failure). Blocking — call off the main thread.
         */
        fun runShell(
            context: Context,
            host: String,
            port: Int,
            command: String,
        ): String? =
            runCatching {
                manager(context).use { mgr ->
                    mgr.connect(host, port)
                    mgr.openStream("shell:$command").use { stream ->
                        stream.openInputStream().bufferedReader().readText()
                    }
                }
            }.getOrElse {
                Log.w(TAG, "runShell '$command' failed: ${it.message}")
                null
            }

        /**
         * Enable Android-11 Wireless Debugging by writing the secure setting
         * (needs WRITE_SECURE_SETTINGS, which we self-grant after the first
         * pairing). @return true if the write succeeded. After this, adbd comes
         * up on a RANDOM port → discover it via mDNS before connecting.
         */
        fun enableWirelessDebugging(context: Context): Boolean =
            runCatching {
                Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)
                true
            }.getOrElse {
                Log.w(TAG, "enable wireless debugging failed (WSS not granted?): ${it.message}")
                false
            }

        /** Self-grant WRITE_SECURE_SETTINGS over the ADB shell (one-time, after pairing). */
        fun selfGrantWriteSecureSettings(
            context: Context,
            host: String,
            port: Int,
        ): Boolean =
            runShell(
                context,
                host,
                port,
                "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
            ) != null

        /**
         * Flip Wi-Fi via `svc wifi` over the ADB shell (shell UID → works even
         * where setWifiEnabled is clamped). @return true if the command ran
         * (connection succeeded); caller should verify the radio state.
         */
        fun setWifi(
            context: Context,
            host: String,
            port: Int,
            on: Boolean,
        ): Boolean =
            runShell(context, host, port, "svc wifi ${if (on) "enable" else "disable"}") != null
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
