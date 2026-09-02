# Changelog

## v1.3.0 (2026-09-02)

### 修复
- **NFC 竞态：直接贴锁大概率卡住、必须移开重贴**（根因分析与修复）
  - 根因：贴锁唤起走系统 intent 派发通道——平台已做过 NDEF 预读、存在性检查持续插手、
    加上冷启动延迟，门锁自定义 0xB1 命令在该通道上大概率不应答；而失败后补开 ReaderMode
    时标签仍在场内，多数系统不会对已在场内的标签重新回调 `onTagDiscovered`，
    只能靠移开手机打断 RF 场触发新事件
  - intent 路径不再重读 144 字节 NDEF：直接解析 intent 附带的 `EXTRA_NDEF_MESSAGES` 取
    device_id，0xB1 成为标签激活后 app 的第一条命令；该通道只快速试一次（1200ms）
  - 失败恢复循环：`disable→enable ReaderMode` 强制 NFC 栈重新评估场内标签（最多 3 轮），
    拿到重新激活的干净通道后自动重试，用户无需移开手机
  - 标签事件抢占：新事件到来时进行中的尝试立即作废（generation 检查点放弃），
    不再被 busy 门静默丢弃（原来卡住期间重贴会被"正在处理"吞掉）
  - NFC 调试日志改为会话内内存缓冲、结束后一次性落盘，不再在 NFC 热路径上做同步文件 IO

### 新增
- **凭证防过期，尽量免手动刷新**
  - NFC 成功响应帧 7..39 字节携带轮换后的链式密钥，现在与 BLE 一致地持久化
    （此前 NFC 丢弃该密钥，导致下次开门落后一拍报 27、被迫刷新）
  - 门锁返回 `24/25/27` 时在 NfcA 会话保持期间更新凭证并原会话重发开门命令：
    27 优先用门锁在响应帧里直接下发的新密钥，`24/25` 走服务器在线刷新——
    不再要求"请再贴一次卡开门"
  - 进入前台时若本地凭证超过 24h 未更新，用缓存 sessionSecret 静默刷新
    （尝试间隔 ≥6h，失败静默，门口还有会话内刷新兜底）

## v1.2.1 (2026-06-21)

### 新增
- **NFC 唤起**：app 未在前台时，贴门锁标签可自动拉起本 app 并直接开门
  - 门锁标签 NDEF 内嵌 AAR（`com.whxinna.userplatform`，官方 app 包名）。实测系统在 AAR 包未安装时**不会**回落到第三方 NDEF 过滤器，因此把本 app 的 `applicationId` 改为该包名，让 AAR 直接指向本 app（`namespace` 与 Kotlin 代码仍为 `com.nkyuu.dooropener`，未改动）
  - Manifest 注册精确 host 的 `NDEF_DISCOVERED`（`uc-zhuli.whxinna.com`）：AAR 命中本包后把带标签的 intent 投给 `MainActivity`，实现一贴即开；只匹配该 host，不污染其它网址标签
  - 保留 `TAG_DISCOVERED` 作为末位兜底

### 修复
- 冷启动竞态：`enableReaderMode` 在处理 intent 标签期间先让位，结束后再补开，避免打断进行中的 NfcA 会话

### 注意
- `applicationId` 变更为 `com.whxinna.userplatform`：安装后是全新包，凭证数据不会从旧 `com.nkyuu.dooropener` 迁移，需重新登录同步；若设备已安装官方 app 会发生包名冲突

## v1.2.0 (2026-06-21)

### 新增
- **支付宝 OAuth 登录**：通过 AIDL 直接绑定支付宝 app 的 MspService，无需引入支付宝 SDK
  - `IAlixPay.aidl` + `IRemoteServiceCallback.aidl`（逆向自支付宝 SDK 15.8.17）
  - `DoorAlipayAuth.kt`：绑定 service → registerCallback03 → pay02 → 解析 auth_code
  - 支付宝版本 >= 3 自动使用 registerCallback03 + r03 + pay02
  - APk 体积 85KB（零 SDK 依赖）
- 登录验证码图形识别支持
- 配置弹窗新增支付宝登录按钮

### 修复
- OAuth 登录请求格式：嵌套对象需 base64 URL-safe 编码（`beforeRequest` 拦截器逻辑）
- 支付宝进程被系统冻结导致 AIDL 调用 hang：先拉起支付宝到前台再绑定 service
- `IRemoteServiceCallback.startActivity` 回调：补充 `ACTION_MAIN` + `CallingPid` 参数

### 文档
- 完善登录 API 文档（验证码/OAuth/CMIC/注册/密码重置/绑定手机）
- 提取微信 AppID + AppSecret（`wx9622aee7b9ae7536`）
- 提取支付宝 AppID（`2021001155694496`）+ PID（`2088901922185401`）
- 记录各 OAuth provider 不用 SDK 的可行性分析
- 逆向支付宝 SDK AuthTask / h 类 / AIDL 通信协议
