package com.multivpn.app.importer

import com.multivpn.app.model.Protocol
import com.multivpn.app.model.VpnProfile
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale

sealed interface ImportResult {
    data class Success(val profile: VpnProfile) : ImportResult
    data class Failure(val reason: String) : ImportResult
}

/**
 * Recognizes configuration formats, NOT a VPN connection implementation.
 * Never include raw input, credential strings, or parser exceptions in errors.
 */
object ProfileImporter {
    private const val MAX_INPUT_LENGTH = 256_000

    fun parse(input: String, fileName: String? = null): ImportResult {
        val text = input.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return ImportResult.Failure("配置为空")
        if (text.length > MAX_INPUT_LENGTH) return ImportResult.Failure("配置超过 256 KB 限制")

        val loweredName = fileName?.lowercase(Locale.ROOT).orEmpty()
        return when {
            loweredName.endsWith(".ovpn") || isOpenVpn(text) -> parseOpenVpn(text, fileName)
            isWireGuard(text) -> parseWireGuard(text, fileName)
            loweredName.endsWith(".conf") -> ImportResult.Failure("无法识别 .conf 内容；目前仅支持 WireGuard 配置")
            loweredName.endsWith(".yaml") || loweredName.endsWith(".yml") ->
                ImportResult.Failure("Clash/Mihomo YAML 订阅尚未实现")
            else -> parseLink(text)
        }
    }

    private fun isOpenVpn(text: String): Boolean {
        val lines = text.lines().map(String::trim)
        return lines.any { it == "client" || it.startsWith("remote ") } &&
            lines.any { it.startsWith("remote ") }
    }

    private fun isWireGuard(text: String): Boolean {
        return text.lines().any { it.trim().equals("[Interface]", ignoreCase = true) } &&
            text.lines().any { it.trim().equals("[Peer]", ignoreCase = true) }
    }

    private fun parseOpenVpn(text: String, fileName: String?): ImportResult {
        val lines = text.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
        val remote = lines.firstOrNull { it.startsWith("remote ", ignoreCase = true) }
            ?: return ImportResult.Failure("OpenVPN 缺少 remote 地址")
        val segments = remote.split(Regex("\\s+"))
        val host = segments.getOrNull(1).orEmpty()
        val port = segments.getOrNull(2)?.toIntOrNull() ?: 1194
        if (!validHost(host) || port !in 1..65535) {
            return ImportResult.Failure("OpenVPN remote 主机或端口无效")
        }
        val transportValue = lines.firstOrNull { it.startsWith("proto ", ignoreCase = true) }
            ?.substringAfter(" ")?.lowercase(Locale.ROOT).orEmpty()
        val tcp = transportValue.startsWith("tcp") ||
            (transportValue.isEmpty() && remote.lowercase(Locale.ROOT).endsWith(" tcp"))
        val protocol = if (tcp) Protocol.OPENVPN_TCP else Protocol.OPENVPN_UDP
        val label = fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "OpenVPN " + if (tcp) "TCP" else "UDP"
        return ImportResult.Success(
            VpnProfile(
                name = label.take(80),
                protocol = protocol,
                endpoint = "$host:$port",
                originalConfig = text
            )
        )
    }

    private fun parseWireGuard(text: String, fileName: String?): ImportResult {
        val lines = text.lines().map { it.trim() }
        val interfaceLines = lines.dropWhile { !it.equals("[Interface]", true) }
            .drop(1).takeWhile { !it.startsWith("[") }
        val peerLines = lines.dropWhile { !it.equals("[Peer]", true) }
            .drop(1).takeWhile { !it.startsWith("[") }
        if (interfaceLines.none { it.startsWith("PrivateKey", true) && it.contains("=") } ||
            peerLines.none { it.startsWith("PublicKey", true) && it.contains("=") }
        ) {
            return ImportResult.Failure("WireGuard 配置缺少 PrivateKey 或 Peer PublicKey")
        }
        val remote = peerLines.firstOrNull { it.startsWith("Endpoint", true) && it.contains("=") }
            ?.substringAfter("=")?.trim().orEmpty()
        val hostPort = parseHostPort(remote)
            ?: return ImportResult.Failure("WireGuard Endpoint 需要 host:port（IPv6 请使用 [地址]:端口）")
        val label = fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "WireGuard"
        return ImportResult.Success(
            VpnProfile(
                name = label.take(80),
                protocol = Protocol.WIREGUARD,
                endpoint = hostPort,
                originalConfig = text
            )
        )
    }

