package com.multivpn.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multivpn.app.engine.EngineRegistry
import com.multivpn.app.importer.ImportResult
import com.multivpn.app.importer.ProfileImporter
import com.multivpn.app.model.Protocol
import com.multivpn.app.model.RoutePolicy
import com.multivpn.app.model.VpnProfile
import com.multivpn.app.storage.ProfileVault
import java.io.ByteArrayOutputStream
import java.io.IOException

private val Ink = Color(0xFF091620)
private val Panel = Color(0xFF132633)
private val Accent = Color(0xFF5EE1C8)
private val Muted = Color(0xFFADC3D0)
private val Amber = Color(0xFFFFC777)

class MainActivity : ComponentActivity() {
    private val vault by lazy { ProfileVault(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val initial = remember { runCatching { vault.loadProfiles() } }
            val storageLocked = initial.isFailure
            var profiles by remember { mutableStateOf(initial.getOrDefault(emptyList())) }
            var policy by remember { mutableStateOf(runCatching { vault.loadPolicy() }.getOrDefault(RoutePolicy())) }
            var notice by remember {
                mutableStateOf(if (storageLocked) "加密配置无法读取。为防止覆盖旧数据，已禁止修改；请检查设备密钥。" else "")
            }

            fun savePolicy(next: RoutePolicy) {
                if (storageLocked) return
                runCatching { vault.savePolicy(next) }
                    .onSuccess { policy = next; notice = "路由偏好已保存（尚未生效）" }
                    .onFailure { notice = "保存路由偏好失败" }
            }

            fun importProfile(text: String, fileName: String? = null) {
                if (storageLocked) return
                when (val parsed = ProfileImporter.parse(text, fileName)) {
                    is ImportResult.Failure -> notice = "导入失败：" + parsed.reason
                    is ImportResult.Success -> {
                        val next = profiles + parsed.profile
                        runCatching { vault.saveProfiles(next) }
                            .onSuccess {
                                profiles = next
                                notice = "已安全保存 " + parsed.profile.protocol.label + " 配置（暂不能连接）"
                            }
                            .onFailure { notice = "加密存储失败，未导入配置" }
                    }
                }
            }

            fun deleteProfile(id: String) {
                if (storageLocked) return
                val nextProfiles = profiles.filterNot { it.id == id }
                val nextPolicy = policy.copy(
                    primaryId = policy.primaryId?.takeUnless { it == id },
                    backupId = policy.backupId?.takeUnless { it == id }
                )
                runCatching {
                    vault.saveProfiles(nextProfiles)
                    vault.savePolicy(nextPolicy)
                }.onSuccess {
                    profiles = nextProfiles
                    policy = nextPolicy
                    notice = "配置已删除"
                }.onFailure { notice = "删除失败，请重试" }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    secondary = Amber,
                    background = Ink,
                    surface = Panel,
                    onSurface = Color.White,
                    onBackground = Color.White,
                    onPrimary = Ink
                )
            ) {
                MultiVpnScreen(
                    profiles = profiles,
                    policy = policy,
                    notice = notice,
                    storageLocked = storageLocked,
                    onImport = ::importProfile,
                    onDelete = ::deleteProfile,
                    onPolicyChange = ::savePolicy,
                    onFileSelected = { uri ->
                        runCatching { readDocument(uri) }
                            .onSuccess { (content, name) -> importProfile(content, name) }
                            .onFailure { notice = "读取文件失败或超过 256 KB，请选择文本 VPN 配置" }
                    }
                )
            }
        }
    }

    private fun readDocument(uri: Uri): Pair<String, String?> {
        val fileName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("File unavailable")
        val bytes = stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (output.size() + size > 256_000) throw IOException("File too large")
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8) to fileName
    }
}

