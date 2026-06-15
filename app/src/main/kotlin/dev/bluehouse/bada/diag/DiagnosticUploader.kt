/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DiagnosticUploader — auto-uploads recent on-device diagnostics to a collector so a
 * developer can read them WITHOUT adb / without the user hunting for a zip or screenshot.
 *
 * ### Why "queue + flush when internet is available"
 * During an NFC/Wi-Fi share the phone's default network is the LOCAL share Wi-Fi, which
 * has NO internet — so a POST sent *during* the tap silently fails (this is exactly why
 * earlier device uploads never arrived). Instead, every [upload] request is QUEUED, and a
 * single [ConnectivityManager] network callback uploads the whole queue the moment a
 * VALIDATED-internet network is available — i.e. right after the share finishes and the
 * phone is back online, or immediately over cellular if cellular data is up. The user does
 * nothing; the logs arrive on their own once there's real internet.
 *
 * ### Where it's invoked
 *  - `MainActivity.onCreate` → `reason="app-open"` (so reopening the app after a tap ships
 *    whatever just happened).
 *  - `SendActivity.onPause` → `reason="send-leave"` (ships the full ring after a tap attempt,
 *    even if the reader's onTag never fired).
 *  - `BadaTapReader` after a send-tap → `reason="nfc-send-tap"`.
 *  - `BadaTapHce` after an ADVERTISEMENT → `reason="nfc-recv-tap"`.
 *
 * ### Status
 * DEBUG instrument. Diagnostics are kept ON-DEVICE by default ([DiagnosticLog]); auto-upload is
 * OPT-IN and disabled unless you set [COLLECTOR_URL] to your own endpoint. The on-device queue is
 * in-memory (survives the share; lost if the process is killed before internet returns).
 */
public object DiagnosticUploader {
    /**
     * Diagnostics collector endpoint. **Left blank by default so the app never phones home.**
     * To enable auto-upload of on-device diagnostics, set this to your own HTTPS endpoint that
     * accepts a `POST` with the recent diagnostic log as the body plus the
     * `?model=&android=&app=&reason=` query params (see [post]). While blank, [upload] keeps the
     * diagnostics ON-DEVICE only (in [DiagnosticLog]) and never makes a network request.
     */
    private const val COLLECTOR_URL = ""

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Cap so a long offline stretch can't grow the queue without bound. */
    private const val MAX_QUEUE = 12

    private data class Pending(val reason: String, val body: String)

    private val queue = ConcurrentLinkedQueue<Pending>()
    private val callbackRegistered = AtomicBoolean(false)

    @Volatile private var wantNotify = false

    /**
     * Queue the recent diagnostics buffer for upload, then ensure the flush-on-internet
     * callback is registered. Returns immediately; nothing blocks.
     *
     * @param reason short label recorded with the upload (e.g. "nfc-send-tap").
     * @param notify when true, a Toast confirms once the queue actually uploads.
     */
    public fun upload(
        context: Context,
        reason: String,
        notify: Boolean = false,
    ) {
        val appContext = context.applicationContext
        if (notify) wantNotify = true
        Thread {
            DiagnosticLog.flushFileSink()
            val body = DiagnosticLog.dumpRecent().ifBlank { "(no recent diagnostics)" }
            while (queue.size >= MAX_QUEUE) queue.poll()
            queue.add(Pending(reason, body))
            DiagnosticLog.w(
                TAG,
                "diagnostics queued ($reason) — uploads when internet is available (queued=${queue.size})",
            )
            if (COLLECTOR_URL.isBlank()) {
                // No collector configured (the default) — keep diagnostics ON-DEVICE only and
                // never make a network request. Set COLLECTOR_URL to your own endpoint to upload.
                DiagnosticLog.w(
                    TAG,
                    "diagnostics kept on-device ($reason) — set COLLECTOR_URL to enable upload",
                )
                return@Thread
            }
            registerFlusher(appContext)
            // If a validated-internet network already exists (e.g. cellular is up), flush now.
            currentInternetNetwork(appContext)?.let { drain(appContext, it) }
        }.apply { isDaemon = true }.start()
    }

    /** Register a single process-lifetime callback that drains the queue whenever a
     * validated-internet network becomes available. Network IO is moved off the callback
     * thread. Registered once (guarded); released when the process dies. */
    private fun registerFlusher(context: Context) {
        if (!callbackRegistered.compareAndSet(false, true)) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            callbackRegistered.set(false)
            return
        }
        val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
        runCatching {
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Thread { drain(context, network) }.apply { isDaemon = true }.start()
                    }
                },
            )
        }.onFailure { callbackRegistered.set(false) }
    }

    /** A network that currently has validated internet (cellular when Wi-Fi is the local
     * share net), or null. */
    private fun currentInternetNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return runCatching {
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }.getOrNull()
    }

    /** Upload every queued item over [network]; on a failure, requeue and stop (a later
     * onAvailable retries). Best-effort. */
    @Synchronized
    private fun drain(
        context: Context,
        network: Network,
    ) {
        var sentAny = false
        var item = queue.poll()
        while (item != null) {
            val result = runCatching { post(context, network, item.reason, item.body) }
                .getOrElse { "upload failed: ${it.message}" }
            DiagnosticLog.w(TAG, "diagnostics upload (${item.reason}): $result")
            if (!result.startsWith("sent")) {
                queue.add(item) // put it back; wait for the next validated network
                break
            }
            sentAny = true
            item = queue.poll()
        }
        if (sentAny && wantNotify) {
            wantNotify = false
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Diagnostics uploaded", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun post(
        context: Context,
        network: Network,
        reason: String,
        body: String,
    ): String {
        val app =
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }.getOrDefault("?")
        val url =
            COLLECTOR_URL +
                "?model=" + enc(Build.MODEL) +
                "&android=" + enc(Build.VERSION.RELEASE) +
                "&app=" + enc(app) +
                "&reason=" + enc(reason)
        // Bind the connection to the validated-internet network so the POST does NOT route
        // onto the no-internet local share Wi-Fi.
        val conn = network.openConnection(URL(url)) as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code in 200..299) "sent ${bytes.size}B (HTTP $code)" else "server HTTP $code"
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String?): String =
        URLEncoder.encode(value ?: "", StandardCharsets.UTF_8.name())

    private const val TAG = "BadaDiagUpload"
}
