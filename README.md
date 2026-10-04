# SEU Door

东南大学九龙湖校区宿舍门禁 Android 客户端，支持 NFC 离线开门和 BLE 离线开门。

## 功能

- NFC 开门：贴近门锁标签后直接发送原始 NfcA 命令开门
- NFC 唤起：app 未在前台时贴门锁标签可自动拉起并直接开门；intent 通道失败时自动切换 ReaderMode 干净通道重试，无需移开重贴
- BLE 开门：扫描或直连门锁蓝牙，发送加密凭证开门
- 凭证同步：使用手机号和密码从服务器拉取 device_id、credential、ble_mac 等信息
- 密码重置：在登录/配置窗口通过绑定手机号和短信验证码设置新的六位数字密码
- 凭证防过期：NFC/BLE 开门后持久化门锁轮换的链式密钥；遇 `24/25/27` 在会话内自动更新并重发；前台静默预刷新超过 24h 未更新的凭证
- 本地安全存储：凭证快照使用 Android Keystore + AES-GCM 加密保存

## 当前实现

- 语言：Kotlin
- UI：Android View XML (Material 3 Like)
- Min SDK：26
- Target SDK：34
- 运行时依赖：仅 `kotlin-stdlib`
- Release 构建启用 R8 代码压缩和资源裁剪

## 项目结构

```text
app/src/main/java/com/nkyuu/dooropener/
├── MainActivity.kt       # 主界面、NFC ReaderMode、BLE/NFC 状态与配置弹窗
├── DoorApi.kt            # 登录、密码重置、门锁详情、凭证同步、签名与响应解析
├── PasswordResetDialog.kt  # 短信验证码与密码重置窗口
├── PasswordResetRules.kt   # 输入校验与短信重发倒计时
├── PasswordResetSession.kt # 保留屏幕旋转期间的请求状态和结果
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

需要 JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0。Windows 使用 `gradlew.bat` 执行对应任务。

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

本地存在 debug keystore 时，`release` 使用 debug 签名并输出 `app-release.apk`，适合本地测试；不存在时输出 `app-release-unsigned.apk`。正式分发需要另外配置发布签名。

验证命令：

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
```

密码重置测试覆盖输入校验、短信倒计时、HTTP 请求签名与参数、服务端错误处理，以及屏幕旋转期间的请求保留与重复提交保护。HTTP 测试使用本地模拟服务器，不会向真实账号发送短信或修改密码。

生命周期单元测试通过模拟会话观察者的解绑、重新绑定来验证请求保留，不等同于 Android 实机旋转测试。真实短信发送、密码重置及 Android 8–11 的权限行为仍需实机验证。

## 安装

`app-debug.apk` 是完整安装包，可以单独安装。启用 USB 调试并授权电脑后，也可以通过 `adb` 安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

同包名且签名一致时，`-r` 会覆盖升级并保留应用数据。本项目与官方应用使用相同包名 `com.whxinna.userplatform`；若签名不同，无法直接覆盖安装，需要先卸载已有应用。卸载会删除该应用的本地数据，重新安装后需要登录并同步凭证。

## 使用方式

首次启动需要在配置弹窗里输入：

- 手机号
- 密码

忘记密码时：

1. 在登录/配置窗口点击“忘记密码”。已有凭证时，可以从右上角设置按钮进入配置窗口。
2. 输入账号绑定的手机号，点击发送验证码。发送成功后开始 60 秒重发倒计时。
3. 输入短信验证码、新的六位数字密码及确认密码，提交重置。
4. 重置成功后回到配置窗口，手机号和新密码会自动填入；点击“同步凭证”完成登录。

重置操作本身不会覆盖已有的离线门禁凭证。发送短信或提交重置期间会禁用重复操作；旋转屏幕后会保留正在进行的请求和结果。发送失败不会启动重发倒计时，服务端拒绝或网络错误会显示在窗口内。

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
- `BLUETOOTH` / `BLUETOOTH_ADMIN` / `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`（Android 11 及以下；定位权限用于蓝牙扫描）
- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（Android 12 及以上）

旧版蓝牙和定位权限均设置 `maxSdkVersion="30"`。Android 12 及以上使用“附近设备”权限，蓝牙扫描声明 `neverForLocation`，不申请定位权限。

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
