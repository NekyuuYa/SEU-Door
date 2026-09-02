# door-fob — ESP32-C3 钥匙扣固件

门锁 App（本仓库根目录的 Android 工程）的配套固件：一个 ESP32-C3 钥匙扣，
按下即开门。App 只负责一次性导出 OAuth 会话，fob 之后通过 WiFi 联网自动刷新
门锁凭证，无需再掏手机。

```
App (本仓库)                     fob (本目录固件)
─────────────                   ─────────────
支付宝 OAuth 登录 ──BLE 配置──▶  存 NVS (session/wifi/portal)
   （一次性）       0xFF10 服务
                                中按/失败重试 ──WiFi+portal+SNTP──▶ 签名 API
                                  staff/credentials → accommodation/details
                                  → staff/door_lock/credentials（取当前链密钥）
                                短按 ──BLE 0xFF12 服务──▶ 门锁
                                  0x74 头 → 0x75 凭证包×3 → 0x78 开门
```

## 与 App 的绑定

- **BLE 配置服务**（fob 作外设）：service `0xFF10`，write `0xFF12`。App 的
  `DoorFob.kt` 把 JSON 写入此特征（字段级局部更新）。fob 的解析器按标准 JSON
  反转义（Android `org.json` 会把 `/` 转义成 `\/`，早期版本曾因此存坏 URL）。
- **网络会话**：`server_url` / `session_secret` / `user_id` / `identity_code`
  来自 App 的 OAuth 导出，与 `DoorApi.kt` 同签名算法（排序参数 + `&key=`
  + MD5 大写）。凭证接口链与 `DoorApi.kt refreshCredentialOnly` 对齐。
- **开门协议**：与 App 的 `DoorBle.kt` / `DoorCrypto.kt` 逐字节一致
  （RC4 + derive_key + CRC8 + 20 字节帧）。关键交互细节：
  - 写特征用**带响应写**，锁的应答走 **0xFF02 通知**（需先写 CCCD 订阅），
    不能用 write-no-rsp + 显式 read。
  - 扫描只认 `XN-<device_id>` 的精确匹配（楼道里有邻居的多把 XN- 锁）。
  - `LE Create Connection` 的 `scan_itvl/scan_window` 必须 ≥0x0004。

## 构建 / 烧录

环境：ESP-IDF **v6.2**（本仓库基于 IDF 6.2 开发；`cjson` 经组件管理器拉取）。

```bash
cd door-fob
export IDF_PATH=...   # 指向你的 esp-idf 6.2 安装
source $IDF_PATH/export.sh
idf.py set-target esp32c3
idf.py build
idf.py -p /dev/ttyACM0 flash        # 板子经 USB-Serial/JTAG 出现为 ttyACM0
idf.py -p /dev/ttyACM0 monitor
```

要点（都固化在 `sdkconfig.defaults` / `partitions.csv`）：
- **4MB flash** + 自定义分区表（factory 1.9MB；WiFi+TLS 后 app 超过默认 1MB）
- BLE 五种角色全开、ATT MTU 517（配置载荷需要）
- 无 bonding（`MAX_BONDS=0`）

> 注意：`sdkconfig` 是本地生成文件（不入库）；首次 `set-target` 时会由
> `sdkconfig.defaults*` 重新生成。

## 使用

| 按键 | 行为 |
|------|------|
| 短按（<1s）| 开门；凭证被拒时自动联网刷新一次并重试 |
| 中按（1~3s）| 联网刷新凭证（LED：3×300ms 成功 / 10×100ms 会话失效 / 5×100ms 其他失败）|
| 长按（≥3s）| 60s 配置窗口（广告 0xFF10，App 或 `tools/ble_provision.py` 写入）|

首次配置：App 里做一次支付宝 OAuth 登录并导出 → 长按 fob 进配置窗口 → 写入。
之后 fob 自持会话、自行刷新，App 只在会话失效时重新导出。

`tools/ble_provision.py`：无 App 时的 BLE 写入工具
（`python3 ble_provision.py session.json`），字段见脚本头部注释。

## 目录

```
CMakeLists.txt            工程根
partitions.csv            自定义分区表（4MB）
sdkconfig.defaults*       构建配置
main/
  main.c                  按键状态机 / 开门 / 自动同步重试
  door_ble.c/.h           BLE 开门协议（中央：扫锁→连→0x74/0x75/0x78）
  door_crypto.c/.h        RC4/MD5/CRC8、帧与载荷构造、响应解析
  door_api.c/.h           签名 API 客户端（三段式取凭证）
  net_service.c/.h        WiFi STA + SEU eportal 认证 + SNTP
  net_config.c/.h         NVS 网络/会话字段
  config_service.c/.h     BLE 配置外设（0xFF10）
  cred_store.c/.h         NVS 门锁凭证
  idf_component.yml       依赖声明（espressif/cjson）
```

设计文档见仓库根 `docs/keyfob-design.md`。
