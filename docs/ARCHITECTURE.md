# MultiVPN 技术架构与安全边界

## 产品边界
用户目标：同一 Android App 统一管理代理和 VPN 协议，并最终实现中国大陆直连、Hysteria 2 主力、Shadowsocks 备用，以及 WireGuard、OpenVPN TCP/UDP 和更多主流协议的可选连接。

VPN 和代理不是同一种协议，不能简单地把所有格式都交给 sing-box。V0.1 仅实现配置管理，**没有 TUN、没有后台服务、没有启动 Android VPN**。

## 分层

\`\`\`text
Compose UI
  ├── ProfileImporter (识别输入格式、验证基础字段)
  ├── ProfileVault (Android Keystore AES-GCM 加密配置)
  ├── RoutePolicy (偏好：CN DIRECT、主力、备用)
  └── EngineRegistry (可用性判定，默认无实际引擎)
         ├── [计划] SingBoxEngine (Hysteria2/SS/VLESS/VMess/Trojan/TUIC...)
         ├── [计划] WireGuardEngine (取决于 sing-box 与原生方案评估)
         ├── [计划] OpenVpnEngine (OpenVPN 3 Core / JNI)
         ├── [计划] IPsecEngine
         └── [计划] OpenConnectEngine
                    │
              [计划] Android VpnService 单一系统 VPN
                    │
              [计划] TUN/路由/DNS 管理
\`\`\`

## 引擎协定
\`ProtocolEngine.supportedProtocols\` 明确声明引擎能力。注册表在没有引擎时返回 \`Unavailable\`，并且 UI 不展示假测速、虚假“已连接”或伪造故障转移。接入真实引擎后需引入细化状态机：Idle / PreparingPermission / Connecting / Connected / Switching / Failed / Disconnecting。

## Android VPN 限制
- 不能把两个独立的 Android VPN 应用同时建立的隧道直接叠加为统一 VPN。
- 应由单一应用的 VpnService 掌控 TUN 生命周期；进程退出、锁屏、重启和网络变化需要真实测试。
- OpenVPN 与 sing-box 跨引擎切换可能会导致现存 TCP / UDP 会话中断，不能承诺无缝。
- 故障切换后应维护状态、重连退避和防止循环切换。

## 数据与安全
- 所有导入的配置可能含密码、密钥或证书，原文只存在于内存及加密本地配置文件中。
- 使用 AndroidKeyStore AES-256 GCM，每次写入使用新的随机 IV。
- 设备备份关闭，避免恢复加密文件但缺少 Keystore 密钥。
- 解密失败禁止自动覆盖旧密文；界面应显式提示，防止误删。
- 不允许把配置内容、账户令牌、私钥输出到 GitHub Actions、日志或崩溃上报中。
- 调试 APK 非正式发布版本；正式发布还需要代码签名、依赖审计、权限说明和隐私政策。

## 配置导入注意事项
\`ProfileImporter\` 只负责粗粒度协议检测和显示字段提取，不作为协议核心的安全配置解析器。实际连接前仍需由对应协议核心完成严格校验。文件导入限制 256 KB，链接一次一条。

\`http://\`、\`https://\` 可能代表普通网页或订阅地址，不能默认为 HTTP 代理，所以 V0.1 只接受显式 \`http+proxy://\`、\`https+proxy://\`。还没有订阅拉取功能。
