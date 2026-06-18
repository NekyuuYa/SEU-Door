package com.nkyuu.dooropener

import java.nio.ByteBuffer
import java.nio.ByteOrder

object DoorCrypto {

    const val FRAME_HEADER: Byte = 0xB1.toByte()
    private const val NFC_SUBCOMMAND: Byte = 0x0D
    private const val UINT_MASK = 0xFFFF_FFFFL
    private const val NFC_FRAME_HEADER_SIZE = 3
    private const val NFC_FRAME_CRC_SIZE = 1
    private const val NFC_ENCRYPTED_OFFSET = NFC_FRAME_HEADER_SIZE
    private const val NFC_RESULT_CODE_OFFSET = 3
    private const val NFC_CHAIN_KEY_FRAME_START = 7
    private const val NFC_CHAIN_KEY_FRAME_END_EXCLUSIVE = 39
    private val KEY_CONST = byteArrayOf(
        172.toByte(),
        171.toByte(),
        188.toByte(),
        218.toByte(),
        174.toByte(),
        191.toByte(),
        20,
        38,
        53,
        66,
        84,
        101,
        114,
        135.toByte(),
        146.toByte(),
        1
    )

    private val ERROR_MESSAGES = mapOf(
        0 to "开门成功",
        1 to "数据CRC校验错误",
        2 to "ISN随机数错误",
        3 to "设备已被占用",
        4 to "保存消费数据到Flash失败",
        5 to "没有历史账单",
        6 to "无流量关阀/推送账单",
        7 to "没有设置密钥",
        12 to "用户已删除",
        13 to "随机数校验失败",
        14 to "项目ID不一致",
        16 to "生成随机密钥失败",
        17 to "用户凭证保存失败",
        18 to "开锁交换密钥失败",
        19 to "解密随机密钥失败",
        20 to "未找到用户信息",
        21 to "钥匙类型不匹配",
        22 to "管理员随机数不匹配",
        23 to "门锁状态已打开",
        24 to "已过有效期",
        25 to "离线次数已用完",
        26 to "用户凭证更新失败",
        27 to "需要更新开锁密钥",
        255 to "未知命令"
    )

    fun deriveKey(deviceId: Int): ByteArray {
        val idBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(deviceId)
            .array()
        val value = (
            ((idBytes[0].toInt() and 0xFF) shl 24) or
                ((idBytes[1].toInt() and 0xFF) shl 16) or
                ((idBytes[2].toInt() and 0xFF) shl 8) or
                (idBytes[3].toInt() and 0xFF)
            ).toLong() and UINT_MASK

        val words = LongArray(4)
        val constBuffer = ByteBuffer.wrap(KEY_CONST).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 4) {
            words[index] = constBuffer.int.toLong() and UINT_MASK
        }

        for (index in 0 until 4) {
            val delta = u32(2654435769L + 305419896L * index)
            val left = u32((value and delta) + index.toLong())
            val middle = u32((value or delta) - 2L * index)
            val right = u32((UINT_MASK xor value) xor delta)
            val transformed = u32(left + middle - right)
            words[index] = u32(words[index] xor transformed)
        }

