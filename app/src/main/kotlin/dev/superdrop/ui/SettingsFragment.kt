/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.superdrop.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import dev.superdrop.MainActivity
import dev.superdrop.R
import dev.superdrop.battery.BatteryOptimizationOemHelper
import dev.superdrop.bugreport.BugReportPreferences
import dev.superdrop.nfc.NfcTapDiagnosticsPreferences
import dev.superdrop.nfc.NfcTapSharePreferences
import dev.superdrop.consent.FullScreenIntentPermission
import dev.superdrop.service.downloads.SaveLocationDisplayName
import dev.superdrop.service.downloads.SaveLocationPreferences
import dev.superdrop.service.receiver.AdvertisedDeviceNames
import dev.superdrop.service.receiver.ReceiverForegroundService
import dev.superdrop.service.receiver.consent.ConsentNotificationStylePreferences
import dev.superdrop.transfer.KeepScreenOnPreferences
import dev.superdrop.transfer.TransferExpertViewPreferences

/**
 * Settings tab content for the bottom-navigation shell in
 * [dev.superdrop.MainActivity].
 *
 * Houses the persistent receiver-side preferences:
 *   * #42 save-location override — pick a SAF tree URI to redirect
 *     incoming files away from the system Downloads folder, or clear
 *     the override to fall back to Downloads.
 *   * #141 advertised Quick Share name — custom override for the
 *     receiver name nearby Quick Share peers see; clearing the override
 *     falls back to Android's device-name chain (system device name,
 *     Bluetooth name, model, then app label).
 *   * #47 background-activity entry point — re-trigger the OEM-aware
 *     battery-optimization Settings page after the first-launch dialog
 *     has been skipped. Status summary is refreshed on every onStart
 *     so a system-Settings round trip reflects immediately.
 *
 * Each control mutates a single SharedPreferences-backed value (or
 * platform power-manager state for the battery row) and refreshes
 * its summary line on every onStart so an external state change
 * (e.g. the user revoked the SAF grant in system Settings while we
 * were paused) reflects immediately.
 */
