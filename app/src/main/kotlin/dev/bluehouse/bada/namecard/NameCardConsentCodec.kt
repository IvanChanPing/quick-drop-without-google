/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

/**
 * **Name Card v2 consent wire codec** — the tiny opcode language the two phones speak on the CONSENT
 * GATT characteristic during a symmetric NameDrop exchange (see
 * the Name Card design notes §7/§7b/B1).
 *
 * ## What it is / what carries it
 * A one-byte-opcode framing (HELLO also carries a 1-byte version). The client WRITES these to the
 * server's CONSENT characteristic; the server NOTIFIES them back to the client. It exists so each
 * side learns the other's Share / Receive-Only choice the instant it is made — v1 had no such
 * channel (the server just handed its card over on connect, and a decline was silent).
 *
 * ## Messages ([ConsentMessage])
 *  - [ConsentMessage.Hello] `0x01 <version>` — sent on connect/subscribe; presence of a HELLO is
 *    how each side confirms the peer speaks v2 (its absence = legacy v1 peer, handled in the BLE
 *    layer, [NameCardBleExchange]).
 *  - [ConsentMessage.ChoiceShare] `0x02` — "I tapped Share" (my card is/was sent).
 *  - [ConsentMessage.ChoiceReceiveOnly] `0x03` — "I tapped Receive Only" (I will not send my card).
 *  - [ConsentMessage.Bye] `0x04` — terminal marker so the peer distinguishes "done" from a dropped
 *    link (a raw disconnect before both chose = No Response).
 *
 * ## Forward compatibility (pinned, B1)
 * [decode] tolerates EXTRA trailing bytes beyond a message's spec length (parse the known prefix and
 * ignore the rest) so a future build can append fields without breaking this one. Malformed input —
 * empty array, unknown opcode, or a HELLO shorter than 2 bytes — decodes to `null`; the caller logs
 * and ignores it (the peer is another build we don't fully control).
 *
 * ## Status
 * Pure-JVM (zero `android.*` imports), exhaustively unit-tested in `NameCardConsentCodecTest`
 * (round-trip all four opcodes; every malformed case; trailing-byte tolerance). The BLE transport
 * that carries these bytes ([NameCardBleExchange]) is compile-only on this box; device-verified only.
 */
internal object NameCardConsentCodec {
    /** Opcode: HELLO — `0x01 <version>`. */
    const val OP_HELLO: Byte = 0x01

    /** Opcode: CHOICE_SHARE — `0x02`. */
    const val OP_CHOICE_SHARE: Byte = 0x02

    /** Opcode: CHOICE_RECEIVE_ONLY — `0x03`. */
    const val OP_CHOICE_RECEIVE_ONLY: Byte = 0x03

    /** Opcode: BYE — `0x04`. */
    const val OP_BYE: Byte = 0x04

    /** Protocol version carried in HELLO by this build. */
    const val PROTOCOL_VERSION: Byte = 0x01

    /** Encode a [message] to its wire bytes. */
    fun encode(message: ConsentMessage): ByteArray =
        when (message) {
            is ConsentMessage.Hello -> byteArrayOf(OP_HELLO, message.version)
            ConsentMessage.ChoiceShare -> byteArrayOf(OP_CHOICE_SHARE)
            ConsentMessage.ChoiceReceiveOnly -> byteArrayOf(OP_CHOICE_RECEIVE_ONLY)
            ConsentMessage.Bye -> byteArrayOf(OP_BYE)
        }

    /** Convenience: this build's HELLO bytes (`0x01 0x01`). */
    fun helloBytes(): ByteArray = encode(ConsentMessage.Hello(PROTOCOL_VERSION))

    /**
     * Decode wire [bytes] into a [ConsentMessage], or `null` if malformed (empty, unknown opcode, or
     * a HELLO shorter than 2 bytes). Extra trailing bytes beyond the spec length are tolerated
     * (forward-compat) — only the known prefix is read.
     */
    fun decode(bytes: ByteArray): ConsentMessage? {
        if (bytes.isEmpty()) return null
        return when (bytes[0]) {
            OP_HELLO -> if (bytes.size >= 2) ConsentMessage.Hello(bytes[1]) else null
            OP_CHOICE_SHARE -> ConsentMessage.ChoiceShare
            OP_CHOICE_RECEIVE_ONLY -> ConsentMessage.ChoiceReceiveOnly
            OP_BYE -> ConsentMessage.Bye
            else -> null
        }
    }
}

/** A decoded consent-channel message. See [NameCardConsentCodec]. */
internal sealed interface ConsentMessage {
    /** `0x01 <version>` — peer speaks v2; [version] is its protocol version. */
    data class Hello(
        val version: Byte,
    ) : ConsentMessage

    /** `0x02` — peer tapped Share. */
    data object ChoiceShare : ConsentMessage

    /** `0x03` — peer tapped Receive Only. */
    data object ChoiceReceiveOnly : ConsentMessage

    /** `0x04` — peer is closing the link after reaching a terminal state. */
    data object Bye : ConsentMessage
}
