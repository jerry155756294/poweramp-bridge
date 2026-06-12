package com.jerry155756294.powerampbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSessionManagerTest {
  private val manager = ProtocolSessionManager()

  @Test
  fun `broadcast socket completes handshake and waits for init`() {
    manager.registerConnection("broadcast", "10.0.0.2")

    val playerResult = manager.processMessage(
      "broadcast",
      IncomingMessage(ProtocolConstants.Player, "Android")
    )
    assertEquals(ProtocolConstants.PlayerName, playerResult.replies.single().data)

    val protocolResult = manager.processMessage(
      "broadcast",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf(
          "protocol_version" to 4,
          "no_broadcast" to false,
          "client_id" to "sender-1"
        )
      )
    )

    assertEquals(ProtocolConstants.Protocol, protocolResult.replies.single().context)
    assertTrue(protocolResult.sessionChanged)
    assertTrue(protocolResult.sessionSnapshot?.broadcastSocketConnected == true)
    assertFalse(protocolResult.sessionSnapshot?.broadcastInitialized == true)

    val initResult = manager.processMessage(
      "broadcast",
      IncomingMessage(ProtocolConstants.Init, null)
    )

    assertTrue(initResult.sendInitialSnapshot)
    assertTrue(initResult.sessionChanged)
    assertTrue(initResult.sessionSnapshot?.broadcastInitialized == true)
    assertEquals("broadcast", manager.broadcastSocketId())
  }

  @Test
  fun `request socket joins same logical client without requiring init`() {
    manager.registerConnection("broadcast", "10.0.0.2")
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "broadcast",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Init, null))

    manager.registerConnection("request", "10.0.0.2")
    manager.processMessage("request", IncomingMessage(ProtocolConstants.Player, "Android"))
    val requestProtocolResult = manager.processMessage(
      "request",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to true, "client_id" to "sender-1")
      )
    )

    assertTrue(requestProtocolResult.sessionChanged)
    assertTrue(requestProtocolResult.sessionSnapshot?.broadcastSocketConnected == true)
    assertTrue(requestProtocolResult.sessionSnapshot?.requestSocketConnected == true)
    assertEquals(1, requestProtocolResult.sessionSnapshot?.requestSocketCount)
    assertFalse(requestProtocolResult.sendInitialSnapshot)

    val commandResult = manager.processMessage(
      "request",
      IncomingMessage(ProtocolConstants.PlayerStatus, null)
    )
    assertEquals(ProtocolConstants.PlayerStatus, commandResult.delegateMessage?.context)
    assertEquals(SocketRole.REQUEST, commandResult.clientInfo?.role)
  }

  @Test
  fun `verifyconnection is allowed before handshake`() {
    manager.registerConnection("probe", "10.0.0.3")

    val result = manager.processMessage(
      "probe",
      IncomingMessage(ProtocolConstants.VerifyConnection, null)
    )

    assertEquals(ProtocolConstants.VerifyConnection, result.replies.single().context)
    assertEquals(true, result.replies.single().data)
    assertEquals("10.0.0.3", result.probeAddress)
    assertFalse(result.disconnect)
    assertEquals(
      ProtocolConstants.VerifyConnection,
      manager.connectionDebugSnapshot("probe")?.lastIncomingContext
    )
    assertEquals("probe_socket_completed", manager.inferCloseCategory("probe"))
  }

  @Test
  fun `same logical client can open multiple request sockets`() {
    manager.registerConnection("broadcast", "10.0.0.2")
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "broadcast",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Init, null))

    repeat(2) { index ->
      val socketId = "request-$index"
      manager.registerConnection(socketId, "10.0.0.2")
      manager.processMessage(socketId, IncomingMessage(ProtocolConstants.Player, "Android"))
      val protocolResult = manager.processMessage(
        socketId,
        IncomingMessage(
          ProtocolConstants.Protocol,
          mapOf("protocol_version" to 4, "no_broadcast" to true, "client_id" to "sender-1")
        )
      )

      assertFalse(protocolResult.disconnect)
      assertEquals(index + 1, protocolResult.sessionSnapshot?.requestSocketCount)
    }
  }

  @Test
  fun `second different client is rejected`() {
    manager.registerConnection("first", "10.0.0.2")
    manager.processMessage("first", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "first",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )

    manager.registerConnection("second", "10.0.0.8")
    manager.processMessage("second", IncomingMessage(ProtocolConstants.Player, "Android"))
    val rejected = manager.processMessage(
      "second",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-2")
      )
    )

    assertTrue(rejected.disconnect)
    assertEquals(ProtocolConstants.NotAllowed, rejected.replies.single().context)
    assertEquals("single_client_only", rejected.rejectionReason)
  }

  @Test
  fun `same client can replace stale broadcast socket immediately`() {
    manager.registerConnection("broadcast-old", "10.0.0.2")
    manager.processMessage("broadcast-old", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "broadcast-old",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )
    manager.processMessage("broadcast-old", IncomingMessage(ProtocolConstants.Init, null))

    manager.registerConnection("broadcast-new", "10.0.0.2")
    manager.processMessage("broadcast-new", IncomingMessage(ProtocolConstants.Player, "Android"))
    val replacement = manager.processMessage(
      "broadcast-new",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )

    assertFalse(replacement.disconnect)
    assertEquals(setOf("broadcast-old"), replacement.socketsToClose)
    assertTrue(replacement.sessionChanged)
    assertTrue(replacement.sessionSnapshot?.broadcastSocketConnected == true)
    assertFalse(replacement.sessionSnapshot?.broadcastInitialized == true)
    assertEquals(
      "broadcast_replaced_by_same_client",
      manager.apply {
        markDisconnectCategory("broadcast-old", "broadcast_replaced_by_same_client")
      }.inferCloseCategory("broadcast-old")
    )
  }

  @Test
  fun `legacy protocol payload is accepted`() {
    manager.registerConnection("legacy", "10.0.0.5")
    manager.processMessage("legacy", IncomingMessage(ProtocolConstants.Player, "Android"))

    val protocolResult = manager.processMessage(
      "legacy",
      IncomingMessage(ProtocolConstants.Protocol, "3")
    )

    assertEquals(4, protocolResult.replies.single().data)
    assertEquals(3, protocolResult.sessionSnapshot?.protocolVersion)
    assertNotNull(protocolResult.sessionSnapshot)
  }

  @Test
  fun `broadcast socket rejects commands before init`() {
    manager.registerConnection("broadcast", "10.0.0.2")
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "broadcast",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )

    val prematureCommand = manager.processMessage(
      "broadcast",
      IncomingMessage(ProtocolConstants.PlayerStatus, null)
    )

    assertTrue(prematureCommand.disconnect)
    assertNull(prematureCommand.delegateMessage)
    assertEquals(
      "protocol_violation_before_init",
      prematureCommand.disconnectCategory
    )
  }

  @Test
  fun `request socket close is classified after command`() {
    manager.registerConnection("broadcast", "10.0.0.2")
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "broadcast",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Init, null))

    manager.registerConnection("request", "10.0.0.2")
    manager.processMessage("request", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "request",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to true, "client_id" to "sender-1")
      )
    )
    manager.processMessage("request", IncomingMessage(ProtocolConstants.PlayerStatus, null))

    assertEquals("request_socket_peer_closed_after_command", manager.inferCloseCategory("request"))
  }

  @Test
  fun `request socket close is classified as completed without extra command`() {
    manager.registerConnection("broadcast", "10.0.0.2")
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "broadcast",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Init, null))

    manager.registerConnection("request", "10.0.0.2")
    manager.processMessage("request", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.processMessage(
      "request",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to true, "client_id" to "sender-1")
      )
    )

    assertEquals("request_socket_completed", manager.inferCloseCategory("request"))
  }

  @Test
  fun `broadcast socket close is classified during handshake`() {
    manager.registerConnection("broadcast", "10.0.0.2")
    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Player, "Android"))

    assertEquals("broadcast_peer_closed_during_handshake", manager.inferCloseCategory("broadcast"))
  }

  @Test
  fun `last incoming and outgoing contexts are recorded`() {
    manager.registerConnection("broadcast", "10.0.0.2")

    manager.processMessage("broadcast", IncomingMessage(ProtocolConstants.Player, "Android"))
    manager.markOutgoingContext("broadcast", ProtocolConstants.Player)
    manager.processMessage(
      "broadcast",
      IncomingMessage(
        ProtocolConstants.Protocol,
        mapOf("protocol_version" to 4, "no_broadcast" to false, "client_id" to "sender-1")
      )
    )
    manager.markOutgoingContext("broadcast", ProtocolConstants.Protocol)

    val snapshot = manager.connectionDebugSnapshot("broadcast")
    assertEquals(ProtocolConstants.Protocol, snapshot?.lastIncomingContext)
    assertEquals(ProtocolConstants.Protocol, snapshot?.lastOutgoingContext)
  }
}
