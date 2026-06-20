# API 文档

> 本文档自带完整加密原语（`deriveKey` / RC4 / CRC8）与 NFC/BLE 帧格式，可独立移植到其他平台。

## 移植提示

- **加密原语 NFC/BLE 通用**：先实现并自测 `deriveKey` / `RC4` / `CRC8`（见「加密算法」），三者任一错全盘失败。
  32 位运算一律**无符号、模 2³²**；注意 `deriveKey` 输入小端拆、输出大端拼。
- **响应是 base64**：业务接口响应需先 base64 解码再解析 JSON。
- **NFC 是 Android 特有写法**：开门 = `enableReaderMode(FLAG_READER_NFC_A)` 下把 40 字节帧**整帧 `transceive`**，
  门锁直接回 20 字节；**不要**用 `0xA2` 写页（这颗联机芯片不响应）。
  - **iOS CoreNFC**：用 `NFCTagReaderSession` + `NFCMiFareTag.sendMiFareCommand` 发原始命令，
    但 iOS 对非标准标签的原始透传**有限制且需 entitlement**，移植前务必先验证能否给该芯片发 `0xB1` 原始帧。
  - **其他平台**：找到「原始 NfcA 透传」能力即可，协议不变。
- **NFC 凭证是静态的**（响应仅 20 字节，装不下 chainKey），不滚动；过期靠重新 `syncCredential`。BLE 才滚动 chainKey。

## 服务器架构

| 服务器 | 地址 | 用途 |
|--------|------|------|
| 认证服务器 | `https://pm.whxinna.com` | 登录、注册、刷新 token |
| 业务服务器 | 登录响应 `server_info.server_addr` | 门锁、凭证、设备操作 |

## 公共参数

每个请求都带以下参数：

| 参数 | 说明 |
|------|------|
| `pid` | 项目 ID，东南大学九龙湖 = `21048` |
| `appid` | 应用 ID = `20104` |
| `timestamp` | Unix 时间戳（秒） |
| `noncestr` | 随机 32 位字符串 |
| `sign` | MD5 签名 |

## 签名算法

```text
1. 收集所有参数（不含 sign）
2. 按 key 字母排序
3. 拼成 "key1=value1&key2=value2&..."
4. 追加 "&key=" + 签名密钥
5. MD5 → 大写
```

- 认证签名密钥: `6d5dbb85b949447a95ff8fda9a9b759b`（固定）
- 业务签名密钥: 登录响应 `server_info.session_secret`（每次登录变化）

## 接口列表

### 1. 登录

```text
GET https://pm.whxinna.com/webapi/users/login
```

| 参数 | 说明 |
|------|------|
| `phone` | 手机号 |
| `pwd` | 密码（明文，6 位数字） |
| `code` | 图形验证码（可选，触发后必填） |

签名密钥: `6d5dbb85b949447a95ff8fda9a9b759b`

**触发验证码**：多次登录失败后，服务器返回 `message` 含 `"本次登录需要进行验证"` 或 `"CAPTCHA_REQUIRED"` 或 `"验证码输入错误"`，
此时需要先调用「获取登录验证码」接口获取图片，用户输入后再带 `code` 参数重新登录。

**响应** (base64 解码):

```json
{
  "user_info": {
    "id": "UUID",
    "phone": "手机号",
    "identity_code": "业务API的identitycode参数",
    "isbind": 1,
    "balance": "0.0000",
    "group": { "name": "学生", ... }
  },
  "platform_token": "平台token",
  "server_info": {
    "server_addr": "https://zhuli104.whxinna.com",
    "session_secret": "业务API签名密钥（每次登录变化）",
    "appsecret": "应用密钥",
    "server_appid": 21048,
    "server_id": 20104,
    "projectname": "东南大学九龙湖校区"
  }
}
```

**后续业务 API 使用:**
- 服务器: `server_info.server_addr`
- 签名密钥: `server_info.session_secret`
- `user_id`: `user_info.id`
- `identitycode`: `user_info.identity_code`

---

### 1.1 获取登录验证码

```text
GET https://pm.whxinna.com/webapi/users/get_login_code
```

| 参数 | 说明 |
|------|------|
| `phone` | 手机号 |

