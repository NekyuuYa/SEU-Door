package com.nkyuu.dooropener

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
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

    fun writeCommandAndReadResponse(tag: Tag, startPage: Int, command: ByteArray): ByteArray {
        return DoorNfc.withNfcA(tag) { nfcA ->
            DoorNfc.writeCommand(nfcA, startPage, command)

            val deadline = SystemClock.elapsedRealtime() + DoorNfc.RESPONSE_TIMEOUT_MS
            var lastRead = command
            while (SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(DoorNfc.RESPONSE_POLL_INTERVAL_MS)
                val current = try {
                    DoorNfc.readBytes(nfcA, startPage, DoorNfc.COMMAND_SIZE)
                } catch (_: TagLostException) {
                    // Tag暂时丢失，继续等待（用户可能稍微移动了手机）
                    continue
                } catch (_: IOException) {
                    continue
                }

                lastRead = current
                if (current.size < 4 || current[0] != DoorCrypto.FRAME_HEADER) {
                    continue
                }

                val payloadSize = current[2].toInt() and 0xFF
                val frameSize = payloadSize + 4
                if (frameSize !in 4..current.size) {
                    continue
                }

                val frame = current.copyOf(frameSize)
                if (!frame.contentEquals(command)) {
                    return@withNfcA frame
                }
            }

            if (lastRead.contentEquals(command)) {
                throw IOException("写入完成，但未等到门锁响应（请保持手机贴近标签）")
            }
            lastRead
        }
    }

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
