# MultiVPN · Android 多协议连接管理器

> **状态：V0.1 开发预览（配置管理 MVP）**。当前**不能建立 VPN/代理连接**；不要将其当作可用的网络隧道客户端。

MultiVPN 的长期目标是在同一个 Android 客户端中统一管理 Hysteria 2、Shadowsocks、WireGuard、OpenVPN TCP/UDP、VLESS、VMess、Trojan、TUIC，以及后续的 IKEv2/IPsec、OpenConnect 等主流 VPN/代理协议。

## 已经实现（V0.1）
- Kotlin + Jetpack Compose Android 应用，中文首页、配置列表和配置导入界面。
- 导入 Hysteria 2 / Shadowsocks / VLESS / VMess / Trojan / TUIC 等链接（格式识别与基本校验）。
- 导入 WireGuard `.conf`、OpenVPN `.ovpn` 文本或文件（解析端点及 TCP/UDP）。
- 配置在 Android Keystore AES-GCM 加密后存入私有本地存储；支持删除、错误提示。
- 配置「中国大陆直连」意向、主力及备用节点（**仅保存偏好，不改变网络路由**）。
- 协议引擎接口及连接能力声明：缺少真实引擎时明确显示“未接入”，绝不伪造连接状态。
- JVM 单元测试与 GitHub Actions Android Debug APK 构建工作流。

## 尚未实现
- 尚未集成 sing-box/libbox、OpenVPN 3、WireGuard 等真实协议核心。
- 不能连接服务器；不能代理流量；没有真实测速、自动故障转移或 DNS 分流。
- “中国大陆直连”是未来路由策略配置，当前不会生效。
- 尚未支持订阅格式、二维码和企业 VPN；这些属于后续里程碑。

## 构建
使用 JDK 17、Android SDK 35 和 Gradle 8.11.1：

```bash
gradle :app:assembleDebug :app:testDebugUnitTest
```

GitHub Actions 支持手动运行 **Android CI**，构建成功后从 Actions Artifacts 获取 Debug APK。

> 仓库尚未包含 Gradle Wrapper 二进制文件（避免提交未经核验的 jar），CI 通过 `gradle/actions/setup-gradle` 安装 Gradle。Android Studio 可使用本地 Gradle 或生成校验过的 Wrapper。

## 后续优先级
1. sing-box Android TUN 适配，先打通 Hysteria 2 / Shadowsocks 单节点连接与可靠断开。
2. 逐步加入 VLESS、VMess、Trojan、TUIC、WireGuard，测试网络恢复、IPv6、DNS、防泄漏。
3. 接入 OpenVPN 3 TCP/UDP，并安全管理 VpnService 引擎切换。
4. 分流、健康探测、故障转移、按应用路由以及其他 VPN 引擎。

详见 [架构与安全设计](docs/ARCHITECTURE.md) 与 [下一阶段验收标准](docs/ROADMAP.md)。

## 安全
请勿将私钥、订阅 token、服务器密码、`.ovpn` 私有证书或 VPN 配置提交到 GitHub。异常日志不应输出完整配置。若 Android Keystore 密钥丢失，原始配置无法恢复，需要重新导入。

本项目暂未审查所有计划集成的第三方核心许可；正式集成前须单独核验。