package com.nkyuu.dooropener

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DoorBle {

    private const val COMMAND_HEADER = 0x74
    private const val COMMAND_PACKET = 0x75
    private const val COMMAND_REFETCH = 0x76
    private const val COMMAND_PACKET_READ = 0x77
    private const val COMMAND_OPEN = 0x78

    private const val SCAN_TIMEOUT_MS = 3_000L
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val SERVICE_DISCOVERY_TIMEOUT_MS = 10_000L
    private const val OPERATION_TIMEOUT_MS = 10_000L
    private const val CONNECT_RETRY_COUNT = 3
    private const val RETRY_DELAY_MS = 800L
    private const val INTER_PACKET_DELAY_MS = 10L
    private const val POST_PACKET_SETTLE_MS = 50L
    private const val MAIN_THREAD_POST_TIMEOUT_MS = 2_000L

    private val SERVICE_UUID: UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
    private val WRITE_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val READ_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    fun openDoor(
        context: Context,
        snapshot: DoorCredentialSnapshot,
        progress: (String) -> Unit = {}
    ): BleDoorOpenResult {
        val target = findDoorDevice(context, snapshot, progress)
        val deviceId = target.deviceId ?: snapshot.deviceId.takeIf { it > 0 }
            ?: throw DoorBleException("无法确定蓝牙门锁 device_id")
        val session = GattSession(context, target.device, deviceId)

        try {
            progress("正在连接 ${target.displayName}")
            session.connect()

            var openAttempt = performOpenSequence(
                context = context,
                session = session,
                deviceId = deviceId,
                credentialHex = snapshot.credentialHex,
                progress = progress
            )

            var updatedCredentialHex: String? = null
            var refreshedCredential = false
            if (openAttempt.resultCode == 27) {
                progress("门锁要求刷新凭证")
                val refresh = refreshCredential(context, session, deviceId, snapshot, progress)
                updatedCredentialHex = refresh.updatedCredentialHex
                refreshedCredential = refresh.refreshed
                progress("正在使用新凭证重试开门")
                openAttempt = performOpenSequence(
                    context = context,
                    session = session,
                    deviceId = deviceId,
                    credentialHex = refresh.updatedCredentialHex,
                    progress = progress
                )
            }

            val success = openAttempt.resultCode == 0 || openAttempt.resultCode == 23
            val note = buildString {
                if (target.deviceId != null && snapshot.deviceId > 0 && snapshot.deviceId != target.deviceId) {
                    append("本地 device_id=${snapshot.deviceId}，蓝牙广播 device_id=${target.deviceId}，已按广播值处理")
                }
                if (target.matchedByFallback) {
                    if (isNotEmpty()) {
                        append('\n')
                    }
                    append("本次按门锁服务 UUID 回退匹配设备")
                }
                if (refreshedCredential && updatedCredentialHex != null) {
                    if (isNotEmpty()) {
                        append('\n')
                    }
                    append("已刷新门锁凭证")
                }
            }.ifBlank { null }

            return BleDoorOpenResult(
                success = success,
                resultCode = openAttempt.resultCode,
                resultMessage = DoorCrypto.describeResultCode(context, openAttempt.resultCode),
                deviceName = target.displayName,
                deviceAddress = target.address,
                deviceId = deviceId,
                credentialHex = if (snapshot.credentialHex.length == 64) snapshot.credentialHex else null,
                updatedCredentialHex = updatedCredentialHex,
                refreshedCredential = refreshedCredential,
                note = note,
                headerPayload = openAttempt.headerPayload,
                headerRequest = openAttempt.headerRequest,
                headerResponse = openAttempt.headerResponse,
                packetPayloads = openAttempt.packetPayloads,
                packetRequests = openAttempt.packetRequests,
                packetResponses = openAttempt.packetResponses,
                openPayload = openAttempt.openPayload,
                openRequest = openAttempt.openRequest,
                openResponse = openAttempt.openResponse
            )
        } finally {
            session.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun findDoorDevice(
        context: Context,
        snapshot: DoorCredentialSnapshot,
        progress: (String) -> Unit
    ): DiscoveredDoorDevice {
        val directTarget = connectByCachedAddress(context, snapshot)
        if (directTarget != null) {
            progress("正在直连门锁 ${directTarget.address}")
            return directTarget
        }

        progress("正在扫描蓝牙门锁")
        return scanForDoorDevice(context, snapshot)
    }

    @SuppressLint("MissingPermission")
    private fun connectByCachedAddress(
        context: Context,
        snapshot: DoorCredentialSnapshot
    ): DiscoveredDoorDevice? {
        val targetAddress = snapshot.bleMac.trim()
        if (targetAddress.isBlank()) {
            return null
        }

        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        if (!adapter.isEnabled) {
            throw DoorBleException("请先开启蓝牙")
        }

        val remoteDevice = runCatching { adapter.getRemoteDevice(targetAddress) }.getOrNull() ?: return null
        return DiscoveredDoorDevice(
            device = remoteDevice,
            address = remoteDevice.address.orEmpty(),
            displayName = snapshot.deviceId.takeIf { it > 0 }?.let { "XN-$it" }
                ?: remoteDevice.address.orEmpty(),
            deviceId = snapshot.deviceId.takeIf { it > 0 },
            rssi = Int.MIN_VALUE,
            matchedByFallback = false
        )
    }

    @SuppressLint("MissingPermission")
    private fun scanForDoorDevice(context: Context, snapshot: DoorCredentialSnapshot): DiscoveredDoorDevice {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw DoorBleException("当前设备不支持蓝牙")
        val adapter = manager.adapter ?: throw DoorBleException("当前设备不支持蓝牙")
        if (!adapter.isEnabled) {
            throw DoorBleException("请先开启蓝牙")
        }
        val scanner = adapter.bluetoothLeScanner ?: throw DoorBleException("无法启动蓝牙扫描")
        val targetAddress = snapshot.bleMac.trim().normalizeBleAddress()
        val targetName = snapshot.deviceId.takeIf { it > 0 }?.let { "XN-$it" }
        if (targetAddress.isBlank() && targetName == null) {
            throw DoorBleException("缺少 BLE MAC 和 device_id，无法扫描门锁")
        }

        val found = AtomicReference<DiscoveredDoorDevice?>()
        val fallback = AtomicReference<DiscoveredDoorDevice?>()
        val candidates = mutableListOf<DiscoveredDoorDevice>()
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val parsedRecord = parseAdvertisement(result.scanRecord?.bytes)
                val name = parsedRecord.localName
                    ?: result.scanRecord?.deviceName
                    ?: runCatching { device.name }.getOrNull()
                val normalizedName = name?.trim().orEmpty()
                val advertisedDeviceId = normalizedName.parseDeviceIdFromBleName()
                val normalizedAddress = device.address.orEmpty().normalizeBleAddress()
                val hasTargetService = parsedRecord.hasService(SERVICE_UUID) ||
                    result.scanRecord?.serviceUuids?.any { parcelUuid -> parcelUuid.uuid == SERVICE_UUID } == true
                val addressMatches = targetAddress.isNotBlank() && normalizedAddress == targetAddress
                val nameMatches = targetName != null &&
                    (
                        normalizedName == targetName ||
                            normalizedName.startsWith("$targetName-") ||
                            normalizedName.contains(targetName)
                        )
                val deviceIdMatches = snapshot.deviceId > 0 && advertisedDeviceId == snapshot.deviceId
                val candidate = DiscoveredDoorDevice(
                    device = device,
                    address = device.address.orEmpty(),
                    displayName = normalizedName.ifBlank { device.address.orEmpty() },
                    deviceId = advertisedDeviceId,
                    rssi = result.rssi,
                    matchedByFallback = false
                )
                synchronized(candidates) {
                    if (candidates.none { it.address.normalizeBleAddress() == normalizedAddress }) {
                        candidates.add(candidate)
                    }
                }
                if (addressMatches || nameMatches || deviceIdMatches) {
                    found.compareAndSet(
                        null,
                        candidate.copy(displayName = normalizedName.ifBlank { targetName ?: device.address.orEmpty() })
                    )
                    latch.countDown()
                    return
                }
                if (hasTargetService) {
                    fallback.updateAndGet { current ->
                        when {
                            current == null -> candidate.copy(matchedByFallback = true)
                            candidate.rssi > current.rssi -> candidate.copy(matchedByFallback = true)
                            else -> current
                        }
                    }
                }
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
            throw DoorBleException("缺少蓝牙权限", exception)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }

        found.get()?.let { return it }
        fallback.get()?.let { return it }

        val seenSummary = synchronized(candidates) {
            candidates.take(5).joinToString("\n") { candidate ->
                buildString {
                    append(candidate.displayName.ifBlank { "-" })
                    append(" / ")
                    append(candidate.address.ifBlank { "-" })
                    append(" / RSSI ")
                    append(candidate.rssi)
                    candidate.deviceId?.let {
                        append(" / d=")
                        append(it)
                    }
                }
            }
        }
        throw DoorBleException(
            buildString {
                append("未找到门锁蓝牙设备: ")
                append(listOfNotNull(targetName, targetAddress.takeIf { it.isNotBlank() }).joinToString(" / "))
                if (seenSummary.isNotBlank()) {
                    append("\n附近设备:\n")
                    append(seenSummary)
                } else {
                    append("\n未扫描到可识别的 BLE 广播，请确认门锁在附近；部分华为设备还需要开启系统定位")
                }
            }
        )
    }

    private fun performOpenSequence(
        context: Context,
        session: GattSession,
        deviceId: Int,
        credentialHex: String,
        progress: (String) -> Unit
    ): BleOpenAttempt {
        progress("正在发送凭证头")
        val headerPayload = DoorCrypto.buildBleHeaderPayload(DoorApi.PROJECT_ID, credentialHex)
        val headerRequest = DoorCrypto.buildBleCommand(
            deviceId = deviceId,
            commandType = COMMAND_HEADER,
            data = headerPayload
        )
        val headerResponse = session.writeAndAwait(headerRequest)
        ensureBleResponse(headerResponse, COMMAND_HEADER, "凭证头")
        val headerCode = headerResponse.resultCodeAt(0)
        if (headerCode != 0) {
            throw DoorBleException("凭证头校验失败: ${DoorCrypto.describeResultCode(context, headerCode)} ($headerCode)")
        }
        val ran = headerResponse.littleEndianIntAt(4)

        val packets = DoorCrypto.buildBleCredentialPackets(
            projectId = DoorApi.PROJECT_ID,
            credentialHex = credentialHex,
            ran = ran
        )
        val packetRequests = mutableListOf<ByteArray>()
        val packetResponses = mutableListOf<BleResponse>()
        val packetPayloads = mutableListOf<ByteArray>()
        packets.forEachIndexed { index, packet ->
            progress("正在发送凭证包 ${index + 1}/${packets.size}")
            val packetRequest = DoorCrypto.buildBleCommand(
                deviceId = deviceId,
                commandType = COMMAND_PACKET,
                data = packet
            )
            val packetResponse = session.writeAndAwait(packetRequest)
            packetPayloads += packet
            packetRequests += packetRequest
            packetResponses += packetResponse
            ensureBleResponse(packetResponse, COMMAND_PACKET, "凭证包 ${index + 1}")
            if (index < packets.lastIndex) {
                SystemClock.sleep(INTER_PACKET_DELAY_MS)
            }
        }

        SystemClock.sleep(POST_PACKET_SETTLE_MS)
        progress("正在发送开门命令")
        val openPayload = ByteArray(DoorCrypto.BLE_DATA_SIZE)
        val openRequest = DoorCrypto.buildBleCommand(
            deviceId = deviceId,
            commandType = COMMAND_OPEN,
            data = openPayload
        )
        val openResponse = session.writeAndAwait(openRequest)
        ensureBleResponse(openResponse, COMMAND_OPEN, "开门")
        return BleOpenAttempt(
            resultCode = resolveBleOpenResultCode(openResponse),
            headerPayload = headerPayload,
            headerRequest = headerRequest,
            headerResponse = headerResponse,
            packetPayloads = packetPayloads,
            packetRequests = packetRequests,
            packetResponses = packetResponses,
            openPayload = openPayload,
            openRequest = openRequest,
            openResponse = openResponse
        )
    }

    private fun ensureBleResponse(response: BleResponse, expectedCommand: Int, stage: String) {
        if (response.commandType != expectedCommand) {
            throw DoorBleException(
                "$stage 响应命令不匹配: len=${response.lengthMarker}, cmd=0x${response.commandType.toString(16)}"
            )
        }
        if (!response.crcValid) {
            throw DoorBleException(
                "$stage 响应CRC校验失败: len=${response.lengthMarker}, cmd=0x${response.commandType.toString(16)}"
            )
        }
    }

    private fun refreshCredential(
        context: Context,
        session: GattSession,
        deviceId: Int,
        snapshot: DoorCredentialSnapshot,
        progress: (String) -> Unit
    ): CredentialRefreshResult {
        if (snapshot.credentialId.isBlank()) {
            throw DoorBleException("缺少 credential_id，无法刷新凭证")
        }

        val refetchResponse = session.writeAndAwait(
            DoorCrypto.buildBleCommand(
                deviceId = deviceId,
                commandType = COMMAND_REFETCH,
                data = DoorCrypto.buildBleRefetchPayload(snapshot.credentialId)
            )
        )
        ensureBleResponse(refetchResponse, COMMAND_REFETCH, "凭证刷新")

        val refetchCode = refetchResponse.resultCodeAt(0)
        if (refetchCode != 0 && refetchCode != 23) {
            throw DoorBleException("门锁拒绝刷新凭证: ${DoorCrypto.describeResultCode(context, refetchCode)} ($refetchCode)")
        }

        val packetCount = refetchResponse.plainData[1].toInt() and 0xFF
        val credentialLength = refetchResponse.littleEndianUShortAt(2)
        val expectedCrc = refetchResponse.plainData[4]
        if (packetCount <= 0 || credentialLength <= 0) {
            throw DoorBleException("门锁返回的刷新参数无效")
        }

        val packetMap = linkedMapOf<Int, ByteArray>()
        repeat(packetCount) { requestedIndex ->
            progress("正在读取新凭证 ${requestedIndex + 1}/$packetCount")
            val packetResponse = session.writeAndAwait(
                DoorCrypto.buildBleCommand(
                    deviceId = deviceId,
                    commandType = COMMAND_PACKET_READ,
                    data = DoorCrypto.buildBlePacketReadPayload(requestedIndex)
                )
            )
            ensureBleResponse(packetResponse, COMMAND_PACKET_READ, "凭证分包")
            val packetIndex = packetResponse.plainData[0].toInt() and 0xFF
            packetMap[packetIndex] = packetResponse.plainData.copyOfRange(1, 16)
        }

        val merged = packetMap.toSortedMap()
            .values
            .fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        if (merged.size < credentialLength) {
            throw DoorBleException("门锁返回的新凭证长度不足")
        }
        val credentialBytes = merged.copyOf(credentialLength)
        val actualCrc = DoorCrypto.crc8(credentialBytes)
        if (actualCrc != expectedCrc) {
            throw DoorBleException("新凭证CRC校验失败")
        }
        val updatedHex = credentialBytes.toHexCompact()
        if (!updatedHex.matches(Regex("^[0-9A-F]{64}$"))) {
            throw DoorBleException("门锁返回的新凭证格式无效")
        }

        return CredentialRefreshResult(
            updatedCredentialHex = updatedHex,
            refreshed = true
        )
    }

    private fun resolveBleOpenResultCode(response: BleResponse): Int {
        return response.resultCodeAt(0)
    }

    private fun String.parseDeviceIdFromBleName(): Int? {
        return removePrefix("XN-")
            .takeWhile { it.isDigit() }
            .toIntOrNull()
    }

    private fun parseAdvertisement(bytes: ByteArray?): ParsedAdvertisement {
        if (bytes == null || bytes.isEmpty()) {
            return ParsedAdvertisement(localName = null, serviceUuids = emptySet())
        }

        var index = 0
        var localName: String? = null
        val serviceUuids = linkedSetOf<UUID>()
        while (index < bytes.size) {
            val length = bytes[index].toInt() and 0xFF
            if (length == 0) {
                break
            }
            val fieldStart = index + 1
            val fieldEndExclusive = fieldStart + length
            if (fieldEndExclusive > bytes.size) {
                break
            }
            val type = bytes[fieldStart].toInt() and 0xFF
            val dataStart = fieldStart + 1
            when (type) {
                0x08, 0x09 -> {
                    if (dataStart < fieldEndExclusive) {
                        localName = String(bytes, dataStart, fieldEndExclusive - dataStart, Charsets.UTF_8)
                    }
                }

                0x02, 0x03 -> {
                    var cursor = dataStart
                    while (cursor + 1 < fieldEndExclusive) {
                        val uuid16 = (bytes[cursor].toInt() and 0xFF) or
                            ((bytes[cursor + 1].toInt() and 0xFF) shl 8)
                        serviceUuids.add(expandBluetoothUuid(uuid16))
                        cursor += 2
                    }
                }

                0x06, 0x07 -> {
                    var cursor = dataStart
                    while (cursor + 15 < fieldEndExclusive) {
                        val chunk = bytes.copyOfRange(cursor, cursor + 16)
                        serviceUuids.add(parseUuid128(chunk))
                        cursor += 16
                    }
                }
            }
            index = fieldEndExclusive
        }

        return ParsedAdvertisement(localName = localName, serviceUuids = serviceUuids)
    }

    private fun expandBluetoothUuid(uuid16: Int): UUID {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", uuid16))
    }

    private fun parseUuid128(bytes: ByteArray): UUID {
        require(bytes.size == 16) { "UUID128 长度错误" }
        var msb = 0L
        var lsb = 0L
        for (index in 7 downTo 0) {
            msb = (msb shl 8) or (bytes[index].toLong() and 0xFF)
        }
        for (index in 15 downTo 8) {
            lsb = (lsb shl 8) or (bytes[index].toLong() and 0xFF)
        }
        return UUID(msb, lsb)
    }

    private fun String.normalizeBleAddress(): String {
        return uppercase().replace(":", "").replace("-", "")
    }

    private fun ByteArray.toHexCompact(): String {
        return joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    private data class CredentialRefreshResult(
        val updatedCredentialHex: String,
        val refreshed: Boolean
    )

    private data class BleOpenAttempt(
        val resultCode: Int,
        val headerPayload: ByteArray,
        val headerRequest: ByteArray,
        val headerResponse: BleResponse,
        val packetPayloads: List<ByteArray>,
        val packetRequests: List<ByteArray>,
        val packetResponses: List<BleResponse>,
        val openPayload: ByteArray,
        val openRequest: ByteArray,
        val openResponse: BleResponse
    )

    @SuppressLint("MissingPermission")
    private class GattSession(
        private val context: Context,
        private val device: BluetoothDevice,
        private val deviceId: Int
    ) {
        private val connectionEvents = ArrayBlockingQueue<ConnectionEvent>(4)
        private val serviceEvents = ArrayBlockingQueue<Int>(2)
        private val descriptorEvents = ArrayBlockingQueue<DescriptorEvent>(4)
        private val writeEvents = ArrayBlockingQueue<WriteEvent>(16)
        private val notificationEvents = ArrayBlockingQueue<NotificationEvent>(16)

        private var gatt: BluetoothGatt? = null
        private var writeCharacteristic: BluetoothGattCharacteristic? = null
        private var readCharacteristic: BluetoothGattCharacteristic? = null

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                connectionEvents.offer(ConnectionEvent(status, newState))
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                serviceEvents.offer(status)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                descriptorEvents.offer(DescriptorEvent(descriptor.uuid, status))
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeEvents.offer(WriteEvent(characteristic.uuid, status))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                notificationEvents.offer(
                    NotificationEvent(
                        characteristic.uuid,
                        characteristic.value?.clone() ?: ByteArray(0)
                    )
                )
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                notificationEvents.offer(NotificationEvent(characteristic.uuid, value.clone()))
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    notificationEvents.offer(
                        NotificationEvent(
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
                    notificationEvents.offer(NotificationEvent(characteristic.uuid, value.clone()))
                }
            }
        }

        fun connect() {
            var lastError: DoorBleException? = null
            repeat(CONNECT_RETRY_COUNT) { attempt ->
                resetQueues()
                closeCurrentGatt()
                try {
                    connectOnce()
                    return
                } catch (exception: DoorBleException) {
                    lastError = exception
                    if (attempt < CONNECT_RETRY_COUNT - 1) {
                        SystemClock.sleep(RETRY_DELAY_MS)
                    }
                }
            }

            throw lastError ?: DoorBleException("连接门锁失败")
        }

        private fun connectOnce() {
            val connectedGatt = connectGattOnMainThread()
            gatt = connectedGatt

            val connection = connectionEvents.poll(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorBleException("连接门锁超时")
            if (connection.status != BluetoothGatt.GATT_SUCCESS ||
                connection.newState != BluetoothProfile.STATE_CONNECTED
            ) {
                throw DoorBleException("连接门锁失败: status=${connection.status}, state=${connection.newState}")
            }

            runCatching {
                connectedGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            }
            if (connectedGatt.discoverServices() != true) {
                throw DoorBleException("发现蓝牙服务失败")
            }
            val serviceStatus = serviceEvents.poll(SERVICE_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorBleException("发现蓝牙服务超时")
            if (serviceStatus != BluetoothGatt.GATT_SUCCESS) {
                throw DoorBleException("发现蓝牙服务失败: $serviceStatus")
            }

            val service = connectedGatt.getService(SERVICE_UUID)
                ?: throw DoorBleException("未找到门锁服务")
            writeCharacteristic = service.getCharacteristic(WRITE_UUID)
                ?: throw DoorBleException("未找到写入特征值")
            readCharacteristic = service.getCharacteristic(READ_UUID)
                ?: throw DoorBleException("未找到响应特征值")

            enableNotifications(connectedGatt, readCharacteristic!!)
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
                throw DoorBleException("主线程创建蓝牙连接超时")
            }
            failure.get()?.let { throw DoorBleException("创建蓝牙连接失败", it) }
            return createdGatt.get() ?: throw DoorBleException("蓝牙连接创建失败")
        }

        fun writeAndAwait(frame: ByteArray): BleResponse {
            val connectedGatt = gatt ?: throw DoorBleException("蓝牙未连接")
            val characteristic = writeCharacteristic ?: throw DoorBleException("写入通道未就绪")
            notificationEvents.clear()

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                connectedGatt.writeCharacteristic(
                    characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    characteristic.value = frame
                    connectedGatt.writeCharacteristic(characteristic)
                }
            }
            if (!started) {
                throw DoorBleException("发送蓝牙命令失败")
            }

            val writeEvent = writeEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorBleException("等待蓝牙写入确认超时")
            if (writeEvent.uuid != characteristic.uuid || writeEvent.status != BluetoothGatt.GATT_SUCCESS) {
                throw DoorBleException("蓝牙写入失败: ${writeEvent.status}")
            }

            val notification = notificationEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorBleException("等待门锁响应超时")
            if (notification.uuid != readCharacteristic?.uuid) {
                throw DoorBleException("收到未知蓝牙响应")
            }

            return DoorCrypto.parseBleResponse(
                deviceId = deviceId,
                frame = notification.value
            )
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
                throw DoorBleException("启用蓝牙通知失败")
            }
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
                ?: throw DoorBleException("门锁不支持通知描述符")
            val cccValue = when {
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> throw DoorBleException("响应特征值不支持通知或指示")
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, cccValue) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = cccValue
                    gatt.writeDescriptor(descriptor)
                }
            }
            if (!started) {
                throw DoorBleException("写入通知描述符失败")
            }
            val event = descriptorEvents.poll(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: throw DoorBleException("等待通知描述符确认超时")
            if (event.uuid != descriptor.uuid || event.status != BluetoothGatt.GATT_SUCCESS) {
                throw DoorBleException("启用通知失败: ${event.status}")
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
            descriptorEvents.clear()
            writeEvents.clear()
            notificationEvents.clear()
        }

    }

    private data class ConnectionEvent(val status: Int, val newState: Int)
    private data class DescriptorEvent(val uuid: UUID, val status: Int)
    private data class WriteEvent(val uuid: UUID, val status: Int)
    private data class NotificationEvent(val uuid: UUID, val value: ByteArray)
    private data class DiscoveredDoorDevice(
        val device: BluetoothDevice,
        val address: String,
        val displayName: String,
        val deviceId: Int?,
        val rssi: Int,
        val matchedByFallback: Boolean
    )

    private data class ParsedAdvertisement(
        val localName: String?,
        val serviceUuids: Set<UUID>
    ) {
        fun hasService(uuid: UUID): Boolean {
            return serviceUuids.contains(uuid)
        }
    }
}

data class BleDoorOpenResult(
    val success: Boolean,
    val resultCode: Int,
    val resultMessage: String,
    val deviceName: String,
    val deviceAddress: String,
    val deviceId: Int,
    val credentialHex: String?,
    val updatedCredentialHex: String?,
    val refreshedCredential: Boolean,
    val note: String?,
    val headerPayload: ByteArray,
    val headerRequest: ByteArray,
    val headerResponse: BleResponse,
    val packetPayloads: List<ByteArray>,
    val packetRequests: List<ByteArray>,
    val packetResponses: List<BleResponse>,
    val openPayload: ByteArray,
    val openRequest: ByteArray,
    val openResponse: BleResponse
)

class DoorBleException(message: String, cause: Throwable? = null) : IOException(message, cause)
