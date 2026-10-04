package com.nkyuu.dooropener

enum class PasswordResetIssue { Phone, Code, Password, Confirmation }

object PasswordResetRules {
    fun isValidPhone(phone: String): Boolean = phone.matches(Regex("^1[3-9][0-9]{9}$"))

    fun validate(phone: String, code: String, password: String, confirmation: String): PasswordResetIssue? {
        return when {
            !isValidPhone(phone) -> PasswordResetIssue.Phone
            !code.matches(Regex("^[0-9]{4,8}$")) -> PasswordResetIssue.Code
            !password.matches(Regex("^[0-9]{6}$")) -> PasswordResetIssue.Password
            confirmation != password -> PasswordResetIssue.Confirmation
            else -> null
        }
    }
}

class PasswordResetCooldown {
    private val sentAt = mutableMapOf<String, Long>()

    fun recordSent(phone: String, nowMillis: Long) { sentAt[phone] = nowMillis }

    fun remainingSeconds(phone: String, nowMillis: Long): Long {
        val sent = sentAt[phone] ?: return 0
        val remaining = (60_000L - (nowMillis - sent)).coerceIn(0L, 60_000L)
        return (remaining + 999L) / 1000L
    }
}
