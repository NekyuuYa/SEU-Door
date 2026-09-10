package com.nkyuu.dooropener

import android.util.Base64
import android.util.Log
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
        return logSyncFailureIfAny(phone) {
            buildSnapshotFromLogin(login(phone, password, code), phone = phone, password = password)
        }
    }

    /**
     * 仅用缓存的 sessionSecret 拉取最新凭证，不重新登录。
     * 如果 sessionSecret 已过期（业务请求返回鉴权失败），抛出异常由调用方回落到完整登录流程。
     */
    fun refreshCredentialOnly(snapshot: DoorCredentialSnapshot): DoorCredentialSnapshot {
        val keyList = retryBusinessGetOnce(
            baseUrl = snapshot.serverUrl,
            sessionSecret = snapshot.sessionSecret,
            projectId = snapshot.projectId,
            appId = snapshot.appId,
            path = "/webapi/v1/staff/credentials",
            params = mutableMapOf(
                "user_id" to snapshot.userId,
                "identitycode" to snapshot.identityCode
            )
        )
        val keyRecord = firstRecord(keyList)
        val keyCredential = normalizeCredentialHex(
            findString(keyRecord ?: keyList, "credential", "chain_key", "chainKey")
        )
        if (keyCredential != null) {
            val keyDeviceId = findPositiveInt(keyRecord ?: keyList, "device_id", "deviceId")
                ?: snapshot.deviceId
            val keyCredentialId = findString(keyRecord ?: keyList, "credential_id", "credentialId", "id")
                ?: snapshot.credentialId
            DoorOfflineLog.append(
                "AUTH",
                "staff credentials refresh device=$keyDeviceId credential=true"
            )
            return snapshot.copy(
                deviceId = keyDeviceId,
                credentialId = keyCredentialId,
                credentialHex = keyCredential,
                updatedAt = System.currentTimeMillis()
            )
        }

        val detailData = businessGet(
            baseUrl = snapshot.serverUrl,
            sessionSecret = snapshot.sessionSecret,
            projectId = snapshot.projectId,
            appId = snapshot.appId,
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
            val credentialData = retryBusinessGetOnce(
                baseUrl = snapshot.serverUrl,
                sessionSecret = snapshot.sessionSecret,
                projectId = snapshot.projectId,
                appId = snapshot.appId,
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

        val normalizedCredential = normalizeCredentialHex(credential)
            ?: snapshot.credentialHex.takeIf { it.isNotBlank() }
            ?: ""

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
        DoorOfflineLog.append("AUTH", "OAuth response fields=${describeObjectKeys(loginData)}")
        val phone = findString(loginData, "phone").orEmpty()
        return logSyncFailureIfAny(phone) {
            buildSnapshotFromLogin(loginData, phone = phone, password = "")
        }
    }

    /**
     * 同步链路的任何失败都写离线日志（此前 JSONException 只进 UI，故障现场不可见）。
     * 记录后原样重抛，不影响调用方的既有错误处理。
     */
    private inline fun <T> logSyncFailureIfAny(phone: String, block: () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            DoorOfflineLog.append(
                "AUTH",
                "sync failed phone=${phone.take(4)}**** ${e.javaClass.simpleName}: ${e.message.orEmpty().take(300)}"
            )
            throw e
        }
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
        // Match the original H5 bridge fields exactly. Its Android bridge leaves deviceToken blank.
        val systemInfo = JSONObject().apply {
            put("appVersion", "3.11.51")
            put("systemType", "Android")
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
                "app_version" to "3.11.51"
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
        val serverInfo = findChildObject(loginData, "server_info")
        serverInfo?.let { logProjectMetadata("server_info", it) }
        val serverProjectId = findPositiveInt(serverInfo ?: loginData, "server_appid", "project_id", "projectId")
        val serverAppId = findPositiveInt(serverInfo ?: loginData, "server_id", "app_id", "appId")
        var projectId = serverProjectId
            ?: PROJECT_ID
        var appId = serverAppId
            ?: APP_ID

        val detailData = retryBusinessGetOnce(
            baseUrl = serverAddr,
            sessionSecret = sessionSecret,
            projectId = projectId,
            appId = appId,
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

        // The official H5 obtains door-lock records from this endpoint. Its first record holds
        // the server-issued local chain key on a fresh installation.
        var keyListData: Any? = null
        if (deviceId.isNullOrBlank() || credential.isNullOrBlank()) {
            keyListData = retryBusinessGetOnce(
                baseUrl = serverAddr,
                sessionSecret = sessionSecret,
                projectId = projectId,
                appId = appId,
                path = "/webapi/v1/staff/credentials",
                params = mutableMapOf(
                    "user_id" to userId,
                    "identitycode" to identityCode
                )
            )
            val keyRecord = firstRecord(keyListData)
            deviceId = deviceId ?: findString(keyRecord ?: keyListData, "device_id", "deviceId")
            credentialId = credentialId ?: findString(keyRecord ?: keyListData, "credential_id", "credentialId", "id")
            credential = credential ?: findString(keyRecord ?: keyListData, "credential", "chain_key", "chainKey")
            DoorOfflineLog.append(
                "AUTH",
                "staff credentials device=${deviceId.orEmpty()} credential=${normalizeCredentialHex(credential) != null}"
            )
        }

        // The current service separates accommodation and lock records. Older deployments
        // embedded door_lock directly in accommodation/details, so keep that path first.
        var credentialData: Any? = null
        if (deviceId.isNullOrBlank()) {
            val roomId = findString(detailData, "room_id", "roomId")
            if (!roomId.isNullOrBlank()) {
                val lockList = businessGet(
                    baseUrl = serverAddr,
                    sessionSecret = sessionSecret,
                    projectId = projectId,
                    appId = appId,
                    path = DOOR_LOCK_LIST_PATH,
                    params = mutableMapOf(
                        "room_id" to roomId,
                        "user_id" to userId,
                        "identitycode" to identityCode
                    )
                )
                val lock = firstRow(lockList)
                deviceId = findString(lock ?: lockList, "device_id", "deviceId")
                credentialId = credentialId ?: findString(lock ?: lockList, "credential_id", "credentialId")
                credential = credential ?: findString(lock ?: lockList, "credential", "chain_key", "chainKey")
            }
        }

        // Some deployments return an empty room lock list but allow listing this account's
        // door-lock credentials without a device_id. Those rows carry the assigned device id.
        if (deviceId.isNullOrBlank()) {
            credentialData = fetchDoorLockCredentials(
                baseUrl = serverAddr,
                sessionSecret = sessionSecret,
                deviceId = null,
                userId = userId,
                identityCode = identityCode,
                projectId = projectId,
                appId = appId
            )
            deviceId = findString(credentialData, "device_id", "deviceId")
            credentialId = credentialId ?: extractCredentialId(credentialData)
            credential = credential ?: findString(credentialData, "credential", "chain_key", "chainKey")
        }

        if (deviceId.isNullOrBlank()) {
            throw DoorApiException(rawText("服务器返回中缺少 device_id"))
        }

        if (credential.isNullOrBlank()) {
            val data = credentialData ?: fetchDoorLockCredentials(
                baseUrl = serverAddr,
                sessionSecret = sessionSecret,
                deviceId = deviceId,
                userId = userId,
                identityCode = identityCode,
                projectId = projectId,
                appId = appId
            )
            if (credentialId.isNullOrBlank()) {
                credentialId = extractCredentialId(data)
            }
            credential = findString(data, "credential", "chain_key", "chainKey")
        }

        val deviceIdInt = deviceId.toIntOrNull()
            ?: throw DoorApiException(rawText("device_id 不是合法整数: $deviceId"))

        // OAuth login responses from older API deployments omit server_info. The original H5
        // resolves that case through the platform's device-to-project lookup.
        if (serverProjectId == null || serverAppId == null) {
            runCatching { fetchProjectByDeviceId(deviceIdInt) }
                .onSuccess { projectInfo ->
                    logProjectMetadata("project_lookup", projectInfo)
                    findPositiveInt(projectInfo, "server_appid", "project_id", "projectId")?.let {
                        projectId = it
                    }
                    findPositiveInt(projectInfo, "server_id", "app_id", "appId")?.let {
                        appId = it
                    }
                    DoorOfflineLog.append("AUTH", "project lookup device=$deviceIdInt project=$projectId app=$appId")
                }
                .onFailure { error ->
                    DoorOfflineLog.append("AUTH", "project lookup failed ${error.javaClass.simpleName}")
                }
        }
        // Newer servers return credential-record metadata here. The offline chain key arrives
        // only after the NFC activation exchange and is intentionally blank until then.
        val normalizedCredential = normalizeCredentialHex(credential).orEmpty()

        return DoorCredentialSnapshot(
            serverUrl = normalizeServerUrl(serverAddr),
            phone = phone,
            password = password,
            userId = userId,
            identityCode = identityCode,
            projectId = projectId,
            appId = appId,
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

    fun startNfcActivation(snapshot: DoorCredentialSnapshot, deviceId: Int): DoorNfcActivationStep {
        return startDigitalCredentialActivation(snapshot, deviceId, "nfc", "1")
    }

    /** Starts the original H5 Bluetooth digital-key activation exchange. */
    fun startBleActivation(snapshot: DoorCredentialSnapshot, deviceId: Int): DoorNfcActivationStep {
        return startDigitalCredentialActivation(snapshot, deviceId, "ble", "112")
    }

    private fun startDigitalCredentialActivation(
        snapshot: DoorCredentialSnapshot,
        deviceId: Int,
        transport: String,
        command: String
    ): DoorNfcActivationStep {
        val credentialId = findOrCreateDigitalCredential(snapshot, deviceId)
        val data = businessGet(
            baseUrl = snapshot.serverUrl,
            sessionSecret = snapshot.sessionSecret,
            projectId = snapshot.projectId,
            appId = snapshot.appId,
            path = "/webapi/v1/door_lock/command/create",
            params = mutableMapOf(
                "device_id" to deviceId.toString(),
                "command" to command,
                "type" to transport,
                "credential_id" to credentialId,
                "user_id" to snapshot.userId,
                "identitycode" to snapshot.identityCode
            )
        )
        return parseNfcActivationStep(data, credentialId)
    }

    fun submitNfcActivationResponses(
        snapshot: DoorCredentialSnapshot,
        step: DoorNfcActivationStep,
        responses: List<String>
    ): DoorNfcActivationStep {
        return submitDigitalCredentialActivationResponses(snapshot, step, responses, "nfc")
    }

    /** Submits one complete round of raw Bluetooth responses and returns the next server step. */
    fun submitBleActivationResponses(
        snapshot: DoorCredentialSnapshot,
        step: DoorNfcActivationStep,
        responses: List<String>
    ): DoorNfcActivationStep {
        return submitDigitalCredentialActivationResponses(snapshot, step, responses, "ble")
    }

    private fun submitDigitalCredentialActivationResponses(
        snapshot: DoorCredentialSnapshot,
        step: DoorNfcActivationStep,
        responses: List<String>,
        transport: String
    ): DoorNfcActivationStep {
        if (responses.isEmpty()) {
            throw DoorApiException(rawText("门锁未返回激活响应"))
        }
        val params = step.requestData.toBusinessParams().apply {
            put("payload", responses.joinToString(","))
            put("type", transport)
            put("user_id", snapshot.userId)
            put("identitycode", snapshot.identityCode)
        }
        val data = businessPost(
            baseUrl = snapshot.serverUrl,
            sessionSecret = snapshot.sessionSecret,
            projectId = snapshot.projectId,
            appId = snapshot.appId,
            path = "/webapi/v1/door_lock/command/parse",
            params = params
        )
        return parseNfcActivationStep(data, step.credentialId)
    }

    private fun findOrCreateDigitalCredential(snapshot: DoorCredentialSnapshot, deviceId: Int): String {
        if (snapshot.credentialId.isNotBlank()) return snapshot.credentialId

        val credentials = fetchDoorLockCredentials(
            baseUrl = snapshot.serverUrl,
            sessionSecret = snapshot.sessionSecret,
            deviceId = deviceId.toString(),
            userId = snapshot.userId,
            identityCode = snapshot.identityCode,
            projectId = snapshot.projectId,
            appId = snapshot.appId
        )
        extractDigitalCredentialId(credentials)?.let { return it }

        val created = businessPost(
            baseUrl = snapshot.serverUrl,
            sessionSecret = snapshot.sessionSecret,
            projectId = snapshot.projectId,
            appId = snapshot.appId,
            path = "/webapi/v1/door_lock/credential/create",
            params = mutableMapOf(
                "device_id" to deviceId.toString(),
                "type" to "3",
                "value" to random.nextInt(1_000_000).toString().padStart(6, '0'),
                "user_id" to snapshot.userId,
                "identitycode" to snapshot.identityCode
            )
        )
        return extractCredentialId(created)
            ?: throw DoorApiException(rawText("服务器未返回数字钥匙 ID"))
    }

    private fun parseNfcActivationStep(data: Any, fallbackCredentialId: String): DoorNfcActivationStep {
        val root = data as? JSONObject
            ?: throw DoorApiException(rawText("服务器未返回合法的激活数据"))
        val payload = root.optString("payload")
        val packets = if (payload.isBlank()) {
            emptyList()
        } else {
            payload.split(',').map { it.trim() }.filter { it.isNotEmpty() }.onEach { packet ->
                if (!packet.matches(Regex("^[0-9A-F]+$", RegexOption.IGNORE_CASE)) || packet.length % 2 != 0) {
                    throw DoorApiException(rawText("服务器返回的激活数据格式无效"))
                }
            }
        }
        val credentialHex = normalizeCredentialHex(
            findString(root, "code", "credential", "chain_key", "chainKey", "secret_key")
        )
        val credentialId = findString(root, "credential_id", "credentialId", "id")
            ?: fallbackCredentialId
        return DoorNfcActivationStep(root, packets, credentialId, credentialHex)
    }

    private fun authGet(path: String, params: MutableMap<String, String>, nonceLength: Int): Any {
        val signedParams = signParams(
            params = params,
            secret = AUTH_SIGN_SECRET,
            nonceLength = nonceLength,
            projectId = null,
            appId = null
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
            nonceLength = nonceLength,
            projectId = null,
            appId = null
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
        projectId: Int = PROJECT_ID,
        appId: Int = APP_ID,
        path: String,
        params: MutableMap<String, String>
    ): Any {
        val signedParams = signParams(
            params = params,
            secret = sessionSecret,
            nonceLength = BUSINESS_NONCE_LENGTH,
            projectId = projectId,
            appId = appId
        )
        return performGet(
            baseUrl = normalizeServerUrl(baseUrl),
            path = path,
            params = signedParams
        )
    }

    /**
     * 登录主链路的业务请求：脏数据/网络抖动时重试一次。
     * 服务器存在多副本不一致时（同一接口时好时坏），第二次请求有机会落到好副本。
     * 只有解析/读取失败才重试；服务器明确返回 result=false（如 login_timeout）不重试。
     */
    private fun retryBusinessGetOnce(
        baseUrl: String,
        sessionSecret: String,
        projectId: Int = PROJECT_ID,
        appId: Int = APP_ID,
        path: String,
        params: MutableMap<String, String>
    ): Any {
        return try {
            businessGet(baseUrl, sessionSecret, projectId, appId, path, params)
        } catch (first: Exception) {
            val serverRejected = first is DoorApiException && first.serverMessage != null
            if (serverRejected) throw first
            DoorOfflineLog.append(
                "API",
                "business get failed, retrying once path=$path ${first.javaClass.simpleName}: ${first.message.orEmpty().take(200)}"
            )
            businessGet(baseUrl, sessionSecret, projectId, appId, path, params)
        }
    }

    private fun businessPost(
        baseUrl: String,
        sessionSecret: String,
        projectId: Int = PROJECT_ID,
        appId: Int = APP_ID,
        path: String,
        params: MutableMap<String, String>
    ): Any {
        val signedParams = signParams(
            params = params,
            secret = sessionSecret,
            nonceLength = BUSINESS_NONCE_LENGTH,
            projectId = projectId,
            appId = appId
        )
        return performPost(
            baseUrl = normalizeServerUrl(baseUrl),
            path = path,
            params = signedParams
        )
    }

    private fun fetchDoorLockCredentials(
        baseUrl: String,
        sessionSecret: String,
        deviceId: String?,
        userId: String,
        identityCode: String,
        projectId: Int,
        appId: Int
    ): Any {
        val params = mutableMapOf<String, String>().apply {
            deviceId?.takeIf { it.isNotBlank() }?.let { put("device_id", it) }
            put("user_id", userId)
            put("identitycode", identityCode)
        }
        return businessGet(
            baseUrl = baseUrl,
            sessionSecret = sessionSecret,
            projectId = projectId,
            appId = appId,
            path = "/webapi/v1/staff/door_lock/credentials",
            params = params
        )
    }

    private fun fetchProjectByDeviceId(deviceId: Int): Any {
        return authGet(
            path = "/webapi/project/get_by_device_id",
            params = mutableMapOf("device_id" to deviceId.toString()),
            nonceLength = AUTH_NONCE_LENGTH
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

    private fun performPost(baseUrl: String, path: String, params: Map<String, String>): Any {
        val body = encodeParams(params).toByteArray(Charsets.UTF_8)
        val connection = openConnection(baseUrl, path)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body) }
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
        nonceLength: Int,
        projectId: Int? = PROJECT_ID,
        appId: Int? = APP_ID
    ): Map<String, String> {
        projectId?.let { params["pid"] = it.toString() }
        appId?.let { params["appid"] = it.toString() }
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
            DoorOfflineLog.append("API", "HTTP $statusCode ${connection.url.path} empty response")
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
            val failureLog = "HTTP $statusCode ${connection.url.path} result=false " +
                "err_msg=${nonBlankServerMessage.orEmpty().replace('\n', ' ').take(256)} " +
                "data=${summarizeEnvelopeData(root)}"
            DoorOfflineLog.append("API", failureLog)
            Log.w(
                "DoorApi",
                failureLog
            )
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

    private fun summarizeEnvelopeData(root: JSONObject): String {
        if (!root.has("data") || root.isNull("data")) return "null"
        return when (val data = root.opt("data")) {
            is JSONObject -> {
                val keys = data.keys().asSequence().toList().sorted().joinToString(",")
                "object($keys)"
            }

            is JSONArray -> "array(${data.length()})"
            is String -> "string(${data.length})"
            else -> data.javaClass.simpleName
        }
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
            return parseJsonOrSalvage(trimmed, isObject = true)
        }
        if (trimmed.startsWith("[")) {
            return parseJsonOrSalvage(trimmed, isObject = false)
        }

        val decodedText = decodeBase64Text(trimmed) ?: return trimmed
        val normalized = decodedText.trim()
        return when {
            normalized.startsWith("{") -> parseJsonOrSalvage(normalized, isObject = true)
            normalized.startsWith("[") -> parseJsonOrSalvage(normalized, isObject = false)
            else -> normalized
        }
    }

    /**
     * 服务器偶发返回含脏字节的 JSON（历史故障：accommodation.name 值里嵌入裸引号 +
     * 二进制垃圾，JSONObject 在 ~char 294 抛 Unterminated object，炸穿整个登录流程）。
     * 严格解析失败时降级为 RawJsonText：findString 等提取器改用正则从原始文本抢救
     * 业务所需字段（device_id / credential 等都有强格式校验，不会误用脏值）。
     */
    private fun parseJsonOrSalvage(text: String, isObject: Boolean): Any {
        val parsed = runCatching {
            if (isObject) JSONObject(text) else JSONArray(text)
        }.getOrNull()
        if (parsed != null) return parsed
        DoorOfflineLog.append(
            "API",
            "json parse failed, falling back to salvage extraction len=${text.length}"
        )
        return RawJsonText(text)
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

    private fun findPositiveInt(node: Any, vararg keys: String): Int? {
        return findString(node, *keys)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun describeObjectKeys(node: Any): String {
        return when (node) {
            is JSONObject -> node.keys().asSequence().toList().sorted().joinToString(",")
            is JSONArray -> "array(${node.length()})"
            else -> node.javaClass.simpleName
        }
    }

    private fun logProjectMetadata(source: String, node: Any) {
        val ids = listOf("server_appid", "server_id", "project_id", "app_id", "id")
            .joinToString(",") { key -> "$key=${findString(node, key).orEmpty()}" }
        DoorOfflineLog.append("AUTH", "$source fields=${describeObjectKeys(node)} ids=$ids")
    }

    private fun firstRow(node: Any): JSONObject? {
        if (node is RawJsonText) return null
        return (node as? JSONObject)
            ?.optJSONArray("rows")
            ?.optJSONObject(0)
    }

    private fun firstRecord(node: Any): JSONObject? {
        if (node is RawJsonText) return null
        return when (node) {
            is JSONArray -> node.optJSONObject(0)
            is JSONObject -> node.optJSONArray("rows")?.optJSONObject(0)
                ?: node.optJSONArray("data")?.optJSONObject(0)
                ?: node
            else -> null
        }
    }

    private fun normalizeCredentialHex(value: String?): String? {
        return value
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.matches(Regex("^[0-9A-F]{64}$")) }
    }

    private fun extractDigitalCredentialId(node: Any): String? {
        if (node is RawJsonText) {
            // 数组原文里找 type=3 的记录 id："type": 3 ... "id": N（同一记录内就近配对）
            val recordRegex = Regex("\\{[^{}]*\"type\"\\s*:\\s*3[^{}]*\\}")
            for (record in recordRegex.findAll(node.text)) {
                salvageIntField(record.value, "id")?.let { return it.toString() }
            }
            return null
        }
        val rows = (node as? JSONObject)?.optJSONArray("rows") ?: return null
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optInt("type", -1) == 3) {
                row.opt("id")?.takeIf { it != JSONObject.NULL }?.toString()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.toBusinessParams(): MutableMap<String, String> {
        val params = linkedMapOf<String, String>()
        for (key in keys()) {
            val value = opt(key)
            if (value != null && value != JSONObject.NULL) {
                params[key] = value.toString()
            }
        }
        return params
    }

    private fun extractCredentialId(node: Any): String? {
        findString(node, "credential_id", "credentialId", "id")?.takeIf { it.isNotBlank() }?.let { return it }

        if (node is RawJsonText) {
            // 与 JSONObject 路径同语义：数组内第一条记录的 id / 对象内 rows 第一条的 id
            val recordRegex = Regex("\\{[^{}]*\\}")
            for (record in recordRegex.findAll(node.text)) {
                salvageIntField(record.value, "id")?.let { return it.toString() }
            }
            return null
        }

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
        if (node is RawJsonText) return findStringInRawText(node.text, keys)
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

    /**
     * 从解析失败的 JSON 原文里按 key 顺序正则抢救字段值。
     * 支持嵌套查找（与 findString 的递归语义一致：顶层未命中时深入子对象）。
     * 值必须不含未转义的控制字符，防止把脏数据半截读进来。
     */
    private fun findStringInRawText(text: String, keys: Array<out String>): String? {
        for (key in keys) {
            salvageStringField(text, key)?.let { return it }
        }
        return null
    }

    private fun salvageStringField(text: String, key: String): String? {
        // "key" : "value" —— 值内允许 \" 转义，但不允许控制字符
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\\\x00-\\x1F]|\\\\.)*)\"")
        val match = regex.find(text) ?: return null
        val raw = match.groupValues[1]
        val unescaped = raw
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
        return unescaped.takeIf { it.isNotBlank() }
    }

    /** 从 JSON 原文里抢救整数字段："key" : 123。 */
    private fun salvageIntField(text: String, key: String): Long? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
        return regex.find(text)?.groupValues?.get(1)?.toLongOrNull()
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
        private const val BUSINESS_NONCE_LENGTH = 32
        private const val CAPTCHA_FETCH_ATTEMPTS = 2
        private const val CAPTCHA_RETRY_DELAY_MS = 200L
        private const val DOOR_LOCK_LIST_PATH = "/webapi/v1/door_lock/list"
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

/**
 * 无法严格解析的 JSON 原文。携带足够信息供提取器用正则抢救关键字段，
 * 提取结果一律过原有格式校验（64-hex / 正整数），不合法即视为缺失。
 */
class RawJsonText(val text: String) {
    override fun toString(): String = text
}

class DoorCaptchaRequiredException(
    serverMessage: String?
) : DoorApiException(
    text = rawText(serverMessage?.takeIf { it.isNotBlank() } ?: "本次登录需要进行验证"),
    serverMessage = serverMessage
)

data class DoorNfcActivationStep(
    val requestData: JSONObject,
    val packets: List<String>,
    val credentialId: String,
    val credentialHex: String?
)
