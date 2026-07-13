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
                target.appendText("${timeFormat.format(Date())} [$category] $message\n")
                if (target.length() > MAX_BYTES) {
                    target.writeText(target.readText().takeLast(RETAIN_BYTES))
                }
            }
        }
    }
}
