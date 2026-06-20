package com.nkyuu.dooropener

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.alipay.android.app.IAlixPay
import com.alipay.android.app.IRemoteServiceCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用支付宝 app 的 IAlixPay AIDL service 完成「快捷登录授权」，无需引入支付宝 SDK。
 *
 * 调用姿势按反编译的 SDK 还原（当前支付宝 version>=3）：
 *   getVersion → registerCallback03(cb, authInfo, null) → r03("alipaySdk","bind_pay",null)
 *   → pay02(authInfo, traceMap)，期间支付宝回调 startActivity 拉起授权 UI，
 *   pay02 阻塞返回 "resultStatus={9000};memo={};result={auth_code=...}"。
 *
 * 必须在工作线程调用：bindService 回调在主线程，pay02 阻塞到用户授权结束。
 */
object DoorAlipayAuth {

    private const val TAG = "DoorAlipay"
    private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    private const val BIND_ACTION = "com.eg.android.AlipayGphone.IAlixPay"
    private const val BIND_TIMEOUT_MS = 15_000L
    private const val SDK_VER = "15.8.17"

    /** 文件日志，logcat 被系统吃掉时备用 */

    /** 工作线程调用。成功返回 result 串里的 auth_code；失败抛 DoorApiException。 */
    fun authorize(context: Context, authInfo: String): String {
        val appContext = context.applicationContext
        val latch = CountDownLatch(1)
        // latch 的 countDown/await 建立 happens-before，service 的写对工作线程可见
        var service: IAlixPay? = null
        val bindStart = System.currentTimeMillis()

        // SDK 用 TransProcessPayActivity 轻量唤醒支付宝进程（不跳主页）
        try {
            val wake = Intent()
            wake.setClassName(ALIPAY_PACKAGE, "com.alipay.android.app.TransProcessPayActivity")
            wake.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(wake)
            Thread.sleep(200)
        } catch (_: Throwable) {}

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Log.d(TAG, "onServiceConnected $name")
                service = IAlixPay.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }

