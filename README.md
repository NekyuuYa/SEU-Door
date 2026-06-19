# SEU Door

东南大学九龙湖校区宿舍门禁 NFC/BLE 开门 Android 客户端。

## 功能

- **NFC 开门**: 手机贴近门锁标签即可离线开门（首次需联网激活）
- **BLE 开门**: 蓝牙连接门锁，发送加密凭证开门（无需激活，离线可用）
- **凭证管理**: 登录后自动从服务器同步门锁地址、凭证、设备信息

## 技术栈

- Kotlin + Jetpack Compose + Material3
- Min SDK 26, Target SDK 34
- Release 体积 ~1.4MB

## 项目结构

```
app/src/main/java/com/nkyuu/dooropener/
├── MainActivity.kt      # Compose UI (NFC/蓝牙 Tab, 配置弹窗)
├── DoorApi.kt            # 服务器 API (登录、凭证同步、激活)
├── DoorBle.kt            # BLE 通信 (扫描、连接、开门协议)
├── DoorCrypto.kt         # 加密算法 (密钥派生、RC4、CRC8、NFC/BLE 命令构造)
├── DoorConfigStore.kt    # 凭证本地存储 (AES-GCM 加密)
├── DoorNfc.kt            # NFC 标签读写
├── DoorNfcHelper.kt      # NDEF 解析、响应轮询
└── ui/theme/             # Material3 主题配色
```

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release (需签名)
./gradlew assembleRelease
# 签名:
BT=~/Android/Sdk/build-tools/37.0.0
$BT/zipalign -v 4 app/build/outputs/apk/release/app-release-unsigned.apk aligned.apk
$BT/apksigner sign --ks your.keystore aligned.apk
```

## API 服务器

- 认证服务器: `https://pm.whxinna.com`
- 业务服务器: 从登录响应 `server_info.server_addr` 获取
- 签名算法: 参数排序拼接 + `&key=密钥` → MD5 大写

详见 [API 文档](api-doc.md)。

## 开门原理

### NFC

1. 手机贴近门锁标签 → 读取 NDEF 中的 device_id
2. 本地构造 40 字节加密命令 (RC4 + CRC8)
3. NfcA.transceive() 写入标签
4. 门锁读取并验证 → 开门

### BLE

1. 连接门锁蓝牙 (Service UUID: `0000ff12-...`)
2. 发送 0x74 凭证头 → 获取随机数 ran
3. 发送 0x75 凭证分包 ×3 → 注册凭证
4. 发送 0x78 开门命令 → 获取结果
5. 若结果码 27 → 发送 0x76/0x77 刷新凭证

## 加密算法

- **密钥派生**: device_id → 16 字节密钥 (TEA-like 变体)
- **加密**: RC4 流密码
- **校验**: CRC8 查表
- **存储**: AES-256-GCM (Android Keystore)

## 逆向文档

详见 [API 文档](api-doc.md)。

## 许可证

仅供学习研究使用。
