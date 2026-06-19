package com.nkyuu.dooropener

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

class DoorApi(private val authServerUrl: String = DEFAULT_AUTH_SERVER_URL) {

    fun syncCredential(phone: String, password: String): DoorCredentialSnapshot {
        val loginData = authGet(
            path = "/webapi/users/login",
            params = mutableMapOf(
                "phone" to phone,
                "pwd" to password
            ),
            nonceLength = AUTH_NONCE_LENGTH
        )

        val userId = requireString(loginData, "id", "user_id", "userId", "uid")
        val identityCode = requireString(loginData, "identity_code", "identitycode")
        val serverAddr = requireString(loginData, "server_addr")
        val sessionSecret = requireString(loginData, "session_secret")

        val detailData = businessGet(
            baseUrl = serverAddr,
            sessionSecret = sessionSecret,
            path = "/webapi/v1/student/accommodation/details",
            params = mutableMapOf(
                "user_id" to userId,
                "identitycode" to identityCode
            )
        )

        val detailDoorLock = findChildObject(detailData, "door_lock")
        var deviceId = findString(detailDoorLock ?: detailData, "device_id", "deviceId")
        var credentialId = findString(detailDoorLock ?: detailData, "credential_id", "credentialId")
        val bleMac = findString(detailDoorLock ?: detailData, "ble_mac", "bleMac")
        var credential = findString(detailDoorLock ?: detailData, "credential", "chain_key", "chainKey")

        if (deviceId.isNullOrBlank()) {
            throw DoorApiException(rawText("服务器返回中缺少 device_id"))
        }

        if (credential.isNullOrBlank()) {
            val credentialData = businessGet(
                baseUrl = serverAddr,
                sessionSecret = sessionSecret,
                path = "/webapi/v1/staff/door_lock/credentials",
                params = mutableMapOf(
                    "device_id" to deviceId,
                    "user_id" to userId,
                    "identitycode" to identityCode
                )
            )
            if (credentialId.isNullOrBlank()) {
                credentialId = extractCredentialId(credentialData)
            }
            credential = findString(credentialData, "credential", "chain_key", "chainKey")
        }

        val deviceIdInt = deviceId.toIntOrNull()
            ?: throw DoorApiException(rawText("device_id 不是合法整数: $deviceId"))
        val normalizedCredential = credential
            ?.uppercase()
            ?.takeIf { it.matches(Regex("^[0-9A-F]{64}$")) }
            ?: throw DoorApiException(rawText("服务器返回的凭证无效"))

        return DoorCredentialSnapshot(
            serverUrl = normalizeServerUrl(serverAddr),
            phone = phone,
            password = password,
            userId = userId,
            identityCode = identityCode,
            deviceId = deviceIdInt,
            credentialId = credentialId.orEmpty(),
            bleMac = bleMac.orEmpty(),
            credentialHex = normalizedCredential,
            updatedAt = System.currentTimeMillis(),
            authServerUrl = normalizeServerUrl(authServerUrl),
            sessionSecret = sessionSecret
        )
    }