签名密钥: `6d5dbb85b949447a95ff8fda9a9b759b`

**响应** (base64 解码):

```json
{
  "codeImg": "<svg ...>...</svg>"
}
```

`codeImg` 为 **SVG 字符串**（非 base64），直接嵌入即可显示。内容为 4 位字母数字验证码（含大小写）。用户输入后作为 `code` 参数传入登录接口。

> **注意**: 响应 `data` 的 base64 padding 不固定，解码时需尝试补 `=`。`codeImg` 的 SVG 内部可能含二进制路径数据，用正则提取 `<text>` 元素或直接渲染为图片展示。

---

### 1.2 OAuth 登录

两个端点，按 provider 分：

```text
GET https://pm.whxinna.com/webapi/oauth/login            # 微信、支付宝
GET https://pm.whxinna.com/webapi/oauth/account_login     # 华为、CMIC一键登录
```

**已提取的 AppID / AppSecret**（从 APK 逆向）:

| 平台 | AppID | AppSecret | 来源 |
|------|-------|-----------|------|
| 微信 | `wx9622aee7b9ae7536` | `425b4b4ad72c0a317f28bc6b311adfd3` | APK resources.arsc |
| 支付宝 | `2021001155694496` | 无（RSA2签名，私钥在服务器） | 服务器 `/webapi/oauth/alipay/auth_info` |
| 支付宝 PID | `2088901922185401` | — | 同上 |

**微信网页 OAuth 流程**（可用，不依赖原生 SDK）:

1. 构造授权 URL（用户扫码）：
   ```
   https://open.weixin.qq.com/connect/oauth2/authorize
     ?appid=wx9622aee7b9ae7536
     &redirect_uri=<回调URL>
     &response_type=code
     &scope=snsapi_userinfo
     &state=STATE
     #wechat_redirect
   ```
2. 用户授权后回调带 `code`
3. 用 AppID + AppSecret 换 `access_token`：
   ```
   GET https://api.weixin.qq.com/sns/oauth2/access_token
     ?appid=wx9622aee7b9ae7536
     &secret=425b4b4ad72c0a317f28bc6b311adfd3
     &code=<code>
     &grant_type=authorization_code
   ```
4. 把 `access_token` + `openid` 发给门锁服务器 `/webapi/oauth/login`

**支付宝网页 OAuth 流程**（用户登录，客户端无需换 token）:

用户登录用**用户信息授权**端点 `publicAppAuthorize.htm`（**不是** `appToAppAuth.htm`）：
```
https://openauth.alipay.com/oauth2/publicAppAuthorize.htm
  ?app_id=2021001155694496
  &scope=auth_user
  &redirect_uri=<白名单回调URL>
```

- 回调带的是**用户 `auth_code`**（不是 `app_auth_code`）。
- **客户端不做 token 交换**：把 `auth_code` 直接发 `/webapi/oauth/login`，RSA 换 token 由**服务器**完成
  （私钥在服务器，这也是 auth_info 要找服务器签名的原因）。
- 已验证：`publicAppAuthorize.htm?app_id=2021001155694496&scope=auth_user&redirect_uri=...`
  → 302 跳支付宝登录页（app_id 受理）。

> **端点辨析**：`appToAppAuth.htm` 是**第三方应用授权（ISV 代商户）**流程，回调 `app_auth_code`
> 需 `alipay.open.auth.token.app` 换 `app_auth_token`——那是给商户授权、不是用户登录，
> 用错才会卡在"客户端换不了 token"。用户登录走 `publicAppAuthorize.htm`，客户端不碰私钥。
>
> **auth_info（`/webapi/oauth/alipay/auth_info`）是 SDK 专用格式**（`method=alipay.open.auth.sdk.code.get`、
> `scope=kuaijie`），喂给原生 `AuthTask.authV2()` 或拉起支付宝 app 用；网页流程用 `publicAppAuthorize.htm`，不用 auth_info。

**跳转支付宝 app（AIDL 方案，无需 SDK）**:

逆向支付宝 SDK 发现，`AuthTask.authV2()` 底层通过 AIDL 绑定支付宝 app 的 service：

