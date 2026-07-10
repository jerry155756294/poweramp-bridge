package com.jerry155756294.powerampbridge.bridge

import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MbrcDiscoveryProtocolTest {
  @Test
  fun `recognizes an MBRC discovery request`() {
    val payload = """{"context":"discovery","address":"192.168.1.20"}"""
      .toByteArray(StandardCharsets.UTF_8)

    assertTrue(MbrcDiscoveryProtocol.isRequest(payload, payload.size))
  }

  @Test
  fun `rejects non discovery payloads`() {
    val payload = """{"context":"notify"}""".toByteArray(StandardCharsets.UTF_8)

    assertFalse(MbrcDiscoveryProtocol.isRequest(payload, payload.size))
  }

  @Test
  fun `notify response matches the sender discovery schema`() {
    val response = JSONObject(
      MbrcDiscoveryProtocol.notify(
        address = "192.168.1.30",
        name = "Samsung A52s 5G",
        port = 3000
      )
    )

    assertEquals("notify", response.getString("context"))
    assertEquals("192.168.1.30", response.getString("address"))
    assertEquals("Samsung A52s 5G", response.getString("name"))
    assertEquals(3000, response.getInt("port"))
  }
}
