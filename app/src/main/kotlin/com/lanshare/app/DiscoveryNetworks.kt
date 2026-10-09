package com.lanshare.app

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** IPv4 CIDR configuration shared by phone and TV; no DNS or TCP probing. */
object DiscoveryNetworks {
    const val MAX_HOSTS = 4096
    data class Subnet(val network: Long, val prefix: Int) {
        val size: Long get() = 1L shl (32 - prefix)
        val cidr: String get() = "${format(network)}/$prefix"
        fun contains(ip: Long) = ip >= network && ip < network + size
        fun hosts(): Sequence<Long> = if (prefix <= 30) (network + 1 until network + size - 1).asSequence()
            else (network until network + size).asSequence()
    }

    fun ipv4(text: String): Long {
        val parts = text.split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }) {
            "发现网段格式错误\n错误内容：$text\n正确格式：192.168.1.0/24"
        }
        return parts.fold(0L) { value, part -> (value shl 8) or part.toLong() }
    }
    fun format(ip: Long): String = listOf(24, 16, 8, 0).joinToString(".") { ((ip shr it) and 255).toString() }
    private fun subnet(ip: Long, prefix: Int): Subnet = Subnet(ip and ((0xffffffffL shl (32 - prefix)) and 0xffffffffL), prefix)
    private fun privateIp(ip: Long): Boolean = (ip shr 24 == 10L) || (ip shr 20 == 0xac1L) || (ip shr 16 == 0xc0a8L)

    fun parse(text: String): List<Subnet> {
        require(text.length <= 16384) { "网段配置过长" }
        val networks = text.split(Regex("[\\s,，;；]+")).filter { it.isNotBlank() }.map { token ->
            val parts = token.split('/')
            require(parts.size == 2) { "发现网段格式错误\n错误内容：$token\n正确格式：192.168.1.0/24" }
            val prefix = parts[1].takeIf { it.matches(Regex("[0-9]{1,2}")) }?.toIntOrNull()
            require(prefix != null && prefix in 20..32) {
                "发现网段格式错误\n错误内容：$token\n前缀需为：/20～/32\n正确格式：192.168.1.0/24"
            }
            val net = subnet(ipv4(parts[0]), prefix)
            require(privateIp(net.network) && privateIp(net.network + net.size - 1)) {
                "发现网段格式错误\n错误内容：$token\n只支持私有网段：10.x.x.x、172.16～31.x.x、192.168.x.x\n正确格式：192.168.1.0/24"
            }
            net
        }.distinct().sortedBy { it.prefix }
        val minimal = mutableListOf<Subnet>()
        for (net in networks) if (minimal.none { it.contains(net.network) }) minimal.add(net)
        require(minimal.sumOf { it.size } <= MAX_HOSTS) {
            "发现网段范围过大\n当前配置合计超过 $MAX_HOSTS 个地址\n请缩小网段或分开配置"
        }
        return minimal
    }

    fun localSubnets(): List<Subnet> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { it.interfaceAddresses }.filter { it.address is Inet4Address && it.networkPrefixLength.toInt() in 1..32 }
            .map { subnet(ipv4(it.address.hostAddress!!), it.networkPrefixLength.toInt()) }.distinct()
    } catch (_: Exception) { emptyList() }

    fun localAddresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.toSet()
    } catch (_: Exception) { emptySet() }

    fun targets(networks: List<Subnet>, ownAddresses: Set<String>): Sequence<String> = networks.asSequence()
        .flatMap { it.hosts() }.map(::format).filter { it !in ownAddresses }

    fun address(host: String): InetAddress = InetAddress.getByAddress(ByteArray(4) { index -> (ipv4(host) shr (24 - index * 8)).toByte() })
}
