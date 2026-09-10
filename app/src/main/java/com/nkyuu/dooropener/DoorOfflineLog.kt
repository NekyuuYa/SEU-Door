package com.nkyuu.dooropener

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persists bounded diagnostics so NFC/API failures remain available after unplugging ADB. */
object DoorOfflineLog {

    private const val FILE_NAME = "offline_diagnostic.log"
    private const val MAX_BYTES = 128 * 1024
    private const val RETAIN_BYTES = 96 * 1024

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (file == null) {
                val directory = context.getExternalFilesDir(null) ?: context.filesDir
                file = File(directory, FILE_NAME)
            }
        }
    }

    fun append(category: String, message: String) {
        synchronized(lock) {
            val target = file ?: return
            runCatching {
                target.parentFile?.mkdirs()
                target.appendText("${timeFormat.format(Date())} [$category] ${sanitize(message)}\n")
                if (target.length() > MAX_BYTES) {
                    target.writeText(target.readText().takeLast(RETAIN_BYTES))
                }
            }
        }
    }

    private const val MAX_MESSAGE_BYTES = 1024

    /**
     * 服务器偶发返回含脏字节的响应，直接落盘会让日志文件变成二进制不可读。
     * 这里把 <0x20 的控制字符（保留 \t \n \r）转成可见转义，并对单条消息截断。
     */
    private fun sanitize(message: String): String {
        if (message.length > MAX_MESSAGE_BYTES) {
            return sanitize(message.take(MAX_MESSAGE_BYTES)) + "…(truncated)"
        }
        val sb = StringBuilder(message.length)
        for (ch in message) {
            when {
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(ch)
                ch < ' ' -> sb.append("\\x").append(String.format("%02X", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
