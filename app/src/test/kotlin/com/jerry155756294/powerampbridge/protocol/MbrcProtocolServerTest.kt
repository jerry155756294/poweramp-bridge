package com.jerry155756294.powerampbridge.protocol

import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class MbrcProtocolServerTest {
  @Test
  fun `stop completes while the accept loop is waiting for another socket`() = runBlocking {
    val connectionAccepted = CompletableDeferred<Unit>()
    val listener = object : MbrcProtocolServer.Listener {
      override suspend fun onCommand(
        clientInfo: ProtocolClientInfo,
        message: IncomingMessage
      ): List<String> = emptyList()

      override suspend fun onBroadcastReady(clientInfo: ProtocolClientInfo): List<String> = emptyList()

      override suspend fun onSessionChanged(snapshot: LogicalClientSnapshot?) = Unit

      override suspend fun onProbe(remoteAddress: String) = Unit

      override suspend fun onBroadcastTraffic(contexts: List<String>) = Unit

      override suspend fun onProtocolEvent(message: String) {
        if (message.startsWith("socket_accepted:")) {
          connectionAccepted.complete(Unit)
        }
      }

      override suspend fun onConnectionRejected(snapshot: ConnectionDebugSnapshot, reason: String) = Unit

      override suspend fun onConnectionClosed(snapshot: ConnectionDebugSnapshot, reason: String) = Unit
    }
    val port = ServerSocket(0).use { it.localPort }
    val server = MbrcProtocolServer(JsonMessageCodec(), listener)

    server.start(port)
    val client = Socket("127.0.0.1", port)
    try {
      withTimeout(2_000) { connectionAccepted.await() }

      withTimeout(2_000) { server.stop() }
    } finally {
      client.close()
    }
  }

  @Test
  fun `incomplete handshake closes with a phase specific timeout`() = runBlocking {
    val connectionAccepted = CompletableDeferred<Unit>()
    val connectionClosed = CompletableDeferred<ConnectionDebugSnapshot>()
    val listener = object : MbrcProtocolServer.Listener {
      override suspend fun onCommand(
        clientInfo: ProtocolClientInfo,
        message: IncomingMessage
      ): List<String> = emptyList()

      override suspend fun onBroadcastReady(clientInfo: ProtocolClientInfo): List<String> = emptyList()

      override suspend fun onSessionChanged(snapshot: LogicalClientSnapshot?) = Unit

      override suspend fun onProbe(remoteAddress: String) = Unit

      override suspend fun onBroadcastTraffic(contexts: List<String>) = Unit

      override suspend fun onProtocolEvent(message: String) {
        if (message.startsWith("socket_accepted:")) {
          connectionAccepted.complete(Unit)
        }
      }

      override suspend fun onConnectionRejected(snapshot: ConnectionDebugSnapshot, reason: String) = Unit

      override suspend fun onConnectionClosed(snapshot: ConnectionDebugSnapshot, reason: String) {
        connectionClosed.complete(snapshot)
      }
    }
    val port = ServerSocket(0).use { it.localPort }
    val server = MbrcProtocolServer(JsonMessageCodec(), listener, handshakeTimeoutMs = 100)

    server.start(port)
    val client = Socket("127.0.0.1", port)
    try {
      withTimeout(2_000) { connectionAccepted.await() }
      val closed = withTimeout(2_000) { connectionClosed.await() }
      assertEquals("handshake_timeout_before_player", closed.disconnectCategory)
    } finally {
      client.close()
      server.stop()
    }
  }

  @Test
  fun `verifyconnection probe closes after its idle timeout`() = runBlocking {
    val connectionClosed = CompletableDeferred<ConnectionDebugSnapshot>()
    val listener = object : MbrcProtocolServer.Listener {
      override suspend fun onCommand(
        clientInfo: ProtocolClientInfo,
        message: IncomingMessage
      ): List<String> = emptyList()

      override suspend fun onBroadcastReady(clientInfo: ProtocolClientInfo): List<String> = emptyList()

      override suspend fun onSessionChanged(snapshot: LogicalClientSnapshot?) = Unit

      override suspend fun onProbe(remoteAddress: String) = Unit

      override suspend fun onBroadcastTraffic(contexts: List<String>) = Unit

      override suspend fun onProtocolEvent(message: String) = Unit

      override suspend fun onConnectionRejected(snapshot: ConnectionDebugSnapshot, reason: String) = Unit

      override suspend fun onConnectionClosed(snapshot: ConnectionDebugSnapshot, reason: String) {
        connectionClosed.complete(snapshot)
      }
    }
    val port = ServerSocket(0).use { it.localPort }
    val server = MbrcProtocolServer(
      codec = JsonMessageCodec(),
      listener = listener,
      handshakeTimeoutMs = 100,
      probeIdleTimeoutMs = 100
    )

    server.start(port)
    val client = Socket("127.0.0.1", port)
    try {
      val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream()))
      val reader = BufferedReader(InputStreamReader(client.getInputStream()))
      writer.write("{\"context\":\"verifyconnection\",\"data\":null}\\r\\n")
      writer.flush()

      val reply = JsonMessageCodec().parse(withTimeout(1_000) { reader.readLine() })
      assertEquals("verifyconnection", reply.context)
      assertEquals(true, reply.data)

      val closed = withTimeout(750) { connectionClosed.await() }
      assertEquals("probe_idle_timeout", closed.disconnectCategory)
    } finally {
      client.close()
      server.stop()
    }
  }
}