        val intent = Intent(BIND_ACTION).setPackage(ALIPAY_PACKAGE)
        val bound = runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.e(TAG, "bindService threw", it) }.getOrDefault(false)
        Log.d(TAG, "bindService bound=$bound")
        if (!bound) {
            runCatching { appContext.unbindService(connection) }
            throw DoorApiException(rawText("无法连接支付宝，请确认已安装支付宝"))
        }

        try {
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw DoorApiException(rawText("连接支付宝超时"))
            }
            val bindEnd = System.currentTimeMillis()
            val alixPay = service ?: throw DoorApiException(rawText("支付宝服务不可用"))

            val callback = object : IRemoteServiceCallback.Stub() {
                // 支付宝在 pay02 阻塞期间回调，要求拉起它自己的授权 UI Activity（支付宝包内，已 exported）
                override fun startActivity(
                    packageName: String?,
                    className: String?,
                    flag: Int,
                    data: Bundle?
                ) {
                    Log.d(TAG, "cb.startActivity pkg=$packageName cls=$className flag=$flag")
                    val ui = Intent(Intent.ACTION_MAIN).apply {
                        if (packageName != null && className != null) {
                            setClassName(packageName, className)
                        }
                        if (flag != 0) addFlags(flag)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val extras = data ?: Bundle()
                        extras.putInt("CallingPid", android.os.Process.myPid())
                        putExtras(extras)
                    }
                    runCatching { appContext.startActivity(ui) }
                        .onFailure { Log.e(TAG, "cb.startActivity failed", it) }
                }

                override fun payEnd(isOk: Boolean, result: String?) {
                    Log.d(TAG, "cb.payEnd isOk=$isOk result=$result")
                }

                override fun isHideLoadingScreen(): Boolean {
                    Log.d(TAG, "cb.isHideLoadingScreen")
                    return false
                }

                override fun getVersion(): Int {
                    Log.d(TAG, "cb.getVersion")
                    return 3
                }

                override fun r03(a: String?, b: String?, data: MutableMap<Any?, Any?>?) {
                    Log.d(TAG, "cb.r03 a=$a b=$b")
                }
            }

            val version = runCatching { alixPay.getVersion() }
                .onFailure { Log.e(TAG, "getVersion failed", it) }
                .getOrDefault(0)
            Log.d(TAG, "IAlixPay version=$version")

            try {
                if (version >= 3) {
                    alixPay.registerCallback03(callback, authInfo, null)
                    runCatching { alixPay.r03("alipaySdk", "bind_pay", null) }
                        .onFailure { Log.e(TAG, "r03 failed", it) }
                } else {
                    alixPay.registerCallback(callback)
                }

                Log.d(TAG, "invoking pay (version=$version)…")
                val raw = if (version >= 2) {
                    alixPay.pay02(authInfo, buildTraceMap(authInfo, bindStart, bindEnd))
                } else {
                    alixPay.Pay(authInfo)
                }
                Log.d(TAG, "pay returned: $raw")
                return parseAuthCode(raw)
            } catch (e: DoorApiException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "pay invoke failed", e)
                throw DoorApiException(rawText("支付宝授权调用失败：${e.message}"))
            } finally {
                runCatching { alixPay.unregisterCallback(callback) }
            }
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    /**
     * pay02 的第二个参数：SDK 追踪信息（按反编译的 key 还原）。
     * app_name / token 必须从 authInfo 串里取（token == target_id，msp 靠它关联会话），
     * 之前填包名/随机 UUID 会让 msp 对不上而挂死。
     */
    private fun buildTraceMap(authInfo: String, bindStart: Long, bindEnd: Long): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val appName = authInfoField(authInfo, "app_name") ?: "mc"
        val token = authInfoField(authInfo, "target_id").orEmpty()
        android.util.Log.d(TAG, "traceMap app_name=$appName token=$token")
        return hashMapOf(
            "sdk_ver" to SDK_VER,
            "app_name" to appName,
            "token" to token,
            "call_type" to "authV2",
            "ts_api_invoke" to now,
            "ts_bind" to bindStart,
            "ts_bend" to bindEnd,
            "ts_pay" to now
        )
    }

    /** 从 authInfo 串（key=value&key=value…）里取某个字段值。 */
    private fun authInfoField(authInfo: String, key: String): String? {
        return authInfo.split("&")
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("$key=")
            ?.takeIf { it.isNotBlank() }
    }

    /** 解析 "resultStatus={9000};memo={};result={auth_code=xxx&user_id=yyy}"。 */
    private fun parseAuthCode(raw: String?): String {
        if (raw.isNullOrBlank()) throw DoorApiException(rawText("支付宝未返回结果"))

        val status = extractBraceValue(raw, "resultStatus")
        if (status != "9000") {
            val memo = extractBraceValue(raw, "memo")?.takeIf { it.isNotBlank() }
            val detail = memo ?: when (status) {
                "6001" -> "已取消支付宝授权"
                else -> "支付宝授权未完成 ($status)"
            }
            throw DoorApiException(rawText(detail))
        }

        val result = extractBraceValue(raw, "result").orEmpty()
        return result.split("&")
            .firstOrNull { it.startsWith("auth_code=") }
            ?.substringAfter("auth_code=")
            ?.takeIf { it.isNotBlank() }
            ?: throw DoorApiException(rawText("支付宝结果缺少 auth_code"))
    }

    /** 取 `key={...}` 大括号内的值（注意 resultStatus 不能被 result 误匹配）。 */
    private fun extractBraceValue(raw: String, key: String): String? {
        val marker = "$key="
        val keyIdx = raw.indexOf(marker)
        if (keyIdx < 0) return null
        val open = raw.indexOf('{', keyIdx + marker.length)
        if (open < 0) return null
        val close = raw.indexOf('}', open)
        if (close < 0) return null
        return raw.substring(open + 1, close)
    }
}
