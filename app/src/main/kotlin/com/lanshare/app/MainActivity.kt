package com.lanshare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.provider.Settings
import java.io.File
import android.view.Gravity
import android.widget.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity(), LanShareService.Listener {
    private val bg = Color.rgb(16, 18, 15)
    private val panel = Color.rgb(29, 33, 27)
    private val lime = Color.rgb(215, 243, 107)
    private val muted = Color.rgb(157, 166, 149)
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var service: LanShareService
    private var devices: List<LanShareService.Device> = emptyList()
    private var selected: LanShareService.Device? = null
    private var pendingUris: List<Uri> = emptyList()
    private var pendingLogSend = false
    private var status = "正在搜索局域网设备…"
    private val receivedFiles = mutableListOf<File>()
    private val receivedPrefs by lazy { getSharedPreferences("received_files", MODE_PRIVATE) }
    private val openLocationRequestCode = 1207
    private val folderDocumentId = "primary:Download/LanShare"
    private val fileManagerReturnNotificationId = 1208
    private val fileManagerReturnChannel = "file_manager_return_v2"
    private val notificationPermissionRequestCode = 42

    override fun onStart() {
        super.onStart()
        cancelFileManagerReturnNotification()
        receivedFiles.clear()
        receivedPrefs.getStringSet("paths", emptySet()).orEmpty().map(::File).filter { it.isFile }
            .sortedByDescending { it.lastModified() }.take(8).forEach { receivedFiles.add(it) }
        if (::content.isInitialized) showHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logcat.init(this)
        Logcat.d("lifecycle", "phone activity created")
        service = LanShareService(this, this)
        acceptShareIntent(intent)
        showHome()
        service.start()
        if (needsBluetoothPermission()) requestBluetoothPermission() else requestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent != null && acceptShareIntent(intent)) showHome()
    }

    override fun onDestroy() { service.stop(); super.onDestroy() }

    private fun acceptShareIntent(intent: Intent): Boolean {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false

        val sharedUris = buildList {
            if (action == Intent.ACTION_SEND) {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { add(it) }
            }
            intent.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
            }
            if (action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { add(it) }
            }
        }.distinct()
        if (sharedUris.isEmpty()) {
            Toast.makeText(this, "没有收到可发送的文件", Toast.LENGTH_SHORT).show()
            return false
        }

        sharedUris.forEach { uri ->
            try {
                val flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (flags != 0) contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Many share providers grant temporary access only; keep using it during this session.
            }
        }
        pendingUris = sharedUris
        selected = null
        status = "已选择 ${sharedUris.size} 个文件，请选择附近设备发送"
        Logcat.d("share intent", "accepted action=$action; uriCount=${sharedUris.size}; type=${intent.type}")
        return true
    }

    private fun needsBluetoothPermission(): Boolean = Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Logcat.d("notification", "requesting POST_NOTIFICATIONS")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequestCode)
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 41)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            41 -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    service.stop()
                    service = LanShareService(this, this)
                    service.start()
                }
                requestNotificationPermission()
            }
            notificationPermissionRequestCode -> Logcat.d("notification", "POST_NOTIFICATIONS result=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
        }
    }

    override fun onDevicesChanged(devices: List<LanShareService.Device>) {
        runOnUiThread { this.devices = devices; status = "已发现 ${devices.size} 台设备"; showHome() }
    }

    override fun onIncoming(request: LanShareService.IncomingRequest): Boolean {
        var result = false
        val latch = CountDownLatch(1)
        runOnUiThread {
            AlertDialog.Builder(this).setTitle("收到文件")
                .setMessage("${request.name}\n大小：${formatSize(request.size)}\n来自：${request.address}")
                .setNegativeButton("拒绝") { _, _ -> latch.countDown() }
                .setPositiveButton("接收") { _, _ -> result = true; latch.countDown() }
                .setOnCancelListener { latch.countDown() }.show()
        }
        latch.await(60, TimeUnit.SECONDS)
        return result
    }

    override fun onTransfer(message: String, progress: Int) { runOnUiThread { status = "$message  $progress%"; showHome() } }
    override fun onError(message: String) { runOnUiThread { status = message; Toast.makeText(this, message, Toast.LENGTH_LONG).show(); showHome() } }

    override fun onApkReceived(file: File) {
        // Receiving only saves the file. APK actions are available from the received-files list.
    }

    override fun onWebFileReceived(file: File) { }

    override fun onFileReceived(file: File) {
        runOnUiThread {
            receivedFiles.removeAll { it.absolutePath == file.absolutePath }
            receivedFiles.add(0, file)
            receivedPrefs.edit().putStringSet("paths", (receivedFiles.map { it.absolutePath } + receivedPrefs.getStringSet("paths", emptySet()).orEmpty()).take(32).toSet()).apply()
            showHome()
        }
    }

    private fun handleReceivedFileClick(file: File) {
        if (!file.extension.equals("apk", true) && file.parentFile?.name == "LanShare") {
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage("已保存到 Download/LanShare")
                .setPositiveButton("关闭", null)
                .show()
            return
        }
        if (file.extension.equals("apk", true)) {
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage("已保存到 Download/LanShare")
                .setNeutralButton("打开位置") { _, _ -> openReceivedFile(file) }
                .setNegativeButton("关闭", null)
                .setPositiveButton("安装") { _, _ -> installApk(file) }
                .show()
        } else openReceivedFile(file)
    }

    private fun openReceivedFile(file: File) {
        val authorized = hasLanShareFolderAccess()
        Logcat.d("open location", "opening folder for file=${file.absolutePath}; exists=${file.isFile}; authorized=$authorized")
        if (authorized && openAuthorizedLanShareFolder()) return
        launchLanShareFolderPicker("open location")
    }

    private fun postFileManagerReturnNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Logcat.d("open location", "notification return action unavailable; POST_NOTIFICATIONS not granted")
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(fileManagerReturnChannel, "LanShare 返回提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "从系统文件管理器返回 LanShare"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 180, 300)
                enableLights(true)
                setShowBadge(true)
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
            })
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(this, fileManagerReturnNotificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = android.app.Notification.Builder(this, fileManagerReturnChannel)
            .setSmallIcon(com.lanshare.app.R.drawable.ic_lanshare)
            .setContentTitle("正在系统文件界面中操作")
            .setContentText("点击返回 LanShare")
            .setContentIntent(pending)
            .setStyle(android.app.Notification.BigTextStyle().bigText("正在系统文件管理器中浏览 Download/LanShare。点击此通知立即返回 LanShare。"))
            .setDefaults(android.app.Notification.DEFAULT_SOUND or android.app.Notification.DEFAULT_VIBRATE or android.app.Notification.DEFAULT_LIGHTS)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(android.app.Notification.PRIORITY_HIGH)
            .build()
        manager.notify(fileManagerReturnNotificationId, notification)
        val channel = manager.getNotificationChannel(fileManagerReturnChannel)
        Logcat.d("open location", "posted file manager return notification; importance=${channel?.importance}; sound=${channel?.sound}; vibration=${channel?.shouldVibrate()}")
    }

    private fun cancelFileManagerReturnNotification() {
        getSystemService(NotificationManager::class.java).cancel(fileManagerReturnNotificationId)
    }

    private fun showLanShareFolder() {
        val files = listReceivedFiles()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 0) }
        if (files.isEmpty()) {
            list.addView(TextView(this).apply { text = "文件夹为空"; setTextColor(muted); setPadding(0, 20, 0, 20) })
        } else {
            files.forEach { file ->
                list.addView(TextView(this).apply {
                    text = "${file.name}\n${formatSize(file.length())}"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setPadding(0, 12, 0, 12)
                    isClickable = true
                    setOnClickListener { handleReceivedFileClick(file) }
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Download/LanShare")
            .setMessage("LanShare 文件列表")
            .setView(list)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openAuthorizedLanShareFolder(): Boolean {
        val tree = contentResolver.persistedUriPermissions.firstOrNull { permission ->
            try { android.provider.DocumentsContract.getTreeDocumentId(permission.uri) == folderDocumentId }
            catch (_: Exception) { false }
        }?.uri ?: return false
        // DocumentsUI does not support ACTION_VIEW for tree URIs. Opening a document
        // picker at the persisted tree shows the folder contents without re-requesting access.
        val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )
        val view = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) putExtra("android.provider.extra.INITIAL_URI", document)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val handler = packageManager.resolveActivity(view, 0)
        Logcat.d("open location", "authorized folder contents handler=${handler?.activityInfo?.packageName ?: "none"}; treeUri=$tree; initialDocumentUri=$document")
        if (handler == null) return false
        return try {
            postFileManagerReturnNotification()
            startActivity(view)
            Logcat.d("open location", "authorized folder contents started; initialDocumentUri=$document")
            true
        } catch (error: Exception) {
            Logcat.e("open location", error)
            false
        }
    }

    private fun hasLanShareFolderAccess(): Boolean {
        return contentResolver.persistedUriPermissions.any { permission ->
            try { android.provider.DocumentsContract.getTreeDocumentId(permission.uri) == folderDocumentId }
            catch (_: Exception) { false }
        }
    }

    private fun requestLanShareFolderAccess() {
        if (hasLanShareFolderAccess()) {
            Logcat.d("folder access", "Download/LanShare permission already persisted")
            return
        }
        Logcat.d("folder access", "Download/LanShare permission missing; requesting at app startup")
        AlertDialog.Builder(this)
            .setTitle("需要访问下载目录")
            .setMessage("请选择 Download/LanShare，LanShare 将使用它保存和查看接收文件。")
            .setNegativeButton("稍后") { _, _ -> Logcat.d("folder access", "startup permission request postponed") }
            .setPositiveButton("选择目录") { _, _ -> launchLanShareFolderPicker("startup permission") }
            .show()
    }

    private fun launchLanShareFolderPicker(reason: String) {
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", folderDocumentId
        )
        val initialDocument = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, folderDocumentId)
        val chooser = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) putExtra("android.provider.extra.INITIAL_URI", initialDocument)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            val handler = packageManager.resolveActivity(chooser, 0)
            Logcat.d("open location", "${reason}: directory picker handler=${handler?.activityInfo?.packageName ?: "none"}; treeUri=$tree; initialDocumentUri=$initialDocument")
            if (handler == null) throw ActivityNotFoundException("No activity handles ACTION_OPEN_DOCUMENT_TREE")
            postFileManagerReturnNotification()
            startActivityForResult(chooser, openLocationRequestCode)
            Logcat.d("open location", "${reason}: directory picker started")
        } catch (error: Exception) {
            Logcat.e("open location", error)
            Toast.makeText(this, "无法打开目录选择器，请在文件管理器中进入 Download/LanShare", Toast.LENGTH_LONG).show()
        }
    }

    private fun listReceivedFiles(): List<File> {
        val dirs = listOf(
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "LanShare")
        )
        return dirs.flatMap { it.listFiles()?.toList() ?: emptyList() }.filter { it.isFile }.sortedByDescending { it.lastModified() }.take(8)
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("需要允许安装未知应用").setMessage("请在系统设置中允许 LanShare 安装应用，返回后再次点击 APK 安装。")
                .setPositiveButton("打开设置") { _, _ -> startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }.setNegativeButton("取消", null).show()
            return
        }
        val uri = Uri.parse("content://$packageName.apkprovider/${Uri.encode(file.name)}")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = android.content.ClipData.newRawUri(file.name, uri)
        }
        try { startActivity(intent) }
        catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("无法打开安装器").setMessage("${e.message ?: "系统安装器拒绝打开此 APK"}\n文件：${file.absolutePath}").setPositiveButton("确定", null).show()
        }
    }

    private fun showHome() {
        val root = baseLayout(); title.text = "局域网传输"
        content.addView(label("我的设备", 20))
        content.addView(rowCard(android.os.Build.MODEL ?: "Android", "在线 · 等待附近设备连接", "本机", lime, false))
        content.addView(DiscoveryNetworkSettings.currentButton(this, false))
        content.addView(label("附近设备", 24))
        if (devices.isEmpty()) content.addView(TextView(this).apply { text = "暂未发现设备\n请确认设备网络互通；跨网段请在设置中配置发现网段"; gravity = Gravity.CENTER; setTextColor(muted); textSize = 14f; setPadding(0, 36, 0, 36) })
        devices.forEach { device ->
            val row = rowCard(device.name, "在线 · ${device.address.hostAddress}", "设备", Color.rgb(124, 189, 235), true)
            row.setOnClickListener {
                selected = device
                if (pendingLogSend) {
                    pendingLogSend = false
                    sendPending()
                } else if (pendingUris.isNotEmpty()) sendPending() else pickFile()
            }
            content.addView(row)
        }
        content.addView(TextView(this).apply { text = status; setTextColor(muted); textSize = 12f; setPadding(4, 18, 4, 0) })
        content.addView(label("已接收文件", 20))
        if (receivedFiles.isEmpty()) content.addView(TextView(this).apply { text = "暂无接收文件"; setTextColor(muted); textSize = 13f; setPadding(4, 0, 4, 12) })
        receivedFiles.take(8).forEach { file ->
            val item = rowCard(file.name, formatSize(file.length()), if (file.extension.equals("apk", true)) "APK" else "文件", Color.rgb(170, 200, 120), true)
            item.setOnClickListener { handleReceivedFileClick(file) }
            content.addView(item)
        }
        setContentView(root)
    }

    private fun baseLayout(): LinearLayout {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(20, 18, 20, 8) }
        title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 25f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        top.addView(title, LinearLayout.LayoutParams(0, 56.dp(), 1f))
        top.addView(TextView(this).apply { text = "●  在线"; setTextColor(lime); textSize = 13f })
        outer.addView(top)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 0, 20, 0) }
        outer.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { setPadding(12, 10, 12, 12); setBackgroundColor(Color.rgb(22, 25, 21)) }
        val refresh = navButton("↻  重新搜索") { selected = null; service.refreshDiscovery(); status = "正在搜索…"; showHome() }
        val settings = navButton("⚙  设置") { showSettings() }
        nav.addView(refresh, LinearLayout.LayoutParams(0, 52.dp(), 1f)); nav.addView(settings, LinearLayout.LayoutParams(0, 52.dp(), 1f)); outer.addView(nav)
        return outer
    }

    private fun showSettings() {
        val input = EditText(this).apply {
            hint = "留空则使用蓝牙名称或系统型号"
            setText(getSharedPreferences("settings", MODE_PRIVATE).getString("device_name", ""))
            setSingleLine(true)
        }
        lateinit var settingsDialog: AlertDialog
        val logActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 0, 48, 0)
            addView(input)
            addView(button("配置发现网段", panel, Color.WHITE).apply {
                setOnClickListener {
                    settingsDialog.dismiss()
                    DiscoveryNetworkSettings.show(this@MainActivity, service, false)
                }
            }, LinearLayout.LayoutParams(-1, 52.dp()).apply { topMargin = 12.dp() })
            addView(TextView(this@MainActivity).apply { text = "版本：${BuildConfig.VERSION_NAME}"; textSize = 12f; setTextColor(muted) })
            addView(TextView(this@MainActivity).apply { text = "日志文件：${Logcat.logPath()}"; setTextColor(muted); textSize = 11f; setPadding(0, 16, 0, 0) })
            val loggingToggle = button(if (Logcat.isEnabled()) "诊断日志：开启" else "诊断日志：关闭", panel, Color.WHITE).apply {
                isFocusable = true
                isFocusableInTouchMode = false
                setOnClickListener {
                    val enabled = Logcat.setEnabled(!Logcat.isEnabled())
                    service.updateDiagnostics()
                    text = if (enabled) "诊断日志：开启" else "诊断日志：关闭"
                    logActions.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
                    Toast.makeText(this@MainActivity, if (enabled) "诊断日志已开启" else "诊断日志已关闭", Toast.LENGTH_SHORT).show()
                }
            }
            addView(loggingToggle, LinearLayout.LayoutParams(-1, 52.dp()).apply { topMargin = 18.dp() })
            logActions.apply {
                visibility = if (Logcat.isEnabled()) android.view.View.VISIBLE else android.view.View.GONE
                addView(button("发送日志", panel, Color.WHITE).apply {
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setOnClickListener {
                        settingsDialog.dismiss()
                        shareLog()
                    }
                }, LinearLayout.LayoutParams(-1, 52.dp()).apply { topMargin = 12.dp() })
                addView(button("清空日志", Color.rgb(184, 92, 92), Color.WHITE).apply {
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setOnClickListener { confirmClearLogs() }
                }, LinearLayout.LayoutParams(-1, 52.dp()).apply { topMargin = 12.dp() })
            }
            addView(logActions)
        }
        settingsDialog = AlertDialog.Builder(this).setTitle("设置").setMessage("优先使用自定义名称，其次使用蓝牙名称。此名称会广播给同一 Wi-Fi 下的设备。")
            .setView(box).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("device_name", input.text.toString().trim()).apply()
                service.stop(); service = LanShareService(this, this); service.start(); showHome()
            }.create()
        settingsDialog.show()
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle("清空诊断日志？")
            .setMessage("将清空手机上的私有日志和 Download/LanShare 中的日志副本。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                Toast.makeText(this, "正在清空日志…", Toast.LENGTH_SHORT).show()
                Thread {
                    val result = Logcat.clearLogs()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .show()
    }

    private fun shareLog() {
        val uri = Uri.parse("content://$packageName.apkprovider/log")
        Logcat.d("send log", "queued app log for LanShare transfer; uri=$uri; path=${Logcat.logPath()}")
        pendingUris = listOf(uri)
        pendingLogSend = true
        selected = null
        status = "日志发送模式：请选择附近设备"
        showHome()
        Toast.makeText(this, "请点击一个附近设备发送日志", Toast.LENGTH_LONG).show()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        Logcat.d("file picker", "opening system file picker")
        postFileManagerReturnNotification()
        try { startActivityForResult(intent, 10) }
        catch (error: Exception) {
            cancelFileManagerReturnNotification()
            Logcat.e("file picker", error)
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_LONG).show()
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == openLocationRequestCode) {
            val returnedUri = data?.data
            Logcat.d("open location", "directory picker result=$resultCode; uri=${returnedUri ?: "none"}; flags=${data?.flags ?: 0}")
            if (returnedUri != null) {
                val documentId = try { android.provider.DocumentsContract.getTreeDocumentId(returnedUri) } catch (_: Exception) { null }
                val isExpected = documentId == folderDocumentId
                Logcat.d("open location", "selected directory expected=$isExpected; documentId=$documentId; uriPath=${returnedUri.path}")
                if (resultCode == RESULT_OK && isExpected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    try {
                        val takeFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        contentResolver.takePersistableUriPermission(returnedUri, takeFlags)
                        Logcat.d("open location", "persisted directory permission; flags=$takeFlags")
                    } catch (error: Exception) {
                        Logcat.e("open location", error)
                    }
                }
            }
            return
        }
        if (requestCode != 10 || resultCode != RESULT_OK || data == null) return
        pendingLogSend = false
        pendingUris = buildList { data.data?.let { add(it) }; data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) } }.distinct()
        if (selected == null && devices.size == 1) selected = devices.first()
        if (selected == null) Toast.makeText(this, "请先点击一个附近设备", Toast.LENGTH_SHORT).show() else sendPending()
    }
    private fun sendPending() { val target = selected ?: return; pendingUris.forEach { service.send(it, target) }; pendingUris = emptyList(); pendingLogSend = false; status = "已加入发送队列"; showHome() }

    private fun navButton(text: String, action: () -> Unit) = TextView(this).apply { this.text = text; gravity = Gravity.CENTER; textSize = 14f; setTextColor(Color.WHITE); setOnClickListener { action() } }
    private fun label(text: String, top: Int) = TextView(this).apply { this.text = text; textSize = 13f; setTextColor(muted); setPadding(4, top.dp(), 4, 10) }
    private fun rowCard(name: String, detail: String, badge: String, badgeColor: Int, arrow: Boolean): LinearLayout { val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(14, 12, 14, 12); background = rounded(panel, 14); isFocusable = arrow; isClickable = arrow }; row.addView(TextView(this).apply { text = badge; gravity = Gravity.CENTER; textSize = 11f; setTextColor(bg); background = rounded(badgeColor, 10) }, LinearLayout.LayoutParams(54.dp(), 46.dp())); val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 0, 8, 0) }; texts.addView(TextView(this).apply { text = name; textSize = 16f; setTextColor(Color.WHITE) }); texts.addView(TextView(this).apply { text = detail; textSize = 12f; setTextColor(muted); setPadding(0, 4, 0, 0) }); row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f)); if (arrow) row.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(muted) }); row.layoutParams = LinearLayout.LayoutParams(-1, 70.dp()).apply { bottomMargin = 10.dp() }; return row }
    private fun button(text: String, color: Int, textColor: Int) = TextView(this).apply { this.text = text; gravity = Gravity.CENTER; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(textColor); background = rounded(color, 14) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.dp().toFloat() }
    private fun formatSize(size: Long) = if (size < 0) "未知" else if (size < 1024 * 1024) "${size / 1024} KB" else "%.1f MB".format(size / 1024f / 1024f)
    private fun localIp(): String = try { java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress ?: "设备 IP" } catch (_: Exception) { "设备 IP" }
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
