package com.nkyuu.dooropener

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.nio.charset.StandardCharsets

/**
 * NFC helper functions extracted from the old MainActivity.
 * Contains tag reading, NDEF parsing, and response polling logic.
 * Does NOT modify any of the protected business logic classes.
 */

/** 流水线被更新的标签事件抢占时抛出，用于放弃进行中的会话（结果作废，不作为错误展示）。 */
class NfcAbortedException : RuntimeException("superseded by a newer tag event")

object DoorNfcHelper {

    private val URI_PREFIX_MAP = mapOf(
        0x00 to "",
        0x01 to "http://www.",
        0x02 to "https://www.",
        0x03 to "http://",
        0x04 to "https://"
    )

    /** 门锁返回这些码说明本地离线凭证已过期/次数用尽/密钥落后，可以在会话内更新凭证后重发。 */
    val STALE_CREDENTIAL_CODES = setOf(24, 25, 27)

    fun readNdefMessage(tag: Tag): NdefMessage {
        val ndef = Ndef.get(tag) ?: throw LocalizedIOException(rawText("标签不支持 NDEF"))
        try {
            ndef.connect()
            return ndef.cachedNdefMessage ?: ndef.ndefMessage
                ?: throw LocalizedIOException(rawText("标签里没有 NDEF 数据"))
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
    }

    fun findDoorUrl(message: NdefMessage): String? {
        return message.records
            .mapNotNull { record ->
                decodeRecordPayload(record)
                    .trim()
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
            .firstOrNull { payload ->
                val uri = Uri.parse(payload)
                uri.getQueryParameter("d") != null || uri.getQueryParameter("device_id") != null
            }
    }

    fun extractDeviceId(url: String): Int? {
        val uri = Uri.parse(url)
        val value = uri.getQueryParameter("d")
            ?: uri.getQueryParameter("device_id")
            ?: return null
        return value.toLongOrNull()
            ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
            ?.toInt()
    }

    /**
     * NDEF 派发的 intent 已附带系统解析好的 NDEF 消息，直接从中取门锁 device_id，
     * 会话内不再重读 144 字节用户内存——0xB1 成为标签激活后 app 发出的第一条命令，
     * 减少平台/自定义协议之间的干扰。
     */
    fun deviceIdFromIntent(intent: Intent): Int? {
        val messages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(
                    android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES,
                    NdefMessage::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(android.nfc.NfcAdapter.EXTRA_NDEF_MESSAGES)
                    ?.mapNotNull { it as? NdefMessage }
                    ?.toTypedArray()
            }
        } catch (_: Exception) {
            null
        } ?: return null
        for (message in messages) {
            val url = findDoorUrl(message) ?: continue
            extractDeviceId(url)?.let { return it }
        }
        return null
    }

    private const val CMD_TIMEOUT_MS = 1500        // 门锁处理+开门需要时间，给足超时
    private const val INTENT_CMD_TIMEOUT_MS = 1200 // intent 派发通道上门锁大概率不应答，快速失败转入恢复流程
    private const val CMD_RETRIES = 3              // ReaderMode 干净通道：最多重试 3 次
    private const val INTENT_CMD_RETRIES = 1       // intent 派发通道：只试一次
    private const val RETRY_GAP_MS = 120L
    private const val MAX_OPEN_ROUNDS = 2          // 首轮 + 凭证更新后重发一轮
    private const val ACTIVATION_TIMEOUT_MS = 3_000
    private const val MAX_ACTIVATION_ROUNDS = 8

    /**
     * 联机式 NFC 门锁开门：全程一次 NfcA session。
     *   连接 → (可选)读 NDEF 拿 device_id → 把 40 字节命令帧整体 transceive → 直接拿响应。
     *
     * 关键：门锁芯片用的是自定义 RF 命令（帧头 0xB1），不是 NTAG 的 0xA2 写页协议。
     * 整条命令一次 transceive 发出，门锁直接在返回值里回响应（与原 app 的 nfc.write 桥一致）。
     *
     * viaDispatchIntent=true 表示标签来自系统 intent 派发（平台已做过 NDEF 预读、
     * 存在性检查也在插手），此通道上门锁大概率不应答：只快速试一次，
     * 失败后由上层 recovery 循环切换到干净的 ReaderMode 通道重试。
     *
     * 门锁返回 [STALE_CREDENTIAL_CODES] 时调用 onStaleCredential 更新凭证
     * （优先用门锁在响应帧里直接下发的新链式密钥，否则由调用方在线刷新），
     * 并在同一 NfcA 会话内用新凭证重发命令——用户无需移开手机再贴一次。
     */
    fun openDoorSingleSession(
        tag: Tag,
        credentialHex: String,
        projectId: Int,
        preParsedDeviceId: Int? = null,
        viaDispatchIntent: Boolean = false,
        isAborted: () -> Boolean = { false },
        onStaleCredential: ((resultCode: Int, lockProvidedKeyHex: String?) -> String)? = null
    ): NfcOpenOutcome {
        return DoorNfc.withNfcA(tag) { nfcA ->
            nfcA.timeout = CMD_TIMEOUT_MS
            appendDebug(
                "NfcA连接成功 intent=$viaDispatchIntent timeout=${nfcA.timeout} " +
                    "sak=${nfcA.sak} atqa=${nfcA.atqa.toHexCompact()}"
            )

            // 1) device_id：intent 路径直接用系统解析好的 NDEF，否则读用户内存解析
            val devId = preParsedDeviceId ?: readDoorDeviceId(nfcA)

            var currentCredential = credentialHex
            var refreshed = false
            var staleRefreshError: Exception? = null
            var outcome: NfcOpenOutcome? = null

            round@ for (round in 0 until MAX_OPEN_ROUNDS) {
                if (isAborted()) throw NfcAbortedException()
                val firstIntentRound = viaDispatchIntent && round == 0
                val attempts = if (firstIntentRound) INTENT_CMD_RETRIES else CMD_RETRIES
                nfcA.timeout = if (firstIntentRound) INTENT_CMD_TIMEOUT_MS else CMD_TIMEOUT_MS

                // 2) 整条命令一次 transceive，门锁直接回响应
                val command = DoorCrypto.buildNfcCommand(devId, currentCredential, projectId)
                appendDebug("devId=$devId 轮次=${round + 1} 发送(${command.size}B)=${command.toHexCompact()}")

                var decoded: DoorResponse? = null
                var lastErr: Exception? = null
                var attempt = 0
                while (decoded == null && attempt < attempts) {
                    if (isAborted()) throw NfcAbortedException()
                    attempt++
                    try {
                        val resp = nfcA.transceive(command)
                        appendDebug("响应 轮次=${round + 1}#${attempt}(${resp.size}B)=${resp.toHexCompact()}")
                        if (resp.size >= 4 && resp[0] == DoorCrypto.FRAME_HEADER) {
                            decoded = DoorCrypto.parseResponse(devId, resp)
                        } else {
                            appendDebug("响应 轮次=${round + 1}#${attempt} 非B1帧，重试")
                        }
                    } catch (e: Exception) {
                        lastErr = e
                        appendDebug("发送 轮次=${round + 1}#${attempt} 失败 ${e.javaClass.simpleName}:${e.message}")
                    }
                    if (decoded == null && attempt < attempts) {
                        SystemClock.sleep(RETRY_GAP_MS)
                    }
                }

                val response = decoded
                    ?: throw lastErr ?: LocalizedIOException(rawText("门锁未返回有效响应"))

                if (response.isSuccess) {
                    outcome = NfcOpenOutcome(devId, response, refreshed, staleRefreshError)
                    break@round
                }
                if (response.resultCode !in STALE_CREDENTIAL_CODES ||
                    onStaleCredential == null || refreshed
                ) {
                    outcome = NfcOpenOutcome(devId, response, refreshed, staleRefreshError)
                    break@round
                }

                // 3) 凭证过期/落后：先试门锁在响应里直接下发的新链式密钥，否则在线刷新，随后同会话重发
                try {
                    currentCredential = onStaleCredential(response.resultCode, response.updatedCredentialHex)
                    refreshed = true
                    appendDebug("凭证已更新 轮次=${round + 1} code=${response.resultCode}，重发开门命令")
                } catch (e: Exception) {
                    staleRefreshError = e
                    appendDebug(
                        "凭证更新失败 轮次=${round + 1} code=${response.resultCode} " +
                            "${e.javaClass.simpleName}:${e.message}"
                    )
                    outcome = NfcOpenOutcome(devId, response, refreshed, staleRefreshError)
                    break@round
                }
            }
            outcome ?: throw LocalizedIOException(rawText("门锁未返回有效响应"))
        }
    }

    /** Relays the original H5 create -> transceive -> parse activation loop. */
    fun activateDigitalCredential(
        tag: Tag,
        snapshot: DoorCredentialSnapshot,
        api: DoorApi = DoorApi(),
        isAborted: () -> Boolean = { false },
        onProgress: (String) -> Unit = {}
    ): DoorCredentialSnapshot {
        clearDebug()
        val tagId = tag.id.toHexCompact()
        appendDebug("开始NFC数字钥匙激活 tag=$tagId 快照设备=${snapshot.deviceId} 凭证=${snapshot.credentialId}")
        try {
            return DoorNfc.withNfcA(tag) { nfcA ->
                nfcA.timeout = ACTIVATION_TIMEOUT_MS
                val deviceId = readDoorDeviceId(nfcA)
                if (deviceId != snapshot.deviceId) {
                    throw LocalizedIOException(
                        rawText("标签 device_id=%1\$s，与已登录宿舍门锁 %2\$s 不一致", deviceId, snapshot.deviceId)
                    )
                }

                var step = api.startNfcActivation(snapshot, deviceId)
                appendDebug("激活create完成 凭证=${step.credentialId} 包数=${step.packets.size} 会话字段=${step.requestData.keys().asSequence().toList().joinToString(",")}")
                repeat(MAX_ACTIVATION_ROUNDS) { round ->
                    if (isAborted()) throw NfcAbortedException()
                    if (step.packets.isEmpty()) {
                        val credentialHex = step.credentialHex
                            ?: throw DoorApiException(rawText("门锁激活未返回本地凭证"))
                        appendDebug("激活完成 轮次=${round + 1} 本地钥匙长度=${credentialHex.length}")
                        return@withNfcA snapshot.copy(
                            deviceId = deviceId,
                            credentialId = step.credentialId,
                            credentialHex = credentialHex,
                            updatedAt = System.currentTimeMillis()
                        )
                    }

                    val responses = ArrayList<String>(step.packets.size)
                    step.packets.forEachIndexed { index, packet ->
                        if (isAborted()) throw NfcAbortedException()
                        onProgress("正在发送激活数据 ${index + 1} / ${step.packets.size}…")
                        val request = packet.hexToBytes()
                        appendDebug("激活轮次=${round + 1} 发送 ${index + 1}/${step.packets.size}=${request.toHexCompact()}")
                        val response = nfcA.transceive(request)
                        if (response.isEmpty()) {
                            throw LocalizedIOException(rawText("门锁未返回激活响应"))
                        }
                        val responseHex = response.toHexCompact()
                        appendDebug("激活轮次=${round + 1} 响应 ${index + 1}/${step.packets.size}=$responseHex")
                        responses += responseHex
                    }
                    appendDebug("提交激活parse 轮次=${round + 1} 响应数=${responses.size}")
                    step = api.submitNfcActivationResponses(snapshot, step, responses)
                    appendDebug("激活parse完成 下轮包数=${step.packets.size} 会话字段=${step.requestData.keys().asSequence().toList().joinToString(",")}")
                }
                throw DoorApiException(rawText("门锁激活轮次超限"))
            }
        } catch (exception: Exception) {
            if (exception is NfcAbortedException) throw exception
            appendDebug("激活失败 ${exception.javaClass.simpleName}: ${exception.message}")
            throw exception
        } finally {
            flushDebug(tagId)
        }
    }

    private fun readDoorDeviceId(nfcA: NfcA): Int {
        val userMem = DoorNfc.readBytes(nfcA, 4, DoorNfc.DEFAULT_USER_BYTES)
        val ndefMsgBytes = extractNdefMessageBytes(userMem)
            ?: throw LocalizedIOException(rawText("标签里没有 NDEF 数据"))
        val url = findDoorUrl(NdefMessage(ndefMsgBytes))
            ?: throw LocalizedIOException(rawText("标签中无门锁 URL"))
        return extractDeviceId(url) ?: throw LocalizedIOException(rawText("URL 中无设备 ID"))
    }

    /**
     * 从 NTAG 用户内存（page4 起）解析出 NDEF 消息字节。
     * Type5/NTAG 内存是一串 TLV：0x00=空，0x03=NDEF消息，0xFE=终止，其它跳过。
     */
    private fun extractNdefMessageBytes(mem: ByteArray): ByteArray? {
        var i = 0
        while (i < mem.size) {
            when (mem[i].toInt() and 0xFF) {
                0x00 -> i++                       // NULL TLV
                0xFE -> return null               // Terminator TLV
                0x03 -> {                         // NDEF Message TLV
                    if (i + 1 >= mem.size) return null
                    var len = mem[i + 1].toInt() and 0xFF
                    var valueStart = i + 2
                    if (len == 0xFF) {            // 3 字节长度
                        if (i + 3 >= mem.size) return null
                        len = ((mem[i + 2].toInt() and 0xFF) shl 8) or (mem[i + 3].toInt() and 0xFF)
                        valueStart = i + 4
                    }
                    if (len == 0 || valueStart + len > mem.size) return null
                    return mem.copyOfRange(valueStart, valueStart + len)
                }
                else -> {                         // 其它 TLV（如 0x01 锁控制），跳过
                    if (i + 1 >= mem.size) return null
                    var len = mem[i + 1].toInt() and 0xFF
                    var valueStart = i + 2
                    if (len == 0xFF) {
                        if (i + 3 >= mem.size) return null
                        len = ((mem[i + 2].toInt() and 0xFF) shl 8) or (mem[i + 3].toInt() and 0xFF)
                        valueStart = i + 4
                    }
                    i = valueStart + len
                }
            }
        }
        return null
    }

    // 调试日志：会话期间只写内存，flushDebug 时一次性落盘（避免 NFC 热路径上的同步文件 IO 拉长会话）
    private val debugLog = StringBuilder()

    fun appendDebug(msg: String) {
        debugLog.appendLine("[${System.currentTimeMillis() % 100000}] $msg")
        if (debugLog.length > 5000) debugLog.delete(0, debugLog.length - 3000)
    }

    fun flushDebug(tagId: String, context: android.content.Context? = null) {
        try {
            val file = if (context != null) {
                java.io.File(context.getExternalFilesDir(null), "nfc_debug.txt")
            } else {
                java.io.File("/sdcard/Download/nfc_debug.txt")
            }
            file.writeText("tag=$tagId\n${debugLog}")
        } catch (_: Exception) {}
        val text = debugLog.toString().trimEnd()
        if (text.isNotEmpty()) {
            DoorOfflineLog.append("NFC", "tag=$tagId\n$text")
        }
    }

    fun clearDebug() { debugLog.clear() }

    private fun decodeRecordPayload(record: NdefRecord): String {
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI)) {
            return decodeUriPayload(record.payload)
        }
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            return decodeTextPayload(record.payload)
        }
        return String(record.payload, StandardCharsets.UTF_8)
    }

    private fun decodeUriPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val prefix = URI_PREFIX_MAP[payload[0].toInt() and 0xFF].orEmpty()
        val remainder = String(payload.copyOfRange(1, payload.size), StandardCharsets.UTF_8)
        return prefix + remainder
    }

    private fun decodeTextPayload(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt() and 0xFF
        val languageLength = status and 0x3F
        val isUtf16 = (status and 0x80) != 0
        val textStart = 1 + languageLength
        if (textStart >= payload.size) return ""
        val charset = if (isUtf16) StandardCharsets.UTF_16 else StandardCharsets.UTF_8
        return String(payload.copyOfRange(textStart, payload.size), charset)
    }
}

data class NfcOpenOutcome(
    val deviceId: Int,
    val response: DoorResponse,
    val refreshedCredential: Boolean = false,
    val staleRefreshError: Exception? = null
)