    fun fetchActivationPayload(snapshot: DoorCredentialSnapshot, deviceId: Int): ByteArray {
        val data = businessGet(
            baseUrl = snapshot.serverUrl,
            sessionSecret = snapshot.sessionSecret,
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
            ?: throw DoorApiException(rawText("服务器未返回合法的激活数据"))

        return payloadHex.hexToBytes()
    }

    private fun authGet(path: String, params: MutableMap<String, String>, nonceLength: Int): Any {
        val signedParams = signParams(
            params = params,
            secret = AUTH_SIGN_SECRET,
            nonceLength = nonceLength
        )
        return performGet(
            baseUrl = normalizeServerUrl(authServerUrl),
            path = path,
            params = signedParams
        )
    }

    private fun businessGet(
        baseUrl: String,
        sessionSecret: String,
        path: String,
        params: MutableMap<String, String>
    ): Any {
        val signedParams = signParams(
            params = params,
            secret = sessionSecret,
            nonceLength = BUSINESS_NONCE_LENGTH
        )
        return performGet(
            baseUrl = normalizeServerUrl(baseUrl),
            path = path,
            params = signedParams
        )
    }

    private fun performGet(baseUrl: String, path: String, params: Map<String, String>): Any {
        val query = encodeParams(params)
        val connection = openConnection(baseUrl, "$path?$query")
        try {
            connection.requestMethod = "GET"
            return readEnvelope(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun signParams(
        params: MutableMap<String, String>,
        secret: String,
        nonceLength: Int
    ): Map<String, String> {
        params["pid"] = PROJECT_ID.toString()
        params["appid"] = APP_ID.toString()
        params["timestamp"] = (System.currentTimeMillis() / 1000L).toString()
        params["noncestr"] = randomNonce(nonceLength)

        val sorted = params.toSortedMap()
        val signSource = sorted.entries.joinToString("&") { (key, value) ->
            "$key=$value"
        }.replace("\"", "").replace(" ", "") + "&key=$secret"
        val sign = md5(signSource).uppercase()

        return linkedMapOf<String, String>().apply {
            putAll(sorted)
            put("sign", sign)
        }
    }

    private fun openConnection(baseUrl: String, path: String): HttpURLConnection {
        val fullUrl = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            baseUrl + path
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
            throw DoorApiException(rawText("服务器返回空响应 (HTTP %1\$s)", statusCode))
        }

        val root = try {
            JSONObject(body)
        } catch (exception: Exception) {
            throw DoorApiException(rawText("服务器返回非法 JSON: %1\$s", body))
        }

        if (!isSuccess(root)) {
            val serverMessage = root.optString("err_msg")
                .ifBlank { root.optString("msg") }
                .ifBlank { root.optString("message") }
            throw DoorApiException(
                serverMessage
                    .takeIf { it.isNotBlank() }
                    ?.let { rawText(it) }
                    ?: rawText("服务器返回失败: HTTP %1\$s", statusCode)
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
        if (root.has("result") && !root.optBoolean("result", true)) {
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
            ?: throw DoorApiException(rawText("服务器返回缺少字段: %1\$s", keys.joinToString("/")))
    }

    private fun findChildObject(node: Any, key: String): JSONObject? {
        return (node as? JSONObject)?.optJSONObject(key)
    }

    private fun extractCredentialId(node: Any): String? {
        findString(node, "credential_id", "credentialId")?.takeIf { it.isNotBlank() }?.let { return it }

        return when (node) {
            is JSONObject -> {
                node.optJSONArray("rows")?.let { rows ->
                    for (index in 0 until rows.length()) {
                        val row = rows.optJSONObject(index) ?: continue
                        val value = row.opt("id")
                        if (value != null && value != JSONObject.NULL) {
                            return value.toString()
                        }
                    }
                }
                null
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    val result = extractCredentialId(node.opt(index))
                    if (!result.isNullOrBlank()) {
                        return result
                    }
                }
                null
            }

            else -> null
        }
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

    companion object {
        const val DEFAULT_AUTH_SERVER_URL = "https://pm.whxinna.com"
        const val DEFAULT_SERVER_URL = "https://zhuli104.whxinna.com"
        const val PROJECT_ID = 21048
        const val APP_ID = 20104

        private const val AUTH_SIGN_SECRET = "6d5dbb85b949447a95ff8fda9a9b759b"
        private const val AUTH_NONCE_LENGTH = 32
        private const val BUSINESS_NONCE_LENGTH = 16
        private val NONCE_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val random = SecureRandom()

        fun normalizeServerUrl(raw: String): String {
            return raw.trim().trimEnd('/')
        }
    }
}

class DoorApiException(
    private val text: TextValue,
    cause: Throwable? = null
) : LocalizedIOException(text, cause)
