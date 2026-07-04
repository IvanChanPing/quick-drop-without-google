/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds as ck
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import dev.bluehouse.bada.protocol.namecard.NameCardEntry
import dev.bluehouse.bada.protocol.namecard.NameCardEntryKind

/**
 * Real [DeviceContactSources] for [NameCardResolver]: reads the device owner's
 * "Me"/profile display name and the SIM line number so a user who never set up a
 * Name Card still shares *something* on a tap (the user's requested fallback).
 *
 * Every read is best-effort and permission-gated — any failure (denied
 * permission, eSIM with no readable number, no "Me" contact, OEM quirk) returns
 * `null`, and [NameCardResolver] degrades to whatever is left (or prompts setup).
 *
 *  - `profileDisplayName()` → `ContactsContract.Profile` DISPLAY_NAME (needs
 *    `READ_CONTACTS`).
 *  - `profilePhoneNumber()` / `profileEmail()` → the first Phone / Email data row
 *    of the owner profile (`ContactsContract.Profile` + Data directory; needs
 *    `READ_CONTACTS`).
 *  - `simPhoneNumber()` → `SubscriptionManager.getPhoneNumber` on API 33+
 *    (needs `READ_PHONE_NUMBERS`), falling back to the deprecated
 *    `TelephonyManager.getLine1Number()` below that. Often `null` in practice
 *    (carriers/eSIM rarely expose it) — that's expected, not an error.
 */
internal class AndroidDeviceContactSources(
    private val context: Context,
) : DeviceContactSources {
    override fun profileDisplayName(): String? =
        try {
            context.contentResolver
                .query(
                    ContactsContract.Profile.CONTENT_URI,
                    arrayOf(ContactsContract.Profile.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.ifBlank { null } else null
                }
        } catch (_: SecurityException) {
            null // READ_CONTACTS not granted.
        }

    override fun profilePhoneNumber(): String? =
        firstProfileData(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)

    override fun profileEmail(): String? =
        firstProfileData(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)

    /**
     * The richer typed fields from the owner profile beyond the primary
     * name/first-phone/first-email: organization + job title, postal address,
     * website, birthday, note, nickname, and any ADDITIONAL phones/emails (the
     * first phone/email are returned by [profilePhoneNumber]/[profileEmail], so
     * they're skipped here to avoid duplicates). Best-effort; needs `READ_CONTACTS`.
     */
    override fun profileEntries(): List<NameCardEntry> =
        try {
            val out = mutableListOf<NameCardEntry>()
            queryProfileData(
                arrayOf(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DATA2,
                    ContactsContract.Data.DATA4,
                ),
            )?.use { c ->
                var phoneSeen = 0
                var emailSeen = 0
                while (c.moveToNext()) {
                    val mime = c.getString(0)
                    val d1 = c.getString(1)?.ifBlank { null }
                    val d4 = c.getString(3)?.ifBlank { null }
                    when (mime) {
                        ck.Phone.CONTENT_ITEM_TYPE ->
                            if (d1 != null && phoneSeen++ > 0) out += NameCardEntry(NameCardEntryKind.PHONE, d1)
                        ck.Email.CONTENT_ITEM_TYPE ->
                            if (d1 != null && emailSeen++ > 0) out += NameCardEntry(NameCardEntryKind.EMAIL, d1)
                        ck.Organization.CONTENT_ITEM_TYPE -> {
                            if (d1 != null) out += NameCardEntry(NameCardEntryKind.COMPANY, d1)
                            if (d4 != null) out += NameCardEntry(NameCardEntryKind.TITLE, d4)
                        }
                        ck.StructuredPostal.CONTENT_ITEM_TYPE ->
                            if (d1 != null) out += NameCardEntry(NameCardEntryKind.ADDRESS, d1)
                        ck.Website.CONTENT_ITEM_TYPE ->
                            if (d1 != null) out += NameCardEntry(NameCardEntryKind.WEBSITE, d1)
                        ck.Event.CONTENT_ITEM_TYPE ->
                            if (d1 != null && safeInt(c, 2) == ck.Event.TYPE_BIRTHDAY) {
                                out += NameCardEntry(NameCardEntryKind.BIRTHDAY, d1)
                            }
                        ck.Note.CONTENT_ITEM_TYPE ->
                            if (d1 != null) out += NameCardEntry(NameCardEntryKind.NOTE, d1)
                        ck.Nickname.CONTENT_ITEM_TYPE ->
                            if (d1 != null) out += NameCardEntry(NameCardEntryKind.NICKNAME, d1)
                    }
                }
            }
            out
        } catch (_: SecurityException) {
            emptyList() // READ_CONTACTS not granted.
        }

    private fun queryProfileData(projection: Array<String>) =
        context.contentResolver.query(
            Uri.withAppendedPath(
                ContactsContract.Profile.CONTENT_URI,
                ContactsContract.Contacts.Data.CONTENT_DIRECTORY,
            ),
            projection,
            null,
            null,
            null,
        )

    /** Reads a cursor column as int, tolerating text values (some mimetypes put text in DATA2). */
    private fun safeInt(
        cursor: android.database.Cursor,
        col: Int,
    ): Int = if (cursor.isNull(col)) 0 else runCatching { cursor.getInt(col) }.getOrDefault(0)

    /**
     * First value (DATA1) of the given data mimetype from the device-owner profile's
     * data rows (`ContactsContract.Profile` + Data directory). Needs `READ_CONTACTS`;
     * returns `null` on denial/absence.
     */
    private fun firstProfileData(mimeType: String): String? =
        try {
            val dataUri =
                Uri.withAppendedPath(
                    ContactsContract.Profile.CONTENT_URI,
                    ContactsContract.Contacts.Data.CONTENT_DIRECTORY,
                )
            context.contentResolver
                .query(
                    dataUri,
                    arrayOf(ContactsContract.Data.DATA1),
                    "${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(mimeType),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.ifBlank { null } else null
                }
        } catch (_: SecurityException) {
            null // READ_CONTACTS not granted.
        }

    override fun simPhoneNumber(): String? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                subscriptionPhoneNumber()
            } else {
                @Suppress("DEPRECATION", "HardwareIds")
                legacyTelephony()?.line1Number?.ifBlank { null }
            }
        } catch (_: SecurityException) {
            null // READ_PHONE_NUMBERS / READ_PHONE_STATE not granted.
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Suppress("ReturnCount")
    private fun subscriptionPhoneNumber(): String? {
        val subs = context.getSystemService(SubscriptionManager::class.java) ?: return null
        val activeSub = SubscriptionManager.getDefaultSubscriptionId()
        if (activeSub == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        return subs.getPhoneNumber(activeSub).ifBlank { null }
    }

    private fun legacyTelephony(): TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
}
