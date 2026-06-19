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
按下按钮 → N32WB031 唤醒 → BLE 扫描目标锁 → 连接 → 发送加密凭证(0x74/0x75/0x78) → 门开 → LED 闪烁 → 回深睡
```

与 app 完全相同的 BLE 协议（见 api-doc.md），不需要 NFC、不需要手机、不需要联网。

### 核心参数

| 项 | 值 |
|---|---|
| 芯片 | **国民技术 N32WB031KEQ6-2**（Cortex-M0 64MHz，BLE 5.1） |
| 协议 | BLE 5.1（兼容门锁 BLE 4.2 接口） |
| 凭证 | 固件内置，开锁后 chainKey 滚动更新 |
| 续航 | CR2032 纽扣电池，每日开 4~6 次，可用 **10~14 个月** |
| 体积 | ~25mm × 18mm × 8mm（含电池座、不含外壳） |
| 延迟 | 按下到开门 ~1~2.5s（大部分时间在 BLE 连接建立） |
| 单片成本 | ~¥16（打样 10 片）/ ~¥10（100 片） |

---

## 2. 硬件设计

### BOM (物料清单)

| # | 名称 | 型号/规格 | 数量 | 单价(参考) |
|---|------|----------|------|-----------|
| 1 | **BLE 芯片** | N32WB031KEQ6-2 (QFN32) | 1 | ~¥5 |
| 2 | 32MHz 晶振 | 3215 封装 + 12pF 负载电容 | 1+2 | ~¥0.3 |
| 3 | 32.768KHz 晶振 | 2012 封装 + 6.8pF 负载电容 | 1+2 | ~¥0.2 |
| 4 | LDO 3.3V | ME6211 (SOT-23-5) | 1 | ~¥0.2 |
| 5 | RF 匹配 | 电感 2.2nH + 电容 0.5pF/1pF (π 型) | 3 | ~¥0.15 |
| 6 | PCB 天线 | 板上倒 F 天线 (25mm) | — | 0 |
| 7 | 按钮 | 4×4mm 侧按贴片 | 1 | ~¥0.08 |
| 8 | LED | 0603 蓝/绿贴片 | 1 | ~¥0.03 |
| 9 | 电阻 | 0603 10kΩ/1kΩ | 3 | ~¥0.03 |
| 10 | 电容 | 0603 100nF/10µF | 4 | ~¥0.05 |
| 11 | CR2032 电池座 | 贴片侧入式 | 1 | ~¥0.25 |
| 12 | CR2032 电池 | 松下/Maxell | 1 | ~¥2 |
| **元器件合计** | | | | **~¥8.5** |

> **为什么选 N32WB031**：¥5 裸芯片（模组方案 ¥12+）、BLE 5.1、Cortex-M0 64MHz、
> 深睡 0.6µA（比 nRF52832 的 1.9µA 更省电）、256KB Flash/48KB SRAM、
> QFN32 仅 4×4mm。国民技术官网有完整 SDK（BLE 协议栈 + HAL）。
> 需要自己画最小系统和天线，但参考设计可直接抄。

### 原理图（文字描述）

```
         ┌─────────────────────────────────────┐
         │         N32WB031 (QFN32)            │
         │                                     │
  VCC ───┤ VDD                          PA0  ├─── [10kΩ] ── VCC (按钮上拉)
  GND ───┤ GND                          PA0  ├──── [按钮] ─── GND
         │                              PA1  ├─── [1kΩ] ── [LED] ── GND
         │                              PA2  ├─── (预留: UART TX)
         │                              PA3  ├─── (预留: UART RX)
         │                              SWD  ├─── (SWDIO/SWCLK 调试)
         │                              RF   ├─── [π匹配] ── [PCB天线]
         │                                     │
         │ HSE ◇─[32MHz 晶振]─◇               │
         │ LSE ◇─[32.768K 晶振]─◇             │
         └─────────────────────────────────────┘
                    │    │    │
                  100nF 100nF 10µF    (VDD 去耦电容)
                   │    │    │
                  GND  GND  GND

              ┌──────────┐
              │ ME6211   │
  CR2032 ────┤ IN  OUT ├─── 3.3V → VCC
              │   GND   │
              └──────────┘
                   │
                  GND
