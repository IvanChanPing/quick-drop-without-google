/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.radiohelper

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.radiohelper.ShareRadioSession.RADIO_BOTH
import dev.bluehouse.bada.radiohelper.ShareRadioSession.RADIO_BT
import dev.bluehouse.bada.radiohelper.ShareRadioSession.RADIO_WIFI
import org.junit.Test

/**
 * Pure-JVM tests for [ShareRadioSession.decidePrepare] — the safety-critical
 * "enable-only-what's-off / restore-only-ours / re-entrant-seed" bitmask logic
 * (#234). The Android-touching [ShareRadioSession.prepare] wrapper delegates the
 * whole decision here, so these guard the part that, if wrong, strands the
 * user's Wi-Fi/Bluetooth ON.
 */
class ShareRadioSessionDecisionTest {
    @Test
    fun `both radios off - attempts both and records both enabled`() {
        val d =
            ShareRadioSession.decidePrepare(
                radios = RADIO_BOTH,
                wifiOn = false,
                btOn = false,
                priorEnabledWifi = false,
                priorEnabledBt = false,
            )
        assertThat(d.attemptWifi).isTrue()
        assertThat(d.attemptBt).isTrue()
        assertThat(d.enabledWifi).isTrue()
        assertThat(d.enabledBt).isTrue()
        assertThat(d.alreadyOn).isEqualTo(0)
    }

    @Test
    fun `radios already on - no attempt, records nothing, reports already-on`() {
        val d =
            ShareRadioSession.decidePrepare(
                radios = RADIO_BOTH,
                wifiOn = true,
                btOn = true,
                priorEnabledWifi = false,
                priorEnabledBt = false,
            )
        assertThat(d.attemptWifi).isFalse()
        assertThat(d.attemptBt).isFalse()
        // We did NOT turn them on, so finish() must NOT turn them off.
        assertThat(d.enabledWifi).isFalse()
        assertThat(d.enabledBt).isFalse()
        assertThat(d.alreadyOn).isEqualTo(RADIO_BOTH)
    }

    @Test
    fun `radios value 0 is treated as both`() {
        val d =
            ShareRadioSession.decidePrepare(
                radios = 0,
                wifiOn = false,
                btOn = false,
                priorEnabledWifi = false,
                priorEnabledBt = false,
            )
        assertThat(d.attemptWifi).isTrue()
        assertThat(d.attemptBt).isTrue()
    }

    @Test
    fun `only requested radio is touched`() {
        val d =
            ShareRadioSession.decidePrepare(
                radios = RADIO_WIFI,
                wifiOn = false,
                btOn = false,
                priorEnabledWifi = false,
                priorEnabledBt = false,
            )
        assertThat(d.attemptWifi).isTrue()
        // BT not requested → never attempted or recorded even though it's off.
        assertThat(d.attemptBt).isFalse()
        assertThat(d.enabledBt).isFalse()
    }

    @Test
    fun `mixed - wifi on, bt off - attempts only bt`() {
        val d =
            ShareRadioSession.decidePrepare(
                radios = RADIO_BOTH,
                wifiOn = true,
                btOn = false,
                priorEnabledWifi = false,
                priorEnabledBt = false,
            )
        assertThat(d.attemptWifi).isFalse()
        assertThat(d.attemptBt).isTrue()
        assertThat(d.enabledWifi).isFalse()
        assertThat(d.enabledBt).isTrue()
        assertThat(d.alreadyOn).isEqualTo(RADIO_WIFI)
    }

    @Test
    fun `re-entrant - prior enabled stays enabled even when radio now reads on`() {
        // A 2nd prepare after we already turned Wi-Fi on: Wi-Fi now reads ON, so we
        // don't re-attempt, but the prior "we enabled it" flag must survive so a
        // later finish() still turns it back off (not stranded ON).
        val d =
            ShareRadioSession.decidePrepare(
                radios = RADIO_BOTH,
                wifiOn = true,
                btOn = false,
                priorEnabledWifi = true,
                priorEnabledBt = false,
            )
        assertThat(d.attemptWifi).isFalse()
        assertThat(d.enabledWifi).isTrue()
        assertThat(d.attemptBt).isTrue()
        assertThat(d.enabledBt).isTrue()
    }

    @Test
    fun `re-entrant - prior bt enabled is preserved`() {
        val d =
            ShareRadioSession.decidePrepare(
                radios = RADIO_BT,
                wifiOn = false,
                btOn = true,
                priorEnabledWifi = false,
                priorEnabledBt = true,
            )
        assertThat(d.attemptBt).isFalse()
        assertThat(d.enabledBt).isTrue()
    }
}
