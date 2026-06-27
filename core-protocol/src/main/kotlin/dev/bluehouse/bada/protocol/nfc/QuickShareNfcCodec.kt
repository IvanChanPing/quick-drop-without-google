/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.nfc

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Byte-level codec for Google Quick Share / Nearby Connections **NFC
 * tap-to-share** (AID `F00000FE2C`). Hand-rolls the three wire shapes the
 * tap exchanges, with no protobuf runtime dependency:
 *
 *  - [encodeHhwwRequest] / [HhwwRequest] — the reader→HCE ADVERTISEMENT
 *    request (`Lhhww;`, 3 protobuf fields).
 *  - [encodeHhwvResponse] / [parseHhwvResponse] — the HCE→reader response
 *    (`Lhhwv;`, 3 protobuf fields) carrying the NfcTag + rxAdv blobs.
 *  - [encodeNfcTag] / [parseNfcTag] — the `deym` NfcTag blob (fixed
 *    big-endian framing).
 *  - [encodeWifiLanRxAdv] / [parseWifiLanEndpoint] — the
 *    `rxInstantConnectionAdv` Nearby Data-Element (DE) TLV stream
 *    advertising a Wi-Fi-LAN IP:port.
 *
 * Every offset, tag, length, and the DE header form were verified against
 * GMS 26.18.33 smali (see `docs/NFC_INTEROP_BYTEMAP.md` §1, §3, §4 for the
 * file:line citations). Where a value could not be cross-verified from the
 * reference (the PCP↔Strategy integer, the NC encryption-key contents) the
 * KDoc flags it as best-effort.
 *
 * This object is pure-JVM (no `android.*`) so it lives in `:core-protocol`
 * and is reachable from both the HCE service (`:app`) and the receiver
 * service (`:service-android`).
 *
 * The wire constants here (APDU header bytes, TLV tags, fixed field offsets and
 * lengths) are inherently numeric, so the object suppresses detekt's MagicNumber —
 * the per-byte protocol comments document each value better than a named constant
 * per offset would.
 */
@Suppress("MagicNumber")
public object QuickShareNfcCodec {
    // ---- AID + APDU constants (verified §memory: NfcAdvertisingChimeraService) ----

