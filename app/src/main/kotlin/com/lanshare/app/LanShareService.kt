package com.lanshare.app

import android.content.ContentValues
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanShareService(private val context: Context, private val listener: Listener) {
    private fun deviceName(context: Context): String {
        val custom = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("device_name", null)
        if (!custom.isNullOrBlank()) return custom
        val bluetooth = try { BluetoothAdapter.getDefaultAdapter()?.name } catch (_: SecurityException) { null }
        return bluetooth?.takeIf { it.isNotBlank() } ?: (Build.MODEL ?: "Android")
    }
    companion object {
        const val DISCOVERY_PORT = 45454
        const val TRANSFER_PORT = 45455
        private const val MAGIC = "LANSHARE"
    }

    interface Listener {
        fun onDevicesChanged(devices: List<Device>)
        fun onIncoming(request: IncomingRequest): Boolean
        fun onTransfer(message: String, progress: Int)
        fun onError(message: String)
        fun onApkReceived(file: File)
        fun onWebFileReceived(file: File)
        fun onFileReceived(file: File)
    }

    data class Device(val id: String, val name: String, val address: InetAddress, val port: Int)
    data class IncomingRequest(val name: String, val size: Long, val address: String)

    private val executor = Executors.newCachedThreadPool()
    private val devices = CopyOnWriteArrayList<Device>()
    private val lastSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val id = context.getSharedPreferences("settings", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).commit()
        }
    }
    private val name = deviceName(context)
    @Volatile private var running = false
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var webGateway: HttpGateway? = null
    private var diagnostics: NetworkDiagnostics? = null
    private val knownHosts = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getStringSet("known_hosts", emptySet())!!.toMutableSet()
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var transferServer: ServerSocket? = null

    @Synchronized fun start() {
        if (running) return
        running = true
        updateDiagnostics()
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            multicastLock = wifi.createMulticastLock("LanShare").apply { setReferenceCounted(true); acquire() }
        } catch (e: Exception) { Logcat.e("multicast lock", e) }
        executor.execute { discoveryLoop() }
        executor.execute { serverLoop() }
        executor.execute { announceLoop() }
        webGateway = HttpGateway(context, name, listener).also { it.start() }
    }

    fun refreshDiscovery() {
        listener.onDevicesChanged(devices.toList())
        Logcat.d("discovery", "refresh requested; UDP closed=${discoverySocket?.isClosed}; TCP closed=${transferServer?.isClosed}; transfer services kept running")
        diagnostics?.snapshot("refresh discovery")
        probeKnownHosts()
        if (running) executor.execute { announceOnce() }
    }

    @Synchronized fun stop() {
        running = false
        diagnostics?.stop()
        diagnostics = null
        discoverySocket?.close()
        transferServer?.close()
        multicastLock?.release()
        webGateway?.stop()
        webGateway = null
        executor.shutdownNow()
    }

    fun send(uri: Uri, device: Device) {
        executor.execute {
            try {
                val resolver = context.contentResolver
                val metadata = resolver.query(uri, arrayOf("_display_name", "_size"), null, null, null)?.use {
                    if (it.moveToFirst()) Pair(it.getString(0) ?: "file", if (it.isNull(1)) -1L else it.getLong(1)) else null
                } ?: Pair("file", -1L)
                resolver.openInputStream(uri)?.use { input -> sendStream(input, metadata.first, metadata.second, device) }
                    ?: throw IOException("无法读取文件")
            } catch (e: Exception) { Logcat.e("send", e); listener.onError("发送失败：${e.message ?: "未知错误"}") }
        }
    }

    fun sendLocalFile(file: File, device: Device) {
        executor.execute {
            try {
                if (!file.isFile) throw IOException("文件不存在：${file.absolutePath}")
                FileInputStream(file).use { sendStream(it, file.name, file.length(), device) }
            } catch (e: Exception) { Logcat.e("send local", e); listener.onError("发送失败：${e.message ?: "未知错误"}") }
        }
    }

    private fun sendStream(input: InputStream, name: String, size: Long, device: Device) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(device.address, device.port), 5_000)
            socket.soTimeout = 30_000
            val out = BufferedOutputStream(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            out.write("$MAGIC|FILE|${FileNames.safe(name)}|$size\n".toByteArray())
            out.flush()
            Logcat.d("send", "waiting for ACCEPT from ${device.address.hostAddress}:${device.port}; file=$name; size=$size")
            if (reader.readLine() != "ACCEPT") throw IOException("对方拒绝接收")
            val buffer = ByteArray(64 * 1024)
            var sent = 0L
            var count: Int
            while (input.read(buffer).also { count = it } > 0) {
                out.write(buffer, 0, count); sent += count
                if (size > 0) listener.onTransfer("正在发送 $name", (sent * 100 / size).toInt())
            }
            out.flush()
            socket.shutdownOutput()
            if (sent != size) throw IOException("文件长度不一致：$sent/$size")
            if (reader.readLine() != "DONE") throw IOException("接收端未确认保存完成")
            Logcat.d("send", "receiver saved $name: $sent bytes")
            listener.onTransfer("已发送 $name", 100)
        }
    }

    private fun announceLoop() {
        while (running) {
            announceOnce()
            try { Thread.sleep(3_000) } catch (_: InterruptedException) { return }
        }
    }

    private fun announceOnce() {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val message = "$MAGIC|HELLO|$id|${name.replace('|', '_')}|$TRANSFER_PORT".toByteArray(StandardCharsets.UTF_8)
                    val targets = linkedSetOf<InetAddress>()
                    NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network ->
                        network.interfaceAddresses.mapNotNull { it.broadcast }.forEach { targets.add(it) }
                    }
                    targets.add(InetAddress.getByName("255.255.255.255"))
                    val query = "$MAGIC|QUERY|$id|${name.replace('|', '_')}|$TRANSFER_PORT".toByteArray(StandardCharsets.UTF_8)
                    val hosts = synchronized(knownHosts) { knownHosts.toList() }
                    val listening = discoverySocket
                    if (listening != null && !listening.isClosed) for (host in hosts) {
                        try {
                            val address = InetAddress.getByName(host)
                            if (address !is Inet4Address || address.isLoopbackAddress || address.isMulticastAddress) continue
                            // Query only peers still on an attached LAN, never stale addresses via the default WAN route.
                            val onLan = NetworkInterface.getNetworkInterfaces()?.toList()?.any { iface ->
                                iface.isUp && !iface.isLoopback && iface.interfaceAddresses.any { local ->
                                    val bytes = local.address.address
                                    val prefix = local.networkPrefixLength.toInt()
                                    bytes.size == 4 && prefix in 1..32 && (0 until prefix).all { bit ->
                                        val mask = 1 shl (7 - bit % 8)
                                        (bytes[bit / 8].toInt() and mask) == (address.address[bit / 8].toInt() and mask)
                                    }
                                }
                            } == true
                            if (onLan) {
                                listening.send(DatagramPacket(query, query.size, address, DISCOVERY_PORT))
                                Logcat.d("discovery query", "unicast to $host:$DISCOVERY_PORT")
                            }
                        } catch (e: Exception) { if (running) Logcat.e("discovery query", e) }
                    }
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(message, message.size, target, DISCOVERY_PORT))
                            Logcat.d("announce", "sent ${message.size} bytes to ${target.hostAddress}:$DISCOVERY_PORT")
                        } catch (e: Exception) {
                            Logcat.e("announce ${target.hostAddress}", e)
                        }
                    }
                }
            } catch (e: Exception) { Logcat.e("announce", e) }
    }

    @Synchronized fun updateDiagnostics() {
        if (running && Logcat.isEnabled()) {
            if (diagnostics == null) {
                diagnostics = NetworkDiagnostics(context).also { it.start() }
                probeKnownHosts()
            }
        } else {
            diagnostics?.stop()
            diagnostics = null
        }
    }

    private fun probeKnownHosts() {
        if (!Logcat.isEnabled()) return
        val activeDiagnostics = diagnostics ?: return
        val hosts = synchronized(knownHosts) { knownHosts.toList() }
        Logcat.d("network probe", "known LAN hosts=$hosts; no subnet scanning")
        hosts.forEach { activeDiagnostics.probe(it, HttpGateway.PORT); activeDiagnostics.probe(it, 45881) }
    }

    private fun discoveryLoop() {
      while (running) {
        try {
            val boundSocket = synchronized(this) {
                if (!running) return
                DatagramSocket(DISCOVERY_PORT).also { discoverySocket = it }
            }
            boundSocket.use { socket ->
                Logcat.d("discovery", "listening on UDP $DISCOVERY_PORT")
                socket.broadcast = true
                socket.soTimeout = 2_000
                val bytes = ByteArray(1024)
                while (running) {
                    try {
                        val packet = DatagramPacket(bytes, bytes.size); socket.receive(packet)
                        val parts = String(packet.data, 0, packet.length).split('|')
                        if (parts.size >= 5 && parts[0] == MAGIC && parts[1] in listOf("HELLO", "QUERY") && parts[2] != id) {
                            val port = parts[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
                            if (parts[1] == "QUERY") {
                                val reply = "$MAGIC|HELLO|$id|${name.replace('|', '_')}|$TRANSFER_PORT".toByteArray(StandardCharsets.UTF_8)
                                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                                Logcat.d("discovery reply", "unicast to ${packet.address.hostAddress}:${packet.port}")
                            }
                            val found = Device(parts[2], parts[3], packet.address, port)
                            lastSeen[found.id] = System.currentTimeMillis()
                            if (devices.none { it.id == found.id }) {
                                Logcat.d("discovery", "found ${found.name} at ${packet.address.hostAddress}:$port")
                                val host = packet.address.hostAddress
                                if (host != null) synchronized(knownHosts) {
                                    knownHosts.add(host)
                                    context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putStringSet("known_hosts", knownHosts.toSet()).apply()
                                }
                            }
                            val index = devices.indexOfFirst { it.id == found.id }
                            if (index < 0) {
                                devices.add(found)
                                listener.onDevicesChanged(devices.toList())
                            } else if (devices[index] != found) {
                                devices[index] = found
                                listener.onDevicesChanged(devices.toList())
                            }
                            // Heartbeats update lastSeen only, preserving order and UI focus.
                        }
                    } catch (_: SocketTimeoutException) { }
                    val expired = devices.filter { System.currentTimeMillis() - (lastSeen[it.id] ?: 0L) > 15000 }
                    if (expired.isNotEmpty()) {
                        devices.removeAll(expired.toSet())
                        expired.forEach { lastSeen.remove(it.id) }
                        listener.onDevicesChanged(devices.toList())
                    }
                }
            }
        } catch (e: Exception) {
            if (running) { Logcat.e("discovery", e); listener.onError("设备发现暂不可用，正在重试：${e.message}") }
        }
        if (running) try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
      }
    }

    private fun serverLoop() {
        try {
            val boundServer = synchronized(this) {
                if (!running) return
                ServerSocket(TRANSFER_PORT).also { transferServer = it }
            }
            boundServer.use { server ->
                while (running) accept(server)
            }
        } catch (e: Exception) { if (running) listener.onError("接收服务启动失败：${e.message}") }
    }

    private fun accept(server: ServerSocket) {
        try {
            server.accept().use { socket ->
                socket.soTimeout = 30_000
                val input = BufferedInputStream(socket.getInputStream())
                val header = readLine(input) ?: return
                val parts = header.split('|')
                if (parts.size < 4 || parts[0] != MAGIC || parts[1] != "FILE") return
                val fileName = FileNames.safe(parts[2])
                val size = parts[3].toLongOrNull() ?: -1L
                val request = IncomingRequest(fileName, size, socket.inetAddress.hostAddress ?: "unknown")
                Logcat.d("receive", "incoming $fileName; size=$size; from=${request.address}")
                val accepted = listener.onIncoming(request)
                val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
                out.write(if (accepted) "ACCEPT\n" else "REJECT\n"); out.flush()
                if (!accepted) return
                Logcat.d("receive", "accepted $fileName")
                val saved = saveIncoming(input, fileName, size)
                out.write("DONE\n"); out.flush()
                listener.onTransfer("已接收 $fileName", 100)
                listener.onFileReceived(saved)
                if (fileName.lowercase().endsWith(".apk")) listener.onApkReceived(saved)
            }
        } catch (e: Exception) {
            if (running) { Logcat.e("receive", e); listener.onError("接收失败：${e.message ?: "未知错误"}") }
        }
    }

    private fun saveIncoming(input: InputStream, name: String, size: Long): File {
        val resolver = context.contentResolver
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanShare"); put(MediaStore.MediaColumns.IS_PENDING, 1) } }
        val isApk = name.lowercase().endsWith(".apk")
        val uri = if (!isApk && Build.VERSION.SDK_INT >= 29) resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) else null
        val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LanShare")
        downloadDir.mkdirs()
        val downloadFile = File(downloadDir, name)
        val output: OutputStream = if (isApk) FileOutputStream(downloadFile) else if (uri != null) resolver.openOutputStream(uri)!! else FileOutputStream(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name))
        output.use { out -> val buffer = ByteArray(64 * 1024); var total = 0L; var n: Int
            while (size < 0 || total < size) {
                n = input.read(buffer, 0, if (size < 0) buffer.size else minOf(buffer.size.toLong(), size - total).toInt())
                if (n < 0) { if (size >= 0) throw EOFException("文件不完整：$total/$size"); break }
                out.write(buffer, 0, n); total += n
                if (size > 0) listener.onTransfer("正在接收 $name", (total * 100 / size).toInt())
            }
            Logcat.d("receive", "received $name: $total bytes")
        }
        if (uri != null && Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return if (isApk) downloadFile else if (Build.VERSION.SDK_INT >= 29) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LanShare/$name")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
        }
    }

    private fun readLine(input: InputStream): String? { val b = ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0) return null; if (c == '\n'.code) return b.toString("UTF-8").trim(); b.write(c); if (b.size() > 2048) return null } }
}