@Composable
private fun MultiVpnScreen(
    profiles: List<VpnProfile>,
    policy: RoutePolicy,
    notice: String,
    storageLocked: Boolean,
    onImport: (String, String?) -> Unit,
    onDelete: (String) -> Unit,
    onPolicyChange: (RoutePolicy) -> Unit,
    onFileSelected: (Uri) -> Unit
) {
    var screen by remember { mutableIntStateOf(0) }
    var paste by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<VpnProfile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFileSelected(uri)
    }

    Scaffold(
        containerColor = Ink,
        topBar = {
            Column(
                modifier = Modifier.fillMaxWidth().background(Ink)
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)
            ) {
                Text("MULTIVPN", color = Accent, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineMedium)
                Text("多协议网络管理中心 · 开发预览 V0.1", color = Muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                NavigationBarItem(
                    selected = screen == 0, onClick = { screen = 0 },
                    icon = { Text("⌂") }, label = { Text("总览") })
                NavigationBarItem(
                    selected = screen == 1, onClick = { screen = 1 },
                    icon = { Text("≡") }, label = { Text("配置导入") })
                NavigationBarItem(
                    selected = screen == 2, onClick = { screen = 2 },
                    icon = { Text("ⓘ") }, label = { Text("支持计划") })
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (notice.isNotEmpty()) {
                Text(notice, color = Amber, style = MaterialTheme.typography.bodyMedium)
            }
            when (screen) {
                0 -> {
                    ConnectionCard()
                    RoutingCard(
                        profiles = profiles, policy = policy,
                        enabled = !storageLocked, onPolicyChange = onPolicyChange
                    )
                    ProfilesCard(profiles, onAddClick = { screen = 1 }, onDelete = { deleting = it })
                }
                1 -> {
                    Section("导入已有 VPN / 代理配置", "密钥加密保存在设备内；不会上传到服务器")
                    OutlinedTextField(
                        value = paste,
                        onValueChange = { paste = it.take(256_000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("粘贴节点链接或完整配置") },
                        placeholder = { Text("hysteria2://…\nss://…\n或 [Interface] / client 配置") },
                        minLines = 5,
                        maxLines = 9
                    )
                    Button(
                        enabled = !storageLocked && paste.isNotBlank(),
                        onClick = { onImport(paste, null) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("识别并安全保存") }
                    OutlinedButton(
                        enabled = !storageLocked,
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从文件导入 .ovpn / WireGuard .conf") }
                    Text(
                        "本阶段可识别：Hysteria 2、Shadowsocks、VLESS、VMess、Trojan、TUIC、AnyTLS、SOCKS5、HTTP 代理链接；WireGuard .conf；OpenVPN .ovpn。每次请导入一条链接。",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("注意：导入成功 ≠ 能建立连接。所有协议核心仍需集成。",
                        color = Amber, style = MaterialTheme.typography.bodySmall)
                    ProfilesCard(profiles, onAddClick = {}, onDelete = { deleting = it }, showAdd = false)
                }
                else -> RoadmapCard()
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (deleting != null) {
        val chosen = deleting!!
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 VPN 配置？") },
            text = { Text("将删除「" + chosen.name + "」以及设备上保存的认证数据。操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = { onDelete(chosen.id); deleting = null }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ConnectionCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接状态", color = Muted, style = MaterialTheme.typography.labelLarge)
            Text("● 未连接", color = Amber, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text("VPN 内核尚未集成。V0.1 只支持配置导入、加密保存和策略预设，不会拦截或修改网络。",
                color = Muted, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("连接（等待协议核心接入）")
            }
        }
    }
}

@Composable
private fun RoutingCard(
    profiles: List<VpnProfile>, policy: RoutePolicy,
    enabled: Boolean, onPolicyChange: (RoutePolicy) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("智能路由 · 规划配置", "此页面仅保存偏好，尚未实际分流")
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("🇨🇳 中国大陆目标直连")
                    Text("未来交由 DNS / 规则引擎执行", color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = policy.chinaDirect,
                    enabled = enabled,
                    onCheckedChange = { onPolicyChange(policy.copy(chinaDirect = it)) }
                )
            }
            HorizontalDivider(color = Ink)
            ProfileSelector(
                title = "主力协议 / 节点",
                selected = profiles.firstOrNull { it.id == policy.primaryId },
                profiles = profiles,
                enabled = enabled,
                onSelect = { chosen ->
                    onPolicyChange(policy.copy(
                        primaryId = chosen,
                        backupId = policy.backupId?.takeUnless { it == chosen }
                    ))
                }
            )
            ProfileSelector(
                title = "备用协议 / 节点",
                selected = profiles.firstOrNull { it.id == policy.backupId },
                profiles = profiles.filterNot { it.id == policy.primaryId },
                enabled = enabled,
                onSelect = { chosen -> onPolicyChange(policy.copy(backupId = chosen)) }
            )
        }
    }
}

@Composable
private fun ProfileSelector(
    title: String,
    selected: VpnProfile?,
    profiles: List<VpnProfile>,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Muted, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(
            enabled = enabled,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selected?.let { it.protocol.label + " · " + it.name } ?: "尚未选择",
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text("⌄")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("不指定") },
                onClick = { expanded = false; onSelect(null) }
            )
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.protocol.label + " · " + profile.name) },
                    onClick = { expanded = false; onSelect(profile.id) }
                )
            }
        }
    }
}

@Composable
private fun ProfilesCard(
    profiles: List<VpnProfile>,
    onAddClick: () -> Unit,
    onDelete: (VpnProfile) -> Unit,
    showAdd: Boolean = true
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("我的 VPN 配置", "共 " + profiles.size + " 条 · 状态均为「等待协议引擎」")
            if (profiles.isEmpty()) {
                Text("还没有节点配置。可以导入你已有的 Hysteria 2 / Shadowsocks / WireGuard / OpenVPN 配置。",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                profiles.forEach { profile ->
                    HorizontalDivider(color = Ink)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(profile.protocol.label + " · " + profile.endpoint,
                                color = Muted, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onDelete(profile) }) { Text("删除") }
                    }
                }
            }
            if (showAdd) {
                OutlinedButton(onClick = onAddClick, modifier = Modifier.fillMaxWidth()) {
                    Text("＋ 添加配置")
                }
            }
        }
    }
}

@Composable
private fun RoadmapCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Section("多协议发展路线", "支持计划并非当前可连接能力")
            listOf(
                "V0.1 · 已实现：节点导入、Keystore 加密、策略预设、基础测试",
                "V0.2 · 下一步：sing-box 接入 Hysteria 2 / Shadowsocks 的 Android VpnService 真实隧道",
                "V0.3 · VLESS / VMess / Trojan / TUIC / WireGuard，连接生命周期与稳定性",
                "V0.4 · OpenVPN TCP/UDP 独立引擎，配置证书管理和引擎切换",
                "V0.5 · 中国大陆直连、DNS / 应用分流、测速、故障转移",
                "未来 · IKEv2/IPsec、OpenConnect、SSTP、SoftEther 适配器"
            ).forEach {
                Text("• " + it, color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text("各 VPN 核心的版本、许可和 Android API 兼容性必须在接入前验证。",
                color = Amber, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
