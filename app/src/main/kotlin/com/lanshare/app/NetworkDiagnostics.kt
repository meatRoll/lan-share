package com.lanshare.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors

/** Observes local network changes only; never scans unrelated hosts or sends diagnostics externally. */
class NetworkDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var stopped = false
    private var registered = false
    @Volatile private var activeSocket: Socket? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { snapshot("available $network") }
        override fun onLost(network: Network) { snapshot("lost $network") }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { snapshot("links $network: $properties") }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { snapshot("capabilities $network: $capabilities") }
    }
    fun start() {
        // registerDefaultNetworkCallback was added in API 24; old TV builds still support LAN transfer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { manager.registerDefaultNetworkCallback(callback); registered = true }
            catch (e: Exception) { Logcat.e("network callback", e) }
        } else {
            Logcat.d("network callback", "skipped on API ${Build.VERSION.SDK_INT}; requires API 24")
        }
        snapshot("startup")
    }
    fun snapshot(reason: String) {
        if (stopped || !Logcat.isEnabled()) return
        Logcat.d("network", "event=$reason")
        try {
            Logcat.d("network", "active=${manager.activeNetwork}; metered=${manager.isActiveNetworkMetered}")
            manager.allNetworks.forEach { network ->
                Logcat.d("network", "network=$network; capabilities=${manager.getNetworkCapabilities(network)}; links=${manager.getLinkProperties(network)}")
            }
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                Logcat.d("network interface", "${iface.name}; up=${iface.isUp}; loopback=${iface.isLoopback}; addresses=${iface.interfaceAddresses.joinToString { "${it.address.hostAddress}/${it.networkPrefixLength} broadcast=${it.broadcast?.hostAddress}" }}")
            }
        } catch (e: Exception) { Logcat.e("network snapshot", e) }
    }
    fun probe(host: String, port: Int) {
        if (stopped || !Logcat.isEnabled()) return
        try { worker.execute {
            if (!stopped && Logcat.isEnabled()) {
                val start = System.currentTimeMillis()
                try {
                    val target = InetAddress.getByName(host)
                    Socket().use { socket ->
                        activeSocket = socket
                        if (stopped || !Logcat.isEnabled()) return@use
                        LanNetwork.bind(socket, appContext, target)
                        socket.connect(InetSocketAddress(target, port), 2500)
                        Logcat.d("network probe", "TCP $host:$port reachable; local=${socket.localAddress.hostAddress}; elapsed=${System.currentTimeMillis() - start}ms")
                    }
                } catch (e: Exception) { Logcat.d("network probe", "TCP $host:$port failed: ${e.javaClass.simpleName}: ${e.message}") }
                finally { activeSocket = null }
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
    fun stop() {
        stopped = true
        if (registered) try { manager.unregisterNetworkCallback(callback) } catch (_: Exception) { }
        try { activeSocket?.close() } catch (_: Exception) { }
        worker.shutdownNow()
    }
}