```kotlin
// 绑定支付宝 service
val intent = Intent("com.eg.android.AlipayGphone.IAlixPay")
intent.setPackage("com.eg.android.AlipayGphone")
bindService(intent, connection, Context.BIND_AUTO_CREATE)

// 获取 IAlixPay 接口
val alixPay = IAlixPay.Stub.asInterface(binder)

// 注册回调（处理支付宝app内部跳转）
alixPay.registerCallback(callback)

// 调用 Pay（同步阻塞，直接返回结果）
val result = alixPay.Pay(authInfo)  // authInfo 从服务器 /alipay/auth_info 获取

// 解析结果
// resultStatus={9000};memo={};result={auth_code=xxx&user_id=xxx}
```

**只需要两个 AIDL 文件**（体积极小，不需要完整 SDK）：
- `IAlixPay.aidl` — 主接口，transaction code 1 = `Pay(String)`
- `IRemoteServiceCallback.aidl` — 回调，处理 `startActivity` 等

**回传通道**: `Pay()` 是同步阻塞调用，结果直接返回，不需要 onActivityResult 或 scheme 回跳。

**请求参数**（已对真实服务器验证）:

与 `/webapi/users/login` 同构——**签名 GET**，但嵌套对象用 **base64 URL-safe 编码**（原 app 的 `beforeRequest` 拦截器逻辑）：

1. 把 `systemInfo`、`authInfo` 等对象 JSON.stringify 后做 base64 编码（`+/` 替换为 `-_`）
2. 编码后的字段名加 `base64_` 前缀
3. 加上 `timestamp`、`noncestr`、`sign` 等公共参数一起签名

```
GET /webapi/oauth/login
  ?base64_systemInfo=<base64({"appVersion":"1.0.0","systemType":"android",...})>
  &base64_authInfo=<base64({"auth_code":"xxx","oauth_type":"alipay_app","sign_type":"RSA"})>
  &app_version=1.0.0
  &timestamp=xxx
  &noncestr=xxx
  &sign=xxx
```

签名密钥: `6d5dbb85b949447a95ff8fda9a9b759b`（签名时 key 包含 `base64_` 前缀）

> **注意**: 不是 POST JSON body，不是平铺参数，不是 bracket 嵌套参数。
> 平铺参数也能通过签名校验但服务器返回 `result:false`（不认）。

**响应** (base64 解码): 同账号密码登录。

**各 provider 获取 auth_code 的方式**:

| provider | 原生 SDK 调用 | 获取的字段 | 能否不用 SDK |
|----------|-------------|-----------|------------|
| 微信 | `zl.oauth.sendAuth({provider:"WeChat",info:null})` | `access_token`, `openid`, `refresh_token` | 用网页OAuth（扫码），见下方 |
| 支付宝 | 先调 `GET /webapi/oauth/alipay/auth_info` 获取参数，再 `zl.oauth.sendAuth({provider:"Alipay",info:JSON})` | `authCode` | 构造Intent跳支付宝app，见下方 |
| 华为 | `zl.oauth.sendAuth({provider:"HarmonyOS",info:""})` | `authCode` | 需华为Account SDK，无法绕过 |
| CMIC | `zl.cmic.loginAuth({info:"cmic_phone"})` | `token` | 需CMIC SDK，无法绕过 |

> **注意**: 微信登录时 `auth_code` 实际传的是 `access_token`，`oauth_type` 固定为 `wechat_app`。
> 华为和CMIC共用 `/webapi/oauth/account_login` 端点，区别仅在 `oauth_type`。

**微信不用 SDK 的方案**:

微信 SDK（`com.tencent.mm.opensdk`）通过 `IWXAPI` + `WXEntryActivity` 与微信 app 通信，
无法简单构造 Intent 绕过。但可以用**网页 OAuth**：

1. 用户在浏览器访问授权 URL（手机会跳转微信 app 或显示二维码）
2. 授权后回调带 `code`
3. 服务器用 AppID + AppSecret 换 `access_token`

网页 OAuth 不需要 SDK，但用户体验是扫码而非直接跳转微信 app。

**登录后流程**:

1. 若 `user_info.is_pwd == 0` → 需先设置密码（`/account/set_password`）
2. 若 `user_info.isbind == false` → 需绑定项目（`/account/bind_project`）
3. 若返回 `"授权信息未找到"` 或 `"openid错误"` → 需走「绑定手机」流程（见 1.6）