    /** Nearby Connections NFC advertising AID. */
    public val ADVERTISING_AID: ByteArray =
        byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0xFE.toByte(), 0x2C)

    /** SELECT-by-name APDU header (`00 A4 04 00`). */
    public const val INS_SELECT: Byte = 0xA4.toByte()

    /** ADVERTISEMENT APDU class/instruction (`80 01`). Reads the static tag. */
    public const val CLA_PROPRIETARY: Byte = 0x80.toByte()
    public const val INS_ADVERTISEMENT: Byte = 0x01

    /** ISO-7816 success / failure status words. */
    public val SW_OK: ByteArray = byteArrayOf(0x90.toByte(), 0x00)
    public val SW_FILE_NOT_FOUND: ByteArray = byteArrayOf(0x6A.toByte(), 0x82.toByte())
    public val SW_INS_NOT_SUPPORTED: ByteArray = byteArrayOf(0x6D.toByte(), 0x00)
    public val SW_WRONG_LENGTH: ByteArray = byteArrayOf(0x67.toByte(), 0x00)

    // ---- Nearby Data-Element (DE) types (verified §4) ----
    private const val DE_TYPE_ENCRYPTION_KEY = 0x17
    private const val DE_TYPE_CONNECTIVITY_CAP = 0x15
    private const val NC_ENCRYPTION_KEY_LEN = 0x20

    // Connectivity-capability medium sub-types (first payload byte).
    private const val MEDIUM_WIFI_LAN_IPV4 = 0x02
    private const val MEDIUM_WIFI_LAN_IPV6 = 0x03

    private const val IPV4_LEN = 4
    private const val IPV6_LEN = 16
    private const val PORT_LEN = 2
    private const val BSSID_LEN = 6

    // deym NfcTag framing (verified §3).
    private const val PCP_MASK = 0x1F
    private const val VERSION_SHIFT = 5
    private const val ENDPOINT_ID_LEN = 4
    private const val SERVICE_ID_HASH_LEN = 3
    private const val BT_MAC_LEN = 6

    /**
     * Strategy/PCP value we advertise. Quick Share's file-transfer session
     * uses Strategy **P2P_POINT_TO_POINT**, and the stock receiver's post-tap
     * handler (`dfdo.run`) DISCARDS the tag unless its PCP equals
     * `dfet.x(localStrategy)`. Verified from GMS 26.18.33 smali: `dfet.x`
     * maps P2P_STAR=1, P2P_CLUSTER=2, P2P_POINT_TO_POINT=3, and the Quick
     * Share Sharing connector pins `Strategy.c` (P2P_POINT_TO_POINT) -> PCP 3
     * -> deym header byte `(1<<5)|3 = 0x23`. (The previous value `2` was both
     * the wrong strategy — that's P2P_CLUSTER — and mislabeled "P2P_STAR".)
     * The deserializer accepts {1,2,3}.
     */
    public const val PCP_P2P_POINT_TO_POINT: Int = 3

    private const val NFC_TAG_VERSION = 1
    private const val UNSIGNED_BYTE = 0xFF

    // -----------------------------------------------------------------
    // Nearby Data-Element (DE) header — `deme.g(length, type)` (§4)
    //   byte0 = (length & 0x7F) | 0x80
    //   byte1 =  type   & 0x7F
    // -----------------------------------------------------------------

    private fun writeDeHeader(
        out: ByteArrayOutputStream,
        length: Int,
        type: Int,
    ) {
        out.write((length and 0x7F) or 0x80)
        out.write(type and 0x7F)
    }

    // =================================================================
    // §4 — rxInstantConnectionAdv (Wi-Fi-LAN)
    // =================================================================

    /**
     * Build a `rxInstantConnectionAdv` advertising a single Wi-Fi-LAN
     * endpoint (our live TCP receiver). Layout (verified `denp.g()` §4):
     *
     * ```
     * [EncryptionKey DE: 0xA0,0x17, <32 key bytes>]
     * [Wi-Fi-LAN ConnectivityCapability DE:
     *    0x80|size, 0x15, version, ip[4|16], port[2 BE], bssid[6]]
     * ```
     *
     * @param ip the receiver's Wi-Fi LAN address (IPv4 or IPv6).
     * @param port the receiver's bound TCP port.
     * @param encryptionKey 32-byte NC public key. The stock parser only
     *   checks size==32 + type==0x17 (best-effort: contents not validated
     *   end-to-end). Callers may pass random bytes.
     * @param bssid optional 6-byte AP BSSID; defaults to all-zero (unknown,
     *   accepted by the length check).
     */
    public fun encodeWifiLanRxAdv(
        ip: InetAddress,
        port: Int,
        encryptionKey: ByteArray,
        bssid: ByteArray = ByteArray(BSSID_LEN),
    ): ByteArray {
        require(encryptionKey.size == NC_ENCRYPTION_KEY_LEN) {
            "encryptionKey must be $NC_ENCRYPTION_KEY_LEN bytes, got ${encryptionKey.size}"
        }
        require(bssid.size == BSSID_LEN) { "bssid must be $BSSID_LEN bytes, got ${bssid.size}" }
        val ipBytes = ip.address
        val (medium, ipLen) =
            when (ip) {
                is Inet4Address -> MEDIUM_WIFI_LAN_IPV4 to IPV4_LEN
                is Inet6Address -> MEDIUM_WIFI_LAN_IPV6 to IPV6_LEN
                else -> error("unsupported InetAddress type ${ip.javaClass.name}")
            }
        require(ipBytes.size == ipLen) { "ip address length mismatch: ${ipBytes.size} != $ipLen" }

        val out = ByteArrayOutputStream()

        // EncryptionKey DE.
        writeDeHeader(out, NC_ENCRYPTION_KEY_LEN, DE_TYPE_ENCRYPTION_KEY)
        out.write(encryptionKey)

        // Wi-Fi-LAN ConnectivityCapability DE.
        val capLen = 1 + ipLen + PORT_LEN + BSSID_LEN
        writeDeHeader(out, capLen, DE_TYPE_CONNECTIVITY_CAP)
        out.write(medium)
        out.write(ipBytes)
        out.write((port ushr 8) and UNSIGNED_BYTE)
        out.write(port and UNSIGNED_BYTE)
        out.write(bssid)

        return out.toByteArray()
    }

    /**
     * A Wi-Fi-LAN endpoint parsed out of a peer's rxAdv.
     */
    public data class WifiLanEndpoint(
        val address: InetAddress,
        val port: Int,
    )

    /**
     * Scan a `rxInstantConnectionAdv` DE stream and return the first
     * Wi-Fi-LAN endpoint, or `null` if none is present / the stream is
     * malformed. The EncryptionKey DE and any non-LAN capability DEs are
     * skipped. Mirrors `dfga.a` (§4) but only extracts the LAN tuple we
     * use; we tolerate (skip) DE types we do not consume.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
    public fun parseWifiLanEndpoint(rxAdv: ByteArray): WifiLanEndpoint? {
        var offset = 0
        while (offset + 2 <= rxAdv.size) {
            val length = rxAdv[offset].toInt() and 0x7F
            val type = rxAdv[offset + 1].toInt() and 0x7F
            val payloadStart = offset + 2
            val payloadEnd = payloadStart + length
            if (payloadEnd > rxAdv.size) return null

            if (type == DE_TYPE_CONNECTIVITY_CAP && length >= 1) {
                val medium = rxAdv[payloadStart].toInt() and UNSIGNED_BYTE
                if (medium == MEDIUM_WIFI_LAN_IPV4 || medium == MEDIUM_WIFI_LAN_IPV6) {
                    val ipLen = if (medium == MEDIUM_WIFI_LAN_IPV4) IPV4_LEN else IPV6_LEN
                    val need = 1 + ipLen + PORT_LEN
                    if (length >= need) {
                        var p = payloadStart + 1
                        val ipBytes = rxAdv.copyOfRange(p, p + ipLen)
                        p += ipLen
                        val port =
                            ((rxAdv[p].toInt() and UNSIGNED_BYTE) shl 8) or
                                (rxAdv[p + 1].toInt() and UNSIGNED_BYTE)
                        val address =
                            runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull()
                                ?: return null
                        return WifiLanEndpoint(address, port)
                    }
                }
            }
            offset = payloadEnd
        }
        return null
    }

    // =================================================================
    // §3 — deym NfcTag blob
    // =================================================================

    /**
     * Build the `deym` NfcTag blob (§3). Big-endian fixed framing:
     *
     * ```
     * [headerByte = (version<<5)|(pcp&0x1F)]
     * [endpointId: 4 ASCII]
     * [serviceIdHash: 3]
     * [infoLen: 1][endpointInfo: infoLen]
     * [btMac: 6]   (all-zero = no MAC)
     * [flags: 1]
     * ```
     *
     * @param endpointId exactly 4 ASCII bytes.
     * @param serviceIdHash exactly 3 bytes (NearbyServiceId hash prefix).
     * @param endpointInfo the Nearby EndpointInfo blob (same as mDNS/BLE).
     * @param btMac optional 6-byte BT-Classic MAC; defaults to all-zero
     *   (the "no MAC" sentinel — the Wi-Fi/multi-medium path needs no MAC).
     * @param pcp Strategy/PCP int (default [PCP_P2P_POINT_TO_POINT]).
     */
    public fun encodeNfcTag(
        endpointId: ByteArray,
        serviceIdHash: ByteArray,
        endpointInfo: ByteArray,
        btMac: ByteArray = ByteArray(BT_MAC_LEN),
        pcp: Int = PCP_P2P_POINT_TO_POINT,
    ): ByteArray {
        require(endpointId.size == ENDPOINT_ID_LEN) {
            "endpointId must be $ENDPOINT_ID_LEN bytes, got ${endpointId.size}"
        }
        require(serviceIdHash.size == SERVICE_ID_HASH_LEN) {
            "serviceIdHash must be $SERVICE_ID_HASH_LEN bytes, got ${serviceIdHash.size}"
        }
        require(btMac.size == BT_MAC_LEN) { "btMac must be $BT_MAC_LEN bytes, got ${btMac.size}" }
        require(endpointInfo.size <= UNSIGNED_BYTE) {
            "endpointInfo must fit in a 1-byte length, got ${endpointInfo.size}"
        }
        require(pcp and PCP_MASK == pcp) { "pcp must fit in 5 bits, got $pcp" }

        val out = ByteArrayOutputStream()
        out.write(((NFC_TAG_VERSION shl VERSION_SHIFT) or (pcp and PCP_MASK)) and UNSIGNED_BYTE)
        out.write(endpointId)
        out.write(serviceIdHash)
        out.write(endpointInfo.size and UNSIGNED_BYTE)
        out.write(endpointInfo)
        out.write(btMac)
        out.write(0x00) // flags
        return out.toByteArray()
    }

    /**
     * A parsed NfcTag (§3). [btMac] is `null` when the on-wire MAC is the
     * all-zero "no MAC" sentinel.
     */
    public data class NfcTag(
        val version: Int,
        val pcp: Int,
        val endpointId: ByteArray,
        val serviceIdHash: ByteArray,
        val endpointInfo: ByteArray,
        val btMac: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NfcTag) return false
            return version == other.version &&
                pcp == other.pcp &&
                endpointId.contentEquals(other.endpointId) &&
                serviceIdHash.contentEquals(other.serviceIdHash) &&
                endpointInfo.contentEquals(other.endpointInfo) &&
                (btMac?.contentEquals(other.btMac ?: ByteArray(0)) ?: (other.btMac == null))
        }

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + pcp
            result = 31 * result + endpointId.contentHashCode()
            result = 31 * result + serviceIdHash.contentHashCode()
            result = 31 * result + endpointInfo.contentHashCode()
            result = 31 * result + (btMac?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Parse a `deym` NfcTag blob (§3). Returns `null` on any truncation /
     * unsupported PCP. The endpointInfo length is the explicit byte at
     * offset 8; the trailing 6-byte MAC + 1 flags byte are optional (a tag
     * may omit them — we treat a missing MAC as `null`).
     */
    @Suppress("ReturnCount")
    public fun parseNfcTag(blob: ByteArray): NfcTag? {
        val fixedHeader = 1 + ENDPOINT_ID_LEN + SERVICE_ID_HASH_LEN + 1
        if (blob.size < fixedHeader) return null
        var offset = 0
        val header = blob[offset].toInt() and UNSIGNED_BYTE
        offset += 1
        val version = header ushr VERSION_SHIFT
        val pcp = header and PCP_MASK
        if (pcp != 1 && pcp != 2 && pcp != 3) return null

        val endpointId = blob.copyOfRange(offset, offset + ENDPOINT_ID_LEN)
        offset += ENDPOINT_ID_LEN
        val serviceIdHash = blob.copyOfRange(offset, offset + SERVICE_ID_HASH_LEN)
        offset += SERVICE_ID_HASH_LEN
        val infoLen = blob[offset].toInt() and UNSIGNED_BYTE
        offset += 1
        if (offset + infoLen > blob.size) return null
        val endpointInfo = blob.copyOfRange(offset, offset + infoLen)
        offset += infoLen

        val btMac: ByteArray? =
            if (offset + BT_MAC_LEN <= blob.size) {
                val mac = blob.copyOfRange(offset, offset + BT_MAC_LEN)
                offset += BT_MAC_LEN
                if (mac.all { it == 0.toByte() }) null else mac
            } else {
                null
            }
        // Trailing flags byte (if present) is ignored.
        return NfcTag(version, pcp, endpointId, serviceIdHash, endpointInfo, btMac)
    }

    // =================================================================
    // §1 — hhww request / hhwv response (protobuf-lite, hand-rolled)
    // =================================================================

    /** The reader→HCE ADVERTISEMENT request (`Lhhww;`). */
    public data class HhwwRequest(
        val serviceId: String,
        val field2: String? = null,
        val field3: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HhwwRequest) return false
            return serviceId == other.serviceId &&
                field2 == other.field2 &&
                (field3?.contentEquals(other.field3 ?: ByteArray(0)) ?: (other.field3 == null))
        }

        override fun hashCode(): Int {
            var result = serviceId.hashCode()
            result = 31 * result + (field2?.hashCode() ?: 0)
            result = 31 * result + (field3?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Encode an `hhww` request (§1): field1=serviceId (string, tag 0x0A),
     * field2=string (tag 0x12), field3=bytes (tag 0x1A).
     */
    public fun encodeHhwwRequest(request: HhwwRequest): ByteArray {
        val out = ByteArrayOutputStream()
        writeProtoString(out, fieldNumber = 1, value = request.serviceId)
        request.field2?.let { writeProtoString(out, fieldNumber = 2, value = it) }
        request.field3?.let { writeProtoBytes(out, fieldNumber = 3, value = it) }
        return out.toByteArray()
    }

    /** Parse an `hhww` request (§1). Returns `null` on malformed input. */
    @Suppress("ReturnCount")
    public fun parseHhwwRequest(bytes: ByteArray): HhwwRequest? {
        val fields = parseProtoFields(bytes) ?: return null
        val serviceId = fields[1]?.let { decodeUtf8(it) } ?: return null
        val field2 = fields[2]?.let { decodeUtf8(it) }
        val field3 = fields[3]
        return HhwwRequest(serviceId, field2, field3)
    }

    /** The HCE→reader response (`Lhhwv;`). */
    public data class HhwvResponse(
        val nfcTag: ByteArray,
        val rxAdv: ByteArray? = null,
        val field3: ByteArray? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HhwvResponse) return false
            return nfcTag.contentEquals(other.nfcTag) &&
                (rxAdv?.contentEquals(other.rxAdv ?: ByteArray(0)) ?: (other.rxAdv == null)) &&
                (field3?.contentEquals(other.field3 ?: ByteArray(0)) ?: (other.field3 == null))
        }

        override fun hashCode(): Int {
            var result = nfcTag.contentHashCode()
            result = 31 * result + (rxAdv?.contentHashCode() ?: 0)
            result = 31 * result + (field3?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Encode an `hhwv` response (§1): field1=nfcTag (bytes, tag 0x0A),
     * field2=rxAdv (bytes, tag 0x12), field3=bytes (tag 0x1A). Optional
     * fields are emitted only when non-null.
     */
    public fun encodeHhwvResponse(response: HhwvResponse): ByteArray {
        val out = ByteArrayOutputStream()
        writeProtoBytes(out, fieldNumber = 1, value = response.nfcTag)
        response.rxAdv?.let { writeProtoBytes(out, fieldNumber = 2, value = it) }
        response.field3?.let { writeProtoBytes(out, fieldNumber = 3, value = it) }
        return out.toByteArray()
    }

    /** Parse an `hhwv` response (§1). Returns `null` on malformed input. */
    @Suppress("ReturnCount")
    public fun parseHhwvResponse(bytes: ByteArray): HhwvResponse? {
        val fields = parseProtoFields(bytes) ?: return null
        val nfcTag = fields[1] ?: return null
        return HhwvResponse(nfcTag, fields[2], fields[3])
    }

    // -----------------------------------------------------------------
    // Minimal protobuf wire helpers (length-delimited fields 1..3 only)
    // -----------------------------------------------------------------

    private const val WIRE_TYPE_LEN_DELIMITED = 2
    private const val VARINT_CONTINUATION = 0x80
    private const val VARINT_PAYLOAD = 0x7F
    private const val VARINT_SHIFT = 7

    private fun writeProtoString(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: String,
    ) {
        writeProtoBytes(out, fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    private fun writeProtoBytes(
        out: ByteArrayOutputStream,
        fieldNumber: Int,
        value: ByteArray,
    ) {
        writeVarint(out, (fieldNumber shl 3) or WIRE_TYPE_LEN_DELIMITED)
        writeVarint(out, value.size)
        out.write(value)
    }

    private fun writeVarint(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        var v = value
        while (true) {
            val b = v and VARINT_PAYLOAD
            v = v ushr VARINT_SHIFT
            if (v == 0) {
                out.write(b)
                return
            }
            out.write(b or VARINT_CONTINUATION)
        }
    }

    /**
     * Parse a protobuf message into a map of field-number → last-seen
     * length-delimited value. Only length-delimited fields are retained;
     * other wire types are skipped. Returns `null` on truncation.
     */
    @Suppress("ReturnCount")
    private fun parseProtoFields(bytes: ByteArray): Map<Int, ByteArray>? {
        val result = mutableMapOf<Int, ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, offset) ?: return null
            offset = afterTag
            val fieldNumber = tag ushr 3
            when (tag and 0x7) {
                WIRE_TYPE_LEN_DELIMITED -> {
                    val (len, afterLen) = readVarint(bytes, offset) ?: return null
                    offset = afterLen
                    if (offset + len > bytes.size) return null
                    result[fieldNumber] = bytes.copyOfRange(offset, offset + len)
                    offset += len
                }
                0 -> { // varint — skip
                    val (_, after) = readVarint(bytes, offset) ?: return null
                    offset = after
                }
                5 -> offset += 4 // fixed32
                1 -> offset += 8 // fixed64
                else -> return null
            }
        }
        return result
    }

    /** Read a base-128 varint; returns (value, nextOffset) or `null`. */
    @Suppress("ReturnCount")
    private fun readVarint(
        bytes: ByteArray,
        start: Int,
    ): Pair<Int, Int>? {
        var result = 0
        var shift = 0
        var offset = start
        while (offset < bytes.size) {
            val b = bytes[offset].toInt() and UNSIGNED_BYTE
            result = result or ((b and VARINT_PAYLOAD) shl shift)
            offset += 1
            if (b and VARINT_CONTINUATION == 0) return result to offset
            shift += VARINT_SHIFT
            if (shift >= 32) return null
        }
        return null
    }

    private fun decodeUtf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
}
