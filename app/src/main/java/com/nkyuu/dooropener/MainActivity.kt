package com.nkyuu.dooropener

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private enum class DoorStatusKind {
    Idle,
    Busy,
    Success,
    Error
}

private data class StatusState(
    val title: String,
    val detail: String,
    val kind: DoorStatusKind = DoorStatusKind.Idle
)

private enum class MainTab {
    Nfc,
    Ble
}

private class MainViews(root: View) {
    val statusDetail: TextView = root.requireView(R.id.status_detail)
    val refreshButton: View = root.requireView(R.id.refresh_button)
    val settingsButton: View = root.requireView(R.id.settings_button)
    val bleOpenButton: View = root.requireView(R.id.ble_open_button)
    val bleFobButton: View = root.requireView(R.id.ble_fob_button)
    val tabNfc: View = root.requireView(R.id.tab_nfc)
    val tabBle: View = root.requireView(R.id.tab_ble)
    val tabNfcLabel: TextView = root.requireView(R.id.tab_nfc_label)
    val tabNfcIcon: ImageView = root.requireView(R.id.tab_nfc_icon)
    val tabBleLabel: TextView = root.requireView(R.id.tab_ble_label)
    val tabBleIcon: ImageView = root.requireView(R.id.tab_ble_icon)
    val tabContainer: FrameLayout = root.requireView(R.id.tab_container)
    val tabIndicator: View = root.requireView(R.id.tab_indicator)
    val statusTitle: TextView = root.requireView(R.id.status_title)
    val statusIcon: ImageView = root.requireView(R.id.status_icon)
    val statusIconContainer: View = root.requireView(R.id.status_icon_container)
}

private class ConfigDialogViews(val root: View) {
    val phoneInput: EditText = root.requireView(R.id.phone_input)
    val passwordInput: EditText = root.requireView(R.id.password_input)
    val captchaContainer: View = root.requireView(R.id.captcha_container)
    val captchaInput: EditText = root.requireView(R.id.captcha_input)
    val captchaWebView: WebView = root.requireView(R.id.captcha_webview)
    val captchaRefreshButton: View = root.requireView(R.id.captcha_refresh_button)
    val errorMessage: TextView = root.requireView(R.id.error_message)
    val cancelButton: View = root.requireView(R.id.cancel_button)
    val syncButton: View = root.requireView(R.id.sync_button)
    val alipayButton: View = root.requireView(R.id.alipay_login_button)
    val forgotPasswordButton: View = root.requireView(R.id.forgot_password_button)
}

private class FobNetworkDialogViews(val root: View) {
    val ssidInput: EditText = root.requireView(R.id.fob_ssid_input)
    val wifiPassInput: EditText = root.requireView(R.id.fob_wifi_pass_input)
    val portalUserInput: EditText = root.requireView(R.id.fob_portal_user_input)
    val portalPassInput: EditText = root.requireView(R.id.fob_portal_pass_input)
    val cancelButton: View = root.requireView(R.id.fob_cancel_button)
    val confirmButton: View = root.requireView(R.id.fob_confirm_button)
}

