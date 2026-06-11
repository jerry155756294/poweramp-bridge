package com.jerry155756294.powerampbridge.protocol

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class MbrcProtocolServer(
  private val codec: JsonMessageCodec,
  private val listener: Listener
) {
  interface Listener {
    suspend fun onCommand(clientInfo: ProtocolClientInfo, message: IncomingMessage): List<String>
    suspend fun onBroadcastReady(clientInfo: ProtocolClientInfo): List<String>
    suspend fun onSessionChanged(snapshot: LogicalClientSnapshot?)
    suspend fun onProbe(remoteAddress: String)
    suspend fun onConnectionRejected(remoteAddress: String, reason: String)
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val mutex = Mutex()
  private val protocolManager = ProtocolSessionManager()
  private val sessions = linkedMapOf<String, ClientSession>()
  private var serverSocket: ServerSocket? = null
  private var acceptJob: Job? = null

  suspend fun start(port: Int) {
    stop()
    serverSocket = ServerSocket().apply {
      reuseAddress = true
      bind(InetSocketAddress(port))
    }
    acceptJob = scope.launch {
      while (isActive) {
        val socket = try {
          serverSocket?.accept()
        } catch (_: Exception) {
          null
        } ?: break

        val socketId = UUID.randomUUID().toString()
        val remoteAddress = socket.inetAddress?.hostAddress.orEmpty()
        val session = ClientSession(socket)
        mutex.withLock {
          sessions[socketId] = session
          protocolManager.registerConnection(socketId, remoteAddress)
        }
        scope.launch {
          handleClient(socketId, remoteAddress, session)
        }
      }
    }
  }

  suspend fun stop() {
    acceptJob?.cancelAndJoin()
    acceptJob = null
    mutex.withLock {
      sessions.values.forEach { it.close() }
      sessions.clear()
    }
    serverSocket?.close()
    serverSocket = null
  }

  suspend fun sendBroadcast(messages: List<String>) {
    val socketId = mutex.withLock { protocolManager.broadcastSocketId() } ?: return
    val target = mutex.withLock { sessions[socketId] } ?: return

    messages.forEach { target.send(it) }
  }

  private suspend fun handleClient(
    socketId: String,
    remoteAddress: String,
    session: ClientSession
  ) {
    try {
      while (true) {
        val line = session.reader.readLine() ?: break
        if (line.isBlank()) continue

        val message = codec.parse(line)
        val result = mutex.withLock {
          protocolManager.processMessage(socketId, message)
        }

        if (result.sessionChanged) {
          listener.onSessionChanged(result.sessionSnapshot)
        }
        result.probeAddress?.let { listener.onProbe(it) }
        result.rejectionReason?.let { listener.onConnectionRejected(remoteAddress, it) }

        sendProtocolReplies(session, result.replies)

        if (result.sendInitialSnapshot && result.clientInfo != null) {
          sendEncodedReplies(session, listener.onBroadcastReady(result.clientInfo))
        }

        if (result.delegateMessage != null && result.clientInfo != null) {
          val replies = listener.onCommand(result.clientInfo, result.delegateMessage)
          sendEncodedReplies(session, replies)
        }

        if (result.disconnect) break
      }
    } catch (error: Exception) {
      Timber.w(error, "Client loop ended for %s", remoteAddress)
    } finally {
      val disconnectResult = mutex.withLock {
        sessions.remove(socketId)
        protocolManager.disconnect(socketId)
      }
      session.close()
      if (disconnectResult.sessionChanged) {
        listener.onSessionChanged(disconnectResult.sessionSnapshot)
      }
    }
  }

  private suspend fun sendProtocolReplies(
    session: ClientSession,
    replies: List<OutgoingMessage>
  ) {
    replies.forEach { reply ->
      session.send(codec.encode(reply.context, reply.data))
    }
  }

  private suspend fun sendEncodedReplies(
    session: ClientSession,
    replies: List<String>
  ) {
    replies.forEach { session.send(it) }
  }

  private class ClientSession(
    socket: Socket
  ) {
    private val rawSocket = socket
    val reader: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))
    private val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
    private val mutex = Mutex()

    suspend fun send(payload: String) {
      mutex.withLock {
        writer.write(payload + "\r\n")
        writer.flush()
      }
    }

    fun close() {
      runCatching { reader.close() }
      runCatching { writer.close() }
      runCatching { rawSocket.close() }
    }
  }
}
