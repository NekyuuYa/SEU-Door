# API 文档

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

```
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

```
GET https://pm.whxinna.com/webapi/users/login
```

| 参数 | 说明 |
|------|------|
| `phone` | 手机号 |
| `pwd` | 密码（明文，6 位数字） |

签名密钥: `6d5dbb85b949447a95ff8fda9a9b759b`

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
- user_id: `user_info.id`
- identitycode: `user_info.identity_code`

---

### 2. 获取门锁信息

```
GET {server_addr}/webapi/v1/student/accommodation/details
```

| 参数 | 说明 |
|------|------|
| `user_id` | 用户 UUID |
| `identitycode` | 登录返回的 identity_code |

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

```
GET {server_addr}/webapi/v1/staff/door_lock/credentials
```

| 参数 | 说明 |
|------|------|
| `device_id` | 门锁设备 ID |
| `user_id` | 用户 UUID |
| `identitycode` | identity_code |

当 `accommodation/details` 未返回 credential 时，用此接口补充获取。

---

### 4. NFC 激活

```
GET {server_addr}/webapi/v1/door_lock/command/create
```

| 参数 | 说明 |
|------|------|
| `device_id` | 门锁设备 ID |
| `command` | `1` |
| `type` | `nfc` |
| `credential_id` | 凭证 ID |
| `user_id` | 用户 UUID |
| `identitycode` | identity_code |

**响应** (base64 解码):

```json
{
  "credential_id": 1234,
  "command": 1,
  "payload": "B10105XXXXXXXXXXXX"
}
```

payload 为 hex 编码的 NFC 写入数据。写入后门锁存储凭证，后续可离线开门。

> **注意**: `type=ble` 当前服务器不支持（返回"暂不支持此命令"）。BLE 开门不需要激活。

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

```
[0]    = 0x14 (长度标识)
[1]    = 0x00
[2]    = 命令类型 (0x74/0x75/0x76/0x77/0x78)
[3-18] = RC4 加密的 16 字节数据
[19]   = CRC8 (对明文计算)
```

### 响应格式 (20 字节)

```
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

```
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

## 加密算法

### 密钥派生

`device_id` → 16 字节密钥。使用 TEA-like 变体，常量表:

```
[172, 171, 188, 218, 174, 191, 20, 38, 53, 66, 84, 101, 114, 135, 146, 1]
```

### RC4

标准 RC4 流密码，密钥由 `deriveKey(deviceId)` 生成。

### CRC8

查表法，表首项为 0，共 256 项。

### NFC 命令 (40 字节)

```
[0]     = 0xB1
[1]     = 0x0D
[2]     = 加密数据长度 (36)
[3-38]  = RC4(projectId[4] + credential[32])
[39]    = CRC8(0x0D, 长度, projectId + credential)
```

写入方式: NfcA WRITE 命令 (0xA2)，每页 4 字节，共 10 页。

### BLE 命令 (20 字节)

```
[0]     = 0x14
[1]     = 0x00
[2]     = 命令类型
[3-18]  = RC4(16字节明文)
[19]    = CRC8(16字节明文)
```

通信方式: 写入 Write 特征值 (ff01)，从 Notify 特征值 (ff02) 接收响应。
