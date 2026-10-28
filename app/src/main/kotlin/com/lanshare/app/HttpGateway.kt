package com.lanshare.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class HttpGateway(private val context: Context, private val deviceName: String, private val listener: LanShareService.Listener) {
    companion object { const val PORT = 45888 }
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val files = mutableListOf<File>()

    fun start() { if (running) return; running = true; executor.execute { loop() } }
    @Synchronized fun stop() { running = false; try { server?.close() } catch (_: Exception) {}; executor.shutdownNow() }

    private fun loop() {
        try {
            synchronized(this) {
                if (!running) return
                server = ServerSocket(PORT)
            }
            while (running) {
                val socket = server!!.accept()
                executor.execute { handle(socket) }
            }
        } catch (e: Exception) {
            if (running) Logcat.e("web", e)
        }
    }
    private fun handle(socket: Socket) { socket.use { s -> try {
        s.soTimeout = 30_000
        val input = BufferedInputStream(s.getInputStream())
        val request = readHeaders(input) ?: return
        val method = request["_method"] ?: "GET"; val path = request["_path"] ?: "/"
        when {
            method == "OPTIONS" -> respond(s, 204, "", "text/plain")
            method == "GET" && path == "/api/info" -> respond(s, 200, "{\"name\":${json(deviceName)},\"port\":$PORT}", "application/json")
            method == "GET" && path == "/api/files" -> respond(s, 200, filesJson(), "application/json")
            method == "GET" && path.startsWith("/download/") -> download(s, URLDecoder.decode(path.removePrefix("/download/"), "UTF-8"))
            method == "POST" && path == "/upload" -> upload(s, input, request)
            else -> respond(s, 404, "Not found", "text/plain")
        }
    } catch (e: Exception) { Logcat.e("web request", e); try { respond(s, 500, "Upload failed", "text/plain") } catch (_: Exception) {} } } }

    private fun upload(socket: Socket, input: InputStream, headers: Map<String, String>) {
        val requestedName = safeName(URLDecoder.decode(headers["x-filename"] ?: "file", "UTF-8"))
        val length = headers["content-length"]?.toLongOrNull() ?: throw IOException("missing content length")
        if (length < 0 || length > 4L * 1024 * 1024 * 1024) throw IOException("invalid file size")
        Logcat.d("web upload", "confirmation requested name=$requestedName; size=$length; host=${socket.inetAddress.hostAddress}")
        if (!listener.onIncoming(LanShareService.IncomingRequest(requestedName, length, socket.inetAddress.hostAddress))) {
            Logcat.d("web upload", "rejected name=$requestedName; host=${socket.inetAddress.hostAddress}")
            respond(socket, 403, "接收已拒绝", "text/plain")
            return
        }
        val storage = receiveStorage(requestedName)
        val file = storage.finalFile
        val temporary = storage.temporaryFile
        val name = file.name
        Logcat.d("web upload", "receiving $name; requested=$requestedName; size=$length; storage=${storage.kind}; final=${file.absolutePath}; temporary=${temporary.absolutePath}")
        listener.onTransfer(LanShareService.TransferProgress(LanShareService.TransferDirection.RECEIVE, name, 0))
        try {
            FileOutputStream(temporary).use { out ->
                val buffer = ByteArray(64 * 1024)
                var left = length
                var received = 0L
                while (left > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (count < 0) throw EOFException()
                    out.write(buffer, 0, count)
                    left -= count
                    received += count
                    if (length > 0) listener.onTransfer(LanShareService.TransferProgress(LanShareService.TransferDirection.RECEIVE, name, (received * 100 / length).toInt()))
                }
                out.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("无法发布接收文件：${temporary.absolutePath} -> ${file.absolutePath}")
        } catch (error: Exception) {
            temporary.delete()
            Logcat.e("web upload storage=${storage.kind}", error)
            listener.onError("接收失败：${error.message ?: "未知错误"}")
            throw error
        }
        Logcat.d("web upload", "saved $name: ${file.length()} bytes; storage=${storage.kind}")
        synchronized(files) { files.removeAll { it.name == name }; files.add(file) }
        if (name.lowercase().endsWith(".apk")) listener.onApkReceived(file)
        listener.onTransfer(LanShareService.TransferProgress(LanShareService.TransferDirection.RECEIVE, name, 100, true))
        listener.onWebFileReceived(file)
        listener.onFileReceived(file)
        respond(socket, 201, "{\"name\":${json(name)},\"size\":${file.length()}}", "application/json")
    }

    private data class ReceiveStorage(val finalFile: File, val temporaryFile: File, val kind: String)

    private fun receiveStorage(requestedName: String): ReceiveStorage {
        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LanShare")
        Logcat.d("storage", "selecting storage; sdk=${Build.VERSION.SDK_INT}; public=${publicDir.absolutePath}; exists=${publicDir.exists()}; writable=${publicDir.canWrite()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            probeMediaStoreDirectory()
        }
        val candidates = listOf(
            Triple(publicDir, "public-downloads", "public"),
            Triple(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LanShare"), "app-external", "app-external")
        )
        for ((directory, kind, _) in candidates) {
            val created = directory.exists() || directory.mkdirs()
            Logcat.d("storage", "candidate=$kind path=${directory.absolutePath}; created=$created; isDirectory=${directory.isDirectory}; writable=${directory.canWrite()}")
            if (created && directory.isDirectory && directory.canWrite()) {
                val finalFile = nextAvailableFile(directory, requestedName)
                return ReceiveStorage(finalFile, File(directory, ".${finalFile.name}.${System.currentTimeMillis()}.part"), kind)
            }
        }
        throw IOException("没有可写的接收目录")
    }

    private fun probeMediaStoreDirectory() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".lanshare-storage-probe")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanShare/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Logcat.d("storage", "MediaStore probe returned null")
            } else {
                Logcat.d("storage", "MediaStore probe created uri=$uri")
                context.contentResolver.delete(uri, null, null)
            }
        } catch (error: Exception) {
            Logcat.e("storage MediaStore probe", error)
        }
    }

    private fun download(socket: Socket, name: String) { val file = allFiles().firstOrNull { it.name == safeName(name) }; if (file == null || !file.isFile) { respond(socket, 404, "Not found", "text/plain"); return }; val out = socket.getOutputStream(); out.write("HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: application/octet-stream\r\nContent-Length: ${file.length()}\r\nContent-Disposition: ${FileNames.contentDisposition(file.name)}\r\nConnection: close\r\n\r\n".toByteArray()); file.inputStream().use { it.copyTo(out) }; out.flush() }
    private fun filesJson(): String = allFiles().joinToString(prefix = "[", postfix = "]") { "{\"name\":${json(it.name)},\"size\":${it.length()},\"url\":\"/download/${URLEncoder.encode(it.name, "UTF-8")}\"}" }
    private fun allFiles(): List<File> {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LanShare")
        val scanned = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        return synchronized(files) { (files + scanned).distinctBy { it.name }.filter { it.isFile } }
    }
    private fun safeName(value: String) = FileNames.safe(value)
    private fun nextAvailableFile(dir: File, requestedName: String): File {
        var candidate = File(dir, requestedName)
        if (!candidate.exists()) return candidate
        val dot = requestedName.lastIndexOf('.')
        val stem = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        for (index in 1..999) {
            candidate = File(dir, "$stem ($index)$extension")
            if (!candidate.exists()) return candidate
        }
        throw IOException("too many files with name $requestedName")
    }
    private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun readHeaders(input: InputStream): Map<String, String>? { val lines = mutableListOf<String>(); while (true) { val line = readLine(input) ?: return null; if (line.isEmpty()) break; lines.add(line) }; if (lines.isEmpty()) return null; val first = lines.removeAt(0).split(" "); val map = mutableMapOf<String, String>("_method" to first[0], "_path" to first.getOrElse(1) { "/" }.substringBefore("?")); lines.forEach { val i = it.indexOf(':'); if (i > 0) map[it.substring(0, i).lowercase()] = it.substring(i + 1).trim() }; return map }
    private fun readLine(input: InputStream): String? { val out = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0) return null; if (c == '\n'.code) return out.toString("UTF-8").trimEnd('\r'); out.write(c); if (out.size() > 16 * 1024) return null } }
    private fun respond(socket: Socket, code: Int, body: String, type: String) = respondBytes(socket, code, body.toByteArray(StandardCharsets.UTF_8), type)
    private fun respondBytes(socket: Socket, code: Int, body: ByteArray, type: String) { val reason = when (code) { 200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }; val out = socket.getOutputStream(); out.write("HTTP/1.1 $code $reason\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Content-Type, X-Filename\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush() }
}
