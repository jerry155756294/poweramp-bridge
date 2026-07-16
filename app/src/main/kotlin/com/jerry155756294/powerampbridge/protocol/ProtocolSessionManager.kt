package com.jerry155756294.powerampbridge.protocol

enum class SocketRole {
  BROADCAST,
  REQUEST,
  PROBE
}

enum class HandshakeState {
  AWAITING_PLAYER,
  AWAITING_PROTOCOL,
  AWAITING_INIT,
  READY
}

data class ProtocolClientInfo(
  val remoteAddress: String,
  val clientId: String?,
  val protocolVersion: Int,
  val role: SocketRole
)

data class LogicalClientSnapshot(
  val remoteAddress: String,
  val clientId: String?,
  val protocolVersion: Int?,
  val broadcastSocketConnected: Boolean,
  val broadcastInitialized: Boolean,
  val requestSocketCount: Int,
  val requestSocketConnected: Boolean
)

data class ConnectionDebugSnapshot(
  val socketId: String,
  val remoteAddress: String,
  val clientId: String?,
  val role: SocketRole?,
  val handshakeState: HandshakeState,
  val protocolVersion: Int,
  val broadcastInitialized: Boolean,
  val requestSocketCount: Int,
  val disconnectCategory: String? = null,
  val lastIncomingContext: String? = null,
  val lastOutgoingContext: String? = null
)

data class OutgoingMessage(
  val context: String,
  val data: Any? = ""
)

data class ProtocolEngineResult(
  val replies: List<OutgoingMessage> = emptyList(),
  val delegateMessage: IncomingMessage? = null,
  val clientInfo: ProtocolClientInfo? = null,
  val disconnect: Boolean = false,
  val disconnectCategory: String? = null,
  val socketsToClose: Set<String> = emptySet(),
  val sessionChanged: Boolean = false,
  val sessionSnapshot: LogicalClientSnapshot? = null,
  val probeAddress: String? = null,
  val rejectionReason: String? = null,
  val sendInitialSnapshot: Boolean = false,
  val protocolEvents: List<String> = emptyList()
)

data class ProtocolDisconnectResult(
  val sessionChanged: Boolean,
  val sessionSnapshot: LogicalClientSnapshot?,
  val protocolEvents: List<String> = emptyList()
)