        val result = ByteArray(16)
        val output = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        for (index in 0 until 4) {
            output.putInt(words[index].toInt())
        }
        return result
    }

    fun rc4Encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val sbox = IntArray(256) { it }
        var j = 0
        for (index in 0 until 256) {
            j = (j + sbox[index] + (key[index % key.size].toInt() and 0xFF)) and 0xFF
            sbox.swap(index, j)
        }

        val result = ByteArray(data.size)
        var x = 0
        var y = 0
        for (index in data.indices) {
            x = (x + 1) and 0xFF
            y = (y + sbox[x]) and 0xFF
            sbox.swap(x, y)
            val keyByte = sbox[(sbox[x] + sbox[y]) and 0xFF]
            result[index] = ((data[index].toInt() and 0xFF) xor keyByte).toByte()
        }
        return result
    }

    fun crc8(data: ByteArray): Byte {
        var crc = 0
        for (byte in data) {
            crc = CRC8_TABLE[crc xor (byte.toInt() and 0xFF)]
        }
        return crc.toByte()
    }

    fun buildNfcCommand(deviceId: Int, credentialHex: String, projectId: Int): ByteArray {
        val credential = credentialHex.hexToBytes()
        require(credential.size == 32) { "credential 必须是32字节" }

        val pidBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(projectId)
            .array()
        val plainData = pidBytes + credential
        val encrypted = rc4Encrypt(plainData, deriveKey(deviceId))

        val command = ByteArray(40)
        command[0] = FRAME_HEADER
        command[1] = NFC_SUBCOMMAND
        command[2] = encrypted.size.toByte()
        System.arraycopy(encrypted, 0, command, 3, encrypted.size)
        command[39] = crc8(byteArrayOf(NFC_SUBCOMMAND, encrypted.size.toByte()) + plainData)
        return command
    }

    fun parseResponse(deviceId: Int, frame: ByteArray): DoorResponse {
        require(frame.size >= NFC_FRAME_HEADER_SIZE + NFC_FRAME_CRC_SIZE) { "响应太短" }
        require(frame[0] == FRAME_HEADER) { "响应帧头错误" }

        val commandId = frame[1].toInt() and 0xFF
        val payloadSize = frame[2].toInt() and 0xFF
        val frameSize = NFC_FRAME_HEADER_SIZE + payloadSize + NFC_FRAME_CRC_SIZE
        require(frame.size >= frameSize) { "响应长度不足" }

        val encryptedPayload = frame.copyOfRange(
            NFC_ENCRYPTED_OFFSET,
            NFC_ENCRYPTED_OFFSET + payloadSize
        )
        val plainPayload = rc4Encrypt(encryptedPayload, deriveKey(deviceId))
        val receivedCrc = frame[NFC_ENCRYPTED_OFFSET + payloadSize]
        val calculatedCrc = crc8(byteArrayOf(frame[1], frame[2]) + plainPayload)
        val resultCode = plainPayload.getOrNull(NFC_RESULT_CODE_OFFSET)?.toInt()?.and(0xFF) ?: -1
        val isSuccess = resultCode == 0 || resultCode == 23
        val updatedCredentialHex = extractUpdatedCredentialFromNfcPayload(plainPayload, isSuccess)

        return DoorResponse(
            commandId = commandId,
            payloadSize = payloadSize,
            frameSize = frameSize,
            resultCode = resultCode,
            resultMessage = ERROR_MESSAGES[resultCode] ?: "未知错误",
            crcValid = receivedCrc == calculatedCrc,
            updatedCredentialHex = updatedCredentialHex,
            isSuccess = isSuccess
        )
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "HEX字符串长度必须为偶数" }
        return chunked(2).map { chunk ->
            chunk.toInt(16).toByte()
        }.toByteArray()
    }

    private fun ByteArray.toHexCompact(): String {
        return joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    private fun IntArray.swap(first: Int, second: Int) {
        val temp = this[first]
        this[first] = this[second]
        this[second] = temp
    }

    private fun extractUpdatedCredentialFromNfcPayload(
        plainPayload: ByteArray,
        isSuccess: Boolean
    ): String? {
        if (!isSuccess) {
            return null
        }

        val payloadStart = NFC_CHAIN_KEY_FRAME_START - NFC_ENCRYPTED_OFFSET
        val payloadEndExclusive = NFC_CHAIN_KEY_FRAME_END_EXCLUSIVE - NFC_ENCRYPTED_OFFSET
        if (payloadStart < 0 || plainPayload.size < payloadEndExclusive) {
            return null
        }

        return plainPayload.copyOfRange(payloadStart, payloadEndExclusive)
            .takeIf { bytes -> bytes.any { it.toInt() != 0 } }
            ?.toHexCompact()
    }

    private fun u32(value: Long): Long {
        return value and UINT_MASK
    }

    private val CRC8_TABLE = intArrayOf(
        0, 94, 188, 226, 97, 63, 221, 131, 194, 156, 126, 32, 163, 253, 31, 65,
        157, 195, 33, 127, 252, 162, 64, 30, 95, 1, 227, 189, 62, 96, 130, 220,
        35, 125, 159, 193, 66, 28, 254, 160, 225, 191, 93, 3, 128, 222, 60, 98,
        190, 224, 2, 92, 223, 129, 99, 61, 124, 34, 192, 158, 29, 67, 161, 255,
        70, 24, 250, 164, 39, 121, 155, 197, 132, 218, 56, 102, 229, 187, 89, 7,
        219, 133, 103, 57, 186, 228, 6, 88, 25, 71, 165, 251, 120, 38, 196, 154,
        101, 59, 217, 135, 4, 90, 184, 230, 167, 249, 27, 69, 198, 152, 122, 36,
        248, 166, 68, 26, 153, 199, 37, 123, 58, 100, 134, 216, 91, 5, 231, 185,
        140, 210, 48, 110, 237, 179, 81, 15, 78, 16, 242, 172, 47, 113, 147, 205,
        17, 79, 173, 243, 112, 46, 204, 146, 211, 141, 111, 49, 178, 236, 14, 80,
        175, 241, 19, 77, 206, 144, 114, 44, 109, 51, 209, 143, 12, 82, 176, 238,
        50, 108, 142, 208, 83, 13, 239, 177, 240, 174, 76, 18, 145, 207, 45, 115,
        202, 148, 118, 40, 171, 245, 23, 73, 8, 86, 180, 234, 105, 55, 213, 139,
        87, 9, 235, 181, 54, 104, 138, 212, 149, 203, 41, 119, 244, 170, 72, 22,
        233, 183, 85, 11, 136, 214, 52, 106, 43, 117, 151, 201, 74, 20, 246, 168,
        116, 42, 200, 150, 21, 75, 169, 247, 182, 232, 10, 84, 215, 137, 107, 53
    )
}

data class DoorResponse(
    val commandId: Int,
    val payloadSize: Int,
    val frameSize: Int,
    val resultCode: Int,
    val resultMessage: String,
    val crcValid: Boolean?,
    val updatedCredentialHex: String?,
    val isSuccess: Boolean
)
