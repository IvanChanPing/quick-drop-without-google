/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.namecard

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.superdrop.discovery.diagnostics.DiagnosticLog
import dev.superdrop.protocol.namecard.NameCard

/**
 * **Name Card → Android contact saver.** Saves a received [NameCard] as a REAL
 * Android contact for the tap-to-share feature (see
 * `docs/NAMEDROP_CONTACT_EXCHANGE_JOURNAL.md`).
 *
 * Deliberately does NOT go through a vCard `.vcf` file (the user reported vCard
 * import is flaky on modern Android, and our card already has structured
 * fields). Instead:
 *  - [saveDirect] — a `ContactsContract` raw-contact insert (needs WRITE_CONTACTS).
 *    Seamless, stays in Super Drop.
 *  - [systemInsertIntent] — the system **Add contact** screen prefilled
 *    (`Intent.ACTION_INSERT`), used as the no-permission fallback when
 *    WRITE_CONTACTS is denied; the user confirms in the OS Contacts UI.
 *
 * Callers ([NameCardTransferActivity]) try [saveDirect] when [hasWritePermission]
 * and otherwise `startActivity([systemInsertIntent])`.
 *
 * Status: compile-only here (ContentResolver/Contacts need a device); on-device
 * verified by saving a real received card.
 */
internal object NameCardSaver {
    private const val TAG = "NameCardSaver"

    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Insert [card] directly via ContactsContract. Returns true on success.
     * Builds a new raw contact (no account = device/local) with a structured
     * name + phone + email as present. Catches all errors → false (caller falls
     * back to [systemInsertIntent]).
     */
    fun saveDirect(
        context: Context,
        card: NameCard,
    ): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        // Raw contact anchor (index 0); subsequent rows back-reference it.
        ops.add(
            ContentProviderOperation
                .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
        )
        card.displayName?.let { name ->
            ops.add(
                dataInsert()
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    ).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build(),
            )
        }
        card.phoneNumber?.let { phone ->
            ops.add(
                dataInsert()
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    ).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                    ).build(),
            )
        }
        card.email?.let { email ->
            ops.add(
                dataInsert()
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    ).withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .withValue(
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                    ).build(),
            )
        }
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            DiagnosticLog.w(TAG, "saved contact directly (${ops.size - 1} fields)")
            true
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            DiagnosticLog.w(TAG, "saveDirect failed: ${t.message}")
            false
        }
    }

    /**
     * The system "Add contact" screen prefilled from [card]. No permission
     * required; the user taps Save in the OS Contacts UI.
     */
    fun systemInsertIntent(card: NameCard): Intent =
        Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            card.displayName?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            card.phoneNumber?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            card.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        }

    private fun dataInsert(): ContentProviderOperation.Builder =
        ContentProviderOperation
            .newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
}
