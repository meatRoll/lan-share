package com.lanshare.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApkFileProvider : ContentProvider() {
    override fun onCreate() = true
    private fun logFile(): File {
        val fallback = File(requireContext().filesDir, "lanshare.log")
        val uri = requireContext().getSharedPreferences("logging", android.content.Context.MODE_PRIVATE).getString("uri", null)?.let(Uri::parse)
        if (uri != null) {
            try {
                val copy = File.createTempFile("lanshare-share-", ".log", requireContext().cacheDir)
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(copy).use { output ->
                        val marker = "=== LanShare exported log; running version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); exported at=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} ===\n"
                        output.write(marker.toByteArray(Charsets.UTF_8))
                        input.copyTo(output)
                    }
                }
                return copy
            } catch (_: Exception) { }
        }
        val copy = File.createTempFile("lanshare-share-", ".log", requireContext().cacheDir)
        FileOutputStream(copy).use { output ->
            val marker = "=== LanShare exported log; running version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); exported at=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} ===\n"
            output.write(marker.toByteArray(Charsets.UTF_8))
            if (fallback.isFile) fallback.inputStream().use { it.copyTo(output) }
        }
        return copy
    }
    private fun fileFor(uri: Uri): File {
        if (uri.path == "/log") return logFile()
        val relative = uri.path?.removePrefix("/") ?: throw FileNotFoundException()
        val roots = listOfNotNull(
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "LanShare"),
            requireContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "LanShare") }
        ).map { it.canonicalFile }
        roots.firstNotNullOfOrNull { root ->
            File(root, relative).canonicalFile.takeIf { file -> file.path.startsWith(root.path + File.separator) && file.isFile }
        }?.let { return it }
        throw FileNotFoundException()
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun getType(uri: Uri) = if (uri.path == "/log") "text/plain" else "application/vnd.android.package-archive"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf("_display_name", "_size")
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { when (it) { "_display_name" -> file.name; "_size" -> file.length(); else -> null } }.toTypedArray())
        return cursor
    }
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
