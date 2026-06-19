package com.nkyuu.dooropener

/** ByteArray → 无分隔符大写 hex（"4F63D2"） */
fun ByteArray.toHexCompact(): String =
    joinToString("") { "%02X".format(it.toInt() and 0xFF) }

/** ByteArray → 冒号分隔大写 hex（"4F:63:D2"），用于调试日志 */
fun ByteArray.toHexString(): String =
    joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

/** 单字节 → 两位 hex（"FF"） */
fun Byte.toHexByte(): String =
    "%02X".format(toInt() and 0xFF)

/** hex 字符串 → ByteArray（忽略大小写，允许空格/冒号分隔） */
fun String.hexToBytes(): ByteArray {
    val hex = replace(Regex("[\\s:-]"), "")
    require(hex.length % 2 == 0) { "hex 字符串长度必须为偶数" }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
