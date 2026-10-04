package com.nkyuu.dooropener

import java.util.concurrent.Executor

enum class PasswordResetState { Idle, Sending, Resetting, Complete }

/** UI-thread state retained across Activity recreation; workers never hold an Activity. */
class PasswordResetSession(
    private val worker: Executor,
    private val completion: Executor,
    private val sendCode: (String) -> Unit,
    private val resetPassword: (String, String, String) -> Unit,
    private val onCodeSent: (String) -> Unit
) {
    var phone = ""
    var code = ""
    var password = ""
    var confirmation = ""
    var state = PasswordResetState.Idle
        private set
    var error: Exception? = null
        private set
    var completedPhone = ""
        private set
    var completedPassword = ""
        private set
    var codeSentNotice = false
    private var observer: (() -> Unit)? = null

    fun attach(listener: () -> Unit) { observer = listener; listener() }
    fun detach(listener: () -> Unit) { if (observer === listener) observer = null }

    fun send(number: String) {
        if (state != PasswordResetState.Idle) return
        state = PasswordResetState.Sending
        error = null
        observer?.invoke()
        worker.execute {
            val failure = try { sendCode(number); onCodeSent(number); null }
                catch (exception: Exception) { exception }
            completion.execute {
                state = PasswordResetState.Idle
                error = failure
                codeSentNotice = failure == null
                observer?.invoke()
            }
        }
    }

    fun submit(number: String, smsCode: String, newPassword: String) {
        if (state != PasswordResetState.Idle) return
        state = PasswordResetState.Resetting
        error = null
        observer?.invoke()
        worker.execute {
            val failure = try { resetPassword(number, smsCode, newPassword); null }
                catch (exception: Exception) { exception }
            completion.execute {
                error = failure
                if (failure == null) {
                    completedPhone = number
                    completedPassword = newPassword
                    state = PasswordResetState.Complete
                } else state = PasswordResetState.Idle
                observer?.invoke()
            }
        }
    }
}
