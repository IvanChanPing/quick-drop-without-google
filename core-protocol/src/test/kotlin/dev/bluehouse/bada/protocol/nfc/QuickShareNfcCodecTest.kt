/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.nfc

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Byte-layout + round-trip tests for [QuickShareNfcCodec]. The golden-byte
 * assertions pin the layout verified from GMS 26.18.33 smali
 * (`docs/NFC_INTEROP_BYTEMAP.md` §1/§3/§4) so a future refactor cannot
 * silently break interop.
 */
class QuickShareNfcCodecTest {
    @Test
    fun wifiLanRxAdv_hasVerifiedGoldenBytes_ipv4() {
        val ip = InetAddress.getByName("192.168.1.42") as Inet4Address
        val key = ByteArray(0x20) { 0x11 }
        val rxAdv = QuickShareNfcCodec.encodeWifiLanRxAdv(ip = ip, port = 0x1F90, encryptionKey = key)

        // EncryptionKey DE header: [0x80|0x20, 0x17] = [0xA0, 0x17] + 32 key bytes.
        assertThat(rxAdv[0]).isEqualTo(0xA0.toByte())
        assertThat(rxAdv[1]).isEqualTo(0x17.toByte())
        // Wi-Fi-LAN cap DE begins at 2 + 32 = 34.
        val cap = 34
        // size byte = 0x0D (13 = 1 ver + 4 ip + 2 port + 6 bssid).
        assertThat(rxAdv[cap]).isEqualTo((0x80 or 0x0D).toByte())
        assertThat(rxAdv[cap + 1]).isEqualTo(0x15.toByte()) // type 0x15
        assertThat(rxAdv[cap + 2]).isEqualTo(0x02.toByte()) // medium IPv4
        // ip 192.168.1.42
        assertThat(rxAdv[cap + 3]).isEqualTo(192.toByte())
        assertThat(rxAdv[cap + 4]).isEqualTo(168.toByte())
        assertThat(rxAdv[cap + 5]).isEqualTo(1.toByte())
        assertThat(rxAdv[cap + 6]).isEqualTo(42.toByte())
        // port 0x1F90 big-endian
        assertThat(rxAdv[cap + 7]).isEqualTo(0x1F.toByte())
        assertThat(rxAdv[cap + 8]).isEqualTo(0x90.toByte())
        // bssid 6 zero bytes
        for (i in 0 until 6) assertThat(rxAdv[cap + 9 + i]).isEqualTo(0.toByte())
        assertThat(rxAdv.size).isEqualTo(cap + 15)
    }

