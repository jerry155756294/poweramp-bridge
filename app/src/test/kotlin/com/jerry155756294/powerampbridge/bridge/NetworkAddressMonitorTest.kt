package com.jerry155756294.powerampbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAddressMonitorTest {
  @Test
  fun `wlan private IPv4 is preferred before other addresses`() {
    val addresses = prioritizeLocalAddresses(
      listOf(
        LocalAddressCandidate("rmnet_data0", "10.42.0.8"),
        LocalAddressCandidate("tailscale0", "100.64.0.3"),
        LocalAddressCandidate("wlan0", "192.168.50.24"),
        LocalAddressCandidate("wlan0", "fe80::1234")
      )
    )

    assertEquals("192.168.50.24", addresses.first())
  }

  @Test
  fun `all private IPv4 ranges are preferred over public and IPv6 addresses`() {
    val addresses = prioritizeLocalAddresses(
      listOf(
        LocalAddressCandidate("tun0", "100.64.0.3"),
        LocalAddressCandidate("rmnet0", "172.20.10.2"),
        LocalAddressCandidate("eth0", "10.0.0.4"),
        LocalAddressCandidate("wlan0", "2001:db8::1")
      )
    )

    assertEquals(listOf("10.0.0.4", "172.20.10.2"), addresses.take(2))
  }
}
