package com.multivpn.app.importer

import com.multivpn.app.model.Protocol
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ProfileImporterTest {
    private fun success(config: String, filename: String? = null) =
        (ProfileImporter.parse(config, filename) as ImportResult.Success).profile

    @Test fun hysteria2Uri() {
        val result = success("hysteria2://password@example.org:443?sni=example.org#Primary")
        assertEquals(Protocol.HYSTERIA2, result.protocol)
        assertEquals("Primary", result.name)
        assertEquals("example.org:443", result.endpoint)
    }

    @Test fun shadowsocksSip002Uri() {
        val result = success("ss://YWVzLTI1Ni1nY206cGFzcw==@vpn.example.org:8388#Backup")
        assertEquals(Protocol.SHADOWSOCKS, result.protocol)
        assertEquals("vpn.example.org:8388", result.endpoint)
    }

    @Test fun shadowsocksLegacyBase64() {
        val data = Base64.getEncoder().encodeToString("aes-256-gcm:secret@proxy.example.org:443".toByteArray())
        assertEquals("proxy.example.org:443", success("ss://$data").endpoint)
    }

    @Test fun wireguardConf() {
        val config = """
            [Interface]
            PrivateKey = FAKE-PRIVATE-KEY
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = FAKE-PUBLIC-KEY
            AllowedIPs = 0.0.0.0/0
            Endpoint = wg.example.org:51820
        """.trimIndent()
        val result = success(config, "home.conf")
        assertEquals(Protocol.WIREGUARD, result.protocol)
        assertEquals("wg.example.org:51820", result.endpoint)
        assertEquals("home", result.name)
    }

    @Test fun openVpnTcp() {
        val result = success("client\nproto tcp-client\nremote ovpn.example.org 443\n", "office.ovpn")
        assertEquals(Protocol.OPENVPN_TCP, result.protocol)
        assertEquals("ovpn.example.org:443", result.endpoint)
    }

    @Test fun openVpnUdp() {
        assertEquals(Protocol.OPENVPN_UDP,
            success("client\nproto udp\nremote ovpn.example.org 1194").protocol)
    }

    @Test fun vmessJsonBase64() {
        val json = """{"v":"2","ps":"VMess demo","add":"vm.example.org","port":"443","id":"fake"}"""
        val url = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())
        val result = success(url)
        assertEquals("vm.example.org:443", result.endpoint)
        assertEquals("VMess demo", result.name)
    }

    @Test fun rejectsInvalidPort() {
        assertTrue(ProfileImporter.parse("vless://uuid@example.org:65536") is ImportResult.Failure)
    }

    @Test fun rejectsMissingOpenVpnRemote() {
        assertTrue(ProfileImporter.parse("client\nproto udp\n", "broken.ovpn") is ImportResult.Failure)
    }

    @Test fun rejectsYamlUntilImplemented() {
        assertTrue(ProfileImporter.parse("proxies: []", "clash.yaml") is ImportResult.Failure)
    }

    @Test fun rejectsMultipleLinks() {
        assertTrue(ProfileImporter.parse("trojan://a@vpn.example.org:443\nvless://a@vpn.example.org:443") is ImportResult.Failure)
    }
}