---

### 1.3 CMIC 一键登录（本机号码登录）

> **限制**: 需要集成中国移动 CMIC SDK 并注册应用，第三方客户端无法直接使用。仅作协议留档。

CMIC（中国移动互联网能力平台）通过 SIM 卡认证，无需短信验证码。

**前提**: 客户端需集成 CMIC SDK，且 `isCmicAuthLoginDisplay` 配置为 true。

**Step 1: 调用 CMIC SDK 获取 token**

```javascript
zl.cmic.loginAuth({info: "cmic_phone"}, callback)
// callback 参数:
//   e.code == 0 → 成功，e.token 为认证 token
//   e.code == 200020 → CMIC 特定错误
//   e.code == 103000 → CMIC 特定错误
```

**Step 2: 用 token 换登录态**

```text
POST https://pm.whxinna.com/webapi/oauth/account_login
```

请求体（同 OAuth 登录）:

```json
{
  "systemInfo": {
    "appVersion": "1.0.0",
    "systemType": "android",
    "systemVersion": "14",
    "deviceModel": "Pixel 7",
    "deviceToken": "IMEI 或 UUID"
  },
  "authInfo": {
    "auth_code": "CMIC SDK 返回的 token",
    "oauth_type": "cmic_phone",
    "sign_type": "RSA"
  },
  "app_version": "1.0.0"
}
```

签名密钥: `6d5dbb85b949447a95ff8fda9a9b759b`

**响应**: 同账号密码登录。

> **与华为一键登录共用端点**: 华为 (`oauth_type: "huawei_account"`) 也走 `/webapi/oauth/account_login`，
> 区别仅在 `oauth_type` 和原生 SDK 调用方式。

---

### 1.4 注册

```text
GET https://pm.whxinna.com/webapi/users/sendregSMS    # 发送短信验证码
POST https://pm.whxinna.com/webapi/users/register      # 注册
```

**发送验证码**: `GET /webapi/users/sendregSMS?phone=xxx`

**注册**:

| 参数 | 说明 |
|------|------|
| `phone` | 手机号 |
| `code` | 短信验证码 |
| `pwd` | 6 位数字密码 |

签名密钥: `6d5dbb85b949447a95ff8fda9a9b759b`

---

### 1.5 密码重置

```text
GET https://pm.whxinna.com/webapi/users/sendresetpwdSMS  # 发送短信验证码
POST https://pm.whxinna.com/webapi/oauth/pwd_reset        # 重置密码
```

**发送验证码**: `GET /webapi/users/sendresetpwdSMS?phone=xxx`

**重置密码**:

| 参数 | 说明 |
|------|------|
| `phone` | 手机号 |
| `code` | 短信验证码 |
| `newpwd` | 新密码（6 位数字） |

签名密钥: `6d5dbb85b949447a95ff8fda9a9b759b`

---

### 1.6 绑定手机（OAuth 后）

首次 OAuth 登录时若返回 `"授权信息未找到"` 或 `"openid错误"`，需走此流程：

**Step 1: 获取验证码**

```text
POST https://pm.whxinna.com/webapi/oauth/get_auth_code
```

| 参数 | 说明 |
|------|------|
| `phone` | 手机号 |
| `oauth_type` | `wechat_app` / `alipay_app` |
| `openid` | 微信登录时传（`wechat_app`） |
| `auth_code` | 支付宝登录时传（`alipay_app`） |

**Step 2: 绑定**

```text
POST https://pm.whxinna.com/webapi/oauth/auth_by_code
```

请求体:

```json
{
  "systemInfo": {
    "appVersion": "1.0.0",
    "systemType": "android",
    "systemVersion": "14",
    "deviceModel": "Pixel 7",
    "deviceToken": "IMEI 或 UUID"
  },
  "userInfo": {
    "phone": "手机号",
    "password": "6位数字密码（可选，首次绑定时设置）",
    "...其他OAuth用户信息"
  },
  "authInfo": {
    "auth_code": "OAuth 授权码",
    "oauth_type": "wechat_app | alipay_app",
    "sign_type": "RSA"
  },
  "code": "短信验证码"
}
```

