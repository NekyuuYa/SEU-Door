package com.nkyuu.dooropener

import android.nfc.Tag
import android.nfc.tech.NfcA
import java.io.IOException

object DoorNfc {

    const val COMMAND_SIZE = 40
    const val DEFAULT_USER_BYTES = 144

    private const val PAGE_SIZE = 4
    private const val USER_DATA_START_PAGE = 4
    private const val READ_BLOCK_PAGES = 4
    private const val TIMEOUT_MS = 800

    fun findStartPage(ndefMessageBytes: ByteArray, maxUserBytes: Int, byteCount: Int): Int {
        val tlvLength = encodedTlvLength(ndefMessageBytes.size)
        val usedPages = (tlvLength + PAGE_SIZE - 1) / PAGE_SIZE
        val candidate = USER_DATA_START_PAGE + usedPages
        val lastUserPage = USER_DATA_START_PAGE + ((maxUserBytes + PAGE_SIZE - 1) / PAGE_SIZE) - 1
        val pageCount = byteCount.toPageCount()
        if (candidate + pageCount - 1 <= lastUserPage) {
            return candidate
        }
        val fallback = lastUserPage - pageCount + 1
        if (fallback < USER_DATA_START_PAGE) {
            throw LocalizedIOException(rawText("标签空间不足，无法写入 %1\$s 字节", byteCount))
        }
        return fallback
    }

    fun findWritableStartPage(
        nfcA: NfcA,
        suggestedStartPage: Int,
        maxUserBytes: Int,
        byteCount: Int,
        allowReuseExisting: Boolean = false
    ): Int {
        val lastUserPage = USER_DATA_START_PAGE + ((maxUserBytes + PAGE_SIZE - 1) / PAGE_SIZE) - 1
        val pageCount = byteCount.toPageCount()
        if (suggestedStartPage + pageCount - 1 > lastUserPage) {
            throw LocalizedIOException(rawText("标签空间不足，无法写入 %1\$s 字节", byteCount))
        }

        val readableBytes = (lastUserPage - suggestedStartPage + 1) * PAGE_SIZE
        val bytes = readBytes(nfcA, suggestedStartPage, readableBytes)
        val freeOffset = findFreeWindow(bytes, byteCount)
        if (freeOffset >= 0) {
            return suggestedStartPage + (freeOffset / PAGE_SIZE)
        }

        if (allowReuseExisting && looksLikeDoorFrame(bytes, byteCount)) {
            return suggestedStartPage
        }

        throw LocalizedIOException(rawText("未找到可写入的空闲标签区域"))
    }

    fun <T> withNfcA(tag: Tag, block: (NfcA) -> T): T {
        val nfcA = NfcA.get(tag) ?: throw LocalizedIOException(rawText("标签不支持 NfcA"))
        try {
            nfcA.connect()
            nfcA.timeout = TIMEOUT_MS
            return block(nfcA)
        } finally {
            try {
                nfcA.close()
            } catch (_: Exception) {
            }
        }
    }

    fun writeCommand(nfcA: NfcA, startPage: Int, command: ByteArray) {
        require(command.size == COMMAND_SIZE)
        writeBytes(nfcA, startPage, command)
    }

    fun writeBytes(nfcA: NfcA, startPage: Int, payload: ByteArray) {
        payload.asPageChunks().forEachIndexed { index, pageBytes ->
            val page = startPage + index
            val response = nfcA.transceive(
                byteArrayOf(
                    0xA2.toByte(),
                    page.toByte(),
                    pageBytes[0],
                    pageBytes[1],
                    pageBytes[2],
                    pageBytes[3]
                )
            )
            if (response.isEmpty() || response[0] != 0x0A.toByte()) {
                throw LocalizedIOException(rawText("写入 page %1\$s 失败", page))
            }
        }
    }

    fun readBytes(nfcA: NfcA, startPage: Int, byteCount: Int): ByteArray {
        val result = ArrayList<Byte>(byteCount)
        var currentPage = startPage
        while (result.size < byteCount) {
            val block = nfcA.transceive(byteArrayOf(0x30, currentPage.toByte()))
            if (block.size < READ_BLOCK_PAGES * PAGE_SIZE) {
                throw LocalizedIOException(rawText("读取 page %1\$s 返回数据过短", currentPage))
            }
            block.forEach { result.add(it) }
            currentPage += READ_BLOCK_PAGES
        }
        return ByteArray(byteCount) { index -> result[index] }
    }

    private fun encodedTlvLength(messageSize: Int): Int {
        val tlvHeaderSize = if (messageSize < 0xFF) 2 else 4
        return tlvHeaderSize + messageSize + 1
    }

    private fun findFreeWindow(bytes: ByteArray, byteCount: Int): Int {
        if (bytes.size < byteCount) {
            return -1
        }
        for (offset in 0..(bytes.size - byteCount) step PAGE_SIZE) {
            val isFree = (offset until offset + byteCount).all { index ->
                val value = bytes[index]
                value == 0x00.toByte() || value == 0xFF.toByte()
            }
            if (isFree) {
                return offset
            }
        }
        return -1
    }

    private fun looksLikeDoorFrame(bytes: ByteArray, byteCount: Int): Boolean {
        if (bytes.size < 4 || byteCount < 4) {
            return false
        }
        if (bytes[0] != 0xB1.toByte()) {
            return false
        }
        val payloadSize = bytes[2].toInt() and 0xFF
        val frameSize = 3 + payloadSize + 1
        return frameSize in 4..byteCount
    }

    private fun Int.toPageCount(): Int {
        return (this + PAGE_SIZE - 1) / PAGE_SIZE
    }

    private fun ByteArray.asPageChunks(): List<ByteArray> {
        val pageCount = (size + PAGE_SIZE - 1) / PAGE_SIZE
        return List(pageCount) { pageIndex ->
            ByteArray(PAGE_SIZE) { byteIndex ->
                getOrElse(pageIndex * PAGE_SIZE + byteIndex) { 0x00 }
            }
        }
    }
}
