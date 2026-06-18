package com.nkyuu.dooropener

import android.nfc.Tag
import android.nfc.tech.NfcA
import java.io.IOException

object DoorNfc {

    const val COMMAND_SIZE = 40
    const val COMMAND_PAGE_COUNT = 10
    const val DEFAULT_USER_BYTES = 144
    const val RESPONSE_TIMEOUT_MS = 4000L
    const val RESPONSE_POLL_INTERVAL_MS = 180L

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
            throw IOException("标签空间不足，无法写入${byteCount}字节数据")
        }
        return fallback
    }

    fun findWritableStartPage(nfcA: NfcA, suggestedStartPage: Int, maxUserBytes: Int, byteCount: Int): Int {
        val lastUserPage = USER_DATA_START_PAGE + ((maxUserBytes + PAGE_SIZE - 1) / PAGE_SIZE) - 1
        val pageCount = byteCount.toPageCount()
        if (suggestedStartPage + pageCount - 1 > lastUserPage) {
            throw IOException("标签空间不足，无法写入${byteCount}字节数据")
        }

        val readableBytes = (lastUserPage - suggestedStartPage + 1) * PAGE_SIZE
        val bytes = readBytes(nfcA, suggestedStartPage, readableBytes)
        val freeOffset = findFreeWindow(bytes, byteCount)
        return if (freeOffset >= 0) {
            suggestedStartPage + (freeOffset / PAGE_SIZE)
        } else {
            suggestedStartPage
        }
    }

    fun <T> withNfcA(tag: Tag, block: (NfcA) -> T): T {
        val nfcA = NfcA.get(tag) ?: throw IOException("标签不支持NfcA")
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
                throw IOException("写入 page $page 失败")
            }
        }
    }

    fun readBytes(nfcA: NfcA, startPage: Int, byteCount: Int): ByteArray {
        val result = ArrayList<Byte>(byteCount)
        var currentPage = startPage
        while (result.size < byteCount) {
            val block = nfcA.transceive(byteArrayOf(0x30, currentPage.toByte()))
            if (block.size < READ_BLOCK_PAGES * PAGE_SIZE) {
                throw IOException("读取 page $currentPage 返回数据过短")
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
