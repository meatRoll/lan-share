package com.lanshare.app

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test

class DiscoveryQrTest {
    @Test fun multipleNetworksRoundTripThroughQrDecoder() {
        val payload = DiscoveryQr.payload("192.168.211.229/24\n192.168.216.0/24\n192.168.211.0/25")
        assertEquals("LANSHARE|NETWORKS|192.168.211.0/24\n192.168.216.0/24", payload)
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(512, 512, pixels))))
        assertEquals("192.168.211.0/24\n192.168.216.0/24", DiscoveryQr.networks(decoded.text))
    }
    @Test fun rejectsUnrelatedAndEmptyPayloads() {
        assertNull(DiscoveryQr.networks("https://example.com"))
        assertNull(DiscoveryQr.networks("LANSHARE|NETWORKS|"))
        for (text in listOf("", "8.8.8.0/24")) {
            try { DiscoveryQr.payload(text); fail("Accepted $text") } catch (_: IllegalArgumentException) { }
        }
    }
}
