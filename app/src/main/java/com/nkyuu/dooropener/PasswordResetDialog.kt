package com.nkyuu.dooropener

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executor

/** Account recovery stays separate from the stored offline door credential. */
class PasswordResetDialog(
    private val activity: Activity,
    private val session: PasswordResetSession,
    private val onComplete: (phone: String, newPassword: String) -> Unit,
    private val onDismiss: () -> Unit
) {
    private val root = LayoutInflater.from(activity).inflate(R.layout.dialog_password_reset, null)
    private val phone: EditText = root.findViewById(R.id.reset_phone_input)
    private val code: EditText = root.findViewById(R.id.reset_code_input)
    private val password: EditText = root.findViewById(R.id.reset_password_input)
    private val confirmation: EditText = root.findViewById(R.id.reset_confirmation_input)
    private val send: Button = root.findViewById(R.id.reset_send_button)
    private val submit: Button = root.findViewById(R.id.reset_submit_button)
    private val cancel: Button = root.findViewById(R.id.reset_cancel_button)
    private val error: TextView = root.findViewById(R.id.reset_error_message)
    private val dialog = AlertDialog.Builder(activity).setView(root).create()
    private val handler = Handler(Looper.getMainLooper())
    private val cooldown = PasswordResetCooldown()
    private val smsPrefs = activity.getSharedPreferences("password_reset_sms", Context.MODE_PRIVATE)
    private var closed = false
    private val loading get() = session.state == PasswordResetState.Sending || session.state == PasswordResetState.Resetting
    private val observer: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            if (closed) return
            updateSendButton()
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        bind(phone, session.phone) { session.phone = it; updateSendButton() }
        bind(code, session.code) { session.code = it }
        bind(password, session.password) { session.password = it }
        bind(confirmation, session.confirmation) { session.confirmation = it }
        send.setOnClickListener { sendCode() }
        submit.setOnClickListener { resetPassword() }
        cancel.setOnClickListener { dismiss() }
        dialog.setOnDismissListener {
            closed = true
            session.detach(observer)
            handler.removeCallbacks(ticker)
            if (!activity.isChangingConfigurations) {
                session.code = ""
                session.password = ""
                session.confirmation = ""
            }
            onDismiss()
        }
    }

    private fun bind(input: EditText, value: String, changed: (String) -> Unit) {
        input.isSaveEnabled = false
        input.setText(value)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    @Suppress("DEPRECATION")
    fun show() {
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        session.attach(observer)
        if (!closed) handler.post(ticker)
    }

    fun dismiss() { dialog.dismiss() }

    private fun remainingSeconds(number: String): Long {
        val saved = smsPrefs.getLong("sent_at_$number", 0L)
        if (saved > 0L) cooldown.recordSent(number, saved)
        return cooldown.remainingSeconds(number, System.currentTimeMillis())
    }

    private fun updateSendButton() {
        val remaining = remainingSeconds(phone.text.toString().trim())
        send.isEnabled = !loading && remaining == 0L
        send.text = when {
            session.state == PasswordResetState.Sending -> activity.getString(R.string.reset_sending)
            remaining > 0L -> activity.getString(R.string.reset_resend_seconds, remaining)
            else -> activity.getString(R.string.reset_send_code)
        }
    }

    private fun render() {
        if (closed || activity.isDestroyed) return
        if (session.state == PasswordResetState.Complete) {
            Toast.makeText(activity, R.string.reset_success, Toast.LENGTH_LONG).show()
            onComplete(session.completedPhone, session.completedPassword)
            dismiss()
            return
        }
        listOf(phone, code, password, confirmation).forEach { it.isEnabled = !loading }
        submit.isEnabled = !loading
        cancel.isEnabled = !loading
        submit.setText(if (session.state == PasswordResetState.Resetting) R.string.reset_submitting else R.string.reset_submit)
        dialog.setCancelable(!loading)
        dialog.setCanceledOnTouchOutside(!loading)
        updateSendButton()
        error.visibility = if (session.error == null) View.GONE else View.VISIBLE
        session.error?.let { error.text = it.resolveMessage(activity) }
        if (session.codeSentNotice) {
            session.codeSentNotice = false
            code.requestFocus()
            Toast.makeText(activity, R.string.reset_code_sent, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showIssue(issue: PasswordResetIssue) {
        error.setText(when (issue) {
            PasswordResetIssue.Phone -> R.string.reset_invalid_phone
            PasswordResetIssue.Code -> R.string.reset_invalid_code
            PasswordResetIssue.Password -> R.string.reset_invalid_password
            PasswordResetIssue.Confirmation -> R.string.reset_password_mismatch
        })
        error.visibility = View.VISIBLE
    }

    private fun sendCode() {
        if (loading) return
        val number = phone.text.toString().trim()
        if (!PasswordResetRules.isValidPhone(number)) {
            showIssue(PasswordResetIssue.Phone)
            return
        }
        if (remainingSeconds(number) > 0L) return
        session.send(number)
    }

    private fun resetPassword() {
        if (loading) return
        val number = phone.text.toString().trim()
        val smsCode = code.text.toString().trim()
        val newPassword = password.text.toString()
        val issue = PasswordResetRules.validate(number, smsCode, newPassword, confirmation.text.toString())
        if (issue != null) { showIssue(issue); return }
        session.submit(number, smsCode, newPassword)
    }

    companion object {
        fun createSession(context: Context, initialPhone: String): PasswordResetSession {
            val prefs = context.applicationContext.getSharedPreferences("password_reset_sms", Context.MODE_PRIVATE)
            val main = Handler(Looper.getMainLooper())
            return PasswordResetSession(
                Executor { Thread(it).start() }, Executor { main.post(it) },
                { DoorApi().sendPasswordResetCode(it) },
                { phone, code, password -> DoorApi().resetPassword(phone, code, password) },
                { prefs.edit().putLong("sent_at_$it", System.currentTimeMillis()).apply() }
            ).also { it.phone = initialPhone }
        }
    }
}