internal class SettingsFragment : Fragment(R.layout.fragment_settings) {
    /**
     * Launcher for the SAF tree picker that backs the "Save received
     * files to" setting (#42). The result URI is persisted via
     * [SaveLocationPreferences] which also takes the persistable
     * read+write grant so the choice survives reboots. The fragment
     * refreshes its current-location label after every successful
     * selection.
     */
    private lateinit var saveLocationLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        saveLocationLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
                if (treeUri == null) return@registerForActivityResult
                try {
                    SaveLocationPreferences.from(requireContext()).setSaveTreeUri(treeUri)
                    refreshSaveLocationLabel()
                } catch (e: SecurityException) {
                    // The platform refused to take the persistable grant
                    // (typically because the URI didn't come from
                    // ACTION_OPEN_DOCUMENT_TREE — defensive guard, the
                    // contract should always return a tree URI). Surface
                    // a soft error so the user picks a different folder.
                    Log.w(TAG, "Save-location pick failed: ${e.message}", e)
                    Toast
                        .makeText(
                            requireContext(),
                            R.string.main_save_location_pick_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.main_save_location_pick).setOnClickListener {
            saveLocationLauncher.launch(null)
        }
        view.findViewById<Button>(R.id.main_save_location_clear).setOnClickListener {
            SaveLocationPreferences.from(requireContext()).clear()
            refreshSaveLocationLabel()
        }

        view.findViewById<Button>(R.id.main_advertised_name_save).setOnClickListener {
            val input = view.findViewById<EditText>(R.id.main_advertised_name_input)
            val stored = AdvertisedDeviceNames.setCustomName(requireContext(), input.text?.toString())
            input.setText(stored.orEmpty())
            refreshAdvertisedNameSection()
            ReceiverForegroundService.start(requireContext())
        }
        view.findViewById<Button>(R.id.main_advertised_name_reset).setOnClickListener {
            AdvertisedDeviceNames.clearCustomName(requireContext())
            refreshAdvertisedNameSection()
            ReceiverForegroundService.start(requireContext())
        }

        view.findViewById<Button>(R.id.settings_battery_open).setOnClickListener {
            // One-tap system "Allow battery optimization exemption?" popup
            // (falls back to the settings list on OEMs that can't launch it).
            MainActivity.requestIgnoreBatteryOptimizations(requireContext())
        }

        view.findViewById<Button>(R.id.settings_fsi_open).setOnClickListener {
            FullScreenIntentPermission.openSettings(requireContext())
        }

        val bugReportSwitch = view.findViewById<SwitchCompat>(R.id.main_bug_report_switch)
        val bugReportPreferences = BugReportPreferences.from(requireContext())
        bugReportSwitch.isChecked = bugReportPreferences.isShakeToReportEnabled()
        bugReportSwitch.setOnCheckedChangeListener { _, checked ->
            bugReportPreferences.setShakeToReportEnabled(checked)
        }

        // "Keep screen on during transfers" toggle (#219). Persists to
        // KeepScreenOnPreferences; Send/Consent activities read it to decide
        // whether to hold FLAG_KEEP_SCREEN_ON while a transfer is active.
        val keepScreenOnSwitch = view.findViewById<SwitchCompat>(R.id.main_keep_screen_on_switch)
        val keepScreenOnPreferences = KeepScreenOnPreferences.from(requireContext())
        keepScreenOnSwitch.isChecked = keepScreenOnPreferences.isKeepScreenOnDuringTransfersEnabled()
        keepScreenOnSwitch.setOnCheckedChangeListener { _, checked ->
            keepScreenOnPreferences.setKeepScreenOnDuringTransfersEnabled(checked)
        }

        // "Expert transfer details" toggle (#220). Persists to
        // TransferExpertViewPreferences; Send/Consent activities show the
        // speed/ETA/medium/Wi-Fi-band diagnostics row when enabled.
        val expertViewSwitch = view.findViewById<SwitchCompat>(R.id.main_transfer_expert_switch)
        val expertViewPreferences = TransferExpertViewPreferences.from(requireContext())
        expertViewSwitch.isChecked = expertViewPreferences.isExpertViewEnabled()
        expertViewSwitch.setOnCheckedChangeListener { _, checked ->
            expertViewPreferences.setExpertViewEnabled(checked)
        }

        // "Show NFC tap diagnostics" toggle — gates the on-screen Toasts during a tap.
        val nfcDiagnosticsSwitch = view.findViewById<SwitchCompat>(R.id.settings_nfc_diagnostics_switch)
        val nfcDiagnosticsPreferences = NfcTapDiagnosticsPreferences.from(requireContext())
        nfcDiagnosticsSwitch.isChecked = nfcDiagnosticsPreferences.isEnabled()
        nfcDiagnosticsSwitch.setOnCheckedChangeListener { _, checked ->
            nfcDiagnosticsPreferences.setEnabled(checked)
        }

        wireNfcTapShareMode(view)
        wireConsentNotificationStyle(view)
    }

    /**
     * Bind the "Incoming transfer style" 3-way selector to
     * [ConsentNotificationStylePreferences]. Reflects the stored style and
     * persists changes; [dev.superdrop.service.receiver.consent.ConsentNotification]
     * reads this preference when it builds the consent heads-up.
     */
    private fun wireConsentNotificationStyle(view: View) {
        val prefs = ConsentNotificationStylePreferences.from(requireContext())
        val group = view.findViewById<RadioGroup>(R.id.settings_consent_style_group)
        val checkedId =
            when (prefs.mode()) {
                ConsentNotificationStylePreferences.Style.BRIDGE ->
                    R.id.settings_consent_style_bridge
                ConsentNotificationStylePreferences.Style.SHEET ->
                    R.id.settings_consent_style_sheet
                ConsentNotificationStylePreferences.Style.RECOLORED ->
                    R.id.settings_consent_style_recolored
            }
        group.check(checkedId)
        group.setOnCheckedChangeListener { _, id ->
            val mode =
                when (id) {
                    R.id.settings_consent_style_bridge ->
                        ConsentNotificationStylePreferences.Style.BRIDGE
                    R.id.settings_consent_style_sheet ->
                        ConsentNotificationStylePreferences.Style.SHEET
                    else -> ConsentNotificationStylePreferences.Style.RECOLORED
                }
            prefs.setMode(mode)
            // "Bottom sheet only" raises the consent sheet via a full-screen
            // intent when a transfer arrives in the background. On Android 14+
            // that needs the full-screen-notification permission — without it
            // the platform blocks the background activity launch and only a
            // plain notification shows (the sheet never pops). Route the user
            // to grant it so this style actually delivers the bottom sheet.
            if (mode == ConsentNotificationStylePreferences.Style.SHEET &&
                FullScreenIntentPermission.isRequestable(requireContext())
            ) {
                Toast
                    .makeText(
                        requireContext(),
                        R.string.settings_consent_style_sheet_needs_fsi,
                        Toast.LENGTH_LONG,
                    ).show()
                FullScreenIntentPermission.openSettings(requireContext())
            }
        }
    }