private fun <T : View> View.requireView(id: Int): T {
    return findViewById<T>(id) ?: throw IllegalStateException("Missing required view: $id")
}

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    companion object {
        private const val PREFS_NAME = "door_opener_ui"
        private const val PREF_LAST_TAB = "last_tab"
        private const val PREF_LAST_AUTO_REFRESH = "last_auto_refresh_attempt"
        private const val PREF_FOB_WIFI_SSID = "fob_wifi_ssid"
        private const val PREF_FOB_WIFI_PASS = "fob_wifi_pass"
        private const val PREF_FOB_PORTAL_USER = "fob_portal_user"
        private const val PREF_FOB_PORTAL_PASS = "fob_portal_pass"
        private const val REQ_BLE_PERMS = 1001

        // 凭证防过期：本地凭证超过 24h 未更新（开门轮换/刷新都会推进 updatedAt）就静默刷一次
        private const val AUTO_REFRESH_CREDENTIAL_AGE_MS = 24 * 60 * 60 * 1000L
        private const val AUTO_REFRESH_RETRY_GAP_MS = 6 * 60 * 60 * 1000L

        // 失败恢复：toggle ReaderMode 强制 NFC 栈重新评估场内标签的循环参数
        private const val RECOVERY_ROUNDS = 3
        private const val RECOVERY_TOGGLE_GAP_MS = 180L
        private const val RECOVERY_DISCOVERY_TIMEOUT_MS = 1000L

        // 单条流水线内的尝试上限：原始标签 + 恢复循环拿到的新标签
        private const val MAX_PIPELINE_ATTEMPTS = 2
    }

    private lateinit var views: MainViews
    private lateinit var prefs: SharedPreferences
    private lateinit var store: DoorConfigStore
    private var nfcAdapter: NfcAdapter? = null

    private val busy = AtomicBoolean(false)

    // NFC 标签串行流水线：新标签事件总是抢占进行中的尝试（generation 递增，
    // 旧任务在下一个检查点抛 NfcAbortedException 自行放弃），
    // 避免 busy 期间事件被丢弃导致“必须移开手机再贴一次”。
    private val nfcExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nfc-pipeline").apply { isDaemon = true }
    }
    private val nfcGeneration = AtomicLong(0)
    private val nfcWorkPending = AtomicBoolean(false)

    // recovery 循环等待重新发现的标签：onTagDiscovered 优先把标签交给等待者
    private val discoveryWaiter = AtomicReference<CompletableFuture<Tag>?>(null)

    @Volatile private var isResumed = false
    @Volatile private var hasCredential = false
    private var pendingBleOpen = false
    private var pendingFobConfig = false
    private var selectedTab = MainTab.Nfc
    private var configDialog: AlertDialog? = null
    private var configViews: ConfigDialogViews? = null

    // NFC / 蓝牙各自独立的状态，避免两个页面串台
    private var nfcState = StatusState("", "")
    private var bleState = StatusState("", "")
    private var isBusyState = false

    // 动画状态追踪：只在状态种类/页签变化时触发图标动画，避免进度刷新时反复抖动
    private var renderedKind: DoorStatusKind? = null
    private var renderedTab: MainTab? = null
    private var currentIconBgColor: Int? = null
    private var iconColorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    // M3 强调缓动（emphasized decelerate）+ 成功状态的轻微回弹
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val overshoot = OvershootInterpolator(2.0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        views = MainViews(findViewById(android.R.id.content))

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        DoorOfflineLog.initialize(applicationContext)
        DoorOfflineLog.append("APP", "MainActivity created")
        store = DoorConfigStore(this)
        hasCredential = store.hasUsableCredential()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        selectedTab = readSavedTab()

        setupViews()
        updateIdleStatus()

        val retainedReset = lastNonConfigurationInstance as? PasswordResetSession
        if (!hasCredential || retainedReset != null) {
            showConfigDialog()
        }
        if (retainedReset != null) configViews?.let { showPasswordResetDialog(it, retainedReset) }
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        enableReaderModeIfIdle()
        updateIdleStatus()
        maybeAutoRefreshCredential()
    }

    override fun onPause() {
        isResumed = false
        nfcAdapter?.disableReaderMode(this)
        stopPulse()
        super.onPause()
    }

    /**
     * 开启 ReaderMode：拿干净的原始 NfcA 通道，平台不插手 NDEF/存在性检查，
     * 自定义命令 transceive(0xB1...) 才能稳定收到门锁响应。
     *
     * 关键：若正在处理标签（典型是 intent 带来的那张）就先不抢通道。
     * 否则 enableReaderMode 会重置 NFC 控制器，把进行中的 NfcA 会话打断。
     * 处理结束后由 setBusyState(false) 再调一次本方法把 ReaderMode 补上。
     * nfcWorkPending 覆盖「intent 已入队但流水线尚未启动」的窗口（冷启动时
     * onResume 可能先于 executor 任务执行）。
     */
    private fun enableReaderModeIfIdle() {
        if (!isResumed || busy.get() || nfcWorkPending.get()) return
        enableReaderModeNow()
    }

    private fun enableReaderModeNow() {
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000)
        }
        nfcAdapter?.enableReaderMode(this, this, flags, options)
    }

    override fun onDestroy() {
        nfcExecutor.shutdownNow()
        iconColorAnimator?.cancel()
        stopPulse()
        passwordResetDialog?.dismiss()
        passwordResetDialog = null
        configDialog?.dismiss()
        configDialog = null
        configViews = null
        super.onDestroy()
    }

    override fun onTagDiscovered(tag: Tag) {
        // recovery 循环正在等重新发现的标签时优先交给它，避免再走抢占逻辑
        val waiter = discoveryWaiter.get()
        if (waiter != null && waiter.complete(tag)) {
            return
        }
        enqueueTag(tag, preParsedDeviceId = null, viaDispatchIntent = false)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BLE_PERMS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted && pendingBleOpen) {
            pendingBleOpen = false
            startBleOpen()
        } else if (granted && pendingFobConfig) {
            pendingFobConfig = false
            startFobConfig()
        } else {
            pendingBleOpen = false
            pendingFobConfig = false
            setBle(
                getString(R.string.st_fail),
                rawText("未授予蓝牙权限").resolve(this),
                DoorStatusKind.Error
            )
        }
    }

    private fun setupViews() {
        views.statusDetail.movementMethod = ScrollingMovementMethod()
        views.statusDetail.setOnLongClickListener {
            val text = views.statusDetail.text?.toString()
            if (!text.isNullOrBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("credential", text))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            true
        }
        views.refreshButton.setOnClickListener { refreshCredential() }
        views.settingsButton.setOnClickListener { showConfigDialog() }
        views.bleOpenButton.setOnClickListener { startBleOpen() }
        views.bleFobButton.setOnClickListener { startFobConfig() }
        views.tabNfc.setOnClickListener { selectTab(MainTab.Nfc) }
        views.tabBle.setOnClickListener { selectTab(MainTab.Ble) }

        updateTabVisuals(animateIndicator = false)
        views.tabContainer.post { positionSegmentIndicator(animate = false) }
    }

    private fun selectTab(tab: MainTab) {
        if (selectedTab == tab) return
        selectedTab = tab
        prefs.edit().putString(PREF_LAST_TAB, tab.name).apply()
        updateTabVisuals(animateIndicator = true)
        renderCurrentState()
    }

    private fun updateTabVisuals(animateIndicator: Boolean) {
        applyTabStyle(views.tabNfcLabel, views.tabNfcIcon, selectedTab == MainTab.Nfc)
        applyTabStyle(views.tabBleLabel, views.tabBleIcon, selectedTab == MainTab.Ble)
        positionSegmentIndicator(animateIndicator)
    }

    private fun applyTabStyle(label: TextView, icon: ImageView, selected: Boolean) {
        val color = if (selected) {
            resolveColor(R.color.color_on_secondary_container)
        } else {
            resolveColor(R.color.color_on_surface_variant)
        }
        label.setTextColor(color)
        icon.imageTintList = ColorStateList.valueOf(color)
    }

    /** 把分段控件的“滑块”定位到当前选中的半区，可选动画过渡。 */
    private fun positionSegmentIndicator(animate: Boolean) {
        val container = views.tabContainer
        if (container.width == 0) return
        val pad = dp(4)
        val indicatorWidth = (container.width - pad * 2) / 2
        val indicatorHeight = container.height - pad * 2
        val indicator = views.tabIndicator
        val lp = indicator.layoutParams as FrameLayout.LayoutParams
        if (lp.width != indicatorWidth || lp.height != indicatorHeight) {
            lp.width = indicatorWidth
            lp.height = indicatorHeight
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            lp.marginStart = pad
            indicator.layoutParams = lp
        }
        val targetX = if (selectedTab == MainTab.Ble) indicatorWidth.toFloat() else 0f
        if (animate) {
            indicator.animate()
                .translationX(targetX)
                .setDuration(300)
                .setInterpolator(emphasized)
                .start()
        } else {
            indicator.translationX = targetX
        }
    }

    private fun renderCurrentState() {
        val status = when (selectedTab) {
            MainTab.Nfc -> nfcState
            MainTab.Ble -> bleState
        }
        renderStatus(selectedTab, status)
        views.bleOpenButton.visibility = if (selectedTab == MainTab.Ble) View.VISIBLE else View.GONE
        val activationPending = store.load()?.requiresDigitalCredentialActivation() == true
        views.bleOpenButton.isEnabled = !isBusyState && (hasCredential || activationPending)
        views.bleFobButton.visibility = if (selectedTab == MainTab.Ble) View.VISIBLE else View.GONE
        views.bleFobButton.isEnabled = !isBusyState && hasCredential
        views.refreshButton.isEnabled = !isBusyState
    }

    private fun renderStatus(tab: MainTab, status: StatusState) {
        views.statusTitle.text = status.title
        views.statusDetail.text = status.detail

        val iconRes = when (tab) {
            MainTab.Nfc -> when (status.kind) {
                DoorStatusKind.Success -> R.drawable.ic_check_circle
                DoorStatusKind.Error -> R.drawable.ic_warning
                DoorStatusKind.Idle, DoorStatusKind.Busy -> R.drawable.ic_nfc
            }
            MainTab.Ble -> when (status.kind) {
                DoorStatusKind.Success -> R.drawable.ic_check_circle
                DoorStatusKind.Error -> R.drawable.ic_warning
                DoorStatusKind.Idle, DoorStatusKind.Busy -> R.drawable.ic_bluetooth
            }
        }

        val statusColor = when (status.kind) {
            DoorStatusKind.Success -> resolveColor(R.color.color_success)
            DoorStatusKind.Error -> resolveColor(R.color.color_error)
            DoorStatusKind.Idle, DoorStatusKind.Busy -> resolveColor(R.color.color_on_surface)
        }
        val iconTint = when (status.kind) {
            DoorStatusKind.Success -> resolveColor(R.color.color_success)
            DoorStatusKind.Error -> resolveColor(R.color.color_error)
            DoorStatusKind.Idle, DoorStatusKind.Busy -> resolveColor(R.color.color_primary)
        }
        val iconBackground = when (status.kind) {
            DoorStatusKind.Success -> resolveColor(R.color.color_success_container)
            DoorStatusKind.Error -> resolveColor(R.color.color_error_container)
            DoorStatusKind.Idle, DoorStatusKind.Busy -> resolveColor(R.color.color_primary_container)
        }

        views.statusTitle.setTextColor(statusColor)

        val kindChanged = status.kind != renderedKind
        val changed = kindChanged || tab != renderedTab
        if (changed) {
            animateIconChange(iconRes, iconTint, iconBackground, status.kind, gentle = !kindChanged)
        } else {
            views.statusIcon.setImageResource(iconRes)
            views.statusIcon.imageTintList = ColorStateList.valueOf(iconTint)
            applyIconBackground(iconBackground, animate = false)
        }

        renderedKind = status.kind
        renderedTab = tab
        updateIdlePulse(tab, status.kind)
    }

    /** 状态种类切换时：图标淡入缩放（成功回弹、失败抖动）+ 背景色渐变。 */
    private fun animateIconChange(
        iconRes: Int,
        iconTint: Int,
        iconBackground: Int,
        kind: DoorStatusKind,
        gentle: Boolean
    ) {
        val icon = views.statusIcon
        stopPulse()
        applyIconBackground(iconBackground, animate = true)

        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(iconTint)
        icon.animate().cancel()
        icon.alpha = 0f
        val startScale = if (gentle) 0.9f else 0.6f
        icon.scaleX = startScale
        icon.scaleY = startScale
        val duration = when {
            gentle -> 200L
            kind == DoorStatusKind.Success -> 420L
            else -> 280L
        }
        val interpolator = if (!gentle && kind == DoorStatusKind.Success) overshoot else emphasized
        icon.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()

        if (!gentle && kind == DoorStatusKind.Error) {
            shakeView(views.statusIconContainer)
        }
    }

    private fun applyIconBackground(target: Int, animate: Boolean) {
        val drawable = views.statusIconContainer.background.mutate() as GradientDrawable
        val from = currentIconBgColor
        iconColorAnimator?.cancel()
        if (animate && from != null && from != target) {
            iconColorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
                duration = 280
                addUpdateListener { drawable.setColor(it.animatedValue as Int) }
                start()
            }
        } else {
            drawable.setColor(target)
        }
        currentIconBgColor = target
    }

    private fun shakeView(view: View) {
        ObjectAnimator.ofFloat(
            view, View.TRANSLATION_X,
            0f, dp(8).toFloat(), -dp(6).toFloat(), dp(4).toFloat(), 0f
        ).apply {
            duration = 340
            start()
        }
    }

    /** NFC 待机时让图标容器轻微“呼吸”，提示用户靠近门锁。 */
    private fun updateIdlePulse(tab: MainTab, kind: DoorStatusKind) {
        val shouldPulse = tab == MainTab.Nfc && kind == DoorStatusKind.Idle && hasCredential
        if (shouldPulse) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulseAnimator?.isStarted == true) return
        val container = views.statusIconContainer
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.06f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val scale = it.animatedValue as Float
                container.scaleX = scale
                container.scaleY = scale
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        views.statusIconContainer.scaleX = 1f
        views.statusIconContainer.scaleY = 1f
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private var passwordResetDialog: PasswordResetDialog? = null
    private var passwordResetSession: PasswordResetSession? = null

    override fun onRetainNonConfigurationInstance(): Any? = passwordResetSession

    private fun showPasswordResetDialog(dialogBinding: ConfigDialogViews, retained: PasswordResetSession? = null) {
        if (passwordResetDialog != null || !busy.compareAndSet(false, true)) return
        setBusyState(true)
        val session = retained ?: PasswordResetDialog.createSession(this, dialogBinding.phoneInput.trimmedText())
        passwordResetSession = session
        passwordResetDialog = PasswordResetDialog(
            activity = this,
            session = session,
            onComplete = { phone, password ->
                if (configViews === dialogBinding) {
                    dialogBinding.phoneInput.setText(phone)
                    dialogBinding.passwordInput.setText(password)
                    dialogBinding.errorMessage.visibility = View.GONE
                    setCaptchaVisible(dialogBinding, false)
                }
            },
            onDismiss = {
                passwordResetDialog = null
                if (!isChangingConfigurations) passwordResetSession = null
                busy.set(false)
                if (!isFinishing && !isDestroyed && !isChangingConfigurations) {
                    setBusyState(false)
                    updateIdleStatus()
                }
            }
        )
        passwordResetDialog?.show()
    }

    private fun showConfigDialog() {
        configDialog?.show()
        if (configDialog != null) return

        val dialogBinding = ConfigDialogViews(LayoutInflater.from(this).inflate(R.layout.dialog_config, null))
        val snapshot = store.load()
        dialogBinding.phoneInput.setText(snapshot?.phone.orEmpty())
        dialogBinding.passwordInput.setText(snapshot?.password.orEmpty())
        configureCaptchaWebView(dialogBinding.captchaWebView)
        setCaptchaVisible(dialogBinding, false)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.setOnShowListener {
            dialogBinding.forgotPasswordButton.setOnClickListener { showPasswordResetDialog(dialogBinding) }
            dialogBinding.cancelButton.setOnClickListener {
                dialog.dismiss()
            }
            dialogBinding.alipayButton.setOnClickListener {
                dialogBinding.errorMessage.visibility = View.GONE
                startAlipayLogin()
            }
            dialogBinding.captchaRefreshButton.setOnClickListener {
                val phone = dialogBinding.phoneInput.trimmedText()
                if (phone.isBlank()) {
                    dialogBinding.errorMessage.text = getString(R.string.err_phn)
                    dialogBinding.errorMessage.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                refreshCaptcha(dialogBinding, phone)
            }
            dialogBinding.syncButton.setOnClickListener {
                val phone = dialogBinding.phoneInput.trimmedText()
                val password = dialogBinding.passwordInput.trimmedText()
                if (phone.isBlank() || password.isBlank()) {
                    dialogBinding.errorMessage.text = getString(R.string.err_phn)
                    dialogBinding.errorMessage.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val requiresCaptcha = dialogBinding.captchaContainer.visibility == View.VISIBLE
                val captcha = dialogBinding.captchaInput.trimmedText()
                if (requiresCaptcha && captcha.isBlank()) {
                    dialogBinding.errorMessage.text = getString(R.string.err_captcha_manual)
                    dialogBinding.errorMessage.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                dialogBinding.errorMessage.visibility = View.GONE
                setConfigDialogLoading(true)
                Thread {
                    try {
                        val fresh = DoorApi().syncCredential(
                            phone = phone,
                            password = password,
                            code = captcha.takeIf { it.isNotBlank() }
                        )
                        store.save(fresh)
                        runOnUiThread {
                            hasCredential = fresh.hasOfflineCredential()
                            updateIdleStatus()
                            Toast.makeText(this, getString(R.string.tst_syn), Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    } catch (e: DoorCaptchaRequiredException) {
                        runOnUiThread {
                            prepareCaptchaChallenge(dialogBinding)
                            dialogBinding.errorMessage.text =
                                e.serverMessage ?: getString(R.string.err_captcha_manual)
                            dialogBinding.errorMessage.visibility = View.VISIBLE
                            refreshCaptcha(dialogBinding, phone)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            dialogBinding.errorMessage.text = e.resolveMessage(this)
                            dialogBinding.errorMessage.visibility = View.VISIBLE
                            setConfigDialogLoading(false)
                        }
                    }
                }.start()
            }
            setConfigDialogLoading(false)
        }

        dialog.setOnDismissListener {
            dialogBinding.captchaWebView.stopLoading()
            configViews = null
            configDialog = null
        }

        // WebView 初始化会创建 ~4MB 的 Chromium 遥测文件，post 到首帧后立即删除
        dialogBinding.captchaWebView.post {
            File(getDataDir(), "app_webview/BrowserMetrics-spare.pma").delete()
        }

        configViews = dialogBinding
        configDialog = dialog
        dialog.setCanceledOnTouchOutside(hasCredential)
        dialog.setCancelable(hasCredential)
        dialog.show()
    }

    private fun setConfigDialogLoading(loading: Boolean) {
        val dialogBinding = configViews ?: return
        dialogBinding.phoneInput.isEnabled = !loading
        dialogBinding.passwordInput.isEnabled = !loading
        dialogBinding.captchaInput.isEnabled = !loading
        dialogBinding.captchaRefreshButton.isEnabled = !loading
        dialogBinding.syncButton.isEnabled = !loading
        dialogBinding.alipayButton.isEnabled = !loading
        dialogBinding.forgotPasswordButton.isEnabled = !loading
        dialogBinding.cancelButton.isEnabled = !loading && hasCredential
    }

    private fun configureCaptchaWebView(webView: WebView) {
        // SVG 静态图，关掉 JS 即可（其余项本就是默认值）
        webView.settings.javaScriptEnabled = false
        webView.setBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun refreshCaptcha(dialogBinding: ConfigDialogViews, phone: String) {
        setConfigDialogLoading(true)
        dialogBinding.errorMessage.visibility = View.GONE
        Thread {
            try {
                val svg = DoorApi().fetchLoginCaptchaSvg(phone)
                runOnUiThread {
                    showCaptchaChallenge(dialogBinding, svg)
                    setConfigDialogLoading(false)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    dialogBinding.errorMessage.text = e.resolveMessage(this)
                    dialogBinding.errorMessage.visibility = View.VISIBLE
                    setConfigDialogLoading(false)
                }
            }
        }.start()
    }

    private fun prepareCaptchaChallenge(dialogBinding: ConfigDialogViews) {
        setCaptchaVisible(dialogBinding, true)
        dialogBinding.captchaInput.text?.clear()
        dialogBinding.captchaInput.requestFocus()
    }

    private fun showCaptchaChallenge(dialogBinding: ConfigDialogViews, svg: String) {
        prepareCaptchaChallenge(dialogBinding)
        renderCaptchaSvg(dialogBinding.captchaWebView, svg)
    }

    private fun setCaptchaVisible(dialogBinding: ConfigDialogViews, visible: Boolean) {
        dialogBinding.captchaContainer.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            dialogBinding.captchaInput.text?.clear()
            renderCaptchaSvg(dialogBinding.captchaWebView, null)
        }
    }

    private fun renderCaptchaSvg(webView: WebView, svg: String?) {
        val html = "<body style=\"margin:0;background:#fff\">${svg.orEmpty()}</body>"
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun setNfc(title: String, detail: String, kind: DoorStatusKind = DoorStatusKind.Idle) {
        nfcState = StatusState(title = title, detail = detail, kind = kind)
        if (selectedTab == MainTab.Nfc) {
            renderCurrentState()
        }
    }

    private fun setBle(title: String, detail: String, kind: DoorStatusKind = DoorStatusKind.Idle) {
        bleState = StatusState(title = title, detail = detail, kind = kind)
        if (selectedTab == MainTab.Ble) {
            renderCurrentState()
        }
    }

    private fun setBusyState(value: Boolean) {
        isBusyState = value
        renderCurrentState()
        configDialog?.let { setConfigDialogLoading(value) }
        // 标签处理结束（含冷启动 intent 的那张），把刚才让位的 ReaderMode 补回来
        if (!value) enableReaderModeIfIdle()
    }

    private fun updateIdleStatus() {
        if (busy.get()) return

        val snapshot = store.load()
        val activationPending = snapshot?.requiresDigitalCredentialActivation() == true
        val account = snapshot?.let {
            getString(R.string.dt_dev, it.phone, it.deviceId)
        }.orEmpty()

        when {
            nfcAdapter == null -> setNfc(
                getString(R.string.st_fail),
                getString(R.string.err_nfc),
                DoorStatusKind.Error
            )
            nfcAdapter?.isEnabled != true -> setNfc(
                getString(R.string.st_fail),
                getString(R.string.err_nfc2),
                DoorStatusKind.Error
            )
            activationPending -> setNfc(
                getString(R.string.st_hold),
                rawText("数字钥匙待激活，可贴近门锁或使用蓝牙完成激活").resolve(this),
                DoorStatusKind.Idle
            )
            !hasCredential -> setNfc(
                getString(R.string.st_fail),
                getString(R.string.err_syn),
                DoorStatusKind.Error
            )
            else -> setNfc(
                getString(R.string.st_hold),
                getString(R.string.sd_nfc, account),
                DoorStatusKind.Idle
            )
        }

        if (activationPending) {
            setBle(
                getString(R.string.st_ble),
                rawText("数字钥匙待激活，点击开始使用蓝牙激活").resolve(this),
                DoorStatusKind.Idle
            )
        } else if (!hasCredential) {
            setBle(
                getString(R.string.st_ble),
                getString(R.string.err_syn),
                DoorStatusKind.Error
            )
        } else {
            setBle(
                getString(R.string.st_ble),
                getString(R.string.sd_tap, account),
                DoorStatusKind.Idle
            )
        }

        renderCurrentState()
    }

    /** 手动刷新凭证：优先用缓存 sessionSecret 直接拉取，失败再回落到完整登录。 */
    private fun refreshCredential() {
        val snapshot = store.load()
        if (snapshot == null || snapshot.phone.isBlank()) {
            showConfigDialog()
            return
        }
        if (!busy.compareAndSet(false, true)) return

        setBusyState(true)
        setNfc(
            getString(R.string.st_rfs),
            getString(R.string.sd_rfrg),
            DoorStatusKind.Busy
        )
        setBle(
            getString(R.string.st_rfs),
            getString(R.string.sd_rfrg),
            DoorStatusKind.Busy
        )

        Thread {
            try {
                val fresh = try {
                    DoorApi().refreshCredentialOnly(snapshot)
                } catch (fallback: Exception) {
                    if (snapshot.password.isBlank()) throw fallback
                    DoorApi().syncCredential(snapshot.phone, snapshot.password)
                }
                store.save(fresh)
                runOnUiThread {
                    hasCredential = fresh.hasOfflineCredential()
                    Toast.makeText(this, getString(R.string.tst_rfr), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (e is DoorCaptchaRequiredException) {
                        Toast.makeText(this, getString(R.string.err_captcha_manual), Toast.LENGTH_LONG).show()
                        showConfigDialogWithCaptcha(snapshot.phone, snapshot.password)
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.tst_rff, e.resolveMessage(this)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                busy.set(false)
                runOnUiThread {
                    setBusyState(false)
                    updateIdleStatus()
                }
            }
        }.start()
    }

    /**
     * 通过 IAlixPay AIDL 拉起支付宝 app 完成快捷登录授权（无 SDK、无内嵌 WebView）。
     * 全程一个工作线程：取 auth_info → Pay() 阻塞授权 → 换登录态并保存（password 留空）。
     */
    private fun startAlipayLogin() {
        if (!busy.compareAndSet(false, true)) return

        DoorOfflineLog.append("AUTH", "Alipay login requested")
        setBusyState(true)
        Toast.makeText(this, getString(R.string.oauth_loading), Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val authInfo = DoorApi().fetchAlipayAuthInfo()
                val authCode = DoorAlipayAuth.authorize(this, authInfo)
                val fresh = DoorApi().syncCredentialWithAlipay(authCode)
                store.save(fresh)
                DoorOfflineLog.append(
                    "AUTH",
                    "Alipay login complete project=${fresh.projectId} app=${fresh.appId} device=${fresh.deviceId} credentialPresent=${fresh.hasOfflineCredential()}"
                )
                runOnUiThread {
                    hasCredential = fresh.hasOfflineCredential()
                    Toast.makeText(this, getString(R.string.tst_syn), Toast.LENGTH_SHORT).show()
                    configDialog?.dismiss()
                }
            } catch (e: Exception) {
                DoorOfflineLog.append("AUTH", "Alipay login failed ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread {
                    if (configDialog?.isShowing != true) {
                        showConfigDialog()
                    }
                    configViews?.let {
                        it.errorMessage.text = e.resolveMessage(this)
                        it.errorMessage.visibility = View.VISIBLE
                    }
                }
            } finally {
                busy.set(false)
                runOnUiThread {
                    setBusyState(false)
                    updateIdleStatus()
                }
            }
        }.start()
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED) {
            return
        }

        @Suppress("DEPRECATION")
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        if (tag == null) return

        // NDEF 派发的 intent 已带系统解析好的 NDEF：直接取 device_id，
        // 会话内跳过 144 字节重读；TAG_DISCOVERED 兜底路径没有该数据则回落到读标签
        val preParsedDeviceId = DoorNfcHelper.deviceIdFromIntent(intent)
        enqueueTag(tag, preParsedDeviceId, viaDispatchIntent = true)
    }

    /**
     * 把标签投递进串行 NFC 流水线。新标签总是抢占进行中的尝试：
     * generation 递增后旧任务在下一个 NFC 操作检查点放弃，
     * 不会出现「busy 期间标签事件被静默丢弃」。
     */
    private fun enqueueTag(tag: Tag, preParsedDeviceId: Int?, viaDispatchIntent: Boolean) {
        nfcWorkPending.set(true)
        val generation = nfcGeneration.incrementAndGet()
        DoorOfflineLog.append(
            "NFC",
            "tag queued uid=${tag.id.toHexCompact()} generation=$generation intent=$viaDispatchIntent"
        )
        nfcExecutor.execute { runTagPipeline(tag, preParsedDeviceId, viaDispatchIntent, generation) }
    }

    /**
     * 串行标签处理流水线（nfcExecutor 线程）。
     *
     * 结构：一次开门尝试（intent 通道快速失败）→ 若为通信层失败且手机仍贴在门锁上，
     * 走 [awaitRecoveredTag] 强制重发现标签，拿干净的 ReaderMode 通道再试一次。
     * 被更新的标签事件抢占时静默放弃（NfcAbortedException），UI 由新流水线接管。
     */
    private fun runTagPipeline(tag: Tag, preParsedDeviceId: Int?, viaDispatchIntent: Boolean, generation: Long) {
        val isCurrent = { nfcGeneration.get() == generation }
        var claimedBusy = false
        try {
            if (!hasCredential) {
                val pendingSnapshot = store.load()?.takeIf { it.requiresDigitalCredentialActivation() }
                if (pendingSnapshot != null) {
                    if (!busy.compareAndSet(false, true)) {
                        runOnUiThread {
                            setNfc(getString(R.string.st_hold), getString(R.string.sd_wait), DoorStatusKind.Busy)
                        }
                        return
                    }
                    claimedBusy = true
                    activatePendingInline(tag, pendingSnapshot, isCurrent)
                } else {
                    runOnUiThread {
                        setNfc(
                            getString(R.string.st_fail),
                            getString(R.string.err_syn),
                            DoorStatusKind.Error
                        )
                        showConfigDialog()
                    }
                }
                return
            }

            if (!busy.compareAndSet(false, true)) {
                // 其它操作（刷新凭证/登录）持有 busy：提示等待，不排队
                runOnUiThread {
                    setNfc(
                        getString(R.string.st_hold),
                        getString(R.string.sd_wait),
                        DoorStatusKind.Busy
                    )
                }
                return
            }
            claimedBusy = true

            val uid = tag.id.toHexCompact()
            runOnUiThread {
                setBusyState(true)
                setNfc(
                    getString(R.string.st_hold),
                    getString(R.string.sd_tag, uid),
                    DoorStatusKind.Busy
                )
            }

            var result: DoorOpenResult? = null
            var attemptTag = tag
            var attemptDeviceId = preParsedDeviceId
            var attemptViaIntent = viaDispatchIntent
            var attemptsLeft = MAX_PIPELINE_ATTEMPTS

            while (result == null && attemptsLeft > 0 && isCurrent()) {
                val snapshot = store.load()
                if (snapshot == null) {
                    runOnUiThread { showConfigDialog() }
                    result = DoorOpenResult(
                        success = false,
                        title = getString(R.string.st_fail),
                        uid = uid,
                        details = getString(R.string.err_crd)
                    )
                    break
                }

                result = tryUnlockOnTag(attemptTag, snapshot, attemptDeviceId, attemptViaIntent, isCurrent)
                if (result.success || !result.recoverable) break

                attemptsLeft--
                if (attemptsLeft <= 0 || !isCurrent()) break

                runOnUiThread {
                    setNfc(getString(R.string.st_hold), getString(R.string.sd_retry), DoorStatusKind.Busy)
                }
                val recovered = awaitRecoveredTag(isCurrent) ?: break
                DoorOfflineLog.append("NFC", "recovery rediscovered tag, retrying on clean channel")
                attemptTag = recovered
                attemptDeviceId = result.deviceId
                attemptViaIntent = false
                result = null // 重试会产生新的结果
            }

            result?.let { outcome ->
                val details = if (!outcome.success && outcome.recoverable) {
                    outcome.details + getString(R.string.dt_nop, getString(R.string.hint_retap))
                } else {
                    outcome.details
                }
                runOnUiThread {
                    setNfc(
                        outcome.title,
                        details,
                        if (outcome.success) DoorStatusKind.Success else DoorStatusKind.Error
                    )
                }
            }
        } catch (e: NfcAbortedException) {
            // 被更新的标签事件抢占，本流水线结果作废
        } finally {
            if (claimedBusy) busy.set(false)
            if (nfcGeneration.get() == generation) {
                nfcWorkPending.set(false)
            }
            if (claimedBusy) {
                runOnUiThread { setBusyState(false) }
            }
        }
    }

    /**
     * 单次标签开门尝试（nfcExecutor 线程）。
     * 通信层失败（门锁不应答/标签掉线）标记 recoverable，由流水线决定是否走恢复重试；
     * 门锁返回 [DoorNfcHelper.STALE_CREDENTIAL_CODES] 时在 NfcA 会话保持期间
     * 更新凭证并重发命令，用户无需再贴一次卡。
     */
    private fun tryUnlockOnTag(
        tag: Tag,
        snapshot: DoorCredentialSnapshot,
        preParsedDeviceId: Int?,
        viaDispatchIntent: Boolean,
        isCurrent: () -> Boolean
    ): DoorOpenResult {
        val uid = tag.id.toHexCompact()
        DoorNfcHelper.clearDebug()
        DoorNfcHelper.appendDebug("开始NFC开门 uid=$uid intent=$viaDispatchIntent")
        try {
            val outcome = DoorNfcHelper.openDoorSingleSession(
                tag = tag,
                credentialHex = snapshot.credentialHex,
                projectId = snapshot.projectId,
                preParsedDeviceId = preParsedDeviceId,
                viaDispatchIntent = viaDispatchIntent,
                isAborted = { !isCurrent() },
                onStaleCredential = { code, lockProvidedKey ->
                    if (code == 27 && lockProvidedKey != null) {
                        // 门锁在响应帧里直接下发了当前链式密钥（同 BLE 0x76/0x77 的语义）
                        store.updateCredential(lockProvidedKey)
                        DoorNfcHelper.appendDebug("使用门锁下发的新链式密钥重试")
                        lockProvidedKey
                    } else {
                        refreshStaleCredential(snapshot, code)
                    }
                }
            )
            val decoded = outcome.response
            DoorNfcHelper.appendDebug(
                "解析结果: code=${decoded.resultCode} success=${decoded.isSuccess} " +
                    "refreshed=${outcome.refreshedCredential}"
            )
            DoorNfcHelper.flushDebug(uid, this)

            // 门锁每次成功开锁可能轮换链式密钥（响应帧 7..39 字节携带新密钥），
            // 必须持久化，否则下次开门必然落后一拍报 27、被迫手动刷新
            decoded.updatedCredentialHex?.let { store.updateCredential(it) }

            if (decoded.isSuccess) {
                val refreshNote = if (outcome.refreshedCredential) {
                    getString(R.string.dt_nop, rawText("凭证已自动更新，本次直接开门").resolve(this))
                } else {
                    ""
                }
                return DoorOpenResult(
                    success = true,
                    title = getString(R.string.st_ok),
                    uid = uid,
                    details = getString(
                        R.string.sd_res,
                        decoded.resultMessageResId.resolve(this),
                        outcome.deviceId,
                        uid
                    ) + refreshNote,
                    deviceId = outcome.deviceId
                )
            }

            val staleNote = when {
                outcome.staleRefreshError != null ->
                    getString(R.string.dt_nop, staleRefreshErrorText(outcome.staleRefreshError!!))
                outcome.refreshedCredential ->
                    getString(R.string.dt_nop, rawText("凭证已自动更新并重试，门锁仍拒绝").resolve(this))
                else -> ""
            }
            return DoorOpenResult(
                success = false,
                title = getString(R.string.st_fail),
                uid = uid,
                details = getString(
                    R.string.sd_res,
                    decoded.resultMessageResId.resolve(this),
                    outcome.deviceId,
                    uid
                ) + staleNote,
                deviceId = outcome.deviceId
            )
        } catch (e: NfcAbortedException) {
            throw e
        } catch (e: Exception) {
            DoorNfcHelper.appendDebug("异常: ${e.javaClass.simpleName}: ${e.message}")
            DoorNfcHelper.flushDebug(uid, this)
            return DoorOpenResult(
                success = false,
                title = getString(R.string.st_fail),
                uid = uid,
                details = getString(R.string.sd_tag, uid) + "\n" + e.resolveMessage(this),
                recoverable = true
            )
        }
    }

    /**
     * 门锁报 24/25/27 时在线刷新凭证。在 NFC 会话保持期间调用（网络往返期间标签须保持在场），
     * 失败抛出由调用方决定回退与提示。返回新的 credentialHex。
     */
    private fun refreshStaleCredential(snapshot: DoorCredentialSnapshot, resultCode: Int): String {
        DoorNfcHelper.appendDebug("门锁要求凭证更新 code=$resultCode，开始在线刷新")
        val fresh = try {
            DoorApi().refreshCredentialOnly(snapshot)
        } catch (fallback: Exception) {
            if (snapshot.password.isBlank()) throw fallback
            DoorApi().syncCredential(snapshot.phone, snapshot.password)
        }
        if (!fresh.hasOfflineCredential()) {
            throw DoorApiException(rawText("服务器未返回有效凭证"))
        }
        store.save(fresh)
        hasCredential = true
        DoorNfcHelper.appendDebug("凭证在线刷新完成 deviceId=${fresh.deviceId}")
        return fresh.credentialHex
    }

    private fun staleRefreshErrorText(e: Exception): String {
        return if (e is DoorCaptchaRequiredException) {
            rawText("凭证需要更新，自动刷新需要验证码，请到配置页手动同步").resolve(this)
        } else {
            rawText("凭证需要更新，自动刷新失败：%1\$s", e.resolveMessage(this)).resolve(this)
        }
    }

    /**
     * 失败恢复：手机仍贴在门锁上时，通过 disable→enable ReaderMode 强制 NFC 栈
     * 重新评估场内标签——很多系统不会对已在场内的标签重新回调 onTagDiscovered，
     * 直接补开 ReaderMode 等不来事件，用户只能移开手机。
     * 拿到重新激活的干净通道后由调用方重试，无需人工重贴。
     */
    private fun awaitRecoveredTag(isCurrent: () -> Boolean): Tag? {
        val waiter = CompletableFuture<Tag>()
        discoveryWaiter.set(waiter)
        try {
            repeat(RECOVERY_ROUNDS) {
                if (!isCurrent() || !isResumed) return null
                runOnUiThread { nfcAdapter?.disableReaderMode(this) }
                SystemClock.sleep(RECOVERY_TOGGLE_GAP_MS)
                if (!isCurrent() || !isResumed) return null
                runOnUiThread { if (isResumed) enableReaderModeNow() }
                try {
                    return waiter.get(RECOVERY_DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // 本轮 toggle 未重新发现标签，继续下一轮
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return null
        } finally {
            discoveryWaiter.compareAndSet(waiter, null)
            // 取消等待者：若 onTagDiscovered 恰在放弃后完成它，complete 会返回 false，
            // 标签正确回落到 enqueueTag 而不是被丢弃
            waiter.cancel(false)
        }
    }

    /** 数字钥匙激活（nfcExecutor 线程内联执行；busy 由流水线持有并在 finally 统一释放）。 */
    private fun activatePendingInline(
        tag: Tag,
        snapshot: DoorCredentialSnapshot,
        isCurrent: () -> Boolean
    ) {
        runOnUiThread {
            setBusyState(true)
            setNfc(
                getString(R.string.st_hold),
                rawText("正在激活数字钥匙").resolve(this),
                DoorStatusKind.Busy
            )
        }

        try {
            val fresh = DoorNfcHelper.activateDigitalCredential(
                tag = tag,
                snapshot = snapshot,
                isAborted = { !isCurrent() }
            ) { progress ->
                runOnUiThread {
                    setNfc(getString(R.string.st_hold), progress, DoorStatusKind.Busy)
                }
            }
            store.save(fresh)
            runOnUiThread {
                hasCredential = true
                setNfc(
                    getString(R.string.st_ok),
                    rawText("数字钥匙激活成功").resolve(this),
                    DoorStatusKind.Success
                )
            }
        } catch (e: NfcAbortedException) {
            throw e
        } catch (e: Exception) {
            runOnUiThread {
                setNfc(
                    getString(R.string.st_fail),
                    e.resolveMessage(this),
                    DoorStatusKind.Error
                )
            }
        }
    }

    /**
     * 凭证防过期：进入前台时若本地凭证超过 [AUTO_REFRESH_CREDENTIAL_AGE_MS] 未更新
     * （成功开门的链式密钥轮换、手动/会话内刷新都会推进 updatedAt），
     * 用缓存的 sessionSecret 静默刷新，避免到门口才报 24/25/27。
     * 尝试频率不低于 [AUTO_REFRESH_RETRY_GAP_MS] 一次，失败静默（门口还有会话内刷新兜底）。
     */
    private fun maybeAutoRefreshCredential() {
        if (busy.get() || nfcWorkPending.get()) return
        val snapshot = store.load() ?: return
        if (!snapshot.hasOfflineCredential() || snapshot.sessionSecret.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - snapshot.updatedAt < AUTO_REFRESH_CREDENTIAL_AGE_MS) return
        if (now - prefs.getLong(PREF_LAST_AUTO_REFRESH, 0L) < AUTO_REFRESH_RETRY_GAP_MS) return
        if (!busy.compareAndSet(false, true)) return
        prefs.edit().putLong(PREF_LAST_AUTO_REFRESH, now).apply()
        DoorOfflineLog.append("AUTH", "auto credential refresh start ageMs=${now - snapshot.updatedAt}")

        Thread {
            try {
                val fresh = DoorApi().refreshCredentialOnly(snapshot)
                store.save(fresh)
                DoorOfflineLog.append("AUTH", "auto credential refresh ok")
                runOnUiThread {
                    hasCredential = fresh.hasOfflineCredential()
                    updateIdleStatus()
                    Toast.makeText(this, getString(R.string.tst_rfr), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                DoorOfflineLog.append(
                    "AUTH",
                    "auto credential refresh failed ${e.javaClass.simpleName}: ${e.message}"
                )
            } finally {
                busy.set(false)
                runOnUiThread {
                    setBusyState(false)
                    updateIdleStatus()
                }
            }
        }.start()
    }

    private fun startBleOpen() {
        if (busy.get()) {
            setBle(
                getString(R.string.st_ble),
                getString(R.string.sd_proc),
                DoorStatusKind.Busy
            )
            return
        }

        val snapshot = store.load() ?: run {
            setBle(
                getString(R.string.st_fail),
                getString(R.string.err_syn),
                DoorStatusKind.Error
            )
            showConfigDialog()
            return
        }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingBleOpen = true
            requestPermissions(missing.toTypedArray(), REQ_BLE_PERMS)
            return
        }

        if (!busy.compareAndSet(false, true)) {
            setBle(
                getString(R.string.st_ble),
                getString(R.string.sd_proc),
                DoorStatusKind.Busy
            )
            return
        }

        setBusyState(true)
        setBle(
            getString(R.string.st_ble),
            if (snapshot.requiresDigitalCredentialActivation()) {
                rawText("正在通过蓝牙初始化数字钥匙").resolve(this)
            } else {
                getString(R.string.sd_prep)
            },
            DoorStatusKind.Busy
        )

        Thread {
            try {
                val result = DoorBle.openDoor(this, snapshot) {
                    runOnUiThread {
                        setBle(getString(R.string.st_ble), it, DoorStatusKind.Busy)
                    }
                }
                result.updatedCredentialHex?.let { store.updateCredential(it) }
                runOnUiThread {
                    if (result.updatedCredentialHex != null) {
                        hasCredential = true
                    }
                    val noteSuffix = result.note?.let { getString(R.string.dt_nop, it) }.orEmpty()
                    setBle(
                        if (result.success) getString(R.string.st_ok) else getString(R.string.st_fail),
                        getString(
                            R.string.sd_bler,
                            result.deviceName,
                            result.deviceAddress,
                            result.resultCode,
                            result.resultMessage,
                            noteSuffix
                        ),
                        if (result.success) DoorStatusKind.Success else DoorStatusKind.Error
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (e is DoorCaptchaRequiredException) {
                        setBle(
                            getString(R.string.st_fail),
                            getString(R.string.err_captcha_manual),
                            DoorStatusKind.Error
                        )
                        showConfigDialogWithCaptcha(snapshot.phone, snapshot.password)
                    } else {
                        setBle(
                            getString(R.string.st_fail),
                            e.resolveMessage(this),
                            DoorStatusKind.Error
                        )
                    }
                }
            } finally {
                busy.set(false)
                runOnUiThread { setBusyState(false) }
            }
        }.start()
    }

    private fun startFobConfig() {
        if (busy.get()) {
            setBle(
                getString(R.string.st_ble),
                getString(R.string.sd_proc),
                DoorStatusKind.Busy
            )
            return
        }

        val snapshot = store.load() ?: run {
            setBle(
                getString(R.string.st_fail),
                getString(R.string.err_syn),
                DoorStatusKind.Error
            )
            showConfigDialog()
            return
        }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingFobConfig = true
            requestPermissions(missing.toTypedArray(), REQ_BLE_PERMS)
            return
        }

        showFobNetworkDialog(snapshot)
    }

    /**
     * 钥匙扣 WiFi/portal 配置输入框（可选，全部留空则只下发凭证，行为与旧版一致）。
     * 输入值存 SharedPreferences 以便下次预填。
     */
    private fun showFobNetworkDialog(snapshot: DoorCredentialSnapshot) {
        val dialogBinding = FobNetworkDialogViews(
            LayoutInflater.from(this).inflate(R.layout.dialog_fob_network, null)
        )
        dialogBinding.ssidInput.setText(prefs.getString(PREF_FOB_WIFI_SSID, "").orEmpty())
        dialogBinding.wifiPassInput.setText(prefs.getString(PREF_FOB_WIFI_PASS, "").orEmpty())
        dialogBinding.portalUserInput.setText(prefs.getString(PREF_FOB_PORTAL_USER, "").orEmpty())
        dialogBinding.portalPassInput.setText(prefs.getString(PREF_FOB_PORTAL_PASS, "").orEmpty())

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.setOnShowListener {
            dialogBinding.cancelButton.setOnClickListener {
                dialog.dismiss()
            }
            dialogBinding.confirmButton.setOnClickListener {
                val wifiSsid = dialogBinding.ssidInput.trimmedText()
                val wifiPass = dialogBinding.wifiPassInput.trimmedText()
                val portalUser = dialogBinding.portalUserInput.trimmedText()
                val portalPass = dialogBinding.portalPassInput.trimmedText()
                prefs.edit()
                    .putString(PREF_FOB_WIFI_SSID, wifiSsid)
                    .putString(PREF_FOB_WIFI_PASS, wifiPass)
                    .putString(PREF_FOB_PORTAL_USER, portalUser)
                    .putString(PREF_FOB_PORTAL_PASS, portalPass)
                    .apply()
                dialog.dismiss()
                runFobConfig(snapshot, wifiSsid, wifiPass, portalUser, portalPass)
            }
        }
        dialog.show()
    }

    private fun runFobConfig(
        snapshot: DoorCredentialSnapshot,
        wifiSsid: String,
        wifiPass: String,
        portalUser: String,
        portalPass: String
    ) {
        if (!busy.compareAndSet(false, true)) {
            setBle(
                getString(R.string.st_ble),
                getString(R.string.sd_proc),
                DoorStatusKind.Busy
            )
            return
        }

        setBusyState(true)
        setBle(
            "配置钥匙扣",
            "正在扫描钥匙扣…",
            DoorStatusKind.Busy
        )

        Thread {
            try {
                val result = DoorFob.configureFob(
                    this,
                    snapshot,
                    wifiSsid = wifiSsid,
                    wifiPass = wifiPass,
                    portalUser = portalUser,
                    portalPass = portalPass
                ) { msg ->
                    runOnUiThread {
                        setBle("配置钥匙扣", msg, DoorStatusKind.Busy)
                    }
                }
                runOnUiThread {
                    val detail = buildString {
                        append("设备: ${result.deviceName}\n")
                        append("地址: ${result.deviceAddress}\n")
                        if (result.deviceInfo.isNotBlank()) {
                            append("信息: ${result.deviceInfo}\n")
                        }
                        append("状态: ${result.statusCode} - ${result.resultMessage}")
                        result.networkStatusCode?.let { code ->
                            append("\n网络配置: $code - ${DoorFob.describeStatusCode(code)}")
                        }
                    }
                    setBle(
                        if (result.success) getString(R.string.st_ok) else getString(R.string.st_fail),
                        detail,
                        if (result.success) DoorStatusKind.Success else DoorStatusKind.Error
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBle(
                        getString(R.string.st_fail),
                        e.resolveMessage(this),
                        DoorStatusKind.Error
                    )
                }
            } finally {
                busy.set(false)
                runOnUiThread { setBusyState(false) }
            }
        }.start()
    }

    private fun resolveColor(colorResId: Int): Int {
        return getColor(colorResId)
    }

    private fun readSavedTab(): MainTab {
        val saved = prefs.getString(PREF_LAST_TAB, MainTab.Nfc.name)
        return MainTab.values().firstOrNull { it.name == saved } ?: MainTab.Nfc
    }

    private fun EditText.trimmedText(): String {
        return text?.toString()?.trim().orEmpty()
    }

    private fun showConfigDialogWithCaptcha(phone: String, password: String) {
        showConfigDialog()
        val dialogBinding = configViews ?: return
        dialogBinding.phoneInput.setText(phone)
        dialogBinding.passwordInput.setText(password)
        prepareCaptchaChallenge(dialogBinding)
        dialogBinding.errorMessage.text = getString(R.string.err_captcha_manual)
        dialogBinding.errorMessage.visibility = View.VISIBLE
        refreshCaptcha(dialogBinding, phone)
    }

}

data class DoorOpenResult(
    val success: Boolean,
    val title: String,
    val uid: String,
    val details: String,
    /** 通信层失败（门锁不应答/掉线），可通过恢复循环重拿到干净通道后重试 */
    val recoverable: Boolean = false,
    /** 本次尝试解析到的门锁 device_id，恢复重试时复用以免再读 NDEF */
    val deviceId: Int? = null
)