    private fun parseLink(text: String): ImportResult {
        if (text.any { it == '\n' || it == '\r' }) {
            return ImportResult.Failure("链接导入一次仅支持一个节点；请使用单独的协议链接")
        }
        val scheme = text.substringBefore("://", "").lowercase(Locale.ROOT)
        val protocol = when (scheme) {
            "hysteria2", "hy2" -> Protocol.HYSTERIA2
            "ss" -> Protocol.SHADOWSOCKS
            "vless" -> Protocol.VLESS
            "vmess" -> Protocol.VMESS
            "trojan" -> Protocol.TROJAN
            "tuic" -> Protocol.TUIC
            "anytls" -> Protocol.ANYTLS
            "socks5", "socks" -> Protocol.SOCKS5
            "http+proxy", "https+proxy" -> Protocol.HTTP_PROXY
            else -> return ImportResult.Failure("暂不支持该配置格式；支持 .ovpn、WireGuard .conf 和常见代理节点链接")
        }

        val encoded = text.substringAfter("://").substringBefore("#")
        if (encoded.isBlank()) return ImportResult.Failure("链接未包含节点信息")

        if (protocol == Protocol.VMESS) {
            val decoded = decodeBase64(encoded)
                ?: return ImportResult.Failure("VMess 链接的 Base64 内容无效")
            if (!decoded.trimStart().startsWith("{")) {
                return ImportResult.Failure("VMess 链接不包含有效的 JSON 配置")
            }
            val host = jsonStringField(decoded, "add")
                ?: return ImportResult.Failure("VMess JSON 缺少 add 字段")
            val portStr = jsonStringField(decoded, "port")
                ?: return ImportResult.Failure("VMess JSON 缺少 port 字段")
            val port = portStr.toIntOrNull()
            if (!validHost(host) || port == null || port !in 1..65535) {
                return ImportResult.Failure("VMess 服务器地址或端口无效")
            }
            val name = jsonStringField(decoded, "ps")?.takeIf { it.isNotBlank() }
                ?: "VMess · $host"
            return ImportResult.Success(
                VpnProfile(name = name.take(80), protocol = protocol,
                    endpoint = "$host:$port", originalConfig = text)
            )
        }

        var urlString = text
        if (protocol == Protocol.SHADOWSOCKS) {
            val data = text.substringAfter("://").substringBefore("#").substringBefore("?")
            // Legacy ss://base64(method:password@host:port)
            if (!data.contains("@")) {
                val plain = decodeBase64(data)
                    ?: return ImportResult.Failure("Shadowsocks 链接格式无效")
                val remote = plain.substringAfterLast("@", "")
                val hp = parseHostPort(remote)
                    ?: return ImportResult.Failure("Shadowsocks 链接缺少有效的服务端地址")
                val name = decodeFragment(text) ?: "Shadowsocks · $hp"
                return ImportResult.Success(
                    VpnProfile(name = name.take(80), protocol = protocol,
                        endpoint = hp, originalConfig = text)
                )
            }
            // URI host field works for SIP002 ss://base64(method:password)@host:port.
            urlString = "ss://" + data.substringAfterLast("@")
        }

        val uri = runCatching { URI(urlString) }.getOrNull()
            ?: return ImportResult.Failure("节点链接不是有效 URI，请检查特殊字符转义")
        val host = uri.host
        val port = uri.port
        if (host.isNullOrBlank() || port !in 1..65535) {
            return ImportResult.Failure("节点链接缺少有效 host:port")
        }
        val endpoint = "$host:$port"
        val name = decodeFragment(text) ?: protocol.label + " · " + host
        return ImportResult.Success(
            VpnProfile(name = name.take(80), protocol = protocol,
                endpoint = endpoint, originalConfig = text)
        )
    }

    private fun jsonStringField(json: String, key: String): String? {
        // Minimal JSON field extraction for *display* only; native engine will do full parsing.
        val afterKey = json.substringAfter("\"" + key + "\"", "")
        if (afterKey.isEmpty() || !afterKey.trimStart().startsWith(":")) return null
        val value = afterKey.trimStart().drop(1).trimStart()
        return if (value.startsWith("\"")) {
            value.drop(1).substringBefore("\"").takeIf { it.isNotBlank() }
        } else {
            value.takeWhile { it.isDigit() }.takeIf { it.isNotBlank() }
        }
    }

    private fun decodeBase64(raw: String): String? {
        val value = raw.replace('-', '+').replace('_', '/')
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return runCatching {
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun decodeFragment(link: String): String? {
        val fragment = link.substringAfter("#", "").takeIf { it.isNotBlank() } ?: return null
        return runCatching { URLDecoder.decode(fragment, "UTF-8") }
            .getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseHostPort(endpoint: String): String? {
        if (endpoint.startsWith("[")) {
            val end = endpoint.indexOf("]:")
            if (end < 2) return null
            val host = endpoint.substring(1, end)
            val port = endpoint.substring(end + 2).toIntOrNull()
            return if (validHost(host) && port != null && port in 1..65535) "[$host]:$port" else null
        }
        val colon = endpoint.lastIndexOf(":")
        if (colon < 1 || endpoint.indexOf(":") != colon) return null
        val host = endpoint.substring(0, colon)
        val port = endpoint.substring(colon + 1).toIntOrNull()
        return if (validHost(host) && port != null && port in 1..65535) "$host:$port" else null
    }

    private fun validHost(host: String): Boolean =
        host.isNotBlank() && host.none { it.isWhitespace() || it == '/' || it == '@' }
}
