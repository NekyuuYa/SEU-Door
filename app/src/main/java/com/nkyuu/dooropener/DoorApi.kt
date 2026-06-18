package com.nkyuu.dooropener

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class DoorApi(private val serverUrl: String) {

    fun syncCredential(phone: String, password: String): DoorCredentialSnapshot {
        val loginData = post(
            path = "/webapi/oauth/auth_by_password",
            params = mutableMapOf(
                "phone" to phone,
                "password" to password
            )
        )
        val userId = requireString(loginData, "user_id", "userId", "uid")
        val identityCode = requireString(loginData, "identitycode", "identity_code")

        val detailData = get(
            path = "/webapi/v1/student/accommodation/details",
            params = mutableMapOf(
                "user_id" to userId,
                "identitycode" to identityCode
            )
        )

        var deviceId = findString(detailData, "device_id", "deviceId")
        var credentialId = findString(detailData, "credential_id", "credentialId")
        var credential = findString(detailData, "credential", "chain_key", "chainKey")

        if (deviceId.isNullOrBlank()) {
            throw DoorApiException("未从服务器返回中找到 device_id")
        }

        if (credential.isNullOrBlank()) {
            val credentialData = get(
                path = "/webapi/v1/staff/door_lock/credentials",
                params = mutableMapOf(
                    "device_id" to deviceId,
                    "user_id" to userId,
                    "identitycode" to identityCode
                )
            )
            if (credentialId.isNullOrBlank()) {
                credentialId = findString(credentialData, "credential_id", "credentialId")
            }
            credential = findString(credentialData, "credential", "chain_key", "chainKey")
        }

        val deviceIdInt = deviceId.toIntOrNull()
            ?: throw DoorApiException("device_id 不是合法整数: $deviceId")
        val normalizedCredential = credential
            ?.uppercase()
            ?.takeIf { it.matches(Regex("^[0-9A-F]{64}$")) }
            ?: throw DoorApiException("服务器返回的 credential 无效")

        return DoorCredentialSnapshot(
            serverUrl = normalizeServerUrl(serverUrl),
            phone = phone,
            password = password,
            userId = userId,
            identityCode = identityCode,
            deviceId = deviceIdInt,
            credentialId = credentialId.orEmpty(),
            credentialHex = normalizedCredential,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun fetchActivationPayload(snapshot: DoorCredentialSnapshot, deviceId: Int): ByteArray {
        require(snapshot.userId.isNotBlank()) { "缺少 user_id，请先同步凭证" }
        require(snapshot.identityCode.isNotBlank()) { "缺少 identitycode，请先同步凭证" }
        require(snapshot.credentialId.isNotBlank()) { "缺少 credential_id，请先同步凭证" }

        val data = get(
            path = "/webapi/v1/door_lock/command/create",
            params = mutableMapOf(
                "device_id" to deviceId.toString(),
                "command" to "1",
                "type" to "nfc",
                "credential_id" to snapshot.credentialId,
                "user_id" to snapshot.userId,
                "identitycode" to snapshot.identityCode
            )
        )

        val payloadHex = when (data) {
            is String -> data.trim()
            else -> findString(data, "payload")
        }?.uppercase()?.takeIf { it.matches(Regex("^[0-9A-F]+$")) && it.length % 2 == 0 }
            ?: throw DoorApiException("服务器未返回合法的激活 payload")

        return payloadHex.hexToBytes()
    }

    private fun post(path: String, params: MutableMap<String, String>): Any {
        val signedParams = signParams(params)
        val body = encodeParams(signedParams)
        val connection = openConnection(path).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
            return readEnvelope(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun get(path: String, params: MutableMap<String, String>): Any {
        val signedParams = signParams(params)
        val query = encodeParams(signedParams)
        val connection = openConnection("$path?$query").apply {
            requestMethod = "GET"
        }
        try {
            return readEnvelope(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun signParams(input: MutableMap<String, String>): Map<String, String> {
        input["pid"] = PROJECT_ID.toString()
        input["appid"] = APP_ID.toString()
        input["timestamp"] = (System.currentTimeMillis() / 1000L).toString()
        input["noncestr"] = randomNonce(16)

        val sorted = input.toSortedMap()
        val signSource = sorted.entries.joinToString("&") { (key, value) ->
            "$key=$value"
        }.replace("\"", "").replace(" ", "") + "&key=$SESSION_SECRET"
        val sign = md5(signSource).uppercase()

        return linkedMapOf<String, String>().apply {
            putAll(sorted)
            put("sign", sign)
        }
    }

    private fun openConnection(path: String): HttpURLConnection {
        val fullUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            normalizeServerUrl(serverUrl) + path
        }
        return (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DoorOpener/1.0")
        }
    }

    private fun readEnvelope(connection: HttpURLConnection): Any {
        val statusCode = connection.responseCode
        val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            ?.readAllText()
            .orEmpty()
        if (body.isBlank()) {
            throw DoorApiException("服务器返回空响应")
        }

        val root = try {
            JSONObject(body)
        } catch (exception: Exception) {
            throw DoorApiException("服务器返回不是合法JSON: $body")
        }

        if (!isSuccess(root)) {
            throw DoorApiException(
                root.optString("msg")
                    .ifBlank { root.optString("message") }
                    .ifBlank { "服务器返回失败: HTTP $statusCode" }
            )
        }

        if (!root.has("data")) {
            return root
        }

        return decodeData(root.get("data"))
    }

    private fun decodeData(raw: Any): Any {
        return when (raw) {
            is JSONObject -> raw
            is JSONArray -> raw
            is String -> decodeDataString(raw)
            else -> raw
        }
    }

    private fun decodeDataString(value: String): Any {
        val trimmed = value.trim()
        if (trimmed.startsWith("{")) {
            return JSONObject(trimmed)
        }
        if (trimmed.startsWith("[")) {
            return JSONArray(trimmed)
        }
        val decodedText = try {
            String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            return trimmed
        }
        val normalized = decodedText.trim()
        return when {
            normalized.startsWith("{") -> JSONObject(normalized)
            normalized.startsWith("[") -> JSONArray(normalized)
            else -> normalized
        }
    }

    private fun isSuccess(root: JSONObject): Boolean {
        if (root.has("success") && !root.optBoolean("success", true)) {
            return false
        }

        val codeValue = root.opt("code")
            ?: root.opt("status")
            ?: root.opt("errno")
            ?: return true
        val code = codeValue.toString()
        return code == "0" || code == "1" || code == "200"
    }

    private fun requireString(data: Any, vararg keys: String): String {
        return findString(data, *keys)?.takeIf { it.isNotBlank() }
            ?: throw DoorApiException("服务器返回缺少字段: ${keys.joinToString("/")}")
    }

    private fun findString(node: Any, vararg keys: String): String? {
        val keySet = keys.toSet()
        return when (node) {
            is JSONObject -> {
                for (key in node.keys()) {
                    if (keySet.contains(key)) {
                        val value = node.opt(key)
                        if (value != null && value != JSONObject.NULL) {
                            return value.toString()
                        }
                    }
                }
                for (key in node.keys()) {
                    val child = node.opt(key)
                    val result = child?.takeIf { it != JSONObject.NULL }?.let { findString(it, *keys) }
                    if (!result.isNullOrBlank()) {
                        return result
                    }
                }
                null
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    val result = findString(node.opt(index), *keys)
                    if (!result.isNullOrBlank()) {
                        return result
                    }
                }
                null
            }

            else -> null
        }
    }

    private fun encodeParams(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
    }

    private fun randomNonce(length: Int): String {
        val builder = StringBuilder(length)
        repeat(length) {
            builder.append(NONCE_CHARS[random.nextInt(NONCE_CHARS.length)])
        }
        return builder.toString()
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private fun InputStream.readAllText(): String {
        return BufferedReader(InputStreamReader(this, Charsets.UTF_8)).use { reader ->
            reader.readText()
        }
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, "UTF-8")
    }

    private fun String.hexToBytes(): ByteArray {
        return chunked(2).map { chunk -> chunk.toInt(16).toByte() }.toByteArray()
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://zhuli104.whxinna.com"
        const val PROJECT_ID = 21048
        const val APP_ID = 20104
        const val SESSION_SECRET = "2b43ee16185944a5ba49a8e4d0374966"

        private val NONCE_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val random = SecureRandom()

        fun normalizeServerUrl(raw: String): String {
            return raw.trim().trimEnd('/')
        }
    }
}

class DoorApiException(message: String, cause: Throwable? = null) : IOException(message, cause)