> 若 `10001` 返回码表示需要设置密码（显示密码输入框），其他码表示验证码已发送。
> 也有 `auth_by_password` 端点用密码代替验证码绑定。

---

### 2. 获取门锁信息

```text
GET {server_addr}/webapi/v1/student/accommodation/details
```

| 参数 | 说明 |
|------|------|
| `user_id` | 用户 UUID |
| `identitycode` | 登录返回的 `identity_code` |

**响应** (base64 解码):

```json
{
  "accommodation": {
    "building_name": "楼栋名",
    "floor_name": "楼层",
    "room_name": "房间号",
    ...
  },
  "door_lock": {
    "device_id": 1234567,
    "ble_name": "XN-1234567",
    "ble_mac": "AA:BB:CC:DD:EE:FF",
    "battery_level": 100.0,
    "credential": "64字符hex（chain_key）",
    "credential_id": 1234
  }
}
```

---

### 3. 获取凭证列表

```text
GET {server_addr}/webapi/v1/staff/door_lock/credentials
```

| 参数 | 说明 |
|------|------|
| `device_id` | 门锁设备 ID |
| `user_id` | 用户 UUID |
| `identitycode` | `identity_code` |

当 `accommodation/details` 未返回 `credential` 时，用此接口补充获取。

---

### 4. NFC 激活

```text
GET {server_addr}/webapi/v1/door_lock/command/create
```

| 参数 | 说明 |
|------|------|
| `device_id` | 门锁设备 ID |
| `command` | `1` |
| `type` | `nfc` |
| `credential_id` | 凭证 ID |
| `user_id` | 用户 UUID |
| `identitycode` | `identity_code` |

**响应** (base64 解码):

```json
{
  "credential_id": 1234,
  "command": 1,
  "payload": "B10105XXXXXXXXXXXX"
}
```

`payload` 为 hex 编码的 NFC 写入数据。

> **注意**: `type=ble` 当前服务器不支持（返回“暂不支持此命令”）。BLE 开门不需要激活。

> **当前客户端不使用此接口**：NFC 离线开门由客户端用同步到的 `credential` 本地构造命令、整帧 `transceive`
> 发给门锁即可（见「NFC 协议」），无需“首次激活”。该接口仅作协议留档。
> 凭证过期时改为重新调用登录/同步流程（`syncCredential`）刷新。

---

## BLE 协议

### 设备信息

| 项目 | 值 |
|------|-----|
| 设备名前缀 | `XN-{device_id}` |
| Service UUID | `0000ff12-0000-1000-8000-00805f9b34fb` |
| Write UUID | `0000ff01-0000-1000-8000-00805f9b34fb` |
| Read/Notify UUID | `0000ff02-0000-1000-8000-00805f9b34fb` |

### 命令格式 (20 字节)

```text
[0]    = 0x14 (长度标识)
[1]    = 0x00
[2]    = 命令类型 (0x74/0x75/0x76/0x77/0x78)
[3-18] = RC4 加密的 16 字节数据
[19]   = CRC8 (对明文计算)
```

### 响应格式 (20 字节)

```text
[0]    = 长度标识（不固定，不需要校验）
[1]    = 0x00
[2]    = 命令类型
[3-18] = RC4 加密数据
[19]   = CRC8
```

### 命令类型

| 命令 | 说明 | 方向 |
|------|------|------|
| `0x74` | 凭证头 | App → Lock |
| `0x75` | 凭证分包 | App → Lock |
| `0x76` | 请求刷新凭证 | App → Lock |
| `0x77` | 读取凭证分包 | App → Lock |
| `0x78` | 开门命令 | App → Lock |

### 开门流程

```text
1. 连接 BLE (优先用缓存的 ble_mac 直连)
2. 发送 0x74 凭证头 → 解析 ran 随机数
3. 发送 0x75 ×3 (ran + projectId + credential 分 3 包)
4. 等待 50ms
5. 发送 0x78 开门 → 解析结果码
```

### 结果码

| 码 | 说明 |
|----|------|
| 0 | 成功 |
| 23 | 门锁已打开（也算成功） |
| 27 | 需要更新凭证（触发 0x76/0x77 刷新流程） |
| 其他 | 失败 |