    @Test
    fun wifiLanRxAdv_roundTrips() {
        val ip = InetAddress.getByName("10.0.0.7")
        val key = ByteArray(0x20) { it.toByte() }
        val rxAdv = QuickShareNfcCodec.encodeWifiLanRxAdv(ip = ip, port = 52999, encryptionKey = key)
        val parsed = QuickShareNfcCodec.parseWifiLanEndpoint(rxAdv)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.address).isEqualTo(ip)
        assertThat(parsed.port).isEqualTo(52999)
    }

    @Test
    fun nfcTag_hasVerifiedGoldenBytes() {
        val endpointId = "ABCD".toByteArray(Charsets.US_ASCII)
        val serviceIdHash = byteArrayOf(0x01, 0x02, 0x03)
        val endpointInfo = byteArrayOf(0x10, 0x20, 0x30)
        val tag =
            QuickShareNfcCodec.encodeNfcTag(
                endpointId = endpointId,
                serviceIdHash = serviceIdHash,
                endpointInfo = endpointInfo,
            )
        // header = (1<<5)|3 = 0x23 (PCP 3 = P2P_POINT_TO_POINT, Quick Share's strategy).
        assertThat(tag[0]).isEqualTo(0x23.toByte())
        // endpointId bytes 1..4.
        assertThat(tag.copyOfRange(1, 5)).isEqualTo(endpointId)
        // serviceIdHash bytes 5..7.
        assertThat(tag.copyOfRange(5, 8)).isEqualTo(serviceIdHash)
        // infoLen byte 8.
        assertThat(tag[8]).isEqualTo(3.toByte())
        // endpointInfo bytes 9..11.
        assertThat(tag.copyOfRange(9, 12)).isEqualTo(endpointInfo)
        // btMac 6 zero bytes + 1 flags byte.
        assertThat(tag.copyOfRange(12, 18)).isEqualTo(ByteArray(6))
        assertThat(tag[18]).isEqualTo(0.toByte())
        assertThat(tag.size).isEqualTo(19)
    }

    @Test
    fun nfcTag_roundTrips_noMac() {
        val endpointId = "WXYZ".toByteArray(Charsets.US_ASCII)
        val serviceIdHash = byteArrayOf(0x0A, 0x0B, 0x0C)
        val endpointInfo = ByteArray(20) { it.toByte() }
        val tag =
            QuickShareNfcCodec.encodeNfcTag(
                endpointId = endpointId,
                serviceIdHash = serviceIdHash,
                endpointInfo = endpointInfo,
            )
        val parsed = QuickShareNfcCodec.parseNfcTag(tag)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.version).isEqualTo(1)
        assertThat(parsed.pcp).isEqualTo(QuickShareNfcCodec.PCP_P2P_POINT_TO_POINT)
        assertThat(parsed.endpointId).isEqualTo(endpointId)
        assertThat(parsed.serviceIdHash).isEqualTo(serviceIdHash)
        assertThat(parsed.endpointInfo).isEqualTo(endpointInfo)
        assertThat(parsed.btMac).isNull()
    }

    @Test
    fun nfcTag_rejectsUnsupportedPcp() {
        val endpointId = "ABCD".toByteArray(Charsets.US_ASCII)
        val tag =
            QuickShareNfcCodec.encodeNfcTag(
                endpointId = endpointId,
                serviceIdHash = byteArrayOf(1, 2, 3),
                endpointInfo = byteArrayOf(9),
            )
        // Corrupt PCP to 0 (unsupported — only {1,2,3} accepted).
        tag[0] = (tag[0].toInt() and 0xE0).toByte()
        assertThat(QuickShareNfcCodec.parseNfcTag(tag)).isNull()
    }

    @Test
    fun hhwwRequest_roundTrips_andHasVerifiedTags() {
        val req = QuickShareNfcCodec.HhwwRequest(serviceId = "NearbySharing")
        val bytes = QuickShareNfcCodec.encodeHhwwRequest(req)
        // field 1 = string => tag byte 0x0A, then length 13.
        assertThat(bytes[0]).isEqualTo(0x0A.toByte())
        assertThat(bytes[1]).isEqualTo(13.toByte())
        val parsed = QuickShareNfcCodec.parseHhwwRequest(bytes)
        assertThat(parsed).isEqualTo(req)
    }

    @Test
    fun hhwvResponse_roundTrips_andHasVerifiedTags() {
        val nfcTag = byteArrayOf(0x23, 1, 2, 3, 4)
        val rxAdv = byteArrayOf(0x55, 0x66)
        val resp = QuickShareNfcCodec.HhwvResponse(nfcTag = nfcTag, rxAdv = rxAdv)
        val bytes = QuickShareNfcCodec.encodeHhwvResponse(resp)
        // field 1 = bytes => tag 0x0A; field 2 = bytes => tag 0x12.
        assertThat(bytes[0]).isEqualTo(0x0A.toByte())
        assertThat(bytes[1]).isEqualTo(nfcTag.size.toByte())
        val parsed = QuickShareNfcCodec.parseHhwvResponse(bytes)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.nfcTag).isEqualTo(nfcTag)
        assertThat(parsed.rxAdv).isEqualTo(rxAdv)
        assertThat(parsed.field3).isNull()
    }

    @Test
    fun fullExchange_hceToReader_roundTrips() {
        // Simulate the HCE building a response and the reader parsing it.
        val endpointInfo = ByteArray(17) { (it + 1).toByte() }
        val nfcTag =
            QuickShareNfcCodec.encodeNfcTag(
                endpointId = "QShr".toByteArray(Charsets.US_ASCII),
                serviceIdHash = byteArrayOf(0x7A, 0x55, 0x12),
                endpointInfo = endpointInfo,
            )
        val rxAdv =
            QuickShareNfcCodec.encodeWifiLanRxAdv(
                ip = InetAddress.getByName("172.16.5.9"),
                port = 8959,
                encryptionKey = ByteArray(0x20),
            )
        val response =
            QuickShareNfcCodec.encodeHhwvResponse(
                QuickShareNfcCodec.HhwvResponse(nfcTag = nfcTag, rxAdv = rxAdv),
            )

        val parsedResp = QuickShareNfcCodec.parseHhwvResponse(response)!!
        val parsedTag = QuickShareNfcCodec.parseNfcTag(parsedResp.nfcTag)!!
        val lan = QuickShareNfcCodec.parseWifiLanEndpoint(parsedResp.rxAdv!!)!!

        assertThat(String(parsedTag.endpointId, Charsets.US_ASCII)).isEqualTo("QShr")
        assertThat(parsedTag.endpointInfo).isEqualTo(endpointInfo)
        assertThat(lan.address).isEqualTo(InetAddress.getByName("172.16.5.9"))
        assertThat(lan.port).isEqualTo(8959)
    }
}
