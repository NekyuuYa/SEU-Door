# SEU Door

东南大学九龙湖校区宿舍门禁 NFC/BLE 开门 Android 客户端。

## 功能

- **NFC 开门**: 手机贴近门锁标签即可离线开门（无需激活）
- **BLE 开门**: 蓝牙连接门锁，发送加密凭证开门（无需激活，离线可用）
- **凭证管理**: 登录后自动从服务器同步门锁地址、凭证、设备信息
- **凭证刷新**: 顶栏一键刷新；门锁报过期（码 24/27）时自动重新同步

## 技术栈

- Kotlin + Jetpack Compose + Material3
- Min SDK 26, Target SDK 34
- Release 体积 ~1.4MB

## 项目结构

```
app/src/main/java/com/nkyuu/dooropener/
├── MainActivity.kt      # Compose UI (NFC/蓝牙 Tab, ReaderMode, 凭证刷新)
├── DoorApi.kt            # 服务器 API (登录、凭证同步)
├── DoorBle.kt            # BLE 通信 (扫描、连接、开门协议)
├── DoorCrypto.kt         # 加密算法 (密钥派生、RC4、CRC8、NFC/BLE 命令构造)
├── DoorConfigStore.kt    # 凭证本地存储 (AES-GCM 加密)
├── DoorNfc.kt            # NfcA 连接与读取
├── DoorNfcHelper.kt      # NDEF 解析、整帧 transceive 开门
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

> 门锁是联机式 NFC 芯片（内存与 MCU 经 I2C 共享），开门走自定义 RF 命令，**不是 NTAG 页读写**。

1. `enableReaderMode(FLAG_READER_NFC_A)` 发现标签
2. `0x30` 读 NDEF → 取 URL 里的 device_id
3. 本地构造 40 字节加密命令 (RC4 + CRC8)
4. **整帧 `NfcA.transceive(命令)`** → 门锁直接返回 20 字节响应（不拆页、不用 `0xA2`）
5. 解析结果码 (0/23 成功)；若 24/27 过期 → 自动重新同步凭证，提示再贴一次

详见 [API 文档](api-doc.md) 的「NFC 协议」。

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
