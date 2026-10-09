package com.lanshare.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

@Suppress("DEPRECATION")
class DiscoveryQrScanActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var preview: SurfaceView
    private var camera: Camera? = null
    @Volatile private var processing = false
    @Volatile private var resumed = false
    private val permissionCode = 7301

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        preview = SurfaceView(this)
        setContentView(preview)
        preview.holder.addCallback(this)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.CAMERA), permissionCode)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == permissionCode && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera(preview.holder)
        else { Toast.makeText(this, "需要相机权限才能扫描网段二维码", Toast.LENGTH_LONG).show(); finish() }
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        if (::preview.isInitialized) openCamera(preview.holder)
    }
    override fun onPause() {
        resumed = false
        releaseCamera()
        super.onPause()
    }
    override fun surfaceCreated(holder: SurfaceHolder) { openCamera(holder) }
    private fun openCamera(holder: SurfaceHolder) {
        if (!resumed || camera != null || !holder.surface.isValid || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            camera = Camera.open().also { opened ->
                camera = opened
                val parameters = opened.parameters
                if (parameters.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true) {
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                    opened.parameters = parameters
                }
                opened.setPreviewDisplay(holder)
                opened.setDisplayOrientation(90)
                opened.setPreviewCallback { data, active -> decode(data, active) }
                opened.startPreview()
            }
        } catch (error: Exception) { releaseCamera(); Toast.makeText(this, "无法打开相机：${error.message}", Toast.LENGTH_LONG).show(); finish() }
    }
    private fun decode(data: ByteArray, active: Camera) {
        if (processing || !resumed) return
        val frame = data.copyOf()
        val size = active.parameters.previewSize ?: return
        processing = true
        Thread {
            try {
                val source = PlanarYUVLuminanceSource(frame, size.width, size.height, 0, 0, size.width, size.height, false)
                val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
                val networks = DiscoveryQr.networks(result.text)
                if (networks != null) runOnUiThread {
                    if (!resumed || isFinishing) return@runOnUiThread
                    setResult(RESULT_OK, Intent().putExtra(DiscoveryQr.RESULT_KEY, networks)); finish()
                }
            } catch (_: ReaderException) {
            } catch (_: Exception) {
            } finally { processing = false }
        }.start()
    }
    private fun releaseCamera() {
        val opened = camera
        camera = null
        try { opened?.setPreviewCallback(null); opened?.stopPreview() } catch (_: Exception) { }
        opened?.release()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { releaseCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
}
