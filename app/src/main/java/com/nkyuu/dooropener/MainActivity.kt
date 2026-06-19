package com.nkyuu.dooropener

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

class MainActivity : ComponentActivity() {

    private lateinit var store: DoorConfigStore
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private lateinit var intentFilters: Array<IntentFilter>

    private val busy = AtomicBoolean(false)
    private var scanMode = ScanMode.OPEN_DOOR
    private var pendingBleOpen = false

    private val _statusTitle = mutableStateOf("请靠近门锁")
    private val _statusDetail = mutableStateOf("")
    private val _statusColor = mutableStateOf(DefaultTextColor)
    private val _isBusy = mutableStateOf(false)
    private val _showConfigDialog = mutableStateOf(false)
    private val _hasCredential = mutableStateOf(false)
    private val _activateMode = mutableStateOf(false)

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it } && pendingBleOpen) {
            pendingBleOpen = false
            startBleOpen()
        } else {
            setStatus("开门失败", "未授予蓝牙权限", ErrorColor)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DoorConfigStore(this)
        _hasCredential.value = store.hasUsableCredential()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        intentFilters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )

        setContent {
            DoorOpenerTheme { MainScreen() }
        }

        if (!_hasCredential.value) _showConfigDialog.value = true
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, null)
        updateIdleStatus()
    }

    override fun onPause() {
        nfcAdapter?.disableForegroundDispatch(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
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
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        iconTint: Color,
        showButton: Boolean,
        buttonText: String = "",
        buttonEnabled: Boolean = true,
        onButtonClick: () -> Unit = {},
        buttonIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        buttonColors: ButtonColors = ButtonDefaults.buttonColors()
    ) {
        val title by _statusTitle
        val detail by _statusDetail
        val statusColor by _statusColor

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            Icon(icon, null, Modifier.size(80.dp), tint = iconTint)
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
        val title by _statusTitle
        val isBusy by _isBusy
        val isActivate by _activateMode

        StatusContent(
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
            showButton = true,
            buttonText = if (isActivate) "取消激活" else "首次激活标签",
            buttonEnabled = !isBusy,
            onButtonClick = { toggleActivationMode() },
            buttonIcon = if (isActivate) Icons.Default.Close else Icons.Default.Nfc,
            buttonColors = if (isActivate)
                ButtonDefaults.buttonColors(containerColor = ErrorColor)
            else
                ButtonDefaults.buttonColors()
        )
    }

    @Composable
    private fun BleTab() {
        val title by _statusTitle
        val isBusy by _isBusy
        val hasCred by _hasCredential

        StatusContent(
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

    private fun setStatus(title: String, detail: String, color: Color = DefaultTextColor) {
        _statusTitle.value = title; _statusDetail.value = detail; _statusColor.value = color
    }

    private fun updateIdleStatus() {
        when {
            nfcAdapter == null -> setStatus("开门失败", "当前设备不支持NFC", ErrorColor)
            nfcAdapter?.isEnabled != true -> setStatus("开门失败", "请先开启NFC", ErrorColor)
            !_hasCredential.value -> setStatus("开门失败", "请先同步凭证", ErrorColor)
            busy.get() -> setStatus("请靠近门锁", "正在处理...")
            else -> {
                val s = store.load()
                setStatus("请靠近门锁", buildString {
                    append(if (_activateMode.value) "激活模式：等待NFC标签" else "离线开门模式：等待NFC标签")
                    s?.let { append("\n账号: ${it.phone}\n设备ID: ${it.deviceId}") }
                })
            }
        }
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

        if (tag == null) { setStatus("开门失败", "未读取到NFC标签", ErrorColor); return }
        if (!_hasCredential.value) { setStatus("开门失败", "请先同步凭证", ErrorColor); _showConfigDialog.value = true; return }
        if (!busy.compareAndSet(false, true)) { setStatus("请靠近门锁", "正在处理，请稍候"); return }

        val snapshot = store.load() ?: run {
            busy.set(false); setStatus("开门失败", "凭证不可用", ErrorColor)
            _showConfigDialog.value = true; return
        }

        val isActivate = _activateMode.value
        _isBusy.value = true
        setStatus("请靠近门锁", "UID: ${tag.id.hex()}\n正在读取标签")

        Thread {
            try {
                val result = if (isActivate) activateDoorTag(tag, snapshot) else processDoorTag(tag, snapshot)
                runOnUiThread {
                    if (isActivate) { _activateMode.value = false; scanMode = ScanMode.OPEN_DOOR }
                    setStatus(result.title, result.details, if (result.success) SuccessColor else ErrorColor)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isActivate) { _activateMode.value = false; scanMode = ScanMode.OPEN_DOOR }
                    setStatus(if (isActivate) "激活失败" else "开门失败",
                        "${e.message}\nUID: ${tag.id.hex()}", ErrorColor)
                }
            } finally { busy.set(false); _isBusy.value = false }
        }.start()
    }

    private fun processDoorTag(tag: Tag, snapshot: DoorCredentialSnapshot): DoorOpenResult {
        val uid = tag.id.hex()
        val msg = DoorNfcHelper.readNdefMessage(tag)
        val url = DoorNfcHelper.findDoorUrl(msg) ?: return DoorOpenResult(false, "开门失败", uid, "标签中无门锁URL")
        val uri = android.net.Uri.parse(url)
        val devIdStr = uri.getQueryParameter("d") ?: uri.getQueryParameter("device_id")
            ?: return DoorOpenResult(false, "开门失败", uid, "URL中无设备ID")
        val devId = devIdStr.toIntOrNull() ?: return DoorOpenResult(false, "开门失败", uid, "设备ID格式错误")

        val cmd = DoorCrypto.buildNfcCommand(devId, snapshot.credentialHex, DoorApi.PROJECT_ID)
        val page = DoorNfc.findStartPage(msg.toByteArray(), DoorNfc.DEFAULT_USER_BYTES, DoorNfc.COMMAND_SIZE)
        val resp = DoorNfcHelper.writeCommandAndReadResponse(tag, page, cmd)
        val decoded = DoorCrypto.parseResponse(devId, resp)

        return DoorOpenResult(decoded.isSuccess,
            if (decoded.isSuccess) "开门成功" else "开门失败", uid,
            "结果: ${decoded.resultMessage}\n设备ID: $devId\nUID: $uid")
    }

    private fun activateDoorTag(tag: Tag, snapshot: DoorCredentialSnapshot): DoorOpenResult {
        val uid = tag.id.hex()
        val msg = DoorNfcHelper.readNdefMessage(tag)
        val url = DoorNfcHelper.findDoorUrl(msg) ?: return DoorOpenResult(false, "激活失败", uid, "标签中无门锁URL")
        val uri = android.net.Uri.parse(url)
        val devIdStr = uri.getQueryParameter("d") ?: uri.getQueryParameter("device_id")
            ?: return DoorOpenResult(false, "激活失败", uid, "URL中无设备ID")
        val devId = devIdStr.toIntOrNull() ?: return DoorOpenResult(false, "激活失败", uid, "设备ID格式错误")

        val payload = DoorApi().fetchActivationPayload(snapshot, devId)
        val page = DoorNfc.findStartPage(msg.toByteArray(), DoorNfc.DEFAULT_USER_BYTES, payload.size)
        DoorNfc.withNfcA(tag) { DoorNfc.writeBytes(it, page, payload) }

        return DoorOpenResult(true, "激活成功", uid, "设备ID: $devId\nUID: $uid\n之后可直接开门")
    }

    private fun startBleOpen() {
        if (busy.get()) { setStatus("请靠近门锁", "正在处理"); return }
        val snapshot = store.load() ?: run {
            setStatus("蓝牙开门失败", "请先同步凭证", ErrorColor); _showConfigDialog.value = true; return
        }

        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { pendingBleOpen = true; blePermissionLauncher.launch(missing.toTypedArray()); return }

        if (!busy.compareAndSet(false, true)) { setStatus("请靠近门锁", "正在处理"); return }
        _isBusy.value = true; setStatus("请靠近门锁", "正在准备蓝牙开门")

        Thread {
            try {
                val result = DoorBle.openDoor(this, snapshot) { runOnUiThread { setStatus("请靠近门锁", it) } }
                result.updatedCredentialHex?.let { store.updateCredential(it) }
                runOnUiThread {
                    setStatus(
                        if (result.success) "开门成功" else "开门失败",
                        "设备: ${result.deviceName}\n地址: ${result.deviceAddress}\n结果: ${result.resultCode} - ${result.resultMessage}" +
                            (result.note?.let { "\n$it" } ?: ""),
                        if (result.success) SuccessColor else ErrorColor
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus("蓝牙开门失败", e.message ?: "未知错误", ErrorColor) }
            } finally { busy.set(false); _isBusy.value = false }
        }.start()
    }

    private fun toggleActivationMode() {
        if (busy.get()) return
        _activateMode.value = !_activateMode.value
        scanMode = if (_activateMode.value) ScanMode.ACTIVATE_TAG else ScanMode.OPEN_DOOR
        updateIdleStatus()
    }

    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
}

enum class ScanMode { OPEN_DOOR, ACTIVATE_TAG }
data class DoorOpenResult(val success: Boolean, val title: String, val uid: String, val details: String)