    /**
     * Bind the dedicated "NFC tap to share" 3-way selector to
     * [NfcTapSharePreferences] (a separate setting from the visible toggle,
     * by user request). Reflects the stored mode and persists changes; the
     * receiver-side NFC HCE reads this preference once tap-to-share lands.
     */
    private fun wireNfcTapShareMode(view: View) {
        val prefs = NfcTapSharePreferences.from(requireContext())
        val group = view.findViewById<RadioGroup>(R.id.settings_nfc_mode_group)
        val checkedId =
            when (prefs.mode()) {
                NfcTapSharePreferences.Mode.APP_FOREGROUND -> R.id.settings_nfc_mode_foreground
                NfcTapSharePreferences.Mode.BACKGROUND -> R.id.settings_nfc_mode_background
                NfcTapSharePreferences.Mode.SHEET_OPEN -> R.id.settings_nfc_mode_sheet
            }
        group.check(checkedId)
        group.setOnCheckedChangeListener { _, id ->
            val mode =
                when (id) {
                    R.id.settings_nfc_mode_foreground -> NfcTapSharePreferences.Mode.APP_FOREGROUND
                    R.id.settings_nfc_mode_background -> NfcTapSharePreferences.Mode.BACKGROUND
                    else -> NfcTapSharePreferences.Mode.SHEET_OPEN
                }
            prefs.setMode(mode)
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-read the save-location preference on every onStart so the
        // label reflects an external change (e.g. the user revoked the
        // grant in system Settings while we were paused). Falls back to
        // the "Downloads (default)" label when the URI is unset or its
        // grant has been lost.
        refreshSaveLocationLabel()
        refreshAdvertisedNameSection()
        refreshBatteryStatus()
        refreshFullScreenIntentSection()
        refreshBugReportSwitch()
        refreshTransferSwitches()
    }

    /**
     * Re-sync the transfer display switches ("Keep screen on during
     * transfers" #219 and "Expert transfer details" #220) in case another
     * Settings surface or restored app state mutated the preferences while
     * this fragment was alive.
     */
    private fun refreshTransferSwitches() {
        val v = view ?: return
        v
            .findViewById<SwitchCompat>(R.id.main_keep_screen_on_switch)
            ?.refreshChecked(
                KeepScreenOnPreferences
                    .from(requireContext())
                    .isKeepScreenOnDuringTransfersEnabled(),
            )
        v
            .findViewById<SwitchCompat>(R.id.main_transfer_expert_switch)
            ?.refreshChecked(TransferExpertViewPreferences.from(requireContext()).isExpertViewEnabled())
    }

    private fun SwitchCompat.refreshChecked(enabled: Boolean) {
        if (isChecked != enabled) {
            isChecked = enabled
        }
    }

    override fun onResume() {
        super.onResume()
        // Full-screen-intent access is toggled on a system Settings page
        // that, like the battery overlay, can dismiss without fully
        // stopping this activity on some OEM ROMs. Re-check on resume so
        // the status line flips immediately after the user grants it.
        refreshFullScreenIntentSection()
        // Re-check the battery-optimization exemption on every resume.
        // The platform's `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
        // dialog is a translucent overlay on some OEM ROMs (vivo
        // OriginOS, in particular) — when it dismisses, the host
        // fragment lifecycle does not always fall back through
        // `onStart` because the underlying activity was never fully
        // stopped. Re-running [refreshBatteryStatus] from `onResume`
        // catches that case so the "Currently: …" label flips
        // immediately after the user grants the exemption, instead
        // of waiting for the next manual tab switch.
        refreshBatteryStatus()
        maybeEscalateBatteryExemption()
    }

    /**
     * After the one-tap battery popup, if the exemption still didn't take
     * (some OEMs — OnePlus ColorOS / vivo OriginOS — show the dialog but
     * silently ignore "Allow"), bounce the user once to App Info, where
     * the battery toggle reliably flips the flag. Guarded by the one-shot
     * [MainActivity.batteryExemptionAwaitingResult] flag so it fires at
     * most once per attempt and never when the popup actually worked.
     */
    private fun maybeEscalateBatteryExemption() {
        if (!MainActivity.batteryExemptionAwaitingResult) return
        MainActivity.batteryExemptionAwaitingResult = false
        if (!BatteryOptimizationOemHelper.isAlreadyExempt(requireContext())) {
            MainActivity.openAppInfo(requireContext())
        }
    }

    /**
     * Re-sync the shake-to-report switch to the preference holder on
     * every onStart so an external write (currently none, but #166
     * adds room for the bug-report flow itself to flip the toggle
     * after a save) reflects without requiring a fragment recreate.
     */
    private fun refreshBugReportSwitch() {
        val v = view ?: return
        val switch = v.findViewById<SwitchCompat>(R.id.main_bug_report_switch) ?: return
        val enabled = BugReportPreferences.from(requireContext()).isShakeToReportEnabled()
        if (switch.isChecked != enabled) {
            switch.isChecked = enabled
        }
    }

    /**
     * Update the "Currently: …" line under the Background activity
     * title to match the platform-reported exemption state. Reading
     * via [BatteryOptimizationOemHelper.isAlreadyExempt] (which wraps
     * `PowerManager.isIgnoringBatteryOptimizations`) means a system
     * Settings round trip flips the label without an app restart.
     */
    private fun refreshBatteryStatus() {
        val label = view?.findViewById<TextView>(R.id.settings_battery_status) ?: return
        val exempt = BatteryOptimizationOemHelper.isAlreadyExempt(requireContext())
        label.text =
            if (exempt) {
                getString(R.string.settings_battery_status_exempt)
            } else {
                getString(R.string.settings_battery_status_not_exempt)
            }
    }

    /**
     * Show the full-screen-alerts card only on Android 14+ (where
     * `USE_FULL_SCREEN_INTENT` is a grantable special access) and reflect
     * the current grant state in its status line. On older platforms the
     * permission is install-time, so the whole card stays hidden.
     */
    @Suppress("ReturnCount")
    private fun refreshFullScreenIntentSection() {
        val v = view ?: return
        val card = v.findViewById<View>(R.id.settings_fsi_card) ?: return
        if (!FullScreenIntentPermission.isApplicable()) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val status = v.findViewById<TextView>(R.id.settings_fsi_status) ?: return
        status.text =
            if (FullScreenIntentPermission.isGranted(requireContext())) {
                getString(R.string.settings_fsi_status_granted)
            } else {
                getString(R.string.settings_fsi_status_not_granted)
            }
    }

    /**
     * Update the "Current: …" line under the save-location title to
     * match the persisted preference. Reads via
     * [SaveLocationPreferences] (which already drops the URI when its
     * grant has been revoked) so a stale URI never shows up here as
     * a misleading label.
     */
    private fun refreshSaveLocationLabel() {
        val label = view?.findViewById<TextView>(R.id.main_save_location_current) ?: return
        val ctx = requireContext()
        val savedUri = SaveLocationPreferences.from(ctx).getSaveTreeUri()
        val displayText =
            if (savedUri != null) {
                val name = SaveLocationDisplayName.resolve(ctx, savedUri)
                getString(R.string.main_save_location_current, name)
            } else {
                getString(R.string.main_save_location_default)
            }
        label.text = displayText
    }

    private fun refreshAdvertisedNameSection() {
        val v = view ?: return
        val ctx = requireContext()
        val input = v.findViewById<EditText>(R.id.main_advertised_name_input)
        val custom = AdvertisedDeviceNames.getCustomName(ctx).orEmpty()
        if (input.text?.toString() != custom) {
            input.setText(custom)
        }

        val current = v.findViewById<TextView>(R.id.main_advertised_name_current)
        current.text =
            getString(
                R.string.main_advertised_name_current,
                AdvertisedDeviceNames.resolve(ctx),
            )
    }

    private companion object {
        const val TAG = "BadaMain"
    }
}
