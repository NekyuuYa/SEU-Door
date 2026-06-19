package com.nkyuu.dooropener

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class DoorConfigStore(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val cipher = DoorConfigCipher()

    fun load(): DoorCredentialSnapshot? {
        val encrypted = preferences.getString(KEY_ENCRYPTED_SNAPSHOT, null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching { decodeEncryptedSnapshot(encrypted) }.getOrNull()
        }

        val legacy = loadLegacySnapshot() ?: return null
        runCatching { save(legacy) }
        clearLegacyKeys()
        return legacy
    }

    fun save(snapshot: DoorCredentialSnapshot) {
        val normalized = snapshot.copy(credentialHex = snapshot.credentialHex.uppercase())
        val json = JSONObject()
            .put(KEY_SERVER_URL, normalized.serverUrl)
            .put(KEY_AUTH_SERVER_URL, normalized.authServerUrl)
            .put(KEY_PHONE, normalized.phone)
            .put(KEY_PASSWORD, normalized.password)
            .put(KEY_USER_ID, normalized.userId)
            .put(KEY_IDENTITY_CODE, normalized.identityCode)
            .put(KEY_DEVICE_ID, normalized.deviceId)
            .put(KEY_CREDENTIAL_ID, normalized.credentialId)
            .put(KEY_BLE_MAC, normalized.bleMac)
            .put(KEY_CREDENTIAL, normalized.credentialHex)
            .put(KEY_SESSION_SECRET, normalized.sessionSecret)
            .put(KEY_UPDATED_AT, normalized.updatedAt)

        preferences.edit()
            .putString(KEY_ENCRYPTED_SNAPSHOT, cipher.encrypt(json.toString()))
            .apply()
        clearLegacyKeys()
    }

    fun updateCredential(credentialHex: String) {
        if (!credentialHex.matches(Regex("^[0-9A-F]{64}$", RegexOption.IGNORE_CASE))) {
            return
        }
        val current = load() ?: return
        save(
            current.copy(
                credentialHex = credentialHex.uppercase(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun hasUsableCredential(): Boolean {
        return load() != null
    }

    private fun decodeEncryptedSnapshot(encrypted: String): DoorCredentialSnapshot {
        val json = JSONObject(cipher.decrypt(encrypted))
        val credential = json.optString(KEY_CREDENTIAL)
            .uppercase()
            .takeIf { it.matches(Regex("^[0-9A-F]{64}$")) }
            ?: throw IllegalStateException("本地 credential 无效")

        return DoorCredentialSnapshot(
            serverUrl = json.optString(KEY_SERVER_URL, DoorApi.DEFAULT_SERVER_URL),
            authServerUrl = json.optString(KEY_AUTH_SERVER_URL, DoorApi.DEFAULT_AUTH_SERVER_URL),
            phone = json.optString(KEY_PHONE),
            password = json.optString(KEY_PASSWORD),
            userId = json.optString(KEY_USER_ID),
            identityCode = json.optString(KEY_IDENTITY_CODE),
            deviceId = json.optInt(KEY_DEVICE_ID),
            credentialId = json.optString(KEY_CREDENTIAL_ID),
            bleMac = json.optString(KEY_BLE_MAC),
            credentialHex = credential,
            sessionSecret = json.optString(KEY_SESSION_SECRET),
            updatedAt = json.optLong(KEY_UPDATED_AT)
        )
    }

    private fun loadLegacySnapshot(): DoorCredentialSnapshot? {
        val credential = preferences.getString(KEY_CREDENTIAL, null)
            ?.uppercase()
            ?.takeIf { it.matches(Regex("^[0-9A-F]{64}$")) }
            ?: return null
        val serverUrl = preferences.getString(KEY_SERVER_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DoorApi.DEFAULT_SERVER_URL

        return DoorCredentialSnapshot(
            serverUrl = serverUrl,
            authServerUrl = DoorApi.DEFAULT_AUTH_SERVER_URL,
            phone = preferences.getString(KEY_PHONE, "").orEmpty(),
            password = preferences.getString(KEY_PASSWORD, "").orEmpty(),
            userId = preferences.getString(KEY_USER_ID, "").orEmpty(),
            identityCode = preferences.getString(KEY_IDENTITY_CODE, "").orEmpty(),
            deviceId = preferences.getInt(KEY_DEVICE_ID, 0),
            credentialId = preferences.getString(KEY_CREDENTIAL_ID, "").orEmpty(),
            bleMac = preferences.getString(KEY_BLE_MAC, "").orEmpty(),
            credentialHex = credential,
            sessionSecret = "",
            updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    private fun clearLegacyKeys() {
        preferences.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_AUTH_SERVER_URL)
            .remove(KEY_PHONE)
            .remove(KEY_PASSWORD)
            .remove(KEY_USER_ID)
            .remove(KEY_IDENTITY_CODE)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_CREDENTIAL_ID)
            .remove(KEY_BLE_MAC)
            .remove(KEY_CREDENTIAL)
            .remove(KEY_SESSION_SECRET)
            .remove(KEY_UPDATED_AT)
            .apply()
    }

    companion object {
        private const val PREF_NAME = "door_credentials"
        private const val KEY_ENCRYPTED_SNAPSHOT = "encrypted_snapshot"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_SERVER_URL = "auth_server_url"
        private const val KEY_PHONE = "phone"
        private const val KEY_PASSWORD = "password"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_IDENTITY_CODE = "identity_code"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CREDENTIAL_ID = "credential_id"
        private const val KEY_BLE_MAC = "ble_mac"
        private const val KEY_CREDENTIAL = "credential_hex"
        private const val KEY_SESSION_SECRET = "session_secret"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}

data class DoorCredentialSnapshot(
    val serverUrl: String,
    val authServerUrl: String,
    val phone: String,
    val password: String,
    val userId: String,
    val identityCode: String,
    val deviceId: Int,
    val credentialId: String,
    val bleMac: String,
    val credentialHex: String,
    val sessionSecret: String,
    val updatedAt: Long
)
