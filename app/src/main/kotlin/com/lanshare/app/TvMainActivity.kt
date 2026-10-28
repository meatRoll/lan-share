package com.lanshare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TvMainActivity : Activity(), LanShareService.Listener {
    private val bg = Color.rgb(13, 15, 12)
    private val panel = Color.rgb(28, 33, 26)
    private val panelFocused = Color.rgb(53, 62, 46)
    private val lime = Color.rgb(215, 243, 107)
    private val muted = Color.rgb(164, 174, 155)
    private lateinit var service: LanShareService
    private lateinit var devicesBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var transferCard: LinearLayout
    private lateinit var transferLabel: TextView
    private lateinit var transferDetail: TextView
    private lateinit var transferBar: ProgressBar
    private var activeTransfer: LanShareService.TransferProgress? = null
    private val transferHandler = Handler(Looper.getMainLooper())
    private var transferClearTask: Runnable? = null
    private var devices = emptyList<LanShareService.Device>()
    private var pendingUris = emptyList<Uri>()
    private var pendingLogSend = false
    private var pendingLogFile: File? = null
    private var selected: LanShareService.Device? = null
    private val receivedFiles = mutableListOf<File>()
    private lateinit var receivedBox: LinearLayout
    private var primaryLeftFocusId = 0
    private var firstDeviceFocusId = 0
    private var firstReceivedFocusId = 0
    private val folderAccessRequestCode = 1207
    private val folderDocumentId = "primary:Download/LanShare"
    private val storagePermissionRequestCode = 43
    private var awaitingAllFilesSettings = false
    private var fileBrowserDialog: AlertDialog? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Logcat.init(this)
        Logcat.d("lifecycle", "tv activity created")
        try { TvDeviceDiagnostics.record(this) } catch (_: Throwable) { /* Diagnostics must not block startup. */ }
        window.setStatusBarColor(bg)
        window.setNavigationBarColor(bg)
        getSharedPreferences("received_files", MODE_PRIVATE).getStringSet("paths", emptySet()).orEmpty().map(::File).filter { it.isFile }
            .sortedByDescending { it.lastModified() }.take(6).forEach { receivedFiles.add(it) }
        service = LanShareService(this, this)
        render()
        service.start()
        requestBluetoothPermission()
        requestStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingAllFilesSettings) {
            awaitingAllFilesSettings = false
            val granted = hasAllFilesAccess()
            browserLog("returned from all-files settings; granted=$granted")
            Toast.makeText(this, if (granted) "所有文件访问已开启，请重新打开文件浏览器" else "未开启所有文件访问，仍只能读取系统允许的文件", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasAllFilesAccess(): Boolean = try {
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    } catch (error: Exception) {
        browserLog("all-files state error=${error.javaClass.simpleName}: ${error.message}")
        false
    }

    private fun requestAllFilesAccess() {
        AlertDialog.Builder(this).setTitle("所有文件访问")
            .setMessage("此权限允许 LanShare 浏览和读取共享存储中的更多文件，包括其他应用创建的文档、日志和 APK。不会自动发送文件，部分受保护目录仍不可访问。请在系统页面手动开启；也可随时在系统设置中撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("打开系统设置") { _, _ ->
                val intents = listOf(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
                var opened = false
                for (intent in intents) {
                    try {
                        browserLog("opening all-files settings; action=${intent.action}; current=${hasAllFilesAccess()}")
                        awaitingAllFilesSettings = true
                        startActivity(intent)
                        opened = true
                        break
                    } catch (error: Exception) {
                        awaitingAllFilesSettings = false
                        browserLog("all-files settings unavailable; action=${intent.action}; error=${error.javaClass.simpleName}: ${error.message}")
                    }
                }
                if (!opened) Toast.makeText(this, "系统不支持此授权入口，无法通过此方式授予所有文件访问", Toast.LENGTH_LONG).show()
            }.show()
    }

    override fun onDestroy() {
        transferClearTask?.let(transferHandler::removeCallbacks)
        fileBrowserDialog?.dismiss()
        service.stop()
        super.onDestroy()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT in 23..28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), storagePermissionRequestCode)
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 41)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 41 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) { service.stop(); service = LanShareService(this, this); service.start() }
        if (requestCode == storagePermissionRequestCode) Logcat.d("storage", "external storage permission=${results.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
    }

    override fun onDevicesChanged(devices: List<LanShareService.Device>) { runOnUiThread { this.devices = devices; renderDevices() } }
    override fun onTransfer(progress: LanShareService.TransferProgress) {
        runOnUiThread {
            activeTransfer = progress
            transferClearTask?.let(transferHandler::removeCallbacks)
            transferClearTask = null
            showTransfer(progress)
            if (progress.done) {
                transferClearTask = Runnable {
                    if (activeTransfer == progress) activeTransfer = null
                    if (::transferCard.isInitialized) transferCard.visibility = View.GONE
                }.also { transferHandler.postDelayed(it, 4_000) }
            }
        }
    }
    override fun onError(message: String) {
        runOnUiThread {
            transferClearTask?.let(transferHandler::removeCallbacks)
            transferClearTask = null
            activeTransfer = null
            if (::transferCard.isInitialized) transferCard.visibility = View.GONE
            status.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onIncoming(request: LanShareService.IncomingRequest): Boolean {
        var accepted = false
        val latch = CountDownLatch(1)
        runOnUiThread { AlertDialog.Builder(this).setTitle("收到文件").setMessage("${request.name}\n${request.address}\n${request.size / 1024} KB").setNegativeButton("拒绝") { _, _ -> latch.countDown() }.setPositiveButton("接收") { _, _ -> accepted = true; latch.countDown() }.setOnCancelListener { latch.countDown() }.show() }
        latch.await(60, TimeUnit.SECONDS)
        return accepted
    }

    override fun onApkReceived(file: File) {
        // Receiving only saves the file. APK actions are available from the received-files list.
    }

    override fun onWebFileReceived(file: File) { onFileReceived(file) }
    override fun onFileReceived(file: File) {
        runOnUiThread {
            receivedFiles.removeAll { it.absolutePath == file.absolutePath }
            receivedFiles.add(0, file)
            val prefs = getSharedPreferences("received_files", MODE_PRIVATE)
            prefs.edit().putStringSet("paths", (receivedFiles.map { it.absolutePath } + prefs.getStringSet("paths", emptySet()).orEmpty()).take(32).toSet()).apply()
            status.text = "已接收：${file.name}"
            renderReceived()
        }
    }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(54, 30, 54, 30); setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "LanShare TV"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            includeFontPadding = true
            maxLines = 1
            setHorizontallyScrolling(false)
            ellipsize = null
        }, LinearLayout.LayoutParams(0, 60, 1f))
        header.addView(TextView(this).apply { text = "●  本机在线"; textSize = 16f; setTextColor(lime) })
        root.addView(header)
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 22, 0, 0) }
        columns.addView(ScrollView(this).apply { addView(infoPanel()) }, LinearLayout.LayoutParams(0, -1, 0.72f))
        val devicesPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 0, 0, 0) }
        devicesPanel.addView(TextView(this).apply { text = "附近设备"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, 14) })
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devicesPanel.addView(ScrollView(this).apply { addView(devicesBox) }, LinearLayout.LayoutParams(-1, 0, 1f))
        columns.addView(devicesPanel, LinearLayout.LayoutParams(0, -1, 0.9f))
        columns.addView(receivedPanel(), LinearLayout.LayoutParams(0, -1, 1.0f))
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "正在搜索局域网设备…"; textSize = 15f; setTextColor(muted); setPadding(0, 16, 0, 0) }
        root.addView(status)
        setContentView(root)
        renderDevices()
        root.post { findViewById<View>(primaryLeftFocusId)?.requestFocus() }
    }

    private fun infoPanel(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 24); background = rounded(panel, 14) }
        box.addView(TextView(this).apply { text = "本机接收中心"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        box.addView(TextView(this).apply { text = "${Build.MODEL}\n等待手机或电脑发送文件"; textSize = 16f; setTextColor(muted); setPadding(0, 14, 0, 0) })
        transferCard = createTransferCard()
        box.addView(transferCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
        val refresh = focusButton("重新搜索设备", 14f, panelFocused) { restartService() }
        primaryLeftFocusId = refresh.id
        box.addView(refresh, LinearLayout.LayoutParams(-1, 56).apply { topMargin = 30 })
        val settings = focusButton("设置", 14f, panelFocused) { showSettings() }
        box.addView(settings, LinearLayout.LayoutParams(-1, 56).apply { topMargin = 14 })
        return box
    }

    private fun createTransferCard(): LinearLayout {
        transferLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        transferDetail = TextView(this).apply { setTextColor(muted); textSize = 14f; setPadding(0, 4, 0, 8) }
        transferBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(lime)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(58, 66, 52))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            background = rounded(panelFocused, 12)
            addView(transferLabel)
            addView(transferDetail)
            addView(transferBar, LinearLayout.LayoutParams(-1, 6))
            visibility = View.GONE
        }
        activeTransfer?.let(::showTransfer)
        return card
    }

    private fun showTransfer(progress: LanShareService.TransferProgress) {
        if (!::transferCard.isInitialized) return
        val direction = if (progress.direction == LanShareService.TransferDirection.SEND) "发送" else "接收"
        transferLabel.text = if (progress.done) "已完成$direction：${progress.name}" else "正在$direction：${progress.name}"
        transferDetail.text = if (progress.done) "100%" else "${progress.progress.coerceIn(0, 100)}%"
        transferBar.progress = progress.progress.coerceIn(0, 100)
        transferCard.visibility = View.VISIBLE
    }

    private fun receivedPanel(): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 0, 0, 0) }
        panel.addView(TextView(this).apply { text = "已接收文件"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, 14) })
        receivedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(this).apply { addView(receivedBox) }, LinearLayout.LayoutParams(-1, 0, 1f))
        renderReceived()
        return panel
    }

    private fun focusButton(label: String, size: Float, normalColor: Int, action: () -> Unit): TextView = TextView(this).apply {
        id = View.generateViewId()
        text = label.replace("↻  ", "").replace("⚙  ", "")
        gravity = Gravity.CENTER
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
        maxLines = 1
        setHorizontallyScrolling(false)
        ellipsize = null
        minWidth = 0
        minimumWidth = 0
        setTextColor(if (normalColor == lime) bg else Color.WHITE)
        background = rounded(normalColor, 10)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) lime else normalColor, 10)
            (view as TextView).setTextColor(if (focused || normalColor == lime) bg else Color.WHITE)
        }
        setPadding(8, 10, 8, 10)
        minimumHeight = 58
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "留空则使用设备型号"
            setText(prefs.getString("device_name", ""))
            setSingleLine(true)
        }
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 6, 0, 6); addView(input) }
        box.addView(TextView(this).apply {
            text = "日志文件：${Logcat.logPath()}"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, 14, 0, 0)
        })
        box.addView(TextView(this).apply { text = "本机网段"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 18, 0, 4) })
        box.addView(DiscoveryNetworkSettings.localNetworksPanel(this, tv = true))
        box.addView(TextView(this).apply { text = "发现网段"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 22, 0, 4) })
        box.addView(focusButton("配置发现网段", 14f, panelFocused) {
            dialog.dismiss()
            DiscoveryNetworkSettings.show(this, service, true)
        }, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 12 })
        box.addView(TextView(this).apply { text = "版本：${BuildConfig.VERSION_NAME}"; textSize = 12f; setTextColor(muted); setPadding(0, 18, 0, 0) })
        lateinit var loggingToggle: TextView
        loggingToggle = focusButton(if (Logcat.isEnabled()) "诊断日志：开启" else "诊断日志：关闭", 14f, panelFocused) {
            val enabled = Logcat.setEnabled(!Logcat.isEnabled())
            service.updateDiagnostics()
            if (enabled) TvDeviceDiagnostics.record(this)
            dialog.dismiss()
            showSettings()
            Toast.makeText(this@TvMainActivity, if (enabled) "诊断日志已开启" else "诊断日志已关闭", Toast.LENGTH_SHORT).show()
        }
        box.addView(loggingToggle, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 12 })
        if (Build.VERSION.SDK_INT >= 30) {
            box.addView(focusButton(if (hasAllFilesAccess()) "所有文件访问：已开启" else "所有文件访问：未开启", 14f, panelFocused) {
                dialog.dismiss()
                requestAllFilesAccess()
            }, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 10 })
        }
        if (Logcat.isEnabled()) {
            box.addView(focusButton("发送诊断日志", 14f, panelFocused) { dialog.dismiss(); sendDiagnosticLog() }, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 10 })
            box.addView(focusButton("清空诊断日志", 14f, panelFocused) { dialog.dismiss(); confirmClearLogs() }, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 10 })
        }
        val scroll = ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            addView(box)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 20)
            background = rounded(panel, 18)
            addView(TextView(this@TvMainActivity).apply { text = "设置"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            addView(TextView(this@TvMainActivity).apply { text = "使用方向键上下浏览全部设置"; textSize = 13f; setTextColor(muted); setPadding(0, 6, 0, 10) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 0) }
        val close = focusButton("关闭", 14f, panelFocused) { dialog.dismiss() }
        val save = focusButton("保存", 14f, lime) {
            prefs.edit().putString("device_name", input.text.toString().trim()).apply()
            dialog.dismiss()
            restartService()
        }
        actions.addView(close, LinearLayout.LayoutParams(0, 58, 1f))
        actions.addView(save, LinearLayout.LayoutParams(0, 58, 1f).apply { marginStart = 12 })
        root.addView(actions, LinearLayout.LayoutParams(-1, 58))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val metrics = resources.displayMetrics
        dialog.window?.setLayout((metrics.widthPixels * 0.88f).toInt(), (metrics.heightPixels * 0.9f).toInt())
        scroll.post { loggingToggle.requestFocus() }
        box.post { scroll.smoothScrollTo(0, loggingToggle.top) }
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("清空诊断日志？")
            .setMessage("将清空电视上的私有日志和 Download/LanShare 中的日志副本。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                val result = Logcat.clearLogs()
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun sendDiagnosticLog() {
        val publicLog = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LanShare/lanshare.log")
        val source = publicLog.takeIf { it.isFile } ?: Logcat.privateLogFile()
        if (source == null) {
            Toast.makeText(this, "未找到诊断日志", Toast.LENGTH_LONG).show()
            return
        }
        val snapshot = File(cacheDir, "lanshare-log-${System.currentTimeMillis()}.log")
        try {
            source.inputStream().use { input -> snapshot.outputStream().use { output -> input.copyTo(output) } }
        } catch (error: Exception) {
            Logcat.e("log snapshot", error)
            Toast.makeText(this, "无法读取诊断日志", Toast.LENGTH_LONG).show()
            return
        }
        selected = null
        pendingUris = emptyList()
        pendingLogSend = true
        pendingLogFile = snapshot
        Logcat.d("send log", "snapshot queued; previous target cleared; waiting for explicit device selection")
        status.text = "日志发送模式：请选择中间栏的设备"
        Toast.makeText(this, "请点击一个附近设备发送日志", Toast.LENGTH_LONG).show()
    }

    private fun receivedDirectories(): List<File> = listOfNotNull(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LanShare"),
        getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "LanShare") }
    ).distinctBy { it.absolutePath }

    private fun listReceivedFiles(): List<File> = receivedDirectories()
        .flatMap { it.listFiles()?.toList() ?: emptyList() }
        .filter { it.isFile }
        .distinctBy { it.name }
        .sortedByDescending { it.lastModified() }
        .take(6)

    private fun renderReceived() {
        if (!::receivedBox.isInitialized) return
        val box = receivedBox
        box.removeAllViews()
        if (receivedFiles.isEmpty()) box.addView(TextView(this).apply { text = "暂无接收文件"; setTextColor(muted) })
        receivedFiles.take(32).forEachIndexed { index, file ->
            val item = TextView(this).apply {
                id = View.generateViewId()
                if (index == 0) firstReceivedFocusId = id
                text = "${file.name}\n${formatSize(file.length())}"
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(14, 10, 14, 10)
                background = rounded(panel, 8)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(if (focused) lime else panel, 8)
                    (view as TextView).setTextColor(if (focused) bg else Color.WHITE)
                }
                setOnClickListener { handleReceivedFileClick(file) }
            }
            box.addView(item, LinearLayout.LayoutParams(-1, 64).apply { bottomMargin = 8 })
        }
    }

    private fun handleReceivedFileClick(file: File) {
        if (file.extension.equals("apk", true)) {
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage("已保存到 ${file.parentFile?.absolutePath ?: "LanShare"}")
                .setNeutralButton("打开位置") { _, _ -> openReceivedFolder(file) }
                .setNegativeButton("关闭", null)
                .setPositiveButton("安装") { _, _ -> installApk(file) }
                .show()
        } else openReceivedFolder(file)
    }

    private fun openReceivedFolder(file: File) {
        Logcat.d("open location", "TV opening built-in manager for file=${file.absolutePath}; exists=${file.isFile}")
        showFileManager(file.parentFile ?: file)
    }

    private fun showFileManager(directory: File) {
        val entries = directory.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }).orEmpty()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 0, 20, 0) }
        list.addView(TextView(this).apply { text = directory.absolutePath; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, 12) })
        directory.parentFile?.let { parent -> list.addView(fileManagerRow("..  返回上级目录") { showFileManager(parent) }) }
        if (entries.isEmpty()) list.addView(TextView(this).apply { text = "目录为空"; setTextColor(muted); setPadding(0, 20, 0, 20) })
        entries.take(200).forEach { entry ->
            val label = if (entry.isDirectory) "[目录] ${entry.name}" else "${entry.name}\n${formatSize(entry.length())}"
            list.addView(fileManagerRow(label) { if (entry.isDirectory) showFileManager(entry) else showFileActions(entry) })
        }
        val scroll = ScrollView(this).apply { isFocusable = false; addView(list) }
        AlertDialog.Builder(this).setTitle("LanShare 文件管理器").setView(scroll).setNegativeButton("关闭", null).show()
        list.post { list.getChildAt(0)?.requestFocus() }
    }

    private fun fileManagerRow(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(14, 12, 14, 12)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = rounded(panel, 8)
        setOnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) lime else panel, 8)
            (view as TextView).setTextColor(if (focused) bg else Color.WHITE)
        }
        setOnClickListener { action() }
    }

    private fun showFileActions(file: File) {
        val builder = AlertDialog.Builder(this).setTitle(file.name).setMessage("${file.absolutePath}\n${formatSize(file.length())}")
        if (file.extension.equals("apk", true)) builder.setPositiveButton("安装") { _, _ -> installApk(file) }
        builder.setNeutralButton("发送") { _, _ ->
            selected?.let { target -> status.text = "正在发送 ${file.name}…"; service.sendLocalFile(file, target) }
                ?: Toast.makeText(this, "请先在中间栏选择设备", Toast.LENGTH_LONG).show()
        }
        builder.setNegativeButton("删除") { _, _ ->
            if (file.delete()) { receivedFiles.removeAll { it.absolutePath == file.absolutePath }; renderReceived(); Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(this, "删除失败", Toast.LENGTH_LONG).show()
        }
        builder.show()
    }

    private fun hasLanShareFolderAccess(): Boolean = contentResolver.persistedUriPermissions.any { permission ->
        try { android.provider.DocumentsContract.getTreeDocumentId(permission.uri) == folderDocumentId }
        catch (_: Exception) { false }
    }

    private fun requestLanShareFolderAccess() {
        if (hasLanShareFolderAccess()) {
            Logcat.d("folder access", "TV Download/LanShare permission already persisted")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("需要访问下载目录")
            .setMessage("请选择 Download/LanShare，TV 将使用它保存和查看接收文件。")
            .setNegativeButton("稍后") { _, _ -> Logcat.d("folder access", "TV startup permission request postponed") }
            .setPositiveButton("选择目录") { _, _ -> launchLanShareFolderPicker("TV startup permission") }
            .show()
    }

    private fun openAuthorizedLanShareFolder(): Boolean {
        val tree = contentResolver.persistedUriPermissions.firstOrNull { permission ->
            try { android.provider.DocumentsContract.getTreeDocumentId(permission.uri) == folderDocumentId }
            catch (_: Exception) { false }
        }?.uri ?: return false
        val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) putExtra("android.provider.extra.INITIAL_URI", document)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val handler = packageManager.resolveActivity(intent, 0)
        Logcat.d("open location", "TV authorized folder contents handler=${handler?.activityInfo?.packageName ?: "none"}; treeUri=$tree; initialDocumentUri=$document")
        if (handler == null) return false
        return try { startActivity(intent); true } catch (error: Exception) { Logcat.e("open location", error); false }
    }

    private fun launchLanShareFolderPicker(reason: String) {
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", folderDocumentId
        )
        val initialDocument = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, folderDocumentId)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) putExtra("android.provider.extra.INITIAL_URI", initialDocument)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            Logcat.d("open location", "$reason: TV directory picker started; treeUri=$tree; initialDocumentUri=$initialDocument")
            startActivityForResult(intent, folderAccessRequestCode)
        } catch (error: Exception) {
            Logcat.e("open location", error)
            try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } catch (_: Exception) { Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_LONG).show() }
        }
    }

    private fun formatSize(size: Long) = if (size < 1024 * 1024) "${size / 1024} KB" else "%.1f MB".format(size / 1024f / 1024f)

    private fun renderDevices() {
        if (!::devicesBox.isInitialized) return
        devicesBox.removeAllViews()
        if (devices.isEmpty()) devicesBox.addView(TextView(this).apply { text = "暂未发现设备\n跨网段请在设置中配置发现网段"; textSize = 16f; setTextColor(muted); setPadding(8, 30, 8, 30) })
        devices.forEachIndexed { index, device ->
            val item = TextView(this).apply {
                id = View.generateViewId()
                if (index == 0) firstDeviceFocusId = id
                text = "${device.name}\n${device.address.hostAddress}"
                // Text is measured in sp; let both lines determine the card height.
                minHeight = 78
                textSize = 17f
                setTextColor(Color.WHITE)
                setPadding(20, 16, 20, 16)
                background = rounded(panel, 10)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                nextFocusLeftId = primaryLeftFocusId
                setOnClickListener {
                    selected = device
                    if (pendingLogSend) {
                        pendingLogSend = false
                        sendPending()
                    } else if (pendingUris.isEmpty()) pickFile() else sendPending()
                }
                setOnFocusChangeListener { view, focused ->
                    view.background = rounded(if (focused) lime else panel, 10)
                    (view as TextView).setTextColor(if (focused) bg else Color.WHITE)
                }
            }
            devicesBox.addView(item, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })
        }
        if (firstDeviceFocusId != 0) findViewById<View>(primaryLeftFocusId)?.nextFocusRightId = firstDeviceFocusId
        if (firstReceivedFocusId != 0) {
            findViewById<View>(firstDeviceFocusId)?.nextFocusRightId = firstReceivedFocusId
            findViewById<View>(firstReceivedFocusId)?.nextFocusLeftId = firstDeviceFocusId
        }
    }

    private fun restartService() { selected = null; service.refreshDiscovery(); status.text = "正在重新搜索…" }
    private fun pickFile() {
        if (selected == null) {
            Toast.makeText(this, "请先在右侧选择接收设备", Toast.LENGTH_LONG).show()
            return
        }
        browserLog("picker opened; sdk=${Build.VERSION.SDK_INT}; manufacturer=${Build.MANUFACTURER}; target=${selected?.name}")
        showFileBrowser(Environment.getExternalStorageDirectory())
    }

    private fun browserLog(message: String) {
        try { Logcat.d("tv file browser", message) } catch (_: Exception) { }
    }

    private fun logBrowserStorageAccess() {
        if (!Logcat.isEnabled()) return
        try {
            val permissions = listOf(
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO"
            )
            permissions.forEach { permission ->
                try { browserLog("permission=$permission; granted=${checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED}") }
                catch (error: Exception) { browserLog("permission=$permission; error=${error.javaClass.simpleName}") }
            }
            if (Build.VERSION.SDK_INT >= 30) browserLog("allFilesAccess=${Environment.isExternalStorageManager()}")
            browserLog("package=$packageName; targetSdk=${applicationInfo.targetSdkVersion}; persistedUriPermissions=${contentResolver.persistedUriPermissions.joinToString { "${it.uri}; read=${it.isReadPermission}; write=${it.isWritePermission}" }}")
        } catch (error: Exception) { browserLog("storage diagnostics failed; error=${error.javaClass.simpleName}: ${error.message}") }
    }

    private fun showFileBrowser(directory: File) {
        fileBrowserDialog?.dismiss()
        val started = System.currentTimeMillis()
        val raw = try { directory.listFiles() } catch (error: Exception) {
            browserLog("listFiles exception; path=${directory.absolutePath}; error=${error.javaClass.simpleName}: ${error.message}")
            null
        }
        val entries = try {
            raw?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }).orEmpty()
        } catch (error: Exception) {
            browserLog("sort failed; path=${directory.absolutePath}; error=${error.javaClass.simpleName}")
            raw?.toList().orEmpty()
        }
        if (Logcat.isEnabled()) {
            try {
                logBrowserStorageAccess()
                browserLog("directory=${directory.absolutePath}; exists=${directory.exists()}; isDirectory=${directory.isDirectory}; canRead=${directory.canRead()}; listFilesNull=${raw == null}; total=${entries.size}; shown=${minOf(200, entries.size)}; omitted=${maxOf(0, entries.size - 200)}; elapsedMs=${System.currentTimeMillis() - started}")
                entries.forEachIndexed { index, entry ->
                    try {
                        browserLog("entry index=$index; shown=${index < 200}; name=${entry.name}; path=${entry.absolutePath}; directory=${entry.isDirectory}; file=${entry.isFile}; readable=${entry.canRead()}; hidden=${entry.isHidden}; bytes=${entry.length()}")
                    } catch (error: Exception) { browserLog("entry index=$index; metadataError=${error.javaClass.simpleName}") }
                }
            } catch (error: Exception) { browserLog("directory metadataError=${error.javaClass.simpleName}") }
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 0, 18, 0) }
        list.addView(TextView(this).apply { text = directory.absolutePath; textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, 12) })
        directory.parentFile?.let { parent -> list.addView(fileBrowserRow("..  返回上级目录") { showFileBrowser(parent) }) }
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply { text = "目录为空或无法读取"; setTextColor(muted); setPadding(0, 16, 0, 16) })
        } else entries.take(200).forEach { entry ->
            val label = if (entry.isDirectory) "[目录] ${entry.name}" else "${entry.name}  (${formatSize(entry.length())})"
            list.addView(fileBrowserRow(label) {
                browserLog("clicked path=${entry.absolutePath}; target=${selected?.name}")
                try {
                    if (entry.isDirectory) showFileBrowser(entry)
                    else selected?.let { target ->
                        fileBrowserDialog?.dismiss()
                        status.text = "正在发送 ${entry.name}…"
                        service.sendLocalFile(entry, target)
                    }
                } catch (error: Exception) {
                    browserLog("click failed; path=${entry.absolutePath}; error=${error.javaClass.simpleName}: ${error.message}")
                    Toast.makeText(this, "无法打开文件或目录", Toast.LENGTH_LONG).show()
                }
            })
        }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isFillViewport = true
            addView(list)
        }
        val dialog = AlertDialog.Builder(this).setTitle("选择要发送的文件").setView(scroll).setNegativeButton("关闭", null).create()
        fileBrowserDialog = dialog
        dialog.setOnDismissListener { if (fileBrowserDialog === dialog) fileBrowserDialog = null }
        dialog.show()
        list.post { if (dialog.isShowing) list.getChildAt(0)?.requestFocus() }
    }

    private fun fileBrowserRow(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(14, 12, 14, 12)
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        background = rounded(panel, 8)
        setOnFocusChangeListener { view, focused ->
            view.background = rounded(if (focused) lime else panel, 8)
            (view as TextView).setTextColor(if (focused) bg else Color.WHITE)
        }
        setOnClickListener { action() }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == folderAccessRequestCode) {
            val uri = data?.data
            val documentId = try { uri?.let { android.provider.DocumentsContract.getTreeDocumentId(it) } } catch (_: Exception) { null }
            val expected = documentId == folderDocumentId
            Logcat.d("folder access", "TV picker result=$resultCode; uri=${uri ?: "none"}; documentId=$documentId; expected=$expected")
            if (resultCode == RESULT_OK && uri != null && expected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try {
                    val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(uri, flags)
                    Logcat.d("folder access", "TV persisted directory permission; flags=$flags")
                } catch (error: Exception) { Logcat.e("folder access", error) }
            }
            return
        }
        if (requestCode != 10 || resultCode != RESULT_OK || data == null) return; pendingLogSend = false; pendingUris = buildList { data.data?.let { add(it) }; data.clipData?.let { for (i in 0 until it.itemCount) add(it.getItemAt(i).uri) } }.distinct(); if (selected == null && devices.size == 1) selected = devices.first(); if (selected == null) Toast.makeText(this, "请先选择目标设备", Toast.LENGTH_SHORT).show() else sendPending() }
    private fun sendPending() {
        val target = selected ?: return
        pendingLogFile?.let { file ->
            pendingLogFile = null
            pendingLogSend = false
            status.text = "正在发送诊断日志…"
            service.sendLocalFile(file, target)
            file.deleteOnExit()
        }
        pendingUris.forEach { service.send(it, target) }
        pendingUris = emptyList()
        pendingLogSend = false
        status.text = "已加入发送队列"
    }

    private fun installApk(file: File) {
        if (!file.isFile) {
            Toast.makeText(this, "APK 文件不存在", Toast.LENGTH_LONG).show()
            return
        }
        val isXgimi = try {
            Build.MANUFACTURER?.trim().equals("XGIMI", ignoreCase = true) ||
                Build.BRAND?.trim().equals("XGIMI", ignoreCase = true)
        } catch (error: Exception) {
            Logcat.e("install device detection", error)
            false
        }
        val canInstall = try {
            Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()
        } catch (error: Exception) {
            Logcat.e("install permission check", error)
            false
        }
        Logcat.d("install", "install requested; manufacturer=${Build.MANUFACTURER}; brand=${Build.BRAND}; model=${Build.MODEL}; sdk=${Build.VERSION.SDK_INT}; xgimiDirectInstall=$isXgimi; canRequestPackageInstalls=$canInstall; file=${file.absolutePath}")
        // XGIMI firmware can install despite returning false and omitting the settings activity.
        // Only skip our preflight check; the system installer still enforces its own policy.
        if (!isXgimi && !canInstall) {
            AlertDialog.Builder(this).setTitle("允许安装未知应用")
                .setMessage("请在系统设置中允许 LanShare 安装应用，返回后重新点击安装 APK。")
                .setPositiveButton("打开设置") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (error: Exception) {
                        Logcat.e("install permission settings", error)
                        Toast.makeText(this, "系统不支持此授权入口，请使用系统支持的安装方式", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("取消", null).show()
            return
        }

        // API 23 TV installers commonly do not resolve FileProvider content:// URIs.
        // Use the legacy file URI there; newer Android versions require content://.
        val useLegacyFileUri = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        val uri = if (useLegacyFileUri) Uri.fromFile(file) else Uri.parse("content://$packageName.apkprovider/${Uri.encode(file.name)}")
        Logcat.d("install", "starting APK install; sdk=${Build.VERSION.SDK_INT}; legacyFileUri=$useLegacyFileUri; file=${file.absolutePath}; uri=$uri")
        val grantFlags = if (useLegacyFileUri) 0 else Intent.FLAG_GRANT_READ_URI_PERMISSION
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            putExtra("android.intent.extra.NOT_UNKNOWN_SOURCE", true)
            addFlags(grantFlags or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!useLegacyFileUri) clipData = android.content.ClipData.newRawUri(file.name, uri)
        }
        try {
            Logcat.d("install", "INSTALL_PACKAGE handler=${packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName ?: "none"}")
            startActivity(intent)
        } catch (installError: Exception) {
            Logcat.e("install ACTION_INSTALL_PACKAGE", installError)
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(grantFlags or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!useLegacyFileUri) clipData = android.content.ClipData.newRawUri(file.name, uri)
            }
            try {
                Logcat.d("install", "VIEW handler=${packageManager.resolveActivity(fallback, 0)?.activityInfo?.packageName ?: "none"}")
                startActivity(fallback)
            } catch (viewError: Exception) {
                Logcat.e("install ACTION_VIEW", viewError)
                AlertDialog.Builder(this).setTitle("无法打开安装器")
                    .setMessage("安装器错误：${installError.message ?: "未知错误"}\n备用方式：${viewError.message ?: "不支持"}\n文件：${file.absolutePath}")
                    .setPositiveButton("确定", null).show()
            }
        }
    }

    private fun localIp(): String = try { java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress ?: "设备 IP" } catch (_: Exception) { "设备 IP" }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
}
