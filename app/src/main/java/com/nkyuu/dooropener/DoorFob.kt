package com.nkyuu.dooropener

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DoorFob {

    private const val SCAN_TIMEOUT_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10_000L
    private const val OPERATION_TIMEOUT_MS = 10_000L
    private const val CONNECT_RETRY_COUNT = 3
    private const val RETRY_DELAY_MS = 800L
    private const val MAIN_THREAD_POST_TIMEOUT_MS = 2_000L

    private val CONFIG_SERVICE_UUID: UUID =
        UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
    private val DEVICE_INFO_UUID: UUID =
        UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
    private val CREDENTIAL_WRITE_UUID: UUID =
        UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
    private val STATUS_UUID: UUID =
        UUID.fromString("0000ff13-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * 扫描并配置钥匙扣（ESP32-C3 fob），将凭证写入 fob 的 config service。
     *
     * 载荷拆成两次独立写入（每次都是完整 JSON，fob 按"出现即更新"处理，兼容旧固件）：
     * 写①凭证 + API 会话（fob 凭此可自行联网刷新凭证）；
     * 写②WiFi/portal 配置（可选，四个字段全空则跳过）。
     *
     * 流程：扫描 → 连接(MTU 协商) → 读取设备信息 → 写入两次 JSON → 各读一次状态结果。
     */
    @SuppressLint("MissingPermission")
    fun configureFob(
        context: Context,
        snapshot: DoorCredentialSnapshot,
        wifiSsid: String = "",
        wifiPass: String = "",
        portalUser: String = "",
        portalPass: String = "",
        progress: (String) -> Unit = {}
    ): FobConfigResult {
        progress("正在扫描钥匙扣")
        val target = scanForFobDevice(context, progress)

        progress("正在连接 ${target.displayName}")
        val session = FobGattSession(context, target.device)

        try {
            session.connect()
            progress("正在读取设备信息")
            val deviceInfo = session.readDeviceInfo()

            // 写①：凭证 + API 会话
            progress("正在写入凭证")
            session.writeCredentialPayload(buildCredentialPayload(snapshot))

            progress("正在等待状态确认")
            val statusA = session.readStatus()

            // 写②：WiFi/portal 配置（可选）
            val hasNet = listOf(wifiSsid, wifiPass, portalUser, portalPass).any { it.isNotBlank() }
            var statusB = 0
            if (hasNet) {
                progress("正在写入网络配置")
                session.writeCredentialPayload(
                    buildNetworkPayload(wifiSsid, wifiPass, portalUser, portalPass)
                )
                statusB = session.readStatus()
            }

            val statusCode = if (statusA != 0) statusA else statusB
            val message = describeStatusCode(statusA)
            return FobConfigResult(
                success = statusA == 0 && statusB == 0,
                statusCode = statusCode,
                resultMessage = message,
                networkStatusCode = if (hasNet) statusB else null,
                deviceInfo = deviceInfo,
                deviceName = target.displayName,
                deviceAddress = target.address
            )
        } finally {
            session.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanForFobDevice(
        context: Context,
        progress: (String) -> Unit
    ): DiscoveredFobDevice {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw DoorFobException("当前设备不支持蓝牙")
        val adapter = manager.adapter ?: throw DoorFobException("当前设备不支持蓝牙")
        if (!adapter.isEnabled) {
            throw DoorFobException("请先开启蓝牙")
        }
        val scanner = adapter.bluetoothLeScanner ?: throw DoorFobException("无法启动蓝牙扫描")

        val found = AtomicReference<DiscoveredFobDevice?>()
        val candidates = mutableListOf<DiscoveredFobDevice>()
        val latch = CountDownLatch(1)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = runCatching { device.name }.getOrNull()
                val address = device.address.orEmpty()

                val hasConfigService = result.scanRecord?.serviceUuids
                    ?.any { it.uuid == CONFIG_SERVICE_UUID } == true

                if (!hasConfigService) return

                val candidate = DiscoveredFobDevice(
                    device = device,
                    address = address,
                    displayName = name?.trim()?.ifBlank { address } ?: address
                )

                synchronized(candidates) {
                    if (candidates.none { it.address == address }) {
                        candidates.add(candidate)
                    }
                }

                // 找到第一个匹配的设备就停
                found.compareAndSet(null, candidate)
                latch.countDown()
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                latch.countDown()
            }
        }

        try {
            scanner.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback
            )
            latch.await(SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (exception: SecurityException) {
            throw DoorFobException("缺少蓝牙权限", exception)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        found.get()?.let { return it }

        val seenSummary = synchronized(candidates) {
            candidates.take(5).joinToString("\n") { "${it.displayName} / ${it.address}" }
        }
        throw DoorFobException(
            buildString {
                append("未找到钥匙扣设备")
                if (seenSummary.isNotBlank()) {
                    append("\n附近设备:\n")
                    append(seenSummary)
                } else {
                    append("\n未扫描到配置服务 (0xFF10)，请确认钥匙扣在附近")
                }
            }
        )
    }

    /**
     * 构建写入 0xFF12 的 JSON 凭证 + 会话载荷（写①）。
     * 会话字段非空才放，保持对旧固件的向后兼容。
     */
    private fun buildCredentialPayload(snapshot: DoorCredentialSnapshot): String {
        val json = JSONObject()
        json.put("device_id", snapshot.deviceId)
        json.put("credential", snapshot.credentialHex)
        json.put("project_id", snapshot.projectId)
        json.put("ble_mac", snapshot.bleMac)
        json.put("credential_id", snapshot.credentialId)
        if (snapshot.serverUrl.isNotBlank()) json.put("server_url", snapshot.serverUrl)
        if (snapshot.sessionSecret.isNotBlank()) json.put("session_secret", snapshot.sessionSecret)
        if (snapshot.userId.isNotBlank()) json.put("user_id", snapshot.userId)
        if (snapshot.identityCode.isNotBlank()) json.put("identity_code", snapshot.identityCode)
        return json.toString()
    }

    /**
     * 构建写入 0xFF12 的 JSON WiFi/portal 载荷（写②，独立生效）。
     */
    private fun buildNetworkPayload(
        ssid: String,
        pass: String,
        portalUser: String,
        portalPass: String
    ): String {
        val json = JSONObject()
        if (ssid.isNotBlank()) json.put("wifi_ssid", ssid)
        if (pass.isNotBlank()) json.put("wifi_pass", pass)
        if (portalUser.isNotBlank()) json.put("portal_user", portalUser)
        if (portalPass.isNotBlank()) json.put("portal_pass", portalPass)
        return json.toString()
    }

    /**
     * 将 0xFF13 返回的状态码转为中文提示。
     */
    fun describeStatusCode(code: Int): String {
        return when (code) {
            0 -> "写入成功"
            1 -> "JSON 解析失败"
            2 -> "凭证格式错误"
            3 -> "缺少 device_id"
            4 -> "Flash 写入失败"
            else -> "未知状态码: $code"
        }
    }

    // ── GATT 会话 ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private class FobGattSession(
        private val context: Context,
        private val device: BluetoothDevice
    ) {
        private val connectionEvents = ArrayBlockingQueue<FobConnectionEvent>(4)
        private val serviceEvents = ArrayBlockingQueue<Int>(2)
        private val mtuEvents = ArrayBlockingQueue<Int>(4)
        private val descriptorEvents = ArrayBlockingQueue<FobDescriptorEvent>(4)
        private val writeEvents = ArrayBlockingQueue<FobWriteEvent>(4)
        private val notificationEvents = ArrayBlockingQueue<FobNotificationEvent>(16)

        private var gatt: BluetoothGatt? = null
        private var deviceInfoCharacteristic: BluetoothGattCharacteristic? = null
        private var credentialWriteCharacteristic: BluetoothGattCharacteristic? = null
        private var statusCharacteristic: BluetoothGattCharacteristic? = null

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                connectionEvents.offer(FobConnectionEvent(status, newState))
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                serviceEvents.offer(status)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                mtuEvents.offer(mtu)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                descriptorEvents.offer(FobDescriptorEvent(descriptor.uuid, status))
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeEvents.offer(FobWriteEvent(characteristic.uuid, status))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                notificationEvents.offer(
                    FobNotificationEvent(characteristic.uuid, value.clone())
                )
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                notificationEvents.offer(
                    FobNotificationEvent(
                        characteristic.uuid,
                        characteristic.value?.clone() ?: ByteArray(0)
                    )
                )
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    notificationEvents.offer(
                        FobNotificationEvent(
                            characteristic.uuid,
                            characteristic.value?.clone() ?: ByteArray(0)
                        )
                    )
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    notificationEvents.offer(
                        FobNotificationEvent(characteristic.uuid, value.clone())
                    )
                }
            }
        }

        fun connect() {
            var lastError: DoorFobException? = null
            repeat(CONNECT_RETRY_COUNT) { attempt ->
                resetQueues()
                closeCurrentGatt()
                try {
                    connectOnce()
                    return
                } catch (exception: DoorFobException) {
                    lastError = exception
                    if (attempt < CONNECT_RETRY_COUNT - 1) {
                        SystemClock.sleep(RETRY_DELAY_MS)
                    }
                }
            }
            throw lastError ?: DoorFobException("连接钥匙扣失败")
        }

        private fun connectOnce() {
            val connectedGatt = connectGattOnMainThread()
            gatt = connectedGatt

            val connection = connectionEvents.poll(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorFobException("连接钥匙扣超时")
            if (connection.status != BluetoothGatt.GATT_SUCCESS ||
                connection.newState != BluetoothProfile.STATE_CONNECTED
            ) {
                throw DoorFobException("连接钥匙扣失败: status=${connection.status}, state=${connection.newState}")
            }

            runCatching {
                connectedGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            }

            // 写①载荷约 400 字节，超过默认 MTU 23 的单次写入上限，必须先协商
            requestMtu(517)

            if (connectedGatt.discoverServices() != true) {
                throw DoorFobException("发现蓝牙服务失败")
            }
            val serviceStatus = serviceEvents.poll(SERVICE_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorFobException("发现蓝牙服务超时")
            if (serviceStatus != BluetoothGatt.GATT_SUCCESS) {
                throw DoorFobException("发现蓝牙服务失败: $serviceStatus")
            }

            val service = connectedGatt.getService(CONFIG_SERVICE_UUID)
                ?: throw DoorFobException("未找到配置服务 (0xFF10)")

            deviceInfoCharacteristic = service.getCharacteristic(DEVICE_INFO_UUID)
            credentialWriteCharacteristic = service.getCharacteristic(CREDENTIAL_WRITE_UUID)
            statusCharacteristic = service.getCharacteristic(STATUS_UUID)

            if (credentialWriteCharacteristic == null) {
                throw DoorFobException("未找到凭证写入特征 (0xFF12)")
            }
            if (statusCharacteristic == null) {
                throw DoorFobException("未找到状态特征 (0xFF13)")
            }

            // 启用状态特征的通知
            enableNotifications(connectedGatt, statusCharacteristic!!)
        }

        private fun connectGattOnMainThread(): BluetoothGatt {
            val latch = CountDownLatch(1)
            val createdGatt = AtomicReference<BluetoothGatt?>()
            val failure = AtomicReference<Throwable?>()

            Handler(Looper.getMainLooper()).post {
                try {
                    val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(context, false, callback)
                    }
                    createdGatt.set(gatt)
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                } finally {
                    latch.countDown()
                }
            }

            if (!latch.await(MAIN_THREAD_POST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw DoorFobException("主线程创建蓝牙连接超时")
            }
            failure.get()?.let { throw DoorFobException("创建蓝牙连接失败", it) }
            return createdGatt.get() ?: throw DoorFobException("蓝牙连接创建失败")
        }

        /**
         * 请求协商 MTU（建连成功后调用，写大载荷前必须完成）。
         */
        fun requestMtu(mtu: Int) {
            if (gatt?.requestMtu(mtu) != true) throw DoorFobException("请求 MTU 失败")
            val negotiated = mtuEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorFobException("MTU 协商超时")
            if (negotiated < mtu) throw DoorFobException("MTU 协商结果不足: $negotiated")
        }

        /**
         * 读取设备信息特征 (0xFF11)。
         */
        fun readDeviceInfo(): String {
            val gatt = this.gatt ?: throw DoorFobException("蓝牙未连接")
            val characteristic = deviceInfoCharacteristic
                ?: throw DoorFobException("未找到设备信息特征 (0xFF11)")

            val started = gatt.readCharacteristic(characteristic)
            if (!started) {
                throw DoorFobException("读取设备信息失败")
            }

            val event = notificationEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorFobException("等待设备信息响应超时")
            if (event.uuid != DEVICE_INFO_UUID) {
                throw DoorFobException("收到未知响应")
            }
            return String(event.value, Charsets.UTF_8)
        }

        /**
         * 写入凭证 JSON 到特征 (0xFF12)。
         */
        fun writeCredentialPayload(jsonPayload: String) {
            val gatt = this.gatt ?: throw DoorFobException("蓝牙未连接")
            val characteristic = credentialWriteCharacteristic
                ?: throw DoorFobException("凭证写入特征未就绪")

            val payload = jsonPayload.toByteArray(Charsets.UTF_8)

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    characteristic.value = payload
                    gatt.writeCharacteristic(characteristic)
                }
            }
            if (!started) {
                throw DoorFobException("发送凭证失败")
            }

            val writeEvent = writeEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorFobException("等待写入确认超时")
            if (writeEvent.uuid != CREDENTIAL_WRITE_UUID ||
                writeEvent.status != BluetoothGatt.GATT_SUCCESS
            ) {
                throw DoorFobException("凭证写入失败: status=${writeEvent.status}")
            }
        }

        /**
         * 读取状态特征 (0xFF13) 的最新值。
         * 优先从通知事件中获取（写入凭证后设备会推送状态通知），
         * 若通知超时则直接读取。
         */
        fun readStatus(): Int {
            // 先等通知（写入 0xFF12 后设备通常会推送状态到 0xFF13）
            val notification = notificationEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (notification != null && notification.uuid == STATUS_UUID) {
                return notification.value.firstOrNull()?.toInt() ?: -1
            }

            // 通知未到达，直接读取
            val gatt = this.gatt ?: throw DoorFobException("蓝牙未连接")
            val characteristic = statusCharacteristic
                ?: throw DoorFobException("状态特征未就绪")

            val started = gatt.readCharacteristic(characteristic)
            if (!started) {
                throw DoorFobException("读取状态失败")
            }

            val readEvent = notificationEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorFobException("等待状态响应超时")
            if (readEvent.uuid != STATUS_UUID) {
                throw DoorFobException("收到未知状态响应")
            }
            return readEvent.value.firstOrNull()?.toInt() ?: -1
        }

        fun close() {
            closeCurrentGatt()
            resetQueues()
        }

        private fun enableNotifications(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                throw DoorFobException("启用蓝牙通知失败")
            }
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
                ?: throw DoorFobException("不支持通知描述符")
            val cccValue = when {
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> throw DoorFobException("状态特征不支持通知或指示")
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, cccValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = cccValue
                    gatt.writeDescriptor(descriptor)
                }
            }
            if (!started) {
                throw DoorFobException("写入通知描述符失败")
            }
            val event = descriptorEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorFobException("等待通知描述符确认超时")
            if (event.uuid != descriptor.uuid || event.status != BluetoothGatt.GATT_SUCCESS) {
                throw DoorFobException("启用通知失败: ${event.status}")
            }
        }

        private fun closeCurrentGatt() {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }

        private fun resetQueues() {
            connectionEvents.clear()
            serviceEvents.clear()
            mtuEvents.clear()
            descriptorEvents.clear()
            writeEvents.clear()
            notificationEvents.clear()
        }
    }

    // ── 内部数据类 ─────────────────────────────────────────────────────────

    private data class FobConnectionEvent(val status: Int, val newState: Int)
    private data class FobDescriptorEvent(val uuid: UUID, val status: Int)
    private data class FobWriteEvent(val uuid: UUID, val status: Int)
    private data class FobNotificationEvent(val uuid: UUID, val value: ByteArray)

    private data class DiscoveredFobDevice(
        val device: BluetoothDevice,
        val address: String,
        val displayName: String
    )
}

// ── 公开结果类 ────────────────────────────────────────────────────────────

data class FobConfigResult(
    val success: Boolean,
    val statusCode: Int,
    val resultMessage: String,
    /** 写②（WiFi/portal）状态码；未下发网络配置时为 null */
    val networkStatusCode: Int? = null,
    val deviceInfo: String,
    val deviceName: String,
    val deviceAddress: String
)

class DoorFobException(message: String, cause: Throwable? = null) : IOException(message, cause)
