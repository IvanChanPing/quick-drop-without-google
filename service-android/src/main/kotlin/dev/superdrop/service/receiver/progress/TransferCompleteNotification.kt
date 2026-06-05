/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.service.receiver.progress

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import dev.superdrop.protocol.connection.ReceivedItem
import dev.superdrop.service.R

/**
 * Completion notification surface for a finished inbound transfer.
 *
 * Completes the consent / progress / completion notification trio that
 * [dev.superdrop.service.receiver.consent.ConsentNotification] and
 * [TransferProgressNotification] began:
 *
 *  - **Consent** (channel `incoming_transfer`, IMPORTANCE_HIGH).
 *  - **Progress** (channel `transfer_progress`, IMPORTANCE_LOW). The
 *    progress notification is *dismissed* by
 *    [TransferProgressCoordinator] the moment the connection reaches a
 *    terminal state.
 *  - **Completion** (this object's channel [CHANNEL_ID],
 *    IMPORTANCE_HIGH): posted once a transfer reaches
 *    [dev.superdrop.protocol.connection.InboundConnectionState.Completed].
 *    Because the progress notification is dismissed on the same terminal
 *    transition, this completion notification effectively *replaces* the
 *    progress card in the shade.
 *
 * ### Why a heads-up channel
 *
 * The progress channel is deliberately quiet (IMPORTANCE_LOW, no sound)
 * because percentage updates should not peek over other UI. Completion
 * is the opposite case — the user explicitly wants to know "your files
 * arrived" with a sound the same way a finished download alerts. So the
 * completion channel is IMPORTANCE_HIGH with the platform default sound
 * and vibration.
 *
 * ### The "Open" action
 *
 * Tapping the notification body (or its explicit **Open** action) routes
 * to the received content:
 *
 *  - **Single received FILE** whose on-disk `content://` Uri can be
 *    resolved from `MediaStore.Downloads` by display name → an
 *    `ACTION_VIEW` on that Uri, granting
 *    [Intent.FLAG_GRANT_READ_URI_PERMISSION]. Mirrors how
 *    `ConsentTrampolineActivity` opens a received image.
 *  - **Multiple files, or a single file whose Uri cannot be resolved**
 *    (the committed name carried a collision suffix, MediaStore has not
 *    yet indexed the row, the save tree was a user-chosen SAF folder,
 *    etc.) → the system Downloads view
 *    ([DownloadManager.ACTION_VIEW_DOWNLOADS]).
 *
 * The Uri resolution is best-effort and never blocks the post: a query
 * miss simply falls back to the Downloads view so the action is always
 * tappable.
 *
 * ### Per-connection notification id
 *
 * The id is derived from the same `connectionId` the progress
 * notification uses, but biased onto a disjoint base
 * ([COMPLETE_ID_BASE]) so the completion card never collides with the
 * receiver-foreground (`0x4C42_4452`), consent (`0x6357_5663`), or
 * progress (`0x7057_5663`) ranges.
 */
public object TransferCompleteNotification {
    /** Channel id for the transfer-completion heads-up. */
    public const val CHANNEL_ID: String = "transfer_complete"

    /**
     * Mask applied to fold a connection id into the Android positive
     * notification id range, biased onto a base disjoint from the
     * receiver-foreground (`0x4C42_4452` "LBDR"), consent
     * (`0x6357_5663` "cWVc"), and progress (`0x7057_5663` "pWVc")
     * ranges. `0x436F_5663` reads "CoVc".
     */
    internal const val COMPLETE_ID_BASE: Int = 0x436F_5663 // "CoVc"

    /**
     * Stable Android notification id for the completion notification of a
     * given connection. Same `connectionId` always yields the same id.
     */
    public fun stableNotificationIdFor(connectionId: Long): Int {
        val low31 = (connectionId and POSITIVE_INT_MASK_LONG).toInt()
        var biased = (COMPLETE_ID_BASE + low31) and POSITIVE_INT_MASK
        if (biased == 0) biased = 1
        return biased
    }

