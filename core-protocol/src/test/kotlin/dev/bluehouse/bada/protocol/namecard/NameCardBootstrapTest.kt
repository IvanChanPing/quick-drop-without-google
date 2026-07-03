/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.namecard

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Pure-JVM tests for [NameCardBootstrap] — the fixed 17-byte NFC tap token. */
class NameCardBootstrapTest {
    private fun token(fill: Byte = 7) = ByteArray(NameCardBootstrap.TOKEN_LEN) { fill }

    @Test
    fun `round-trips`() {
        val b = NameCardBootstrap(NameCardBootstrap.CURRENT_VERSION, token())
        assertThat(NameCardBootstrap.parse(b.serialize())).isEqualTo(b)
    }

    @Test
    fun `serialized size is fixed at 17 bytes`() {
        assertThat(NameCardBootstrap(1, token()).serialize().size).isEqualTo(NameCardBootstrap.SIZE)
        assertThat(NameCardBootstrap.SIZE).isEqualTo(17)
    }

    @Test
    fun `parse rejects wrong length`() {
        assertThat(NameCardBootstrap.parse(ByteArray(0))).isNull()
        assertThat(NameCardBootstrap.parse(ByteArray(NameCardBootstrap.SIZE - 1))).isNull()
        assertThat(NameCardBootstrap.parse(ByteArray(NameCardBootstrap.SIZE + 1))).isNull()
    }

    @Test
    fun `constructor rejects a wrong-size token`() {
        assertThrows<IllegalArgumentException> { NameCardBootstrap(1, ByteArray(8)) }
    }

    @Test
    fun `preserves the version byte`() {
        assertThat(NameCardBootstrap.parse(NameCardBootstrap(5, token()).serialize())!!.version)
            .isEqualTo(5)
    }
}
