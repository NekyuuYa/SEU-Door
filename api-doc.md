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
