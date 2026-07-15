package com.jerry155756294.powerampbridge.protocol

import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
