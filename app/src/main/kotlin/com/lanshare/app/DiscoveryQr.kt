package com.lanshare.app

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.charset.StandardCharsets

object DiscoveryQr {
    const val PREFIX = "LANSHARE|NETWORKS|"
    const val RESULT_KEY = "lanshare_networks"

    fun payload(networks: String): String {
        val canonical = DiscoveryNetworks.parse(networks).joinToString("\n") { it.cidr }
        require(canonical.isNotEmpty()) { "没有可分享的网段" }
        require(canonical.length <= 1800) { "网段过多，请分批分享二维码" }
        return PREFIX + canonical
    }
    fun networks(payload: String): String? = payload.takeIf { it.startsWith(PREFIX) }
        ?.removePrefix(PREFIX)?.trim()?.takeIf { it.isNotEmpty() }

    fun bitmap(networks: String, size: Int = 760): Bitmap {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to StandardCharsets.UTF_8.name(), EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix: BitMatrix = MultiFormatWriter().encode(payload(networks), BarcodeFormat.QR_CODE, size, size, hints)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until size) for (x in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) 0xff10120f.toInt() else 0xfff5f7ef.toInt())
        }
    }
}