---

## NFC 协议

门锁内嵌的是**联机式 NFC 前端芯片**（FM11NT021 类，240 字节用户区，CC=`E1101E00`，SAK=`0x00` / ATQA=`0x0044`），
内存通过 I2C 与门锁 MCU 共享。**关键：开门走自定义 RF 命令，不是 NTAG 的页读写。**

### 读取（标识门锁）

用标准 `0x30` READ 读出 NDEF（page4 起的 TLV），解析 URL 里的 `?d=` 拿到 `device_id`。

### 写入 / 开门（核心）

- **入口必须用 `enableReaderMode(FLAG_READER_NFC_A)`**（不是 `enableForegroundDispatch`）。
  `ForegroundDispatch` 下平台会插手 NDEF/存在性检查，自定义命令收不到稳定响应。
- 把 40 字节命令帧（帧头 `0xB1`）**整帧 `NfcA.transceive()`** 发出，门锁直接返回 **20 字节响应**。
- ❌ **不要**拆成 10 页用 `0xA2` WRITE 写。该芯片对 `0xA2`（甚至高层 `Ndef.writeNdefMessage`）一律不响应，
  表现为 `Transceive failed` / `Tag was lost`。

### 响应（20 字节）

```text
[0]     = 0xB1 帧头
[1]     = commandId
[2]     = payload 长度
[3..]   = RC4 加密 payload
[末]    = CRC8
```

`resultCode = RC4解密(payload)[3]`，`0/23` 为成功。结果码表同上。

### 凭证特性

- NFC 命令 `buildNfcCommand(deviceId, credential, projectId)` 是**确定性的**（无 nonce），
  同一 `credential` 每次生成的 40 字节完全相同。
- 响应仅 20 字节，**装不下 32 字节 chainKey**，因此 **NFC 凭证是静态的、不滚动**（与 BLE 不同）。
- “过期”只能由服务器侧离线凭证到期触发（门锁回 `24 已过有效期` / `27`），
  客户端检测到后用已存账号密码重新 `syncCredential`（见「NFC 激活」节的说明）。

---

## 加密算法

> 以下三种原语 NFC / BLE 共用。所有 32 位运算均为**无符号、模 2³²**（加减后 `& 0xFFFFFFFF`）。

### 密钥派生 deriveKey(deviceId) → 16 字节

常量：
- `DELTA0 = 0x9E3779B9` (`2654435769`)
- `DELTA_STEP = 0x12345678` (`305419896`)
- `KEY_CONST`（16 字节）= `[172,171,188,218, 174,191,20,38, 53,66,84,101, 114,135,146,1]`

```text
# 1) deviceId 按小端拆 4 字节 [b0,b1,b2,b3]（b0 为最低字节），再按大端拼回（= 字节序翻转）
value = (b0<<24 | b1<<16 | b2<<8 | b3) & 0xFFFFFFFF

# 2) KEY_CONST 按 little-endian 读成 4 个 uint32
words[0..3] = KEY_CONST 每 4 字节一组、按小端解析

# 3) 4 轮混淆
for i in 0..3:
    delta  = (DELTA0 + DELTA_STEP * i) & 0xFFFFFFFF
    left   = ((value AND delta) + i) & 0xFFFFFFFF
    middle = ((value OR delta) - 2*i) & 0xFFFFFFFF
    right  = ((0xFFFFFFFF XOR value) XOR delta) & 0xFFFFFFFF
    t      = (left + middle - right) & 0xFFFFFFFF
    words[i] = words[i] XOR t

# 4) 输出：4 个 word 各按【大端】写 4 字节，拼成 16 字节
return BE32(words[0]) ++ BE32(words[1]) ++ BE32(words[2]) ++ BE32(words[3])
```

> 注意第 1 步与第 4 步字节序相反（输入小端拆/大端拼，输出大端）。

### RC4

标准 RC4（无丢弃 / 无 `drop-n`）。密钥 = `deriveKey(deviceId)`（16 字节）。
KSA 用 `key[i % 16]` 初始化 S 盒，PRGA 逐字节 XOR。加解密同一函数。

### CRC8

查表法，初值 `0x00`、无最终异或：

