package com.nkyuu.dooropener

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.net.Uri
import android.os.SystemClock
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * NFC helper functions extracted from the old MainActivity.
 * Contains tag reading, NDEF parsing, and response polling logic.
 * Does NOT modify any of the protected business logic classes.
 */
object DoorNfcHelper {

    private val URI_PREFIX_MAP = mapOf(
        0x00 to "",
        0x01 to "http://www.",
        0x02 to "https://www.",
        0x03 to "http://",
        0x04 to "https://"
    )

    fun readNdefMessage(tag: Tag): NdefMessage {
        val ndef = Ndef.get(tag) ?: throw IOException("标签不支持NDEF")
        try {
            ndef.connect()
            return ndef.cachedNdefMessage ?: ndef.ndefMessage
                ?: throw IOException("标签里没有NDEF数据")
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

    private const val CMD_TIMEOUT_MS = 1500   // 门锁处理+开门需要时间，给足超时
    private const val CMD_RETRIES = 3         // 原 app 也是最多重试 3 次

    /**
     * 联机式 NFC 门锁开门：全程一次 NfcA session。
     *   连接 → 0x30 读 NDEF 拿 device_id → 把 40 字节命令帧整体 transceive → 直接拿响应。
     *
     * 关键：门锁芯片用的是自定义 RF 命令（帧头 0xB1），不是 NTAG 的 0xA2 写页协议。
     * 整条命令一次 transceive 发出，门锁直接在返回值里回响应（与原 app 的 nfc.write 桥一致）。
     */
    fun openDoorSingleSession(tag: Tag, credentialHex: String, projectId: Int): NfcOpenOutcome {
        return DoorNfc.withNfcA(tag) { nfcA ->
            nfcA.timeout = CMD_TIMEOUT_MS
            appendDebug("NfcA连接成功 timeout=${nfcA.timeout} sak=${nfcA.sak} atqa=${nfcA.atqa.toHexCompact()}")

            // 1) 读用户内存，解析 NDEF → device_id
            val userMem = DoorNfc.readBytes(nfcA, 4, DoorNfc.DEFAULT_USER_BYTES)
            val ndefMsgBytes = extractNdefMessageBytes(userMem)
                ?: throw IOException("标签里没有NDEF数据")
            val url = findDoorUrl(NdefMessage(ndefMsgBytes)) ?: throw IOException("标签中无门锁URL")
            val devId = extractDeviceId(url) ?: throw IOException("URL中无设备ID")

            // 2) 整条命令一次 transceive，门锁直接回响应
            val command = DoorCrypto.buildNfcCommand(devId, credentialHex, projectId)
            appendDebug("devId=$devId 直接发送(${command.size}B)=${command.toHexCompact()}")

            var lastErr: Exception? = null
            repeat(CMD_RETRIES) { i ->
                try {
                    val resp = nfcA.transceive(command)
                    appendDebug("响应#${i + 1}(${resp.size}B)=${resp.toHexCompact()}")
                    if (resp.size >= 4 && resp[0] == DoorCrypto.FRAME_HEADER) {
                        return@withNfcA NfcOpenOutcome(devId, DoorCrypto.parseResponse(devId, resp))
                    }
                    appendDebug("响应#${i + 1} 非B1帧，重试")
                } catch (e: Exception) {
                    lastErr = e
                    appendDebug("发送#${i + 1} 失败 ${e.javaClass.simpleName}:${e.message}")
                }
                SystemClock.sleep(80)
            }
            throw lastErr ?: IOException("门锁未返回有效响应")
        }
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
    }

    fun clearDebug() { debugLog.clear() }

    fun ByteArray.toHexString(): String {
        return joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    fun ByteArray.toHexCompact(): String {
        return joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

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

data class NfcOpenOutcome(val deviceId: Int, val response: DoorResponse)
