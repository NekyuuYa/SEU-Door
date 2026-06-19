package com.nkyuu.dooropener

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import java.util.concurrent.atomic.AtomicBoolean

// 门锁返回这些码说明本地离线凭证已过期/需更新，自动重新同步
private val CREDENTIAL_REFRESH_CODES = setOf(24, 27)

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
    val errorMessage: TextView = root.requireView(R.id.error_message)
    val cancelButton: View = root.requireView(R.id.cancel_button)
    val syncButton: View = root.requireView(R.id.sync_button)
}

private fun <T : View> View.requireView(id: Int): T {
    return findViewById<T>(id) ?: throw IllegalStateException("Missing required view: $id")
}

class MainActivity : Activity(), NfcAdapter.ReaderCallback {

    companion object {
        private const val PREFS_NAME = "door_opener_ui"
        private const val PREF_LAST_TAB = "last_tab"
        private const val REQ_BLE_PERMS = 1001
    }

    private lateinit var views: MainViews
    private lateinit var prefs: SharedPreferences
    private lateinit var store: DoorConfigStore
    private var nfcAdapter: NfcAdapter? = null

    private val busy = AtomicBoolean(false)
    private var pendingBleOpen = false
    private var selectedTab = MainTab.Nfc
    private var configDialog: AlertDialog? = null
    private var configViews: ConfigDialogViews? = null

    // NFC / 蓝牙各自独立的状态，避免两个页面串台
    private var nfcState = StatusState("", "")
    private var bleState = StatusState("", "")
    private var isBusyState = false
    private var hasCredential = false

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
        store = DoorConfigStore(this)
        hasCredential = store.hasUsableCredential()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        selectedTab = readSavedTab()

        setupViews()
        updateIdleStatus()

