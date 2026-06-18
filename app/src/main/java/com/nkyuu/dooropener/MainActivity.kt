package com.nkyuu.dooropener

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var statusTextView: TextView
    private lateinit var detailTextView: TextView
    private lateinit var configureButton: Button
    private lateinit var activateButton: Button

    private lateinit var store: DoorConfigStore
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var scanMode = ScanMode.OPEN_DOOR

    private var configDialog: AlertDialog? = null
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFilters: Array<IntentFilter>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = DoorConfigStore(this)
        configureWindow()
        setContentView(createContentView())

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutableFlag()
        )
        intentFilters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )

        configureButton.setOnClickListener {
            showConfigDialog(force = false)
        }
        activateButton.setOnClickListener {
            toggleActivationMode()
        }

        updateIdleState()
        handleNfcIntent(intent)

        if (!store.hasUsableCredential()) {
            window.decorView.post {
                showConfigDialog(force = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, null)
        updateIdleState()
    }

    override fun onPause() {
        nfcAdapter?.disableForegroundDispatch(this)
        super.onPause()
    }

    override fun onDestroy() {
        configDialog?.dismiss()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED
        ) {
            return
        }

        val tag = intent.getTagCompat() ?: run {
            renderFailure("开门失败", "未读取到NFC标签")
            return
        }

        if (!store.hasUsableCredential()) {
            renderFailure("开门失败", "请先完成首次配置并同步凭证")
            showConfigDialog(force = true)
            return
        }

        if (!busy.compareAndSet(false, true)) {
            renderNeutral("请靠近门锁", "正在处理上一张标签，请稍候")
            return
        }

        val snapshot = store.load() ?: run {
            busy.set(false)
            renderFailure("开门失败", "本地凭证不可用，请重新配置")
            showConfigDialog(force = true)
            return
        }

        val requestedMode = scanMode
        renderNeutral("请靠近门锁", "UID: ${tag.id.toHexString()}\n正在读取标签")
        worker.execute {
            try {
                val result = when (requestedMode) {
                    ScanMode.OPEN_DOOR -> processDoorTag(tag, snapshot)
                    ScanMode.ACTIVATE_TAG -> activateDoorTag(tag, snapshot)
                }
                runOnUiThread {
                    if (requestedMode == ScanMode.ACTIVATE_TAG) {
                        scanMode = ScanMode.OPEN_DOOR
                    }
                    updateActionButtons()
                    renderResult(result)
                }
            } catch (exception: Exception) {
                val uid = tag.id.toHexString()
                runOnUiThread {
                    if (requestedMode == ScanMode.ACTIVATE_TAG) {
                        scanMode = ScanMode.OPEN_DOOR
                    }
                    updateActionButtons()
                    renderFailure(
                        title = if (requestedMode == ScanMode.ACTIVATE_TAG) "首次激活失败" else "开门失败",
                        details = buildString {
                            append(mapExceptionMessage(exception))
                            append('\n')
                            append("UID: ")
                            append(uid)
                        },
                        uid = uid
                    )
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun processDoorTag(tag: Tag, snapshot: DoorCredentialSnapshot): DoorOpenResult {
        val uid = tag.id.toHexString()
        val ndefMessage = readNdefMessage(tag)
        val doorUrl = findDoorUrl(ndefMessage)
            ?: return DoorOpenResult(
                success = false,
                title = "开门失败",
                uid = uid,
                details = "未在标签中找到门锁URL"
            )
        val deviceId = extractDeviceId(doorUrl)
            ?: return DoorOpenResult(
                success = false,
                title = "开门失败",
                uid = uid,
                details = "URL中缺少device_id: $doorUrl"
            )
        if (snapshot.deviceId > 0 && snapshot.deviceId != deviceId) {
            return DoorOpenResult(
                success = false,
                title = "开门失败",
                uid = uid,
                details = "标签设备与本地凭证不一致\n标签device_id: $deviceId\n本地device_id: ${snapshot.deviceId}"
            )
        }

        val ndef = Ndef.get(tag)
        val maxSize = ndef?.maxSize ?: DoorNfc.DEFAULT_USER_BYTES
        val suggestedStartPage = DoorNfc.findStartPage(ndefMessage.toByteArray(), maxSize, DoorNfc.COMMAND_SIZE)
        val startPage = DoorNfc.withNfcA(tag) { nfcA ->
            DoorNfc.findWritableStartPage(nfcA, suggestedStartPage, maxSize, DoorNfc.COMMAND_SIZE)
        }
        val command = DoorCrypto.buildNfcCommand(deviceId, snapshot.credentialHex, DoorApi.PROJECT_ID)
        val response = writeCommandAndReadResponse(tag, startPage, command)
        val decoded = DoorCrypto.parseResponse(deviceId, response)

        if (decoded.updatedCredentialHex != null) {
            store.updateCredential(decoded.updatedCredentialHex)
        }

        val resultLabel = if (decoded.isSuccess) "成功" else "失败"
        return DoorOpenResult(
            success = decoded.isSuccess,
            title = if (decoded.isSuccess) "开门成功" else "开门失败",
            uid = uid,
            details = buildString {
                append("门锁结果: ")
                append(resultLabel)
                append(" (")
                append(decoded.resultCode)
                append(" - ")
                append(decoded.resultMessage)
                append(')')
                append('\n')
                append("device_id: ")
                append(deviceId)
                append('\n')
                append("写入页: ")
                append(startPage)
                append('-')
                append(startPage + DoorNfc.COMMAND_PAGE_COUNT - 1)
                append('\n')
                append("标签URL: ")
                append(doorUrl)
                append('\n')
                append("响应命令: 0x")
                append("%02X".format(decoded.commandId))
                append('\n')
                append("响应长度: frame=")
                append(decoded.frameSize)
                append(", data=")
                append(decoded.payloadSize)
                append('\n')
                if (decoded.crcValid != null) {
                    append("响应CRC: ")
                    append(if (decoded.crcValid) "通过" else "失败")
                    append('\n')
                }
                append("响应HEX: ")
                append(response.toHexCompact())
                decoded.updatedCredentialHex?.let {
                    append('\n')
                    append("凭证已更新")
                }
                if (!decoded.isSuccess && decoded.resultCode == 27) {
                    append('\n')
                    append("需要重新联网同步凭证")
                }
            }
        )
    }

    private fun activateDoorTag(tag: Tag, snapshot: DoorCredentialSnapshot): DoorOpenResult {
        val uid = tag.id.toHexString()
        val ndefMessage = readNdefMessage(tag)
        val doorUrl = findDoorUrl(ndefMessage)
            ?: return DoorOpenResult(
                success = false,
                title = "首次激活失败",
                uid = uid,
                details = "未在标签中找到门锁URL"
            )
        val deviceId = extractDeviceId(doorUrl)
            ?: return DoorOpenResult(
                success = false,
                title = "首次激活失败",
                uid = uid,
                details = "URL中缺少device_id: $doorUrl"
            )
        if (snapshot.deviceId > 0 && snapshot.deviceId != deviceId) {
            return DoorOpenResult(
                success = false,
                title = "首次激活失败",
                uid = uid,
                details = "标签设备与本地凭证不一致\n标签device_id: $deviceId\n本地device_id: ${snapshot.deviceId}"
            )
        }

        val payload = DoorApi(snapshot.serverUrl).fetchActivationPayload(snapshot, deviceId)
        val ndef = Ndef.get(tag)
        val maxSize = ndef?.maxSize ?: DoorNfc.DEFAULT_USER_BYTES
        val suggestedStartPage = DoorNfc.findStartPage(ndefMessage.toByteArray(), maxSize, payload.size)
        val startPage = DoorNfc.withNfcA(tag) { nfcA ->
            DoorNfc.findWritableStartPage(nfcA, suggestedStartPage, maxSize, payload.size)
        }

        DoorNfc.withNfcA(tag) { nfcA ->
            DoorNfc.writeBytes(nfcA, startPage, payload)
        }
        val verifyBytes = DoorNfc.withNfcA(tag) { nfcA ->
            DoorNfc.readBytes(nfcA, startPage, payload.size)
        }
        if (!verifyBytes.contentEquals(payload)) {
            return DoorOpenResult(
                success = false,
                title = "首次激活失败",
                uid = uid,
                details = "标签写入后校验失败\n写入页: $startPage\npayload: ${payload.toHexCompact()}\n回读: ${verifyBytes.toHexCompact()}"
            )
        }

        return DoorOpenResult(
            success = true,
            title = "激活写入成功",
            uid = uid,
            details = buildString {
                append("首次激活 payload 已写入标签")
                append('\n')
                append("device_id: ")
                append(deviceId)
                append('\n')
                append("payload长度: ")
                append(payload.size)
                append('\n')
                append("写入页: ")
                append(startPage)
                append('-')
                append(startPage + ((payload.size + 3) / 4) - 1)
                append('\n')
                append("标签URL: ")
                append(doorUrl)
            }
        )
    }

    private fun readNdefMessage(tag: Tag): NdefMessage {
        val ndef = Ndef.get(tag) ?: throw IOException("标签不支持NDEF")
        try {
            ndef.connect()
            return ndef.cachedNdefMessage ?: ndef.ndefMessage
            ?: throw IOException("标签里没有NDEF数据")
        } finally {
            try {
                ndef.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun writeCommandAndReadResponse(tag: Tag, startPage: Int, command: ByteArray): ByteArray {
        DoorNfc.withNfcA(tag) { nfcA ->
            DoorNfc.writeCommand(nfcA, startPage, command)
        }

        val deadline = SystemClock.elapsedRealtime() + DoorNfc.RESPONSE_TIMEOUT_MS
        var lastRead = command
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(DoorNfc.RESPONSE_POLL_INTERVAL_MS)
            val current = try {
                DoorNfc.withNfcA(tag) { nfcA ->
                    DoorNfc.readBytes(nfcA, startPage, DoorNfc.COMMAND_SIZE)
                }
            } catch (_: TagLostException) {
                break
            }

            lastRead = current
            if (current.size < 4 || current[0] != DoorCrypto.FRAME_HEADER) {
                continue
            }

            val payloadSize = current[2].toInt() and 0xFF
            val frameSize = payloadSize + 4
            if (frameSize !in 4..current.size) {
                continue
            }

            val frame = current.copyOf(frameSize)
            if (!frame.contentEquals(command)) {
                return frame
            }
        }

        if (lastRead.contentEquals(command)) {
            throw IOException("写入完成，但未等到门锁响应")
        }
        return lastRead
    }

    private fun findDoorUrl(message: NdefMessage): String? {
        return message.records
            .mapNotNull { record ->
                decodeRecordPayload(record)
                    .takeIf { payload -> payload.startsWith("http://") || payload.startsWith("https://") }
            }
            .firstOrNull { payload ->
                val uri = Uri.parse(payload)
                uri.getQueryParameter("d") != null || uri.getQueryParameter("device_id") != null
            }
    }

    private fun extractDeviceId(url: String): Int? {
        val uri = Uri.parse(url)
        val value = uri.getQueryParameter("d")
            ?: uri.getQueryParameter("device_id")
            ?: return null
        return value.toLongOrNull()
            ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
            ?.toInt()
    }

    private fun decodeRecordPayload(record: NdefRecord): String {
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_URI)) {
            return decodeUriPayload(record.payload)
        }
        return String(record.payload, StandardCharsets.UTF_8)
    }

    private fun decodeUriPayload(payload: ByteArray): String {
        if (payload.isEmpty()) {
            return ""
        }
        val prefix = URI_PREFIX_MAP[payload[0].toInt() and 0xFF].orEmpty()
        val remainder = String(payload.copyOfRange(1, payload.size), StandardCharsets.UTF_8)
        return prefix + remainder
    }

    private fun showConfigDialog(force: Boolean) {
        if (configDialog?.isShowing == true) {
            return
        }

        val snapshot = store.load()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val serverInput = createDialogInput(
            hint = "服务器地址",
            initial = snapshot?.serverUrl ?: DoorApi.DEFAULT_SERVER_URL,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val phoneInput = createDialogInput(
            hint = "手机号",
            initial = snapshot?.phone.orEmpty(),
            inputType = InputType.TYPE_CLASS_PHONE
        )
        val passwordInput = createDialogInput(
            hint = "密码",
            initial = snapshot?.password.orEmpty(),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val errorView = TextView(this).apply {
            setTextColor(ERROR_COLOR)
            textSize = 13f
            visibility = View.GONE
        }

        container.addView(serverInput)
        container.addView(phoneInput)
        container.addView(passwordInput)
        container.addView(
            errorView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (force) "首次配置" else "配置账号")
            .setView(container)
            .setNegativeButton(if (force) "稍后" else "取消", null)
            .setPositiveButton("同步凭证", null)
            .setCancelable(!force)
            .create()
        configDialog = dialog

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val serverUrl = serverInput.text.toString().trim()
                val phone = phoneInput.text.toString().trim()
                val password = passwordInput.text.toString()

                if (serverUrl.isBlank() || phone.isBlank() || password.isBlank()) {
                    errorView.text = "请完整填写服务器、手机号和密码"
                    errorView.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                if (!busy.compareAndSet(false, true)) {
                    errorView.text = "当前正在处理其他任务，请稍候"
                    errorView.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                errorView.visibility = View.GONE
                positiveButton.isEnabled = false
                renderNeutral("请靠近门锁", "正在联网同步凭证")

                worker.execute {
                    try {
                        val synced = DoorApi(serverUrl).syncCredential(phone, password)
                        store.save(synced)
                        runOnUiThread {
                            busy.set(false)
                            positiveButton.isEnabled = true
                            dialog.dismiss()
                            updateIdleState()
                        }
                    } catch (exception: Exception) {
                        runOnUiThread {
                            busy.set(false)
                            positiveButton.isEnabled = true
                            errorView.text = mapExceptionMessage(exception)
                            errorView.visibility = View.VISIBLE
                            renderFailure("开门失败", "同步凭证失败：${mapExceptionMessage(exception)}")
                        }
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            configDialog = null
            updateIdleState()
        }
        dialog.show()
    }

    private fun createDialogInput(hint: String, initial: String, inputType: Int): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(initial)
            this.inputType = inputType
            setBackgroundColor(0xFFF7F7F7.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextColor(READY_COLOR)
            setHintTextColor(DETAIL_COLOR)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        }
    }

    private fun toggleActivationMode() {
        if (busy.get()) {
            renderNeutral("请靠近门锁", "当前正在处理任务，请稍候")
            return
        }
        if (!store.hasUsableCredential()) {
            renderFailure("首次激活失败", "请先配置账号并同步凭证")
            showConfigDialog(force = true)
            return
        }
        val snapshot = store.load()
        if (scanMode == ScanMode.ACTIVATE_TAG) {
            scanMode = ScanMode.OPEN_DOOR
            updateActionButtons()
            updateIdleState()
            return
        }
        if (snapshot == null || snapshot.credentialId.isBlank() || snapshot.userId.isBlank() || snapshot.identityCode.isBlank()) {
            renderFailure("首次激活失败", "本地缺少 credential_id 或登录态，请重新同步凭证")
            return
        }
        scanMode = ScanMode.ACTIVATE_TAG
        updateActionButtons()
        renderNeutral(
            "请靠近门锁",
            "激活模式已开启\n下一次贴标签将联网获取 payload 并写入标签"
        )
    }

    private fun updateIdleState() {
        when {
            nfcAdapter == null -> renderFailure("开门失败", "当前设备不支持NFC")
            nfcAdapter?.isEnabled != true -> renderFailure("开门失败", "请先在系统设置中开启NFC")
            !store.hasUsableCredential() -> renderFailure("开门失败", "请先配置账号并同步门禁凭证")
            busy.get() -> renderNeutral("请靠近门锁", "正在处理，请保持手机贴近门锁标签")
            else -> {
                val snapshot = store.load()
                renderNeutral(
                    title = "请靠近门锁",
                    details = buildString {
                        append(
                            if (scanMode == ScanMode.ACTIVATE_TAG) {
                                "激活模式：等待NFC标签"
                            } else {
                                "离线开门模式：等待NFC标签"
                            }
                        )
                        snapshot?.let {
                            append('\n')
                            append("账号: ")
                            append(it.phone)
                            append('\n')
                            append("设备ID: ")
                            append(it.deviceId)
                            append('\n')
                            append("credential_id: ")
                            append(it.credentialId.ifBlank { "-" })
                            append('\n')
                            append("上次同步: ")
                            append(it.updatedAt.formatForUi())
                        }
                    }
                )
            }
        }
        updateActionButtons()
    }

    private fun renderNeutral(title: String, details: String) {
        statusTextView.text = title
        statusTextView.setTextColor(READY_COLOR)
        detailTextView.text = details
    }

    private fun renderResult(result: DoorOpenResult) {
        if (result.success) {
            statusTextView.text = result.title
            statusTextView.setTextColor(SUCCESS_COLOR)
            detailTextView.text = "UID: ${result.uid}\n${result.details}"
        } else {
            renderFailure(result.title, result.details, result.uid)
        }
    }

    private fun renderSuccess(uid: String, details: String) {
        statusTextView.text = "开门成功"
        statusTextView.setTextColor(SUCCESS_COLOR)
        detailTextView.text = "UID: $uid\n$details"
    }

    private fun renderFailure(title: String, details: String, uid: String? = null) {
        statusTextView.text = title
        statusTextView.setTextColor(ERROR_COLOR)
        detailTextView.text = buildString {
            uid?.takeIf { it.isNotBlank() }?.let {
                append("UID: ")
                append(it)
                append('\n')
            }
            append(details)
        }
    }

    private fun mapExceptionMessage(exception: Exception): String {
        return when (exception) {
            is TagLostException -> "标签已移开，请重新贴近门锁"
            is DoorApiException -> exception.message ?: "门禁服务器返回错误"
            is IOException -> exception.message ?: "NFC通信失败"
            else -> exception.message ?: exception.javaClass.simpleName
        }
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.WHITE
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topSpacer = View(this)
        val bottomSpacer = View(this)

        statusTextView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 33f
            setTextColor(READY_COLOR)
        }

        detailTextView = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(DETAIL_COLOR)
            setLineSpacing(0f, 1.25f)
        }

        configureButton = Button(this).apply {
            text = getString(R.string.configure_button)
            setAllCaps(false)
        }
        activateButton = Button(this).apply {
            setAllCaps(false)
        }

        val detailScroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                detailTextView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(
            topSpacer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            statusTextView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            detailScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24)
            }
        )
        root.addView(
            bottomSpacer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(
                    activateButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    configureButton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(12)
                    }
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(24), bars.top + dp(24), dp(24), bars.bottom + dp(24))
            insets
        }

        return root
    }

    private fun updateActionButtons() {
        if (!::activateButton.isInitialized || !::configureButton.isInitialized) {
            return
        }
        activateButton.text = getString(
            if (scanMode == ScanMode.ACTIVATE_TAG) {
                R.string.cancel_activate_button
            } else {
                R.string.activate_button
            }
        )
        activateButton.isEnabled = !busy.get()
        configureButton.isEnabled = !busy.get()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun pendingIntentMutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getTagCompat(): Tag? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }

    private fun Long.formatForUi(): String {
        return try {
            Instant.ofEpochMilli(this)
                .atZone(ZoneId.systemDefault())
                .format(TIME_FORMATTER)
        } catch (_: Exception) {
            toString()
        }
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    private fun ByteArray.toHexCompact(): String {
        return joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    companion object {
        private val URI_PREFIX_MAP = mapOf(
            0x00 to "",
            0x01 to "http://www.",
            0x02 to "https://www.",
            0x03 to "http://",
            0x04 to "https://"
        )

        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private const val READY_COLOR = 0xFF111111.toInt()
        private const val SUCCESS_COLOR = 0xFF1B8A3A.toInt()
        private const val ERROR_COLOR = 0xFFC62828.toInt()
        private const val DETAIL_COLOR = 0xFF6B7280.toInt()
    }
}

private data class DoorOpenResult(
    val success: Boolean,
    val title: String,
    val uid: String,
    val details: String
)

private enum class ScanMode {
    OPEN_DOOR,
    ACTIVATE_TAG
}
