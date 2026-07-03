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

    fun syncCredential(phone: String, password: String, code: String? = null): DoorCredentialSnapshot {
        return buildSnapshotFromLogin(login(phone, password, code), phone = phone, password = password)
    }

    /**
     * 仅用缓存的 sessionSecret 拉取最新凭证，不重新登录。
     * 如果 sessionSecret 已过期（业务请求返回鉴权失败），抛出异常由调用方回落到完整登录流程。
     */
    fun refreshCredentialOnly(snapshot: DoorCredentialSnapshot): DoorCredentialSnapshot {
        val detailData = businessGet(
            baseUrl = snapshot.serverUrl,
            sessionSecret = snapshot.sessionSecret,
            path = "/webapi/v1/student/accommodation/details",
            params = mutableMapOf(
                "user_id" to snapshot.userId,
                "identitycode" to snapshot.identityCode
            )
        )

        val detailDoorLock = findChildObject(detailData, "door_lock")
        var credentialId = findString(detailDoorLock ?: detailData, "credential_id", "credentialId")
        val bleMac = findString(detailDoorLock ?: detailData, "ble_mac", "bleMac")
        var credential = findString(detailDoorLock ?: detailData, "credential", "chain_key", "chainKey")

        if (credential.isNullOrBlank()) {
            val credentialData = businessGet(
                baseUrl = snapshot.serverUrl,
                sessionSecret = snapshot.sessionSecret,
                path = "/webapi/v1/staff/door_lock/credentials",
                params = mutableMapOf(
                    "device_id" to snapshot.deviceId.toString(),
                    "user_id" to snapshot.userId,
                    "identitycode" to snapshot.identityCode
                )
            )
            if (credentialId.isNullOrBlank()) {
                credentialId = extractCredentialId(credentialData)
            }
            credential = findString(credentialData, "credential", "chain_key", "chainKey")
        }

        val normalizedCredential = credential
            ?.uppercase()
            ?.takeIf { it.matches(Regex("^[0-9A-F]{64}$")) }
            ?: throw DoorApiException(rawText("服务器返回的凭证无效"))

        return snapshot.copy(
            credentialHex = normalizedCredential,
            credentialId = credentialId?.takeIf { it.isNotBlank() } ?: snapshot.credentialId,
            bleMac = bleMac?.takeIf { it.isNotBlank() } ?: snapshot.bleMac,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 支付宝 OAuth 登录：用外部支付宝 app 授权拿到的 auth_code 换登录态，
     * 之后与密码登录走完全相同的业务流程。snapshot 的 password 留空；
     * 凭证过期时优先用 refreshCredentialOnly（缓存 sessionSecret）静默刷新，
     * sessionSecret 过期才回落到完整登录流程。
     */
    fun syncCredentialWithAlipay(authCode: String): DoorCredentialSnapshot {
        val loginData = oauthLogin(authCode, OAUTH_TYPE_ALIPAY)
        val phone = findString(loginData, "phone").orEmpty()
        return buildSnapshotFromLogin(loginData, phone = phone, password = "")
    }

    /** 取支付宝快捷登录 auth_info（服务器 RSA2 签名的 SDK 串），交给 IAlixPay.Pay()。 */
    fun fetchAlipayAuthInfo(): String {
        val body = authGetRaw(
            path = "/webapi/oauth/alipay/auth_info",
            params = mutableMapOf(),
            nonceLength = AUTH_NONCE_LENGTH
        )
        val outer = parseJsonObject(body) ?: throw DoorApiException(rawText("支付宝授权信息获取失败"))
        if (!isSuccess(outer)) {
            val serverMessage = extractServerMessage(outer)
            throw DoorApiException(
                serverMessage?.let { rawText(it) } ?: rawText("支付宝授权信息获取失败"),
                serverMessage = serverMessage
            )
        }
        // data 解码后是 JSON 字符串字面量（外层带引号），剥成真正的 authInfo 串
        val decoded = decodeBase64Text(outer.optString("data"))?.trim()
            ?: throw DoorApiException(rawText("支付宝授权信息为空"))
        return unquoteJsonString(decoded).takeIf { it.isNotBlank() }
            ?: throw DoorApiException(rawText("支付宝授权信息为空"))
    }

    private fun unquoteJsonString(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("\"")) return trimmed
        return runCatching { JSONArray("[$trimmed]").getString(0) }
            .getOrDefault(trimmed.trim('"'))
    }

    private fun oauthLogin(authCode: String, oauthType: String): Any {
        // OAuth 登录：GET + base64 编码的嵌套参数（原 app 的 beforeRequest 逻辑）。
        val systemInfo = JSONObject().apply {
            put("appVersion", "1.0.0")
            put("systemType", "android")
            put("systemVersion", android.os.Build.VERSION.RELEASE)
            put("deviceModel", android.os.Build.MODEL)
            put("deviceToken", "")
        }
        val authInfo = JSONObject().apply {
            put("auth_code", authCode)
            put("oauth_type", oauthType)
            put("sign_type", OAUTH_SIGN_TYPE)
        }
        val b64Sys = base64UrlEncode(systemInfo.toString())
        val b64Auth = base64UrlEncode(authInfo.toString())
        val result = authGet(
            path = "/webapi/oauth/login",
            params = mutableMapOf(
                "base64_systemInfo" to b64Sys,
                "base64_authInfo" to b64Auth,
                "app_version" to "1.0.0"
            ),
            nonceLength = AUTH_NONCE_LENGTH
        )
        return result
    }

    private fun base64UrlEncode(data: String): String {
        return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')
    }

    private fun buildSnapshotFromLogin(
        loginData: Any,
        phone: String,
        password: String
    ): DoorCredentialSnapshot {
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

    fun fetchLoginCaptchaSvg(phone: String): String {
        var lastError: Throwable? = null
        repeat(CAPTCHA_FETCH_ATTEMPTS) { attempt ->
            try {
                return fetchLoginCaptchaSvgOnce(phone)
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < CAPTCHA_FETCH_ATTEMPTS) {
                    Thread.sleep(CAPTCHA_RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: captchaError()
    }

    private fun fetchLoginCaptchaSvgOnce(phone: String): String {
        val body = authGetRaw(
            path = "/webapi/users/get_login_code",
            params = mutableMapOf("phone" to phone),
            nonceLength = AUTH_NONCE_LENGTH
        )

        val outer = parseJsonObject(body) ?: throw captchaError()
        if (!isSuccess(outer)) {
            val serverMessage = extractServerMessage(outer)
            throw DoorApiException(
                text = serverMessage?.let { rawText(it) } ?: rawText("服务器返回失败"),
                serverMessage = serverMessage
            )
        }

        // data 缺失 / base64 不合法 / 内层非 JSON / 无 svg —— 都归为同一类「取不到验证码」
        val decoded = decodeBase64Text(outer.optString("data"))?.trim() ?: throw captchaError()
        val inner = parseJsonObject(decoded) ?: throw captchaError()
        return extractSvgSnippet(inner.optString("codeImg")) ?: throw captchaError()
    }

    private fun captchaError() = DoorApiException(rawText("验证码获取失败"))

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

    /**
     * OAuth 登录专用：嵌套参数 GET（axios 序列化格式）。
     * 把 {systemInfo:{appVersion:xxx}, authInfo:{auth_code:xxx}} 展开成
     * systemInfo[appVersion]=xxx&authInfo[auth_code]=xxx 的 query string。
     */

    private fun authGetRaw(path: String, params: MutableMap<String, String>, nonceLength: Int): String {
        val signedParams = signParams(
            params = params,
            secret = AUTH_SIGN_SECRET,
            nonceLength = nonceLength
        )
        return performGetRaw(
            baseUrl = normalizeServerUrl(authServerUrl),
            path = path,
            params = signedParams
        )
    }

    private fun login(phone: String, password: String, code: String?): Any {
        val params = mutableMapOf(
            "phone" to phone,
            "pwd" to password
        )
        if (!code.isNullOrBlank()) {
            params["code"] = code
        }

        return try {
            authGet(
                path = "/webapi/users/login",
                params = params,
                nonceLength = AUTH_NONCE_LENGTH
            )
        } catch (exception: DoorApiException) {
            if (exception.serverMessage?.let(::isCaptchaRelatedMessage) != true) {
                throw exception
            }
            throw DoorCaptchaRequiredException(serverMessage = exception.serverMessage)
        }
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


    private fun performGetRaw(baseUrl: String, path: String, params: Map<String, String>): String {
        val query = encodeParams(params)
        val connection = openConnection(baseUrl, "$path?$query")
        try {
            connection.requestMethod = "GET"
            return readBody(connection)
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
        val body = readBody(connection, statusCode)
        if (body.isBlank()) {
            throw DoorApiException(rawText("服务器返回空响应 (HTTP %1\$s)", statusCode))
        }

        val root = try {
            JSONObject(body)
        } catch (exception: Exception) {
            throw DoorApiException(rawText("服务器返回非法 JSON: %1\$s", body))
        }

        if (!isSuccess(root)) {
            val serverMessage = extractServerMessage(root)
            val nonBlankServerMessage = serverMessage?.takeIf { it.isNotBlank() }
            throw DoorApiException(
                nonBlankServerMessage
                    ?.let { rawText(it) }
                    ?: rawText("服务器返回失败: HTTP %1\$s", statusCode),
                serverMessage = nonBlankServerMessage
            )
        }

        if (!root.has("data")) {
            return root
        }

        return decodeData(root.get("data"))
    }

    private fun readBody(connection: HttpURLConnection, resolvedStatusCode: Int? = null): String {
        val statusCode = resolvedStatusCode ?: connection.responseCode
        return (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            ?.readAllText()
            .orEmpty()
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

        val decodedText = decodeBase64Text(trimmed) ?: return trimmed
        val normalized = decodedText.trim()
        return when {
            normalized.startsWith("{") -> JSONObject(normalized)
            normalized.startsWith("[") -> JSONArray(normalized)
            else -> normalized
        }
    }

    private fun extractSvgSnippet(text: String): String? {
        val normalized = text.trim()
        val start = normalized.indexOf("<svg", ignoreCase = true)
        if (start < 0) return null

        val endToken = "</svg>"
        val end = normalized.lowercase().lastIndexOf(endToken)
        if (end <= start) return null
        return normalized.substring(start, end + endToken.length)
    }

    private fun decodeBase64Text(value: String): String? {
        val trimmed = value.trim()
        val candidates = linkedSetOf(
            trimmed,
            trimmed.replace('-', '+').replace('_', '/')
        )

        for (candidate in candidates) {
            val padded = when (candidate.length % 4) {
                2 -> "$candidate=="
                3 -> "$candidate="
                else -> candidate
            }
            runCatching {
                return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
            }
        }
        return null
    }

    private fun parseJsonObject(value: String): JSONObject? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun extractServerMessage(root: JSONObject): String? {
        return root.optString("err_msg")
            .ifBlank { root.optString("msg") }
            .ifBlank { root.optString("message") }
            .takeIf { it.isNotBlank() }
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

        // === 支付宝 OAuth（IAlixPay AIDL 拉起支付宝 app，无 SDK，见 DoorAlipayAuth）===
        private const val OAUTH_TYPE_ALIPAY = "alipay_app"
        private const val OAUTH_SIGN_TYPE = "RSA"
        private const val AUTH_NONCE_LENGTH = 32
        private const val BUSINESS_NONCE_LENGTH = 16
        private const val CAPTCHA_FETCH_ATTEMPTS = 2
        private const val CAPTCHA_RETRY_DELAY_MS = 200L
        private val NONCE_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val random = SecureRandom()
        private val CAPTCHA_MARKERS = listOf(
            "本次登录需要进行验证",
            "CAPTCHA_REQUIRED",
            "验证码输入错误"
        )

        fun normalizeServerUrl(raw: String): String {
            return raw.trim().trimEnd('/')
        }

        private fun isCaptchaRelatedMessage(message: String): Boolean {
            return CAPTCHA_MARKERS.any { marker -> message.contains(marker, ignoreCase = true) }
        }
    }
}

open class DoorApiException(
    private val text: TextValue,
    val serverMessage: String? = null,
    cause: Throwable? = null
) : LocalizedIOException(text, cause)

class DoorCaptchaRequiredException(
    serverMessage: String?
) : DoorApiException(
    text = rawText(serverMessage?.takeIf { it.isNotBlank() } ?: "本次登录需要进行验证"),
    serverMessage = serverMessage
)
