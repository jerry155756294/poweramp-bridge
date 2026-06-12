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
    acceptJob?.cancelAndJoin()
    acceptJob = null
    mutex.withLock {
      sessions.values.forEach { it.close() }
      sessions.clear()
    }
    serverSocket?.close()
    serverSocket = null
    listener.onProtocolEvent("listener_stopped")
  }

  suspend fun sendBroadcast(messages: List<String>) {
    val socketId = mutex.withLock { protocolManager.broadcastSocketId() } ?: return
    val target = mutex.withLock { sessions[socketId] } ?: return

    messages.forEach { payload ->
      mutex.withLock { protocolManager.markOutgoingContext(socketId, codec.parse(payload).context) }
      target.send(payload)
    }
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
          protocolManager.connectionDebugSnapshot(socketId)?.let { snapshot ->
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
      Timber.w(error, "Client loop ended for %s", remoteAddress)
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
    replies.forEach { reply ->
      mutex.withLock { protocolManager.markOutgoingContext(socketId, reply.context) }
      session.send(codec.encode(reply.context, reply.data))
    }
  }

  private suspend fun sendEncodedReplies(
    socketId: String,
    session: ClientSession,
    replies: List<String>
  ) {
    replies.forEach { payload ->
      mutex.withLock { protocolManager.markOutgoingContext(socketId, codec.parse(payload).context) }
      session.send(payload)
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

  private fun shortSocketId(socketId: String): String = socketId.take(8)

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