class ProtocolSessionManager(
  private val serverProtocolVersion: Int = ProtocolConstants.ProtocolVersion
) {
  private val connections = linkedMapOf<String, ConnectionState>()
  private var logicalClient: LogicalClientSession? = null

  fun registerConnection(socketId: String, remoteAddress: String) {
    connections[socketId] = ConnectionState(socketId = socketId, remoteAddress = remoteAddress)
  }

  fun processMessage(socketId: String, message: IncomingMessage): ProtocolEngineResult {
    val connection = connections[socketId]
      ?: return ProtocolEngineResult(disconnect = true)
    connection.lastIncomingContext = message.context

    if (message.context == ProtocolConstants.VerifyConnection) {
      connection.role = SocketRole.PROBE
      connection.probeCompleted = true
      return ProtocolEngineResult(
        replies = listOf(OutgoingMessage(ProtocolConstants.VerifyConnection, true)),
        probeAddress = connection.remoteAddress
      )
    }

    if (message.context == ProtocolConstants.Ping) {
      return ProtocolEngineResult(
        replies = listOf(OutgoingMessage(ProtocolConstants.Pong, message.data ?: ""))
      )
    }

    if (message.context == ProtocolConstants.Pong) {
      return ProtocolEngineResult()
    }

    return when (connection.handshakeState) {
      HandshakeState.AWAITING_PLAYER -> handleAwaitingPlayer(connection, message)
      HandshakeState.AWAITING_PROTOCOL -> handleAwaitingProtocol(connection, message)
      HandshakeState.AWAITING_INIT -> handleAwaitingInit(connection, message)
      HandshakeState.READY -> handleReady(connection, message)
    }
  }

  fun disconnect(socketId: String): ProtocolDisconnectResult {
    val connection = connections.remove(socketId) ?: return ProtocolDisconnectResult(false, logicalClient?.toSnapshot())
    val currentSession = logicalClient ?: return ProtocolDisconnectResult(false, null)

    var changed = false
    val protocolEvents = mutableListOf<String>()
    var updatedSession = currentSession

    if (currentSession.activeBroadcastSocketId == connection.socketId) {
      updatedSession = updatedSession.copy(
        activeBroadcastSocketId = null,
        broadcastInitialized = false
      )
      changed = true
    }

    if (currentSession.pendingBroadcastSocketId == connection.socketId) {
      updatedSession = updatedSession.copy(pendingBroadcastSocketId = null)
      protocolEvents += "pending_broadcast_closed"
      changed = true
    }

    if (connection.socketId in currentSession.requestSocketIds) {
      updatedSession = updatedSession.copy(
        requestSocketIds = currentSession.requestSocketIds - connection.socketId
      )
      changed = true
    }

    logicalClient = if (
      updatedSession.activeBroadcastSocketId == null &&
      updatedSession.pendingBroadcastSocketId == null &&
      updatedSession.requestSocketIds.isEmpty()
    ) {
      null
    } else {
      updatedSession
    }

    return ProtocolDisconnectResult(
      sessionChanged = changed,
      sessionSnapshot = logicalClient?.toSnapshot(),
      protocolEvents = protocolEvents
    )
  }

  fun broadcastSocketId(): String? = logicalClient
    ?.takeIf { it.activeBroadcastSocketId != null && it.broadcastInitialized }
    ?.activeBroadcastSocketId

  fun handshakeTimeoutCategory(socketId: String): String? = when (
    connections[socketId]?.handshakeState
  ) {
    HandshakeState.AWAITING_PLAYER -> "handshake_timeout_before_player"
    HandshakeState.AWAITING_PROTOCOL -> "handshake_timeout_before_protocol"
    HandshakeState.AWAITING_INIT -> "handshake_timeout_before_init"
    HandshakeState.READY, null -> null
  }

  fun connectionDebugSnapshot(socketId: String): ConnectionDebugSnapshot? {
    val connection = connections[socketId] ?: return null
    val session = logicalClient
    return ConnectionDebugSnapshot(
      socketId = connection.socketId,
      remoteAddress = connection.remoteAddress,
      clientId = connection.clientId ?: session?.clientId,
      role = connection.role,
      handshakeState = connection.handshakeState,
      protocolVersion = connection.protocolVersion,
      broadcastInitialized = session?.broadcastInitialized ?: false,
      requestSocketCount = session?.requestSocketIds?.size ?: 0,
      disconnectCategory = connection.disconnectCategory,
      lastIncomingContext = connection.lastIncomingContext,
      lastOutgoingContext = connection.lastOutgoingContext
    )
  }

  fun markOutgoingContext(socketId: String, context: String) {
    connections[socketId]?.lastOutgoingContext = context
  }

  fun markDisconnectCategory(socketId: String, category: String) {
    connections[socketId]?.disconnectCategory = category
  }

  fun inferCloseCategory(socketId: String): String = inferCloseCategory(connections[socketId])

  private fun handleAwaitingPlayer(
    connection: ConnectionState,
    message: IncomingMessage
  ): ProtocolEngineResult {
    if (message.context != ProtocolConstants.Player) {
      return ProtocolEngineResult(disconnect = true, disconnectCategory = "protocol_violation_before_player")
    }

    connection.handshakeState = HandshakeState.AWAITING_PROTOCOL
    return ProtocolEngineResult(
      replies = listOf(OutgoingMessage(ProtocolConstants.Player, ProtocolConstants.PlayerName))
    )
  }

  private fun handleAwaitingProtocol(
    connection: ConnectionState,
    message: IncomingMessage
  ): ProtocolEngineResult {
    if (message.context != ProtocolConstants.Protocol) {
      return ProtocolEngineResult(disconnect = true, disconnectCategory = "protocol_violation_before_protocol")
    }

    val handshake = parseHandshake(message.data)
    val attach = attachToLogicalClient(connection, handshake)
    if (attach == null) {
      return ProtocolEngineResult(
        replies = listOf(OutgoingMessage(ProtocolConstants.NotAllowed, "single_client_only")),
        disconnect = true,
        disconnectCategory = "single_client_only",
        rejectionReason = "single_client_only"
      )
    }

    connection.role = attach.clientInfo.role
    connection.clientId = attach.clientInfo.clientId
    connection.protocolVersion = attach.clientInfo.protocolVersion
    connection.handshakeState = if (attach.clientInfo.role == SocketRole.BROADCAST) {
      HandshakeState.AWAITING_INIT
    } else {
      HandshakeState.READY
    }

    return ProtocolEngineResult(
      replies = listOf(OutgoingMessage(ProtocolConstants.Protocol, serverProtocolVersion)),
      socketsToClose = attach.replacedSocketIds,
      sessionChanged = true,
      sessionSnapshot = logicalClient?.toSnapshot(),
      protocolEvents = if (attach.clientInfo.role == SocketRole.BROADCAST) {
        listOf("pending_broadcast_created")
      } else {
        emptyList()
      }
    )
  }

  private fun handleAwaitingInit(
    connection: ConnectionState,
    message: IncomingMessage
  ): ProtocolEngineResult {
    if (message.context != ProtocolConstants.Init) {
      if (logicalClient?.pendingBroadcastSocketId == connection.socketId) {
        return ProtocolEngineResult(
          delegateMessage = message,
          clientInfo = connection.toClientInfo(),
          protocolEvents = listOf("pre_init_command_accepted")
        )
      }
      return ProtocolEngineResult(disconnect = true, disconnectCategory = "protocol_violation_before_init")
    }

    // A reconnect remains pending until init. This keeps a healthy active broadcast socket
    // available for state traffic while the new socket finishes its handshake.
    if (logicalClient?.pendingBroadcastSocketId != connection.socketId) {
      return ProtocolEngineResult(disconnect = true, disconnectCategory = "stale_broadcast_init")
    }

    val previousActiveSocketId = logicalClient?.activeBroadcastSocketId
    connection.handshakeState = HandshakeState.READY
    logicalClient = logicalClient?.copy(
      activeBroadcastSocketId = connection.socketId,
      pendingBroadcastSocketId = null,
      broadcastInitialized = true
    )

    return ProtocolEngineResult(
      clientInfo = connection.toClientInfo(),
      socketsToClose = previousActiveSocketId
        ?.takeIf { it != connection.socketId }
        ?.let(::setOf)
        ?: emptySet(),
      sessionChanged = true,
      sessionSnapshot = logicalClient?.toSnapshot(),
      sendInitialSnapshot = true,
      protocolEvents = buildList {
        add("pending_broadcast_promoted")
        if (previousActiveSocketId != null && previousActiveSocketId != connection.socketId) {
          add("active_broadcast_replaced")
        }
      }
    )
  }

  private fun handleReady(
    connection: ConnectionState,
    message: IncomingMessage
  ): ProtocolEngineResult {
    connection.readyMessageCount += 1
    return ProtocolEngineResult(
      delegateMessage = message,
      clientInfo = connection.toClientInfo()
    )
  }

  private fun attachToLogicalClient(
    connection: ConnectionState,
    handshake: ParsedHandshake
  ): AttachResult? {
    val role = if (handshake.noBroadcast) SocketRole.REQUEST else SocketRole.BROADCAST
    val currentSession = logicalClient
    val requestedClientId = handshake.clientId?.takeIf { it.isNotBlank() }

    if (currentSession == null) {
      logicalClient = LogicalClientSession(
        remoteAddress = connection.remoteAddress,
        clientId = requestedClientId,
        protocolVersion = handshake.protocolVersion,
        activeBroadcastSocketId = null,
        pendingBroadcastSocketId = if (role == SocketRole.BROADCAST) connection.socketId else null,
        requestSocketIds = if (role == SocketRole.REQUEST) setOf(connection.socketId) else emptySet(),
        broadcastInitialized = false
      )
      return AttachResult(
        clientInfo = ProtocolClientInfo(
          remoteAddress = connection.remoteAddress,
          clientId = requestedClientId,
          protocolVersion = handshake.protocolVersion,
          role = role
        )
      )
    }

    if (!matchesCurrentSender(currentSession, connection.remoteAddress, requestedClientId)) {
      return null
    }

    val effectiveClientId = currentSession.clientId ?: requestedClientId
    val replacedSocketIds = mutableSetOf<String>()
    if (
      role == SocketRole.BROADCAST &&
      currentSession.pendingBroadcastSocketId != null &&
      currentSession.pendingBroadcastSocketId != connection.socketId
    ) {
      replacedSocketIds += currentSession.pendingBroadcastSocketId
    }
    logicalClient = currentSession.copy(
      clientId = effectiveClientId,
      protocolVersion = handshake.protocolVersion,
      pendingBroadcastSocketId = if (role == SocketRole.BROADCAST) {
        connection.socketId
      } else {
        currentSession.pendingBroadcastSocketId
      },
      requestSocketIds = if (role == SocketRole.REQUEST) {
        currentSession.requestSocketIds + connection.socketId
      } else {
        currentSession.requestSocketIds
      },
      broadcastInitialized = currentSession.broadcastInitialized
    )

    return AttachResult(
      clientInfo = ProtocolClientInfo(
        remoteAddress = connection.remoteAddress,
        clientId = effectiveClientId,
        protocolVersion = handshake.protocolVersion,
        role = role
      ),
      replacedSocketIds = replacedSocketIds
    )
  }

  private fun parseHandshake(data: Any?): ParsedHandshake {
    if (data is Map<*, *>) {
      val version = parseProtocolVersion(data["protocol_version"])
      return ParsedHandshake(
        protocolVersion = version,
        noBroadcast = data["no_broadcast"].asBoolean(),
        clientId = data["client_id"]?.toString()
      )
    }

    return ParsedHandshake(
      protocolVersion = parseProtocolVersion(data),
      noBroadcast = false,
      clientId = null
    )
  }

  private fun parseProtocolVersion(data: Any?): Int {
    val parsed = when (data) {
      is Number -> data.toDouble().toInt()
      is String -> data.toDoubleOrNull()?.toInt()
      else -> null
    }
    return (parsed ?: serverProtocolVersion).coerceIn(2, serverProtocolVersion)
  }

  private fun Any?.asBoolean(): Boolean = when (this) {
    is Boolean -> this
    is Number -> this.toInt() != 0
    is String -> this.equals("true", ignoreCase = true)
    else -> false
  }

  private fun matchesCurrentSender(
    currentSession: LogicalClientSession,
    remoteAddress: String,
    requestedClientId: String?
  ): Boolean {
    val currentClientId = currentSession.clientId
    return if (currentClientId != null && requestedClientId != null) {
      currentClientId == requestedClientId
    } else {
      currentSession.remoteAddress == remoteAddress
    }
  }

  private data class ParsedHandshake(
    val protocolVersion: Int,
    val noBroadcast: Boolean,
    val clientId: String?
  )

  private data class AttachResult(
    val clientInfo: ProtocolClientInfo,
    val replacedSocketIds: Set<String> = emptySet()
  )

  private data class ConnectionState(
    val socketId: String,
    val remoteAddress: String,
    var role: SocketRole? = null,
    var clientId: String? = null,
    var protocolVersion: Int = ProtocolConstants.ProtocolVersion,
    var handshakeState: HandshakeState = HandshakeState.AWAITING_PLAYER,
    var disconnectCategory: String? = null,
    var lastIncomingContext: String? = null,
    var lastOutgoingContext: String? = null,
    var readyMessageCount: Int = 0,
    var probeCompleted: Boolean = false
  ) {
    fun toClientInfo(): ProtocolClientInfo = ProtocolClientInfo(
      remoteAddress = remoteAddress,
      clientId = clientId,
      protocolVersion = protocolVersion,
      role = role ?: SocketRole.BROADCAST
    )
  }

  private data class LogicalClientSession(
    val remoteAddress: String,
    val clientId: String?,
    val protocolVersion: Int?,
    val activeBroadcastSocketId: String?,
    val pendingBroadcastSocketId: String?,
    val requestSocketIds: Set<String>,
    val broadcastInitialized: Boolean
  ) {
    fun toSnapshot(): LogicalClientSnapshot = LogicalClientSnapshot(
      remoteAddress = remoteAddress,
      clientId = clientId,
      protocolVersion = protocolVersion,
      broadcastSocketConnected = activeBroadcastSocketId != null || pendingBroadcastSocketId != null,
      broadcastInitialized = broadcastInitialized,
      requestSocketCount = requestSocketIds.size,
      requestSocketConnected = requestSocketIds.isNotEmpty()
    )
  }

  private fun inferCloseCategory(connection: ConnectionState?): String = when {
    connection?.disconnectCategory != null -> connection.disconnectCategory!!
    connection == null -> "peer_closed_unknown"
    connection.role == SocketRole.PROBE && connection.probeCompleted -> "probe_socket_completed"
    connection.role == SocketRole.REQUEST && connection.readyMessageCount > 0 ->
      "request_socket_peer_closed_after_command"
    connection.role == SocketRole.REQUEST -> "request_socket_completed"
    connection.handshakeState == HandshakeState.AWAITING_PLAYER -> "broadcast_peer_closed_before_player"
    connection.handshakeState != HandshakeState.READY -> "broadcast_peer_closed_during_handshake"
    else -> "broadcast_peer_closed_after_init"
  }
}
