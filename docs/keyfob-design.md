# BLE 钥匙扣设计方案

独立硬件开门设备，走 BLE 协议（与手机 app 共用同一套门锁接口）。

---

## 目录

1. [概述](#1-概述)
2. [硬件设计](#2-硬件设计)
3. [固件架构](#3-固件架构)
4. [数据导入方案](#4-数据导入方案)
5. [功耗与续航](#5-功耗与续航)
6. [JLC 打样指南](#6-jlc-打样指南)
7. [开发板替代方案](#7-开发板替代方案)

---

## 1. 概述

### 工作原理

```
按下按钮 → nRF52 唤醒 → BLE 扫描目标锁 → 连接 → 发送加密凭证(0x74/0x75/0x78) → 门开 → LED 闪烁 → 回深睡
```

与 app 完全相同的 BLE 协议（见 api-doc.md），不需要 NFC、不需要手机、不需要联网。

### 核心参数

| 项 | 值 |
|---|---|
| 协议 | BLE 4.2+ (与门锁 BLE 接口完全一致) |
| 凭证 | 固件内置，开锁后 chainKey 滚动更新 |
| 续航 | CR2032 纽扣电池，每日开 4~6 次，可用 **8~12 个月** |
| 体积 | ~30mm × 20mm × 8mm（含电池座、不含外壳） |
| 延迟 | 按下到开门 ~1~2.5s（大部分时间在 BLE 连接建立） |

---

## 2. 硬件设计

### BOM (物料清单)

| # | 名称 | 型号/规格 | JLC 编号 | 数量 | 单价(参考) |
|---|------|----------|----------|------|-----------|
| 1 | **BLE 模组** | E104-BT02 (nRF52832) | C2688814 | 1 | ~¥12 |
| 2 | 按钮 | 4×4mm 侧按贴片 | C127509 | 1 | ~¥0.1 |
| 3 | CR2032 电池座 | 贴片侧入式 | C45884 | 1 | ~¥0.3 |
| 4 | LED | 0603 蓝/绿贴片 | C2297 | 1 | ~¥0.05 |
| 5 | 电阻 | 0603 10kΩ (上拉) | C25076 | 2 | ~¥0.01 |
| 6 | 电阻 | 0603 1kΩ (LED限流) | C25077 | 1 | ~¥0.01 |
| 7 | 电容 | 0603 100nF (去耦) | C49678 | 2 | ~¥0.01 |
| 8 | CR2032 电池 | 松下/Maxell | — | 1 | ~¥2 |

**总计 BOM ~¥15 + PCB ¥2 + 贴片 ¥8 ≈ ¥25/片**（10 片起）

> **为什么选 E104-BT02**：JLC 标配库存、nRF52832 内核、带板载天线、3.3V LDO、
> 20 个 GPIO 引出、有完整 SDK 支持。比自己画 nRF52 最小系统省至少一轮 PCB 打样。
> 其他可选模组：E73-2G4M04S（更小但 GPIO 少）、飞易通 FB33（价格更低）。

### 原理图（文字描述）

```
         ┌──────────────────────┐
         │   E104-BT02 (nRF52)  │
         │                      │
  VCC ───┤ VCC          P0.13 ├─── [10kΩ] ── VCC   (按钮上拉)
  GND ───┤ GND          P0.13 ├──── [按钮] ─── GND  (按下拉低)
         │              P0.14 ├─── [1kΩ] ── [LED] ── GND
         │              P0.15 ├─── (预留: 串口 TX，数据导入用)
         │              P0.16 ├─── (预留: 串口 RX，数据导入用)
         │                      │
  VCC ───┤ VCC          [天线]  │
         └──────────────────────┘
              │         │
             100nF     100nF       (VCC 去耦电容 ×2)
              │         │
             GND       GND

         ┌─────────┐
         │ CR2032  │
         │ 3V 电池 │
         └────┬────┘
              VCC
```

### PCB 要点

- **双面板**即可，尺寸尽量小（可做圆形/方形，像普通钥匙扣）
- **天线区域**不要铺铜（模组板载天线下方净空 ≥5mm）
- 按钮放边缘方便按
- LED 露出外壳（或用导光柱）
- CR2032 座放背面，整机厚度 = 模组 + 电池座 ≈ 6~8mm
- 留 2 个测试点（SWDIO/SWCLK，调试用）+ 2 个 UART 测试点（数据导入）

---

## 3. 固件架构

### 状态机

```
           ┌──────────────────────────┐
           │      DEEP SLEEP          │ ← 上电/开门后自动进入
           │   (功耗 ~1~3µA)          │
           └────────────┬─────────────┘
                        │ 按钮按下 (GPIO 中断)
                        ▼
           ┌──────────────────────────┐
           │   CONFIG_CHECK           │
           │  (凭证是否存在？)        │
           └─────┬──────────┬─────────┘
          不存在 │          │ 存在
                 ▼          ▼
     ┌────────────┐   ┌──────────────────┐
     │ CONFIG MODE│   │   BLE_OPEN       │
     │ (BLE广播)  │   │  扫描→连接→开门  │
     │ 等待配置   │   │                  │
     └────────────┘   └───────┬──────────┘
        │                     │
        │ 手机连接写入凭证     │ 结果码
        │ 保存到Flash         │ 27: 刷新凭证(如有网，交手机)
        │ 重启               │ 0/23: LED闪 → 回DEEP SLEEP
        │                     │ 其他: LED快闪 → 回DEEP SLEEP
        ▼                     ▼
     ┌──────────────────────────────────┐
     │          DEEP SLEEP              │
     └──────────────────────────────────┘
```

### 关键模块

```
firmware/
├── main.c                  # 主循环、状态机
├── ble_door.c/.h           # BLE 开门协议 (移植自 api-doc.md)
├── crypto.c/.h             # deriveKey / RC4 / CRC8 (移植自 DoorCrypto.kt)
├── credential.c/.h         # 凭证读写 (Flash 存储)
├── config_service.c/.h     # BLE 配置服务 (数据导入用)
├── button.c/.h             # 按钮驱动 (GPIO 中断 + 防抖)
├── led.c/.h                # LED 驱动 (PWM 闪烁)
└── nrf52_config.h          # SDK 配置、引脚定义
```

### 开门协议（C 伪代码）

```c
// 与 api-doc.md BLE 协议完全一致
int door_open(void) {
    // 1. 扫描目标锁 (名称前缀 "XN-{device_id}")
    ble_scan_result_t *lock = ble_scan("XN-", 3000);  // 3s 超时
    if (!lock) return ERR_SCAN_TIMEOUT;

    // 2. 连接
    ble_conn_t *conn = ble_connect(lock, 10000);       // 10s 超时
    if (!conn) return ERR_CONNECT_FAIL;

    // 3. 发送 0x74 凭证头
    uint8_t header_data[16] = {0x28, 0x00, 0x03};
    header_data[3] = crc8(project_id_le ++ credential);
    uint8_t header_resp[20];
    ble_write_command(conn, 0x74, header_data, header_resp);
    if (header_resp[3] != 0) return ERR_HEADER_FAIL;
    uint32_t ran = le32_to_uint(header_resp + 7);  // plainData[4..7]

    // 4. 发送 0x75 凭证分包 ×3
    uint8_t payload[40];  // ran(4) + projectId(4) + credential(32)
    memcpy(payload, &ran, 4);                    // LE
    memcpy(payload + 4, project_id_le, 4);
    memcpy(payload + 8, credential, 32);
    for (int i = 0; i < 3; i++) {
        uint8_t pkt[16] = {0};
        pkt[0] = i;
        memcpy(pkt + 1, payload + i * 15, min(15, 40 - i * 15));
        ble_write_command(conn, 0x75, pkt, resp);
        if (i < 2) delay_ms(10);
    }
    delay_ms(50);

    // 5. 发送 0x78 开门
    uint8_t open_data[16] = {0};
    ble_write_command(conn, 0x78, open_data, resp);
    int code = resp[3];  // plainData[0] = 结果码
    ble_disconnect(conn);

    // 6. 处理结果
    if (code == 27) {
        // 需要更新凭证 → 进入 CONFIG MODE 等待手机推送新凭证
        credential_needs_refresh = true;
        save_refresh_flag();
    }
    return code;  // 0/23 = 成功
}
```

### BLE 命令构建（移植自 DoorCrypto.kt）

```c
// buildBleCommand() 的 C 版本
void build_ble_command(uint8_t *out, int cmd_type, const uint8_t *data16,
                       int device_id, const uint8_t *credential) {
    uint8_t key[16];
    derive_key(device_id, key);

    uint8_t plain[16];
    memcpy(plain, data16, 16);
    uint8_t encrypted[16];
    rc4_encrypt(plain, encrypted, key);

    out[0] = 0x14;           // 长度标识
    out[1] = 0x00;           // 保留
    out[2] = cmd_type;       // 命令类型
    memcpy(out + 3, encrypted, 16);
    out[19] = crc8(plain);
}
```

---

## 4. 数据导入方案

### 方案概览

**通过 BLE 配置服务，用手机/电脑把凭证写入钥匙扣。**

钥匙扣有两种模式：

| 模式 | 触发方式 | 行为 |
|------|---------|------|
| **正常模式** | 短按按钮 | 开门 |
| **配置模式** | **长按 3 秒** | 广播 BLE 配置服务，等待设备写入凭证 |

配置完成后自动保存到 Flash、重启进入正常模式。

### BLE 配置服务定义

| UUID | 说明 | 读 | 写 |
|------|------|----|----|
| `0xFF10` | **配置服务** | — | — |
| `0xFF11` | 设备信息特征 | ✅ | ❌ |
| `0xFF12` | 凭证写入特征 | ❌ | ✅ |
| `0xFF13` | 状态/结果特征 | ✅ | ❌ |

#### 0xFF11 设备信息（只读，20 字节）

```
[0..3]   = firmware_version (LE uint32)
[4..7]   = device_id (LE int32, 0 = 未配置)
[8]      = has_credential (0/1)
[9]      = has_refresh_pending (0/1)
[10..19] = 保留
```

#### 0xFF12 凭证写入（只写）

写入格式（JSON，UTF-8）：

```json
{
  "device_id": 2283914,
  "credential": "AABBCCDD...（64 hex 字符）",
  "project_id": 21048,
  "ble_mac": "AA:BB:CC:DD:EE:FF",
  "credential_id": 1234
}
```

> 设备收到后：校验字段 → 保存到 Flash → 返回结果到 0xFF13 → 重启。

#### 0xFF13 状态（只读通知，1 字节）

| 值 | 说明 |
|----|------|
| 0 | 配置成功 |
| 1 | JSON 解析失败 |
| 2 | credential 格式错误（非 64 hex） |
| 3 | device_id 缺失 |
| 4 | Flash 写入失败 |

### 导入方式（三选一，推荐 A）

#### A. 手机 App 导入（最方便）

现有 Android app 加一个「导出到钥匙扣」功能：

```
打开 app → NFC 页切到蓝牙页 → 点「配置钥匙扣」按钮
→ 弹窗：「长按钥匙扣 3 秒，LED 慢闪后松手」
→ app 扫描到配置服务 → 自动发送当前凭证 JSON → 收到结果码 0 → 提示成功
```

app 侧代码改动很小——就是一个 BLE 写特征值：

```kotlin
// 伪代码：app 端发送凭证到钥匙扣
fun exportToFob(snapshot: DoorCredentialSnapshot) {
    val json = JSONObject().apply {
        put("device_id", snapshot.deviceId)
        put("credential", snapshot.credentialHex)
        put("project_id", DoorApi.PROJECT_ID)
        put("ble_mac", snapshot.bleMac)
        put("credential_id", snapshot.credentialId)
    }
    // 连接钥匙扣 → 写 0xFF12 → 读 0xFF13 确认
    bleWrite("0xFF12", json.toString().toByteArray())
    val status = bleRead("0xFF13")
    if (status[0] == 0) toast("钥匙扣配置成功！")
}
```

**优势**：零额外硬件、用户操作最少、凭证从 app 直接同步过来。

#### B. Web Bluetooth 导入（无需装 app）

在浏览器中打开一个网页（可以用 `web/` 目录里已有的 HTTPS 服务器），通过 Web Bluetooth API 写入：

```javascript
// 伪代码：Web Bluetooth 配置
async function configureFob() {
  const device = await navigator.bluetooth.requestDevice({
    filters: [{ services: ['0xFF10'] }]
  });
  const server = await device.gatt.connect();
  const service = await server.getPrimaryService('0xFF10');

  // 读当前状态
  const info = await service.getCharacteristic('0xFF11');
  const infoVal = await info.readValue();

  // 写入凭证
  const writer = await service.getCharacteristic('0xFF12');
  const config = JSON.stringify({
    device_id: 2283914,
    credential: "AABBCCDD...",
    project_id: 21048,
    ble_mac: "AA:BB:CC:DD:EE:FF",
    credential_id: 1234
  });
  await writer.writeValue(new TextEncoder().encode(config));

  // 读结果
  const status = await service.getCharacteristic('0xFF13');
  const result = await status.readValue();
  alert(result.getUint8(0) === 0 ? "配置成功！" : "配置失败");
}
```

**优势**：手机/电脑浏览器都能用，不依赖 Android app。
**限制**：iOS Safari 不支持 Web Bluetooth；需要 HTTPS（`web/` 里的证书可以用）。

#### C. USB/串口导入（调试用）

通过 UART（预留的 P0.15/P0.16）发送 JSON 配置串：

```
# 用 USB-TTL 连接钥匙扣 UART
echo '{"device_id":2283914,"credential":"AABB...","project_id":21048}' > /dev/ttyUSB0
```

**优势**：最简单、不需要 BLE 栈。
**限制**：需要 USB-TTL 转接器和外壳上开口，不适合量产。

### 凭证更新（过期处理）

钥匙扣开门后如果收到 **结果码 27**（需更新凭证）：

1. LED 快闪 3 次提示用户
2. 自动进入**配置模式**（广播配置服务）
3. 用户用手机 app 连接 → app 重新 `syncCredential` → 把新凭证写入钥匙扣
4. 收到新凭证 → 保存 → 重启 → 可以继续开门

> **注意**：钥匙扣没有网，无法自己刷新凭证。每次服务器侧凭证过期，
> 都需要手机介入一次（app 里点「刷新并导出到钥匙扣」）。
> 凭证有效期通常较长（数月），所以这个频率不高。

---

## 5. 功耗与续航

### 各阶段功耗

| 阶段 | 电流 | 持续时间 | 频率 |
|------|------|---------|------|
| 深睡 | ~2µA | 持续 | — |
| 按钮唤醒 + 准备 | ~5mA | ~5ms | 每次开门 |
| BLE 扫描 | ~6mA | ~0.5~2s | 每次开门 |
| BLE 连接 + 握手 | ~8mA | ~0.2~0.5s | 每次开门 |
| LED 闪烁 | ~5mA | ~1s | 每次开门 |

### 续航估算

**假设每天开门 6 次，每次平均 2 秒 BLE 活跃 + 1 秒 LED：**

```
深睡电流:   2µA × 24h = 48µAh/天
BLE+LED:    8mA × 6次 × 3s = 144mAs = 40µAh/天
─────────────────────────────────────
合计:       ~88µAh/天

CR2032 容量:  ~225mAh
理论续航:     225000µAh ÷ 88µAh ≈ 2557天 ≈ 7年
```

实际上电池自放电 + 低温 + 电流峰值会打折，**保守估计 8~12 个月**。

> **对比**：手机 app 开一次门 BLE 突发 ~8mA × 1~2s，但手机电池大 100 倍，
> 所以对手机来说无所谓。钥匙扣用纽扣电池，同样功耗占比完全不同，
> 但因为事件驱动（不开门 = 基本不耗电），续航依然很好。

---

## 6. JLC 打样指南

### PCB 打样

1. **EDA 工具**：立创 EDA（LCEDA）最方便，元件库直接对接 JLC 库存
2. **板子参数**：
   - 层数：2
   - 板厚：1.0mm（做薄一点，钥匙扣不能太厚）
   - 尺寸：建议 28mm × 18mm（圆形或圆角矩形）
   - 表面处理：HASL（便宜）或 ENIG（好焊）
3. **下单**：嘉立创 PCB 打样 https://www.jlc.com/newOrder
   - 5 片 ¥2、10 片 ¥5（含运费）
   - 24 小时出货

### SMT 贴片

1. 在 JLC SMT 页面上传 BOM + 坐标文件
2. 选「单面贴」（元件放正面，电池座放背面手焊）
3. **E104-BT02 模组需要手焊**（JLC SMT 不一定支持所有模组封装）
   - 替代：用 JLC 支持的 nRF52832 最小封装（QFN48），自己画最小系统
   - 或者：全部手焊（模组引脚大，手工能焊）
4. 贴片费 ~¥8/片（10 片起）

### 下单清单

| 项目 | 数量 | 价格 |
|------|------|------|
| PCB 打样 | 10 片 | ~¥5 |
| SMT 贴片 | 10 片 | ~¥80 |
| E104-BT02 模组 | 10 个 | ~¥120（淘宝/立创商城） |
| CR2032 电池 | 10 个 | ~¥20 |
| **合计** | | **~¥225（¥22.5/个）** |

### 外壳

- **3D 打印**：PLA/PETG，分上下壳，卡扣或螺丝固定
- **硅胶套**：最简单，买个通用硅胶钥匙扣套，塞进去
- **树脂灌封**：防水防摔，适合最终版

---

## 7. 开发板替代方案

**不想自己画 PCB？** 直接买开发板也能验证：

| 开发板 | 价格 | 优点 | 缺点 |
|--------|------|------|------|
| **nRF52832 DK** | ~¥150 | 官方开发板、调试器内置 | 大、不能做钥匙扣 |
| **E104-BT02 评估板** | ~¥25 | 同模组、直接验证固件 | 无按键/LED，需飞线 |
| **ESP32-C3 Mini** | ~¥10 | 便宜、BLE 5.0 | 功耗比 nRF52 高 |
| **Seeed XIAO nRF52840** | ~¥60 | 小巧、有按键 | 贵 |

**建议路径**：先买 E104-BT02 模组（~¥12）+ 面包板验证固件 → 确认能开门 → 画 PCB 量产。

---

## 附录：移植检查清单

从 app 移植固件时，按此顺序实现和自测：

- [ ] **Crypto 先行**：`deriveKey` / `RC4` / `CRC8`（用已知 device_id 验证输出与 app 一致）
- [ ] **BLE 命令构建**：`buildBleCommand` 单元测试（对比 app 输出的 hex）
- [ ] **BLE 扫描连接**：能扫到 `XN-{device_id}`、能连接、能发现服务 `0xFF12`
- [ ] **开门握手**：`0x74` → 拿 ran → `0x75` ×3 → `0x78` → 结果码
- [ ] **Flash 存储**：凭证持久化、断电不丢
- [ ] **状态机**：按钮中断唤醒 → 开门 → LED → 回深睡
- [ ] **配置模式**：长按 3 秒进入 → 手机能写入凭证 → 保存到 Flash
- [ ] **功耗实测**：深睡电流 < 5µA、开门平均电流 < 10mA
- [ ] **长期测试**：连续开门 100 次无失败、待机 1 周电量无明显下降
