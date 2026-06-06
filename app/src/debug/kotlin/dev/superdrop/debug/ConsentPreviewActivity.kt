package dev.superdrop.debug

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import dev.superdrop.service.R as ServiceR

/**
 * DEBUG-ONLY preview of the incoming-transfer consent notification.
 *
 * The real consent notification can only be posted in response to a live
 * inbound Quick Share connection (it needs a `ConsentRegistry.Entry` wrapping
 * a real `InboundConnection`), which cannot be produced on an emulator with no
 * Wi-Fi/BLE. This harness posts a notification using the EXACT same layout
 * resources (`notification_consent` / `notification_consent_bridge`) and the
 * same `DecoratedCustomViewStyle` + RemoteViews binding the production path in
 * `ConsentNotification.build()` uses, so whether each custom layout INFLATES is
 * tested faithfully (RemoteViews inflation succeeds/fails identically on every
 * Android version — it is not device-specific).
 *
 * Trigger (debug build only):
 *   adb shell am start -n dev.superdrop.debug/dev.superdrop.debug.ConsentPreviewActivity \
 *     --es style bridge        # or: recolored
 */
class ConsentPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val style = intent?.getStringExtra("style")?.lowercase() ?: "bridge"
        val layoutRes =
            when (style) {
                "recolored" -> ServiceR.layout.notification_consent
                else -> ServiceR.layout.notification_consent_bridge
            }

        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Consent preview (debug)",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }

        val custom =
            RemoteViews(packageName, layoutRes).apply {
                setTextViewText(ServiceR.id.notif_consent_title, "OnePlus 12 wants to share")
                setTextViewText(ServiceR.id.notif_consent_body, "1 file (207 KB) · PIN 7699")
                setTextViewText(ServiceR.id.notif_consent_accept, "Accept")
                setTextViewText(ServiceR.id.notif_consent_decline, "Decline")
            }

        val n =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("OnePlus 12 wants to share")
                .setContentText("1 file (207 KB) · PIN 7699")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setAutoCancel(false)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(custom)
                .setCustomBigContentView(custom)
                .setCustomHeadsUpContentView(custom)
                .build()

        manager.notify(PREVIEW_ID, n)
        finish()
    }

    private companion object {
        const val CHANNEL_ID = "debug_consent_preview"
        const val PREVIEW_ID = 990011
    }
}
