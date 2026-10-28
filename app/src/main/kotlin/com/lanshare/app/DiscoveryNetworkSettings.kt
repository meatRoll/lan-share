package com.lanshare.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*

object DiscoveryNetworkSettings {
    private val lime = Color.rgb(215, 243, 107)

    fun localNetworksPanel(context: Context, tv: Boolean = false): LinearLayout {
        val networks = TextView(context).apply {
            textSize = if (tv) 14f else 13f
            setTextColor(lime)
            setPadding(0, 4, 0, 4)
        }
        fun updateNetworks() {
            val current = DiscoveryNetworks.localSubnets().joinToString("\n") { it.cidr }
            networks.text = if (current.isEmpty()) "未连接 IPv4 网络" else current
        }
        updateNetworks()
        val copy = Button(context).apply {
            text = "复制网段"
            isAllCaps = false
            setOnClickListener {
                val current = DiscoveryNetworks.localSubnets().joinToString("\n") { it.cidr }
                if (current.isNotEmpty()) {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("LanShare 当前网段", current))
                    Toast.makeText(context, "当前网段已复制", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "当前没有可复制的 IPv4 网段", Toast.LENGTH_SHORT).show()
            }
        }
        val qr = Button(context).apply {
            text = "显示网段二维码"
            isAllCaps = false
            setOnClickListener { showQr(context, tv = tv) }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(networks)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(copy, LinearLayout.LayoutParams(0, 52.dp(context), 1f))
                addView(qr, LinearLayout.LayoutParams(0, 52.dp(context), 1f).apply { marginStart = 8.dp(context) })
            })
        }
    }

    fun currentButton(context: Context, tv: Boolean): TextView = TextView(context).apply {
        val networks = DiscoveryNetworks.localSubnets().joinToString("\n") { it.cidr }
        text = if (networks.isEmpty()) "当前网段：未连接 IPv4 网络" else "当前网段\n$networks\n${if (tv) "按确认键复制" else "点击复制"}"
        textSize = if (tv) 14f else 13f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16, 12, 16, 12)
        setTextColor(Color.rgb(215, 243, 107))
        isFocusable = true
        isClickable = networks.isNotEmpty()
        setOnClickListener {
            val latest = DiscoveryNetworks.localSubnets().joinToString("\n") { it.cidr }
            if (latest.isNotEmpty()) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("LanShare 当前网段", latest))
                Toast.makeText(context, "当前网段已复制", Toast.LENGTH_SHORT).show()
            }
        }
        if (tv) setOnFocusChangeListener { _, focused ->
            setBackgroundColor(if (focused) Color.rgb(215, 243, 107) else Color.TRANSPARENT)
            setTextColor(if (focused) Color.rgb(13, 15, 12) else Color.rgb(215, 243, 107))
        }
    }

    private fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()

    fun showQr(context: Context, networks: String? = null, tv: Boolean = false) {
        val qrNetworks = networks ?: DiscoveryNetworks.localSubnets().joinToString("\n") { it.cidr }
        if (qrNetworks.isBlank()) {
            Toast.makeText(context, "当前没有可显示的 IPv4 网段", Toast.LENGTH_LONG).show()
            return
        }
        val bitmap = try { DiscoveryQr.bitmap(qrNetworks) } catch (error: IllegalArgumentException) {
            Toast.makeText(context, error.message ?: "无法生成网段二维码", Toast.LENGTH_LONG).show()
            return
        }
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(20, 8, 20, 8) }
        box.addView(ImageView(context).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = "发现网段二维码"
        }, LinearLayout.LayoutParams(-1, 420))
        box.addView(TextView(context).apply {
            text = "扫码导入的网段：\n$qrNetworks"
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 12, 0, 0)
        })
        AlertDialog.Builder(context).setTitle("发现网段二维码").setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("关闭", null).show()
    }

    fun scan(context: Context, requestCode: Int) {
        (context as? android.app.Activity)?.startActivityForResult(Intent(context, DiscoveryQrScanActivity::class.java), requestCode)
            ?: context.startActivity(Intent(context, DiscoveryQrScanActivity::class.java))
    }
    fun show(context: Context, service: LanShareService, tv: Boolean) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        box.addView(TextView(context).apply {
            text = "填写要搜索的网段，每行一个，例如 192.168.1.0/24。支持多个私有 IPv4 网段，前缀 /20～/32，合计最多 4096 个地址。留空仅使用正常广播和已知地址发现。保存后立即搜索，每轮完成后等待 30 秒再次查询。"
            textSize = 14f
        })
        val input = EditText(context).apply {
            hint = "192.168.1.0/24\n192.168.2.0/24"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP
            minLines = 3
            maxLines = 6
            setText(prefs.getString("discovery_networks", ""))
        }
        box.addView(input)
        val addCurrent = Button(context).apply {
            text = "加入当前网段"
            setOnClickListener {
                input.setText((input.text.toString().trim().split('\n').filter { it.isNotBlank() } + DiscoveryNetworks.localSubnets().map { it.cidr }).distinct().joinToString("\n"))
            }
        }
        box.addView(addCurrent)
        box.addView(Button(context).apply {
            text = "显示填写网段的二维码"
            setOnClickListener { showQr(context, input.text.toString(), tv) }
        })
        fun save(dialog: android.content.DialogInterface) {
            try {
                val canonical = DiscoveryNetworks.parse(input.text.toString()).joinToString("\n") { it.cidr }
                prefs.edit().putString("discovery_networks", canonical).apply()
                service.updateDiscoveryNetworks()
                Toast.makeText(context, "发现网段已保存，正在搜索", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } catch (error: IllegalArgumentException) {
                input.error = error.message?.replace("\n", " · ")
                Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                input.requestFocus()
            }
        }
        if (!tv) {
            val dialog = AlertDialog.Builder(context).setTitle("发现网段")
                .setView(ScrollView(context).apply { addView(box) })
                .setNegativeButton("取消", null).setPositiveButton("保存并搜索", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(dialog) }
            }
            dialog.show()
            return
        }
        val dialog = Dialog(context)
        val scroll = ScrollView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            addView(box)
        }
        val title = TextView(context).apply { text = "发现网段"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        val hint = TextView(context).apply { text = "使用方向键上下浏览，保存按钮始终可见"; textSize = 13f; setTextColor(Color.rgb(164, 174, 155)); setPadding(0, 6, 0, 10) }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 20)
            background = GradientDrawable().apply { setColor(Color.rgb(28, 33, 26)); cornerRadius = 18f }
            addView(title)
            addView(hint)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 0) }
        val cancel = tvButton(context, "取消", Color.rgb(53, 62, 46)) { dialog.dismiss() }
        val saveButton = tvButton(context, "保存并搜索", lime) { save(dialog) }
        actions.addView(cancel, LinearLayout.LayoutParams(0, 58, 1f))
        actions.addView(saveButton, LinearLayout.LayoutParams(0, 58, 1f).apply { marginStart = 12 })
        root.addView(actions, LinearLayout.LayoutParams(-1, 58))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val metrics = context.resources.displayMetrics
        dialog.window?.setLayout((metrics.widthPixels * 0.88f).toInt(), (metrics.heightPixels * 0.9f).toInt())
        saveButton.post { saveButton.requestFocus() }
    }

    private fun tvButton(context: Context, label: String, color: Int, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (color == lime) Color.rgb(13, 15, 12) else Color.WHITE)
        background = GradientDrawable().apply { setColor(color); cornerRadius = 10f }
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        setPadding(8, 10, 8, 10)
        minimumHeight = 58
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            view.background = GradientDrawable().apply { setColor(if (focused) lime else color); cornerRadius = 10f }
            (view as TextView).setTextColor(if (focused || color == lime) Color.rgb(13, 15, 12) else Color.WHITE)
        }
    }
}
