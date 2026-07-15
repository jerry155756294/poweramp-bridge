package com.jerry155756294.powerampbridge.protocol

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
    suspend fun onBroadcastTraffic(contexts: List<String>)
    suspend fun onProtocolEvent(message: String)
    suspend fun onConnectionRejected(snapshot: ConnectionDebugSnapshot, reason: String)
    suspend fun onConnectionClosed(snapshot: ConnectionDebugSnapshot, reason: String)
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
        socket.keepAlive = true
        socket.tcpNoDelay = true

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
    listener.onProtocolEvent("listener_started:$port")
  }

  suspend fun stop() {
    // Closing the socket is what unblocks ServerSocket.accept(). Cancelling the coroutine first
    // leaves it parked in the blocking accept call and can prevent the foreground service from
    // finishing its stop sequence.
    val socketToClose = serverSocket
    serverSocket = null
    val jobToJoin = acceptJob
    acceptJob = null
    runCatching { socketToClose?.close() }
    jobToJoin?.cancelAndJoin()

    val sessionsToClose = mutex.withLock {
      val activeSessions = sessions.values.toList()
      sessions.clear()
      activeSessions
    }
    sessionsToClose.forEach { it.close() }
    listener.onProtocolEvent("listener_stopped")
  }

  suspend fun sendBroadcast(messages: List<String>) {
    val socketId = mutex.withLock { protocolManager.broadcastSocketId() } ?: return
    val target = mutex.withLock { sessions[socketId] } ?: return

    val sentContexts = mutableListOf<String>()
    for (payload in messages) {
      val context = codec.parse(payload).context
      try {
        target.send(payload)
      } catch (error: IOException) {
        handleOutboundFailure(socketId, target, error)
        return
      }
      mutex.withLock { protocolManager.markOutgoingContext(socketId, context) }
      sentContexts += context
    }
    listener.onBroadcastTraffic(sentContexts)
  }

  suspend fun sendKeepalivePing(): Boolean {
    val socketId = mutex.withLock { protocolManager.broadcastSocketId() } ?: return false
    val target = mutex.withLock { sessions[socketId] } ?: return false
    val payload = codec.encode(ProtocolConstants.Ping, "")

    try {
      target.send(payload)
    } catch (error: IOException) {
      handleOutboundFailure(socketId, target, error)
      return false
    }
    mutex.withLock { protocolManager.markOutgoingContext(socketId, ProtocolConstants.Ping) }
    listener.onProtocolEvent("keepalive_ping:${shortSocketId(socketId)}")
    listener.onBroadcastTraffic(listOf(ProtocolConstants.Ping))
    return true
  }

  private suspend fun handleClient(
    socketId: String,
    remoteAddress: String,
    session: ClientSession
  ) {
    var closeReason = "peer_closed"
    try {
      listener.onProtocolEvent("socket_accepted:${shortSocketId(socketId)}@$remoteAddress")
      while (true) {
        val line = session.reader.readLine() ?: break
        if (line.isBlank()) continue

        val message = codec.parse(line)
        listener.onProtocolEvent("socket_in:${shortSocketId(socketId)}:${message.context}")
        val result = mutex.withLock {
          protocolManager.processMessage(socketId, message)
        }
        result.disconnectCategory?.let { category ->
          mutex.withLock { protocolManager.markDisconnectCategory(socketId, category) }
        }

        if (result.sessionChanged) {
          listener.onSessionChanged(result.sessionSnapshot)
        }
        result.probeAddress?.let { listener.onProbe(it) }
        result.rejectionReason?.let { reason ->
          mutex.withLock { protocolManager.connectionDebugSnapshot(socketId) }?.let { snapshot ->
            listener.onConnectionRejected(snapshot, reason)
          }
          listener.onProtocolEvent("socket_rejected:${shortSocketId(socketId)}:$reason")
        }

        sendProtocolReplies(socketId, session, result.replies)
        closeSupersededSockets(result.socketsToClose)

        if (result.sendInitialSnapshot && result.clientInfo != null) {
          listener.onProtocolEvent("broadcast_ready:${shortSocketId(socketId)}")
          sendEncodedReplies(socketId, session, listener.onBroadcastReady(result.clientInfo))
        }

        if (result.delegateMessage != null && result.clientInfo != null) {
          val replies = listener.onCommand(result.clientInfo, result.delegateMessage)
          sendEncodedReplies(socketId, session, replies)
        }

        if (result.disconnect) break
      }
    } catch (error: Exception) {
      closeReason = "socket_read_error:${error.javaClass.simpleName}"
      mutex.withLock { protocolManager.markDisconnectCategory(socketId, closeReason) }
      if (error is IOException && isExpectedSocketClose(error)) {
        Timber.d("Client loop closed normally for %s: %s", remoteAddress, error.message)
      } else {
        Timber.w(error, "Client loop ended for %s", remoteAddress)
      }
    } finally {
      val closeSnapshot = mutex.withLock {
        val category = protocolManager.inferCloseCategory(socketId)
        val snapshot = protocolManager.connectionDebugSnapshot(socketId)
        if (snapshot?.disconnectCategory == null) {
          protocolManager.markDisconnectCategory(socketId, category)
        }
        protocolManager.connectionDebugSnapshot(socketId)
      }
      val connectionSnapshot = mutex.withLock {
        closeSnapshot ?: protocolManager.connectionDebugSnapshot(socketId)
      }
      val disconnectResult = mutex.withLock {
        sessions.remove(socketId)
        protocolManager.disconnect(socketId)
      }
      session.close()
      listener.onConnectionClosed(
        connectionSnapshot ?: ConnectionDebugSnapshot(
          socketId = shortSocketId(socketId),
          remoteAddress = remoteAddress,
          clientId = null,
          role = null,
          handshakeState = HandshakeState.AWAITING_PLAYER,
          protocolVersion = ProtocolConstants.ProtocolVersion,
          broadcastInitialized = false,
          requestSocketCount = 0,
          disconnectCategory = "peer_closed_unknown"
        ),
        closeReason
      )
      listener.onProtocolEvent(
        "socket_closed:${shortSocketId(socketId)}:${connectionSnapshot?.disconnectCategory ?: "peer_closed_unknown"}"
      )
      if (disconnectResult.sessionChanged) {
        listener.onSessionChanged(disconnectResult.sessionSnapshot)
      }
    }
  }

  private suspend fun sendProtocolReplies(
    socketId: String,
    session: ClientSession,
    replies: List<OutgoingMessage>
  ) {
    val sentContexts = mutableListOf<String>()
    replies.forEach { reply ->
      session.send(codec.encode(reply.context, reply.data))
      mutex.withLock { protocolManager.markOutgoingContext(socketId, reply.context) }
      sentContexts += reply.context
    }
    if (sentContexts.isNotEmpty() && isBroadcastSocket(socketId)) {
      listener.onBroadcastTraffic(sentContexts)
    }
  }

  private suspend fun sendEncodedReplies(
    socketId: String,
    session: ClientSession,
    replies: List<String>
  ) {
    val sentContexts = mutableListOf<String>()
    replies.forEach { payload ->
      val context = codec.parse(payload).context
      session.send(payload)
      mutex.withLock { protocolManager.markOutgoingContext(socketId, context) }
      sentContexts += context
    }
    if (sentContexts.isNotEmpty() && isBroadcastSocket(socketId)) {
      listener.onBroadcastTraffic(sentContexts)
    }
  }

  private suspend fun closeSupersededSockets(socketIds: Set<String>) {
    if (socketIds.isEmpty()) return
    val staleSessions = mutex.withLock {
      socketIds.mapNotNull { staleSocketId ->
        protocolManager.markDisconnectCategory(staleSocketId, "broadcast_replaced_by_same_client")
        sessions[staleSocketId]
      }
    }
    staleSessions.forEach { it.close() }
  }

  private suspend fun handleOutboundFailure(
    socketId: String,
    session: ClientSession,
    error: IOException
  ) {
    val category = "socket_write_error:${error.javaClass.simpleName}"
    mutex.withLock { protocolManager.markDisconnectCategory(socketId, category) }
    session.close()
    listener.onProtocolEvent("socket_write_error:${shortSocketId(socketId)}:${error.javaClass.simpleName}")
  }

  private fun isExpectedSocketClose(error: IOException): Boolean {
    val message = error.message.orEmpty()
    return message.contains("Socket closed", ignoreCase = true) ||
      message.contains("Stream closed", ignoreCase = true) ||
      message.contains("Connection reset", ignoreCase = true) ||
      message.contains("Broken pipe", ignoreCase = true)
  }

  private suspend fun isBroadcastSocket(socketId: String): Boolean =
    mutex.withLock { protocolManager.connectionDebugSnapshot(socketId)?.role == SocketRole.BROADCAST }

  private fun shortSocketId(socketId: String): String = socketId.take(8)

  private class ClientSession(
    socket: Socket
  ) {
    private val rawSocket = socket
    val reader: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
    private val mutex = Mutex()

    suspend fun send(payload: String) {
      mutex.withLock {
        writer.write(payload + "\r\n")
        writer.flush()
      }
    }

    fun close() {
      // readLine() can be blocked while holding BufferedReader's lock. Close the raw socket
      // first so that read unblocks before attempting to close the wrapped reader/writer.
      runCatching { rawSocket.close() }
      runCatching { reader.close() }
      runCatching { writer.close() }
    }
  }
}
