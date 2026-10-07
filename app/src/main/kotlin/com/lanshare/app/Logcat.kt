package com.lanshare.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logcat {
    private const val FILE_NAME = "lanshare.log"
    private val lock = Any()
    private lateinit var appContext: Context
    private var logFile: File? = null
    private var logUri: Uri? = null
    private var initialized = false
    private var runtimeEnabled = false

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            logFile = File(appContext.filesDir, FILE_NAME)
            runtimeEnabled = appContext.getSharedPreferences("logging", Context.MODE_PRIVATE)
                .getBoolean("enabled", BuildConfig.LOGGING_ENABLED)
            if (BuildConfig.LOGGING_ENABLED && Build.VERSION.SDK_INT >= 29) {
                try {
                    val resolver = appContext.contentResolver
                    val prefs = appContext.getSharedPreferences("logging", Context.MODE_PRIVATE)
                    val stored = prefs.getString("uri", null)?.let { Uri.parse(it) }
                    if (stored != null) {
                        try { resolver.openOutputStream(stored, "wa")?.use { logUri = stored } }
                        catch (_: Exception) { }
                    }
                    // Some systems add .txt to text/plain files. Do not match only the exact display name.
                    if (logUri == null) {
                        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                            arrayOf(Environment.DIRECTORY_DOWNLOADS + "/LanShare/"),
                            "${MediaStore.MediaColumns._ID} ASC")?.use { cursor ->
                            while (cursor.moveToNext() && logUri == null) {
                                val name = cursor.getString(1)
                                if (name == FILE_NAME || name == "$FILE_NAME.txt" || Regex("lanshare(?:\\.log)? \\(\\d+\\)\\.(?:log|txt)").matches(name)) {
                                    val candidate = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                                    try { resolver.openOutputStream(candidate, "wa")?.use { logUri = candidate } }
                                    catch (_: Exception) { }
                                }
                            }
                        }
                    }
                    if (logUri == null) logUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanShare/")
                    })
                    logUri?.let { prefs.edit().putString("uri", it.toString()).commit() }
                } catch (error: Exception) { android.util.Log.e("LanShare/logging", "Public log unavailable; using private file", error) }
            }
            initialized = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                e("crash ${thread.name}", error)
                if (previous != null) previous.uncaughtException(thread, error)
                else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(1) }
            }
        }
        d("logging", "=== session started; version=${BuildConfig.VERSION_NAME}; publicUri=$logUri ===")
    }

    fun isEnabled(): Boolean = synchronized(lock) { initialized && BuildConfig.LOGGING_ENABLED && runtimeEnabled }

    fun setEnabled(enabled: Boolean): Boolean = synchronized(lock) {
        if (!BuildConfig.LOGGING_ENABLED) {
            runtimeEnabled = false
            return false
        }
        runtimeEnabled = enabled
        appContext.getSharedPreferences("logging", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (enabled) write("DEBUG", "logging", "=== logging enabled; version=${BuildConfig.VERSION_NAME} ===")
        enabled
    }

    fun d(tag: String, message: String) {
        if (!isEnabled()) return
        write("DEBUG", tag, message)
        android.util.Log.d("LanShare/$tag", message)
    }

    fun e(tag: String, error: Throwable) {
        if (!isEnabled()) return
        write("ERROR", tag, android.util.Log.getStackTraceString(error))
        android.util.Log.e("LanShare/$tag", error.message, error)
    }

    fun logPath(): String = if (logUri != null) "Download/LanShare/$FILE_NAME（旧文件可能带 .txt 后缀）" else logFile?.absolutePath ?: FILE_NAME

    fun privateLogFile(): File? = synchronized(lock) { logFile?.takeIf { it.isFile } }

    fun clearLogs(): String = synchronized(lock) {
        if (!initialized) return "日志尚未初始化"
        var cleared = 0L
        logFile?.let { file ->
            if (file.isFile) cleared += file.length()
            try { FileOutputStream(file, false).use { } }
            catch (error: Exception) { android.util.Log.e("LanShare/logging", "Private log clear failed", error) }
        }
        logUri?.let { uri ->
            try {
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { }
            } catch (error: Exception) {
                android.util.Log.e("LanShare/logging", "Public log clear failed", error)
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val resolver = appContext.contentResolver
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(Environment.DIRECTORY_DOWNLOADS + "/LanShare/"),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)
                        if (name == FILE_NAME || name == "$FILE_NAME.txt" || Regex("lanshare(?:\\.log)? \\(\\d+\\)\\.(?:log|txt)").matches(name)) {
                            val candidate = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                            if (candidate != logUri) resolver.delete(candidate, null, null)
                        }
                    }
                }
            } catch (error: Exception) { android.util.Log.e("LanShare/logging", "Old public log cleanup failed", error) }
        }
        val marker = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} [DEBUG] [logging] === logs cleared; version=${BuildConfig.VERSION_NAME} ===\n"
        try { FileOutputStream(logFile!!, true).use { it.write(marker.toByteArray()) } } catch (_: Exception) { }
        try { logUri?.let { appContext.contentResolver.openOutputStream(it, "wa")?.use { output -> output.write(marker.toByteArray()) } } } catch (_: Exception) { }
        "已清空日志（${cleared} bytes）"
    }

    private fun write(level: String, tag: String, message: String) {
        if (!initialized || !runtimeEnabled) return
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} [$level] [$tag] $message\n"
        synchronized(lock) {
            // Keep a single private canonical log as a fallback; never rotate or truncate either file.
            try { FileOutputStream(logFile!!, true).use { it.write(line.toByteArray()) } }
            catch (error: Exception) { android.util.Log.e("LanShare/logging", "Private log write failed", error) }
            try { logUri?.let { uri -> appContext.contentResolver.openOutputStream(uri, "wa")?.use { it.write(line.toByteArray()) } } }
            catch (error: Exception) { android.util.Log.e("LanShare/logging", "Public log write failed", error) }
        }
    }
}