    /**
     * Idempotently install the completion notification channel on API
     * 26+. IMPORTANCE_HIGH so the completion peeks (heads-up) and plays
     * the channel default sound + vibration — `setSound(null, null)` is
     * intentionally NOT applied, unlike the quiet progress channel.
     */
    public fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_complete_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.transfer_complete_channel_description)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the completion notification for [connectionId].
     *
     * @param items The successfully received items (FILE + TEXT). The
     *   file count drives the title; the single-file case drives the
     *   Open-Uri resolution.
     * @param sourceDeviceName The sender's advertised device name,
     *   carried over from the consent / progress metadata. `null` when
     *   the sender was in hidden visibility mode — the title then omits
     *   the "from <sender>" clause.
     */
    public fun build(
        context: Context,
        connectionId: Long,
        items: List<ReceivedItem>,
        sourceDeviceName: String?,
    ): Notification {
        val fileItems = items.filterIsInstance<ReceivedItem.File>()
        val fileCount = fileItems.size

        val title = titleFor(context, fileCount, sourceDeviceName)

        val openIntent = openPendingIntent(context, connectionId, fileItems)

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)

        if (openIntent != null) {
            builder
                .setContentIntent(openIntent)
                .addAction(
                    NotificationCompat.Action
                        .Builder(
                            android.R.drawable.ic_menu_view,
                            context.getString(R.string.transfer_complete_action_open),
                            openIntent,
                        ).build(),
                )
        }

        return builder.build()
    }

    /**
     * Post (or replace) the completion notification for [connectionId].
     * Idempotent under the stable id. Returns the notification id used,
     * or `-1` if no [NotificationManager] is available.
     */
    public fun post(
        context: Context,
        connectionId: Long,
        items: List<ReceivedItem>,
        sourceDeviceName: String?,
    ): Int {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return -1
        val id = stableNotificationIdFor(connectionId)
        manager.notify(id, build(context, connectionId, items, sourceDeviceName))
        return id
    }

    /**
     * Dismiss the completion notification for [connectionId]. Safe to
     * call before [post] — the platform `cancel` is a no-op for unknown
     * ids. Used by the service teardown path so a completion card never
     * lingers past a service stop.
     */
    public fun dismiss(
        context: Context,
        connectionId: Long,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(stableNotificationIdFor(connectionId))
    }

    private fun titleFor(
        context: Context,
        fileCount: Int,
        sourceDeviceName: String?,
    ): String {
        // Spell out the count line. The plural is handled inline today
        // (the receive UI strings do the same); a `plurals` resource
        // lands with the localisation pass alongside the progress
        // strings.
        val device = sourceDeviceName?.takeIf { it.isNotBlank() }
        return if (device != null) {
            context.getString(R.string.transfer_complete_title_with_name, fileCount, device)
        } else {
            context.getString(R.string.transfer_complete_title_unknown_sender, fileCount)
        }
    }

    /**
     * Build the `PendingIntent` for the body tap + Open action.
     *
     * Resolves to an `ACTION_VIEW` on a single received file's
     * `content://` Uri when exactly one FILE arrived and its row can be
     * found in `MediaStore.Downloads`; otherwise opens the system
     * Downloads view. Returns `null` only if neither a viewable file Uri
     * nor a Downloads-view intent can be constructed (effectively never
     * on a real device — `DownloadManager.ACTION_VIEW_DOWNLOADS` is
     * always resolvable).
     */
    private fun openPendingIntent(
        context: Context,
        connectionId: Long,
        fileItems: List<ReceivedItem.File>,
    ): PendingIntent? {
        val viewIntent = buildOpenIntent(context, fileItems)
        val activityIntent = viewIntent ?: downloadsViewIntent()
        return PendingIntent.getActivity(
            context,
            openRequestCodeFor(connectionId),
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Resolve the body/Open intent. A single FILE whose Downloads row
     * resolves → an `ACTION_VIEW` on its Uri (read-grant flagged);
     * anything else → `null` (caller falls back to the Downloads view).
     */
    private fun buildOpenIntent(
        context: Context,
        fileItems: List<ReceivedItem.File>,
    ): Intent? {
        if (fileItems.size != 1) return null
        val name = fileItems.single().header.fileName.takeIf { it.isNotBlank() } ?: return null
        val uri = resolveDownloadsUri(context, name) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            // Use the MediaStore-reported MIME when available so the
            // chooser narrows to a sensible viewer; fall back to a
            // wildcard so the action is still usable.
            val mime = resolveMimeType(context, uri)
            if (mime != null) {
                setDataAndType(uri, mime)
            } else {
                data = uri
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun downloadsViewIntent(): Intent =
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Query `MediaStore.Downloads` for a recently-added row whose
     * display name equals [displayName], returning its `content://` Uri
     * or `null` if no match is found.
     *
     * Mirrors `ConsentTrampolineActivity.findReceivedImageUri` but
     * against the Downloads collection (received files land there via
     * [dev.superdrop.service.downloads.MediaStoreDownloadsEnvironment]),
     * not just images. Best-effort: a `SecurityException` (no read
     * grant) or an empty result returns `null`, and the caller falls
     * back to the Downloads view.
     *
     * Pre-API-29 the Downloads collection is not queryable this way (the
     * legacy environment writes a plain file under the public Downloads
     * directory), so we return `null` and fall back to the Downloads
     * view there too.
     */
    @Suppress("ReturnCount")
    private fun resolveDownloadsUri(
        context: Context,
        displayName: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(displayName)
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"
        return try {
            context.contentResolver
                .query(collection, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    } else {
                        null
                    }
                }
        } catch (e: SecurityException) {
            // No read grant on the Downloads collection — fall back to
            // the Downloads view rather than surfacing a broken Open.
            null
        }
    }

    /**
     * Best-effort MIME lookup for a resolved Downloads Uri. `null` when
     * the resolver has no type (the chooser then opens with the Uri
     * alone). Never throws — a denied/absent row collapses to `null`.
     */
    private fun resolveMimeType(
        context: Context,
        uri: Uri,
    ): String? =
        try {
            context.contentResolver.getType(uri)
        } catch (e: SecurityException) {
            null
        }

    private fun openRequestCodeFor(connectionId: Long): Int =
        ((connectionId * REQUEST_CODE_STRIDE + OPEN_OFFSET) and POSITIVE_INT_MASK_LONG).toInt()

    private const val POSITIVE_INT_MASK: Int = 0x7FFF_FFFF
    private const val POSITIVE_INT_MASK_LONG: Long = 0x7FFF_FFFFL

    private const val REQUEST_CODE_STRIDE = 1L
    private const val OPEN_OFFSET = 0L
}