        if (!hasCredential) {
            showConfigDialog()
        }
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // ReaderMode：拿干净的原始 NfcA 通道，平台不插手 NDEF/存在性检查，
        // 自定义命令 transceive(0xB1...) 才能稳定收到门锁响应。
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 1000)
        }
        nfcAdapter?.enableReaderMode(this, this, flags, options)
        updateIdleStatus()
    }

    override fun onPause() {
        nfcAdapter?.disableReaderMode(this)
        stopPulse()
        super.onPause()
    }

    override fun onDestroy() {
        iconColorAnimator?.cancel()
        stopPulse()
        configDialog?.dismiss()
        configDialog = null
        configViews = null
        super.onDestroy()
    }

    override fun onTagDiscovered(tag: Tag) {
        handleTag(tag)
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
        } else {
            pendingBleOpen = false
            setBle(
                getString(R.string.st_fail),
                rawText("未授予蓝牙权限").resolve(this),
                DoorStatusKind.Error
            )
        }
    }

    private fun setupViews() {
        views.statusDetail.movementMethod = ScrollingMovementMethod()
        views.refreshButton.setOnClickListener { refreshCredential() }
        views.settingsButton.setOnClickListener { showConfigDialog() }
        views.bleOpenButton.setOnClickListener { startBleOpen() }
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
        views.bleOpenButton.isEnabled = !isBusyState && hasCredential
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

    private fun showConfigDialog() {
        configDialog?.show()
        if (configDialog != null) return

        val dialogBinding = ConfigDialogViews(LayoutInflater.from(this).inflate(R.layout.dialog_config, null))
        val snapshot = store.load()
        dialogBinding.phoneInput.setText(snapshot?.phone.orEmpty())
        dialogBinding.passwordInput.setText(snapshot?.password.orEmpty())

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.setOnShowListener {
            dialogBinding.cancelButton.setOnClickListener {
                dialog.dismiss()
            }
            dialogBinding.syncButton.setOnClickListener {
                val phone = dialogBinding.phoneInput.trimmedText()
                val password = dialogBinding.passwordInput.trimmedText()
                if (phone.isBlank() || password.isBlank()) {
                    dialogBinding.errorMessage.text = getString(R.string.err_phn)
                    dialogBinding.errorMessage.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                dialogBinding.errorMessage.visibility = View.GONE
                setConfigDialogLoading(true)
                Thread {
                    try {
                        val fresh = DoorApi().syncCredential(phone, password)
                        store.save(fresh)
                        runOnUiThread {
                            hasCredential = true
                            updateIdleStatus()
                            Toast.makeText(this, getString(R.string.tst_syn), Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
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
            configViews = null
            configDialog = null
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
        dialogBinding.syncButton.isEnabled = !loading
        dialogBinding.cancelButton.isEnabled = !loading && hasCredential
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
    }

    private fun updateIdleStatus() {
        if (busy.get()) return

        val snapshot = store.load()
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

        if (!hasCredential) {
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

    /** 手动刷新凭证：用已存的账号密码重新同步（处理过期）。 */
    private fun refreshCredential() {
        val snapshot = store.load()
        if (snapshot == null || snapshot.phone.isBlank() || snapshot.password.isBlank()) {
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
                val fresh = DoorApi().syncCredential(snapshot.phone, snapshot.password)
                store.save(fresh)
                runOnUiThread {
                    hasCredential = true
                    Toast.makeText(this, getString(R.string.tst_rfr), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.tst_rff, e.resolveMessage(this)),
                        Toast.LENGTH_LONG
                    ).show()
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

        if (tag != null) {
            handleTag(tag)
        }
    }

    /** ReaderMode 回调在子线程触发，所有 UI 更新走 runOnUiThread。 */
    private fun handleTag(tag: Tag) {
        if (!hasCredential) {
            runOnUiThread {
                setNfc(
                    getString(R.string.st_fail),
                    getString(R.string.err_syn),
                    DoorStatusKind.Error
                )
                showConfigDialog()
            }
            return
        }

        if (!busy.compareAndSet(false, true)) {
            runOnUiThread {
                setNfc(
                    getString(R.string.st_hold),
                    getString(R.string.sd_wait),
                    DoorStatusKind.Busy
                )
            }
            return
        }

        val snapshot = store.load() ?: run {
            busy.set(false)
            runOnUiThread {
                setNfc(
                    getString(R.string.st_fail),
                    getString(R.string.err_crd),
                    DoorStatusKind.Error
                )
                showConfigDialog()
            }
            return
        }

        runOnUiThread {
            setBusyState(true)
            setNfc(
                getString(R.string.st_hold),
                getString(R.string.sd_tag, tag.id.toHexCompact()),
                DoorStatusKind.Busy
            )
        }

        Thread {
            try {
                val result = processDoorTag(tag, snapshot)
                runOnUiThread {
                    setNfc(
                        result.title,
                        result.details,
                        if (result.success) DoorStatusKind.Success else DoorStatusKind.Error
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setNfc(
                        getString(R.string.st_fail),
                        getString(R.string.sd_tag, tag.id.toHexCompact()) + "\n" + e.resolveMessage(this),
                        DoorStatusKind.Error
                    )
                }
            } finally {
                busy.set(false)
                runOnUiThread { setBusyState(false) }
            }
        }.start()
    }

    private fun processDoorTag(tag: Tag, snapshot: DoorCredentialSnapshot): DoorOpenResult {
        val uid = tag.id.toHexCompact()
        DoorNfcHelper.clearDebug()
        DoorNfcHelper.appendDebug("开始NFC开门 uid=$uid")
        try {
            val outcome = DoorNfcHelper.openDoorSingleSession(tag, snapshot.credentialHex, DoorApi.PROJECT_ID)
            val decoded = outcome.response
            DoorNfcHelper.appendDebug("解析结果: code=${decoded.resultCode} success=${decoded.isSuccess}")
            DoorNfcHelper.flushDebug(uid, this)

            if (decoded.isSuccess) {
                return DoorOpenResult(
                    true,
                    getString(R.string.st_ok),
                    uid,
                    getString(
                        R.string.sd_res,
                        decoded.resultMessageResId.resolve(this),
                        outcome.deviceId,
                        uid
                    )
                )
            }

            if (decoded.resultCode in CREDENTIAL_REFRESH_CODES &&
                snapshot.phone.isNotBlank() && snapshot.password.isNotBlank()) {
                return try {
                    val fresh = DoorApi().syncCredential(snapshot.phone, snapshot.password)
                    store.save(fresh)
                    DoorOpenResult(
                        false,
                        getString(R.string.st_crd),
                        uid,
                        rawText("原凭证 %1\$s，已自动刷新\n请再贴一次卡开门",
                            decoded.resultMessageResId.resolve(this)
                        ).resolve(this)
                    )
                } catch (e: Exception) {
                    DoorOpenResult(
                        false,
                        getString(R.string.st_fail),
                        uid,
                        rawText("凭证 %1\$s，自动刷新失败：%2\$s",
                            decoded.resultMessageResId.resolve(this),
                            e.resolveMessage(this)
                        ).resolve(this)
                    )
                }
            }

            return DoorOpenResult(
                false,
                getString(R.string.st_fail),
                uid,
                getString(
                    R.string.sd_res,
                    decoded.resultMessageResId.resolve(this),
                    outcome.deviceId,
                    uid
                )
            )
        } catch (e: Exception) {
            DoorNfcHelper.appendDebug("异常: ${e.javaClass.simpleName}: ${e.message}")
            DoorNfcHelper.flushDebug(uid, this)
            throw e
        }
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
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
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
            getString(R.string.sd_prep),
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

    private fun Throwable.resolveMessage(context: MainActivity): String {
        return when (this) {
            is LocalizedMessage -> resolveMessage(context)
            else -> message ?: context.getString(R.string.err_unk)
        }
    }
}

data class DoorOpenResult(val success: Boolean, val title: String, val uid: String, val details: String)
