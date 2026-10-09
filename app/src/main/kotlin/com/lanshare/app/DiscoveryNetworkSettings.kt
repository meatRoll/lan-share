package com.lanshare.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.*

object DiscoveryNetworkSettings {
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
        val dialog = AlertDialog.Builder(context).setTitle("发现网段")
            .setView(ScrollView(context).apply { addView(box) })
            .setNegativeButton("取消", null).setPositiveButton("保存并搜索", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val canonical = DiscoveryNetworks.parse(input.text.toString()).joinToString("\n") { it.cidr }
                    prefs.edit().putString("discovery_networks", canonical).apply()
                    service.updateDiscoveryNetworks()
                    Toast.makeText(context, "发现网段已保存，正在搜索", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } catch (error: IllegalArgumentException) {
                    input.error = error.message
                    Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                    input.requestFocus()
                }
            }
            if (tv) dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
        }
        dialog.show()
    }
}
