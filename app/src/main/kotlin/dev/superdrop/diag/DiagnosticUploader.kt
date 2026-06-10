/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.diag

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * DiagnosticUploader — auto-uploads recent on-device diagnostics to a collector
 * so a developer can read them WITHOUT adb / without the user hunting for a zip.
 *
 * ### What it does / why
 *
 * The user can't run `adb logcat`, so NFC-tap diagnosis was stuck. This posts
 * [DiagnosticLog]'s recent in-memory buffer (which now includes the NFC tap
 * lines — see `SuperDropTapReader` / `SuperDropTapHce`) to [COLLECTOR_URL] over
 * HTTPS, tagged with device model + Android version + app version + a `reason`.
 * Fully background (no ANR), best-effort (failures are swallowed / toasted).
 *
 * ### Where it's invoked
 *  - `MainActivity.onCreate` → `reason="app-open"` (notify=true; shows a toast),
 *    so opening the app ships whatever just happened (covers the receiver phone
 *    whose HCE may never have fired on a tap).
 *  - `SuperDropTapReader` after a send-tap exchange → `reason="nfc-send-tap"`.
 *  - `SuperDropTapHce` after handling an ADVERTISEMENT → `reason="nfc-recv-tap"`.
 *
 * ### Status
 * DEBUG diagnostic instrument. The collector is an ephemeral cloudflared tunnel;
 * if it changes the upload fails (observable via the toast) and the URL must be
 * re-baked. Server-side receipt verified via curl; the on-device POST path is
 * exercised the first time the app runs this.
 */
public object DiagnosticUploader {
    /**
     * Ephemeral cloudflared collector endpoint (POST body = recent diagnostics).
     * Re-bake if the tunnel restarts. Re-baked 2026-06-10 for the Round-2
     * NFC-tap-to-Quick-Share byte trace (SuperDropTapReader logs SELECT/ADV
     * response bytes + tag-loss timing); collector = /root/nfc-diag/collector.py
     * on the dev box behind this quick tunnel. NOTE: the on-device
     * shake-to-bug-report (BugReportFlowSupport) captures the SAME DiagnosticLog
     * ring in a ZIP with no network dependency — use that if the tunnel is down.
     */
    private const val COLLECTOR_URL = "https://missing-advocate-wing-programmer.trycloudflare.com/"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Upload the recent diagnostics buffer. Returns immediately; the work runs
     * on a throwaway background thread.
     *
     * @param reason short label recorded with the upload (e.g. "nfc-send-tap").
     * @param notify when true, a Toast reports success/failure to the user.
     */
    public fun upload(
        context: Context,
        reason: String,
        notify: Boolean = false,
    ) {
        val appContext = context.applicationContext
        Thread {
            val result =
                runCatching {
                    DiagnosticLog.flushFileSink()
                    val body = DiagnosticLog.dumpRecent().ifBlank { "(no recent diagnostics)" }
                    post(appContext, reason, body)
                }.getOrElse { "upload failed: ${it.message}" }
            DiagnosticLog.w(TAG, "diagnostics upload ($reason): $result")
            if (notify) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "Diagnostics: $result", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun post(
        context: Context,
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
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val out: OutputStream = conn.outputStream
            out.use { it.write(bytes) }
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
