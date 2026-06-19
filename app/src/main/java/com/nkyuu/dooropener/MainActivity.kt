package com.nkyuu.dooropener

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nkyuu.dooropener.ui.theme.DoorOpenerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val ErrorColor = Color(0xFFBA1A1A)
private val SuccessColor = Color(0xFF1B8A3A)
private val DefaultTextColor = Color(0xFF191C1B)

// 门锁返回这些码说明本地离线凭证已过期/需更新，自动重新同步
private val CREDENTIAL_REFRESH_CODES = setOf(24, 27)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private lateinit var store: DoorConfigStore
    private var nfcAdapter: NfcAdapter? = null

    private val busy = AtomicBoolean(false)
    private var pendingBleOpen = false

    // NFC / 蓝牙各自独立的状态，避免两个 Tab 串台
    private val _nfcTitle = mutableStateOf("请靠近门锁")
    private val _nfcDetail = mutableStateOf("")
    private val _nfcColor = mutableStateOf(DefaultTextColor)
    private val _bleTitle = mutableStateOf("蓝牙开门")
    private val _bleDetail = mutableStateOf("")
    private val _bleColor = mutableStateOf(DefaultTextColor)
    private val _isBusy = mutableStateOf(false)
    private val _showConfigDialog = mutableStateOf(false)
    private val _hasCredential = mutableStateOf(false)

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it } && pendingBleOpen) {
            pendingBleOpen = false
            startBleOpen()
        } else {
            setBle("开门失败", "未授予蓝牙权限", ErrorColor)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DoorConfigStore(this)
        _hasCredential.value = store.hasUsableCredential()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            DoorOpenerTheme { MainScreen() }
        }

        if (!_hasCredential.value) _showConfigDialog.value = true
        // 冷启动（贴卡唤起 app）时 intent 里可能带 Tag
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
        super.onPause()
    }

    override fun onTagDiscovered(tag: Tag) {
        handleTag(tag)
    }

    // ==================== Compose UI ====================

    @Composable
    private fun MainScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }

        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                CenterAlignedTopAppBar(
                    title = { Text("SEU Door") },
                    actions = {
                        val isBusy by _isBusy
                        IconButton(onClick = { refreshCredential() }, enabled = !isBusy) {
                            Icon(Icons.Default.Refresh, "刷新凭证")
                        }
                        IconButton(onClick = { _showConfigDialog.value = true }) {
                            Icon(Icons.Default.Settings, "配置")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Nfc, null) },
                        label = { Text("NFC") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Bluetooth, null) },
                        label = { Text("蓝牙") }
                    )
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                Crossfade(selectedTab, label = "tab") { tab ->
                    if (tab == 0) NfcTab() else BleTab()
                }
            }
        }

        if (_showConfigDialog.value) ConfigDialog { _showConfigDialog.value = false }
    }

    @Composable
    private fun StatusContent(
        title: String,
        detail: String,
        statusColor: Color,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        iconTint: Color,
        showButton: Boolean,
        buttonText: String = "",
        buttonEnabled: Boolean = true,
        onButtonClick: () -> Unit = {},
        buttonIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        buttonColors: ButtonColors = ButtonDefaults.buttonColors()
    ) {
        // 开门成功时图标弹入
        val iconScale = remember { Animatable(1f) }
        LaunchedEffect(title) {
            if (title.contains("成功")) {
                iconScale.snapTo(0.3f)
                iconScale.animateTo(
                    1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                )
            } else {
                iconScale.snapTo(1f)
            }
        }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Icon(icon, null, Modifier.size(80.dp).scale(iconScale.value), tint = iconTint)
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium,
                color = statusColor, textAlign = TextAlign.Center)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(detail, Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            if (showButton) {
                Button(onClick = onButtonClick, enabled = buttonEnabled,
                    colors = buttonColors,
                    modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    buttonIcon?.let { Icon(it, null); Spacer(Modifier.width(8.dp)) }
                    Text(buttonText, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    @Composable
    private fun NfcTab() {
        val title by _nfcTitle
        val detail by _nfcDetail
        val color by _nfcColor

        StatusContent(
            title = title,
            detail = detail,
            statusColor = color,
            icon = when {
                title.contains("成功") -> Icons.Default.CheckCircle
                title.contains("失败") -> Icons.Default.Error
                else -> Icons.Default.Nfc
            },
            iconTint = when {
                title.contains("成功") -> SuccessColor
                title.contains("失败") -> ErrorColor
                else -> MaterialTheme.colorScheme.primary
            },
            showButton = false
        )
    }

    @Composable
    private fun BleTab() {
        val title by _bleTitle
        val detail by _bleDetail
        val color by _bleColor
        val isBusy by _isBusy
        val hasCred by _hasCredential

        StatusContent(
            title = title,
            detail = detail,
            statusColor = color,
            icon = when {
                title.contains("成功") -> Icons.Default.CheckCircle
                title.contains("失败") -> Icons.Default.Error
                else -> Icons.Default.Bluetooth
            },
            iconTint = when {
                title.contains("成功") -> SuccessColor
                title.contains("失败") -> ErrorColor
                else -> MaterialTheme.colorScheme.primary
            },
            showButton = true,
            buttonText = "蓝牙开门",
            buttonEnabled = !isBusy && hasCred,
            onButtonClick = { startBleOpen() },
            buttonIcon = Icons.Default.Bluetooth
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConfigDialog(onDismiss: () -> Unit) {
        var phone by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }
        var errorMsg by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val ctx = this

        LaunchedEffect(Unit) { store.load()?.let { phone = it.phone; password = it.password } }

        ModalBottomSheet(onDismissRequest = { if (!isLoading) onDismiss() }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                Text("配置门禁账号", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(phone, { phone = it.trim() }, label = { Text("手机号") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !isLoading)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(password, { password = it.trim() }, label = { Text("密码") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !isLoading)
                errorMsg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = ErrorColor, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (phone.isBlank() || password.isBlank()) { errorMsg = "请输入手机号和密码"; return@Button }
                        isLoading = true; errorMsg = null
                        scope.launch {
                            try {
                                val snap = withContext(Dispatchers.IO) { DoorApi().syncCredential(phone, password) }
                                store.save(snap); _hasCredential.value = true
                                withContext(Dispatchers.Main) {
                                    updateIdleStatus()   // 刷新两个 Tab 的空闲文案（账号/设备ID）
                                    Toast.makeText(ctx, "同步成功", Toast.LENGTH_SHORT).show(); onDismiss()
                                }
                            } catch (e: Exception) { errorMsg = e.message ?: "同步失败" }
                            finally { isLoading = false }
                        }
                    },
                    enabled = !isLoading, modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("同步门禁凭证")
                }
            }
        }
    }

    // ==================== Business Logic ====================

    private fun setNfc(title: String, detail: String, color: Color = DefaultTextColor) {
        _nfcTitle.value = title; _nfcDetail.value = detail; _nfcColor.value = color
    }

    private fun setBle(title: String, detail: String, color: Color = DefaultTextColor) {
        _bleTitle.value = title; _bleDetail.value = detail; _bleColor.value = color
    }

    private fun updateIdleStatus() {
        if (busy.get()) return   // 正在开门，不要覆盖进行中的状态

        val s = store.load()
        val account = s?.let { "\n账号: ${it.phone}\n设备ID: ${it.deviceId}" } ?: ""

        // NFC tab
        when {
            nfcAdapter == null -> setNfc("开门失败", "当前设备不支持NFC", ErrorColor)
            nfcAdapter?.isEnabled != true -> setNfc("开门失败", "请先开启NFC", ErrorColor)
            !_hasCredential.value -> setNfc("开门失败", "请先同步凭证", ErrorColor)
            else -> setNfc("请靠近门锁", "离线开门模式：等待NFC标签$account")
        }

        // 蓝牙 tab
        if (!_hasCredential.value) setBle("蓝牙开门", "请先同步凭证", ErrorColor)
        else setBle("蓝牙开门", "点击下方按钮开门$account")
    }

    /** 手动刷新凭证：用已存的账号密码重新同步（处理过期）。 */
    private fun refreshCredential() {
        val snap = store.load()
        if (snap == null || snap.phone.isBlank() || snap.password.isBlank()) {
            _showConfigDialog.value = true
            return
        }
        if (!busy.compareAndSet(false, true)) return
        _isBusy.value = true
        setNfc("刷新凭证", "正在刷新…")
        setBle("刷新凭证", "正在刷新…")

        Thread {
            try {
                val fresh = DoorApi().syncCredential(snap.phone, snap.password)
                store.save(fresh)
                runOnUiThread {
                    _hasCredential.value = true
                    Toast.makeText(this, "凭证已刷新", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "刷新失败：${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                busy.set(false)
                runOnUiThread { _isBusy.value = false; updateIdleStatus() }
            }
        }.start()
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED) return

        @Suppress("DEPRECATION")
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        if (tag != null) handleTag(tag)
    }

    /** ReaderMode 回调在子线程触发，所有 UI 更新走 runOnUiThread。 */
    private fun handleTag(tag: Tag) {
        if (!_hasCredential.value) {
            runOnUiThread { setNfc("开门失败", "请先同步凭证", ErrorColor); _showConfigDialog.value = true }
            return
        }
        if (!busy.compareAndSet(false, true)) {
            runOnUiThread { setNfc("请靠近门锁", "正在处理，请稍候") }
            return
        }
        val snapshot = store.load() ?: run {
            busy.set(false)
            runOnUiThread { setNfc("开门失败", "凭证不可用", ErrorColor); _showConfigDialog.value = true }
            return
        }

        runOnUiThread {
            _isBusy.value = true
            setNfc("请靠近门锁", "UID: ${tag.id.hex()}\n正在读取标签")
        }

        Thread {
            try {
                val result = processDoorTag(tag, snapshot)
                runOnUiThread {
                    setNfc(result.title, result.details, if (result.success) SuccessColor else ErrorColor)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setNfc("开门失败", "${e.message}\nUID: ${tag.id.hex()}", ErrorColor)
                }
            } finally {
                busy.set(false)
                runOnUiThread { _isBusy.value = false }
            }
        }.start()
    }

    private fun processDoorTag(tag: Tag, snapshot: DoorCredentialSnapshot): DoorOpenResult {
        val uid = tag.id.hex()
        DoorNfcHelper.clearDebug()
        DoorNfcHelper.appendDebug("开始NFC开门 uid=$uid")
        try {
            val outcome = DoorNfcHelper.openDoorSingleSession(tag, snapshot.credentialHex, DoorApi.PROJECT_ID)
            val decoded = outcome.response
            DoorNfcHelper.appendDebug("解析结果: code=${decoded.resultCode} success=${decoded.isSuccess}")
            DoorNfcHelper.flushDebug(uid, this)

            if (decoded.isSuccess) {
                return DoorOpenResult(true, "开门成功", uid,
                    "结果: ${decoded.resultMessage}\n设备ID: ${outcome.deviceId}\nUID: $uid")
            }

            // 离线凭证过期/需更新：用存的账号密码自动重新同步，之后再贴一次即可
            if (decoded.resultCode in CREDENTIAL_REFRESH_CODES &&
                snapshot.phone.isNotBlank() && snapshot.password.isNotBlank()) {
                return try {
                    val fresh = DoorApi().syncCredential(snapshot.phone, snapshot.password)
                    store.save(fresh)
                    DoorOpenResult(false, "凭证已刷新", uid,
                        "原凭证${decoded.resultMessage}，已自动刷新\n请再贴一次卡开门")
                } catch (e: Exception) {
                    DoorOpenResult(false, "开门失败", uid,
                        "凭证${decoded.resultMessage}，自动刷新失败：${e.message}")
                }
            }

            return DoorOpenResult(false, "开门失败", uid,
                "结果: ${decoded.resultMessage}\n设备ID: ${outcome.deviceId}\nUID: $uid")
        } catch (e: Exception) {
            DoorNfcHelper.appendDebug("异常: ${e.javaClass.simpleName}: ${e.message}")
            DoorNfcHelper.flushDebug(uid, this)
            throw e
        }
    }

    private fun startBleOpen() {
        if (busy.get()) { setBle("蓝牙开门", "正在处理"); return }
        val snapshot = store.load() ?: run {
            setBle("开门失败", "请先同步凭证", ErrorColor); _showConfigDialog.value = true; return
        }

        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { pendingBleOpen = true; blePermissionLauncher.launch(missing.toTypedArray()); return }

        if (!busy.compareAndSet(false, true)) { setBle("蓝牙开门", "正在处理"); return }
        _isBusy.value = true; setBle("蓝牙开门", "正在准备蓝牙开门")

        Thread {
            try {
                val result = DoorBle.openDoor(this, snapshot) { runOnUiThread { setBle("蓝牙开门", it) } }
                result.updatedCredentialHex?.let { store.updateCredential(it) }
                runOnUiThread {
                    setBle(
                        if (result.success) "开门成功" else "开门失败",
                        "设备: ${result.deviceName}\n地址: ${result.deviceAddress}\n结果: ${result.resultCode} - ${result.resultMessage}" +
                            (result.note?.let { "\n$it" } ?: ""),
                        if (result.success) SuccessColor else ErrorColor
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { setBle("开门失败", e.message ?: "未知错误", ErrorColor) }
            } finally { busy.set(false); _isBusy.value = false }
        }.start()
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
}

data class DoorOpenResult(val success: Boolean, val title: String, val uid: String, val details: String)