```text
crc = 0
for b in data: crc = TABLE[crc XOR b]
return crc & 0xFF
```

`TABLE` 为 256 项（MSB-first，指纹 `TABLE[1]=0x5E`），前 16 项：

```text
0, 94, 188, 226, 97, 63, 221, 131, 194, 156, 126, 32, 163, 253, 31, 65, ...
```

完整表见源码 `DoorCrypto.kt` 的 `CRC8_TABLE`（可直接复制）。

### NFC 命令 (40 字节)

```text
[0]     = 0xB1
[1]     = 0x0D
[2]     = 加密数据长度 (36)
[3-38]  = RC4(projectId[4] + credential[32])
[39]    = CRC8(0x0D, 长度, projectId + credential)
```

通信方式：把整个 40 字节帧通过 `NfcA.transceive()` **一次性发送**，门锁直接在返回值里回 20 字节响应。
**不是** NTAG 的 `0xA2` 写页协议（这颗联机芯片不响应 `0xA2`，详见「NFC 协议」）。

### BLE 命令 (20 字节)

```text
[0]     = 0x14
[1]     = 0x00
[2]     = 命令类型
[3-18]  = RC4(16字节明文)
[19]    = CRC8(16字节明文)
```

通信方式：写入 Write 特征值 (`ff01`)，从 Notify 特征值 (`ff02`) 接收响应。

### BLE 各命令的 16 字节明文载荷

> `projectId` / `ran` 均为 **little-endian** 4 字节；`credential` = 32 字节（64 hex）。

- **0x74 凭证头**：`[0]=0x28(=40), [1]=0x00, [2]=0x03, [3]=CRC8(projectId_LE ++ credential), [4..15]=0`
  - 响应：明文 `[0]` 为结果码（须为 0）；`ran = 明文[4..7]` 按小端解析
- **0x75 凭证分包 ×3**：先拼 `payload = ran_LE(4) ++ projectId_LE(4) ++ credential(32)` = 40 字节，
  按每 15 字节切 3 包；每包明文 = `[0]=包序号(0/1/2), [1..15]=该段 15 字节`
- **0x78 开门**：明文 16 字节全 0（凭证已在前几步注册）
  - 响应：明文 `[0]` 为结果码
- **0x76 / 0x77**（结果码 27 时刷新凭证）：`0x76` data = `credential_id` 小端 4 字节（其余 0）；
  `0x77` data = `[0]=请求的分包序号`，门锁分包回传新 `credential`（`0x76` 响应里 `[1]`=包数、`[2..3]`=凭证总长、`[4]`=CRC8）

> NFC 没有上述握手：凭证静态，直接整帧发 `0xB1` 命令即可（见「NFC 协议」）。

---

## 当前客户端实现备注

以下内容是**实现侧补充说明**，用于说明当前仓库如何落地上述协议，不替代上面的原始协议资料。

### 请求与解析

- 当前客户端对外层响应做宽松解析，失败判断兼容 `success` / `result` / `code` / `status` / `errno`。
- `data` 字段兼容三种形式：直接 JSON、JSON 字符串、Base64 字符串。
- 字段查找采用递归方式，兼容多种命名：
  - 用户 ID：`id` / `user_id` / `userId` / `uid`
  - 身份码：`identity_code` / `identitycode`
  - 凭证：`credential` / `chain_key` / `chainKey`
  - 凭证 ID：`credential_id` / `credentialId`
  - 蓝牙地址：`ble_mac` / `bleMac`

### 本地同步结果

同步后本地保存的核心字段为：

- `serverUrl`
- `authServerUrl`
- `phone`
- `password`
- `userId`
- `identityCode`
- `deviceId`
- `credentialId`
- `bleMac`
- `credentialHex`
- `sessionSecret`
- `updatedAt`

其中 `credentialHex` 会被规范化为 64 位大写 hex。

### 当前开门处理差异

- NFC 主流程不调用 `/door_lock/command/create`，而是直接使用本地 `credential` 构造 40 字节命令。
- NFC 自动重新同步会处理结果码 `24` 与 `27`。
- BLE 自动刷新只在结果码 `27` 时触发，不处理 `24`。
- BLE 实现当前还会校验响应的 `[1] == 0x00`、命令类型、CRC8。
