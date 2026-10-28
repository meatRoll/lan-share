package com.lanshare.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

/** Keeps local transfers on Wi-Fi or Ethernet instead of a VPN or mobile-data default route. */
object LanNetwork {
    fun bind(socket: DatagramSocket, context: Context): Network? = bind(context, null) { network, target ->
        network.bindSocket(socket)
        Logcat.d("network", "bound UDP socket to LAN network=$network; target=${target?.hostAddress ?: "none"}")
    }

    fun bind(socket: Socket, context: Context, target: InetAddress?): Network? = bind(context, target) { network, address ->
        network.bindSocket(socket)
        Logcat.d("network", "bound TCP socket to LAN network=$network; target=${address?.hostAddress ?: "none"}")
    }

    private inline fun bind(context: Context, target: InetAddress?, bindSocket: (Network, InetAddress?) -> Unit): Network? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val network = select(context, target) ?: return null
        return try {
            bindSocket(network, target)
            network
        } catch (error: Exception) {
            Logcat.d("network", "could not bind socket to LAN network=$network; target=${target?.hostAddress ?: "none"}: ${error.message}")
            null
        }
    }

    private fun select(context: Context, target: InetAddress?): Network? = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = manager.allNetworks.filter { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        // Prefer the interface with a direct route, then the active LAN, then any LAN that routes to the target.
        candidates.firstOrNull { network ->
            target != null && manager.getLinkProperties(network)?.routes?.any { route ->
                !route.isDefaultRoute && route.matches(target)
            } == true
        } ?: candidates.firstOrNull { it == manager.activeNetwork }
            ?: candidates.firstOrNull { network ->
                target != null && manager.getLinkProperties(network)?.routes?.any { it.matches(target) } == true
            }
            ?: candidates.firstOrNull()
    } catch (error: Exception) {
        Logcat.d("network", "could not select LAN network: ${error.message}")
        null
    }
}
