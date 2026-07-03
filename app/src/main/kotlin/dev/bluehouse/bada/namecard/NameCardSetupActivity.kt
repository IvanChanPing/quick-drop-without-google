/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import dev.bluehouse.bada.R

/**
 * **My Name Card setup screen** — the page reached by tapping the **"Name Card"**
 * row in Settings ([dev.bluehouse.bada.ui.SettingsFragment]). Here the user enters
 * the contact card they share when two phones tap (NameDrop-style; see
 * the Name Card design notes).
 *
 * UI (R.layout.activity_name_card_setup):
 *  - `nameCardNameInput` — text field, "Name".
 *  - `nameCardPhoneInput` — phone field, "Phone".
 *  - `nameCardEmailInput` — email field, "Email".
 *  - `nameCardUsePhoneInfoButton` — pill, "Use my phone info": fills empty fields
 *    from the device "Me"/SIM ([AndroidDeviceContactSources]) after granting
 *    READ_CONTACTS / READ_PHONE_NUMBERS.
 *  - `nameCardSaveButton` — primary pill, "Save": persists via
 *    [NameCardProfileStore] and finishes.
 *  - `nameCardClearButton` — secondary pill, "Clear": wipes the saved card.
 *
 * Persists to [NameCardProfileStore]; read back here on open and by
 * [NameCardResolver] at tap time. Status: compile-only here (no display in the
 * build env); on-device verified via the real screen.
 */
internal class NameCardSetupActivity : AppCompatActivity() {
    private lateinit var store: NameCardProfileStore

    private val nameInput by lazy { findViewById<EditText>(R.id.nameCardNameInput) }
    private val phoneInput by lazy { findViewById<EditText>(R.id.nameCardPhoneInput) }
    private val emailInput by lazy { findViewById<EditText>(R.id.nameCardEmailInput) }

    /**
     * Permission request for "Use my phone info". On grant, fills any empty
     * field from the device sources; on denial, a Toast (the user can still type
     * manually). Requests both contacts + phone-number reads at once.
     */
    private val pullInfoPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                fillFromDevice()
            } else {
                toast(R.string.name_card_use_phone_info_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_name_card_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = NameCardProfileStore.from(this)
        nameInput.setText(store.displayName().orEmpty())
        phoneInput.setText(store.phoneNumber().orEmpty())
        emailInput.setText(store.email().orEmpty())

        // "Share my card when phones tap" master switch.
        val enablePrefs = NameCardPreferences.from(this)
        findViewById<SwitchCompat>(R.id.nameCardEnableSwitch).apply {
            isChecked = enablePrefs.isEnabled()
            setOnCheckedChangeListener { _, checked -> enablePrefs.setEnabled(checked) }
        }

        // "Symmetric consent (beta)" toggle — turns on the both-phones-choose Name Card v2 flow.
        // When enabled, ask for notification permission so the "waiting / declined" heads-up can show.
        findViewById<SwitchCompat>(R.id.nameCardV2Switch).apply {
            isChecked = enablePrefs.isV2Enabled()
            setOnCheckedChangeListener { _, checked ->
                enablePrefs.setV2Enabled(checked)
                if (checked) requestNotificationPermissionIfNeeded()
            }
        }

        findViewById<Button>(R.id.nameCardSaveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.nameCardClearButton).setOnClickListener { clear() }
        findViewById<Button>(R.id.nameCardUsePhoneInfoButton).setOnClickListener {
            pullInfoPermission.launch(devicePermissions())
        }
    }

    /** POST_NOTIFICATIONS request (API 33+) for the v2 consent heads-up; result is advisory (heads-up is optional). */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // heads-up degrades gracefully if denied
        }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun save() {
        val name = nameInput.text?.toString()
        val phone = phoneInput.text?.toString()
        val email = emailInput.text?.toString()
        if (name.isNullOrBlank() && phone.isNullOrBlank() && email.isNullOrBlank()) {
            toast(R.string.name_card_save_empty)
            return
        }
        store.save(name, phone, email)
        toast(R.string.name_card_saved)
        finish()
    }

    private fun clear() {
        store.clear()
        nameInput.setText("")
        phoneInput.setText("")
        emailInput.setText("")
        toast(R.string.name_card_cleared)
    }

    /** Fill only the EMPTY fields from the device sources (don't clobber typed text). */
    private fun fillFromDevice() {
        val sources = AndroidDeviceContactSources(this)
        var filledAnything = false
        if (nameInput.text.isNullOrBlank()) {
            sources.profileDisplayName()?.let {
                nameInput.setText(it)
                filledAnything = true
            }
        }
        if (phoneInput.text.isNullOrBlank()) {
            sources.simPhoneNumber()?.let {
                phoneInput.setText(it)
                filledAnything = true
            }
        }
        toast(if (filledAnything) R.string.name_card_use_phone_info_filled else R.string.name_card_use_phone_info_empty)
    }

    private fun devicePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_NUMBERS)
        } else {
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)
        }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
