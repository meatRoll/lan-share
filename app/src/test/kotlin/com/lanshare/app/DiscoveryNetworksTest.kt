package com.lanshare.app

import org.junit.Assert.*
import org.junit.Test

class DiscoveryNetworksTest {
    @Test fun normalizesAndDeduplicatesOverlappingNetworks() {
        val networks = DiscoveryNetworks.parse("192.168.211.229/24\n192.168.211.0/25,192.168.211.0/24\n10.1.2.3/32")
        assertEquals(listOf("192.168.211.0/24", "10.1.2.3/32"), networks.map { it.cidr })
        val hosts = DiscoveryNetworks.targets(networks, setOf("192.168.211.2")).toList()
        assertEquals(254, hosts.size)
        assertTrue("192.168.211.229" in hosts)
        assertFalse("192.168.211.0" in hosts)
        assertFalse("192.168.211.255" in hosts)
        assertFalse("192.168.211.2" in hosts)
    }

    @Test fun supportsPointToPointAndSingleHost() {
        assertEquals(listOf("10.0.0.0", "10.0.0.1", "172.31.1.2"), DiscoveryNetworks.targets(
            DiscoveryNetworks.parse("10.0.0.0/31\n172.31.1.2/32"), emptySet()).toList())
    }

    @Test fun rejectsInvalidPublicAndOversizedRanges() {
        for (text in listOf("192.168.1.0", "192.168.300.0/24", "8.8.8.0/24", "172.32.0.0/24", "10.0.0.0/19", "10.0.0.0/20\n10.0.16.0/20", "example.com/24")) {
            try { DiscoveryNetworks.parse(text); fail("Accepted $text") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(4094, DiscoveryNetworks.targets(DiscoveryNetworks.parse("10.0.0.0/20"), emptySet()).count())
        assertTrue(DiscoveryNetworks.parse(" \n ").isEmpty())
    }
}
