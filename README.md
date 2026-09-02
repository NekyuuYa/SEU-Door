# SEU Door

东南大学九龙湖校区宿舍门禁 Android 客户端，支持 NFC 离线开门和 BLE 离线开门。

## 功能

- NFC 开门：贴近门锁标签后直接发送原始 NfcA 命令开门
- NFC 唤起：app 未在前台时贴门锁标签可自动拉起并直接开门；intent 通道失败时自动切换 ReaderMode 干净通道重试，无需移开重贴
- BLE 开门：扫描或直连门锁蓝牙，发送加密凭证开门
- 凭证同步：使用手机号和密码从服务器拉取 device_id、credential、ble_mac 等信息
- 凭证防过期：NFC/BLE 开门后持久化门锁轮换的链式密钥；遇 `24/25/27` 在会话内自动更新并重发；前台静默预刷新超过 24h 未更新的凭证
- 本地安全存储：凭证快照使用 Android Keystore + AES-GCM 加密保存

## 当前实现

- 语言：Kotlin
- UI：Android View XML (Material 3 Like)
- Min SDK：26
- Target SDK：34
- 运行时依赖：仅 `kotlin-stdlib`
- 当前 release APK：约 `75KB`（比原版小一千倍）

## 项目结构

```text
app/src/main/java/com/nkyuu/dooropener/
├── MainActivity.kt       # 主界面、NFC ReaderMode、BLE/NFC 状态与配置弹窗
├── DoorApi.kt            # 登录、门锁详情、凭证同步、签名与响应解析
├── DoorBle.kt            # BLE 扫描、连接、通知、开门与凭证刷新协议
├── DoorCrypto.kt         # 密钥派生、RC4、CRC8、NFC/BLE 命令构造与响应解析
├── DoorConfigStore.kt    # 凭证快照读写
├── DoorConfigCipher.kt   # Android Keystore + AES-GCM
├── DoorNfc.kt            # NfcA 原始读写辅助
├── DoorNfcHelper.kt      # NDEF 解析与单次 NFC 开门流程
├── HexUtil.kt            # Hex 编解码
└── LocalizedMessage.kt   # 面向 UI 的本地化错误消息封装
```

## 构建

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

release 包输出路径：

```text
app/build/outputs/apk/release/app-release.apk
```

当前工程的 `release` 签名配置继承自 `debug` 签名，仅用于本地安装与测试，不适合正式分发。

## 安装

设备连接 `adb` 后可直接覆盖安装：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 使用方式

首次启动需要在配置弹窗里输入：

- 手机号
- 密码

同步成功后：

- NFC 页签：手机靠近门锁标签即可开门
- BLE 页签：点击按钮，通过蓝牙连接门锁并开门

凭证过期基本无需手动干预：

- 顶栏仍可手动刷新（兜底）
- NFC/BLE 开门成功后自动保存门锁轮换的链式密钥，本地凭证与门锁保持同步
- NFC 遇到 `24/25/27`：27 优先用门锁响应帧直接下发的新密钥，`24/25` 自动在线刷新，随后同一 NFC 会话内直接重发开门命令（无需再贴一次卡）
- BLE 遇到 `27` 会自动刷新凭证并重试
- 进入前台时若凭证超过 24h 未更新会静默刷新一次

## 权限与特性

Manifest 中当前使用：

- `NFC`
- `INTERNET`
- `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_FINE_LOCATION`（Android 11 及以下）
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（Android 12 及以上）

设备特性：

- `android.hardware.nfc` 为必需

## 协议说明

### NFC

门锁不是普通 NTAG 页写入模型，开门走原始 NfcA 自定义命令：

1. `enableReaderMode(FLAG_READER_NFC_A | FLAG_READER_SKIP_NDEF_CHECK)`（干净通道，平台不插手）
2. 读 NDEF，解析 URL 中的 `device_id`
3. 本地构造 40 字节 NFC 命令帧
4. 整帧 `NfcA.transceive(...)`
5. 解析 20 字节响应，结果码 `0/23` 视为成功；成功响应帧 7..39 字节为轮换后的链式密钥，需持久化

不使用 `0xA2` 分页写入，也不依赖服务器下发激活数据。

#### NFC 唤起（app 未在前台时）

门锁标签 NDEF 内嵌 AAR（Android Application Record，`com.whxinna.userplatform`），优先级高于第三方 NDEF 过滤器；实测系统在该包未安装时不会回落到第三方过滤器。因此：

1. 本 app 的 `applicationId` 设为 `com.whxinna.userplatform`，让 AAR 直接指向本 app（`namespace`/代码仍为 `com.nkyuu.dooropener`）
2. Manifest 注册精确 host 的 `NDEF_DISCOVERED`（`uc-zhuli.whxinna.com`），AAR 命中本包后把带标签的 intent 投给 `MainActivity`，一贴即开
3. `TAG_DISCOVERED` 作为末位兜底

注意：灭屏/锁屏能否读卡取决于系统设置（部分机型默认息屏不读卡），app 无法强制。

#### 两条通道与竞态处理

intent 派发通道（app 未在前台贴锁）与 ReaderMode 通道行为不同，是"贴锁卡住"问题的根源：

- intent 通道上平台已做过 NDEF 预读、存在性检查持续插手，加上冷启动延迟，门锁 0xB1 大概率不应答；此通道只快速试一次（超时 1200ms），device_id 直接取 intent 附带的 `EXTRA_NDEF_MESSAGES`，不再重读 144 字节用户内存
- 通信层失败后进入恢复循环：`disable→enable ReaderMode` 强制 NFC 栈重新评估场内标签（最多 3 轮，每轮等待 1s）。多数系统会在 toggle 后对场内标签重新回调 `onTagDiscovered`，拿到重新激活的干净通道后自动重试——用户无需移开手机再贴
- 所有标签事件进入单线程流水线，新事件抢占进行中的尝试（旧尝试在检查点作废），不会被 busy 门丢弃

若恢复循环未重发现标签（少数系统的栈不重派发场内标签），最终提示"门锁未响应，请将手机移开后重新贴靠门锁"。

### BLE

当前 BLE 流程：

1. 依据缓存的 `ble_mac` 直连，或扫描 `XN-{device_id}` / 目标服务 UUID
2. 发送 `0x74` 凭证头，获取随机数
3. 发送 `0x75` 凭证分包
4. 发送 `0x78` 开门命令
5. 如返回 `27`，走 `0x76` / `0x77` 刷新凭证

### 加密

- 密钥派生：`device_id -> 16B key`
- 数据加密：RC4
- 校验：CRC8
- 本地存储：AES-GCM

更完整的接口和协议留档见 [api-doc.md](api-doc.md)。

## 注意事项

- 该工程当前针对单一门禁协议实现，强依赖学校现有服务端与门锁格式
- 未做正式发布签名、应用市场适配或多机型全面验证
