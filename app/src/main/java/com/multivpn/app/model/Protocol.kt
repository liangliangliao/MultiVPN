package com.multivpn.app.model

enum class Protocol(val label: String, val group: String) {
    HYSTERIA2("Hysteria 2", "代理"),
    SHADOWSOCKS("Shadowsocks", "代理"),
    VLESS("VLESS", "代理"),
    VMESS("VMess", "代理"),
    TROJAN("Trojan", "代理"),
    TUIC("TUIC", "代理"),
    ANYTLS("AnyTLS", "代理"),
    WIREGUARD("WireGuard", "VPN"),
    OPENVPN_UDP("OpenVPN UDP", "VPN"),
    OPENVPN_TCP("OpenVPN TCP", "VPN"),
    SOCKS5("SOCKS5", "代理"),
    HTTP_PROXY("HTTP(S) Proxy", "代理"),
    IKEV2("IKEv2/IPsec", "VPN"),
    OPENCONNECT("OpenConnect", "VPN"),
    SSTP("SSTP", "VPN"),
    SOFTETHER("SoftEther", "VPN");

    companion object {
        val planned: List<Protocol> = entries
    }
}
