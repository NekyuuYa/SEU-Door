# Changelog

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