```

### PCB 要点

- **双面板**，尺寸 ~25×18mm（圆角矩形，像普通钥匙扣）
- **PCB 天线**区域不要铺铜（板边净空 ≥5mm），天线朝外
- **射频匹配网络**靠近芯片 RF 引脚，走线尽量短
- **32MHz 晶振**靠近芯片 HSE 引脚，走线对称、包地
- 按钮放边缘方便按，LED 露出外壳
- CR2032 座放背面，整机厚度 ≈ 6~8mm
- 留 SWD 测试点（SWDIO/SWCLK）+ UART 测试点（数据导入）
- 芯片 QFN32 4×4mm 极小，PCB 面积比用 E104-BT02 模组方案缩小 ~30%

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
└── n32wb031_config.h       # SDK 配置、引脚定义
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

通过 UART（预留的 PA2/PA3）发送 JSON 配置串：

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
| 深睡 | **~0.6µA** | 持续 | — |
| 按钮唤醒 + 准备 | ~5mA | ~5ms | 每次开门 |
| BLE 扫描 | ~4.5mA | ~0.5~2s | 每次开门 |
| BLE 连接 + 握手 | ~5mA | ~0.2~0.5s | 每次开门 |
| LED 闪烁 | ~5mA | ~1s | 每次开门 |

### 续航估算

**假设每天开门 6 次，每次平均 2 秒 BLE 活跃 + 1 秒 LED：**

```
深睡电流:   0.6µA × 24h = 14.4µAh/天
BLE+LED:    5mA × 6次 × 3s = 90mAs = 25µAh/天
─────────────────────────────────────
合计:       ~40µAh/天

CR2032 容量:  ~225mAh
理论续航:     225000µAh ÷ 40µAh ≈ 5625天 ≈ 15年
```

实际上电池自放电 + 低温 + 电流峰值会打折，**保守估计 10~14 个月**。
N32WB031 深睡仅 0.6µA（nRF52832 为 1.9µA），续航提升 ~20%。

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
3. N32WB031 是 QFN32 4×4mm 标准封装，JLC SMT 可直接贴
4. 晶振和 RF 匹配元件也都是标准 0603/0402 封装，全部可贴
5. 贴片费 ~¥8/片（10 片起）

### 下单清单

| 项目 | 数量 | 价格 |
|------|------|------|
| PCB 打样 | 10 片 | ~¥5 |
| SMT 贴片（全贴） | 10 片 | ~¥80 |
| N32WB031KEQ6-2 | 10 个 | ~¥50（淘宝/立创） |
| 晶振 + 匹配元件 | 10 套 | ~¥10 |
| CR2032 电池 | 10 个 | ~¥20 |
| **合计** | | **~¥165（¥16.5/个）** |

### 外壳

- **3D 打印**：PLA/PETG，分上下壳，卡扣或螺丝固定
- **硅胶套**：最简单，买个通用硅胶钥匙扣套，塞进去
- **树脂灌封**：防水防摔，适合最终版

---

## 7. 开发板替代方案

**不想自己画 PCB？** 先用开发板验证固件：

| 开发板 | 价格 | 优点 | 缺点 |
|--------|------|------|------|
| **N32WB031 评估板** | ~¥30 | 同芯片、国民技术官方 | 需联系代理或淘宝 |
| **ESP32-C3 Mini** | ~¥10 | 便宜、BLE 5.0 | 功耗比 N32WB031 高 |
| **Seeed XIAO nRF52840** | ~¥60 | 小巧、有按键、社区好 | 贵 |
| **E104-BT02 模组** | ~¥12 | 带天线、免画射频 | 封装不一致，固件需适配 |

**建议路径**：买 N32WB031 评估板（或最小系统板）验证固件 → 确认能开门 → 画 PCB 量产。
如果评估板不好买，也可以先用 ESP32-C3 验证协议逻辑（crypto + BLE 握手），
确认没问题后再切到 N32WB031 画板。

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
