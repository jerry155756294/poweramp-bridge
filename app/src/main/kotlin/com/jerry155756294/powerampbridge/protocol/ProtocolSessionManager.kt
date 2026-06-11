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
  val requestSocketConnected: Boolean
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
  val sessionChanged: Boolean = false,
  val sessionSnapshot: LogicalClientSnapshot? = null,
  val probeAddress: String? = null,
  val rejectionReason: String? = null,
  val sendInitialSnapshot: Boolean = false
)

data class ProtocolDisconnectResult(
  val sessionChanged: Boolean,
  val sessionSnapshot: LogicalClientSnapshot?
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

    if (message.context == ProtocolConstants.VerifyConnection) {
      connection.role = SocketRole.PROBE
      return ProtocolEngineResult(
        replies = listOf(OutgoingMessage(ProtocolConstants.VerifyConnection, "")),
        probeAddress = connection.remoteAddress
      )
    }

    if (message.context == ProtocolConstants.Ping) {
      return ProtocolEngineResult(
        replies = listOf(OutgoingMessage(ProtocolConstants.Pong, message.data ?: ""))
      )
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
    var updatedSession = currentSession

    if (currentSession.broadcastSocketId == connection.socketId) {
      updatedSession = updatedSession.copy(
        broadcastSocketId = null,
        broadcastInitialized = false
      )
      changed = true
    }

    if (currentSession.requestSocketId == connection.socketId) {
      updatedSession = updatedSession.copy(requestSocketId = null)
      changed = true
    }

    logicalClient = if (updatedSession.broadcastSocketId == null && updatedSession.requestSocketId == null) {
      null
    } else {
      updatedSession
    }

    return ProtocolDisconnectResult(
      sessionChanged = changed,
      sessionSnapshot = logicalClient?.toSnapshot()
    )
  }

  fun broadcastSocketId(): String? = logicalClient
    ?.takeIf { it.broadcastSocketId != null && it.broadcastInitialized }
    ?.broadcastSocketId

  private fun handleAwaitingPlayer(
    connection: ConnectionState,
    message: IncomingMessage
  ): ProtocolEngineResult {
    if (message.context != ProtocolConstants.Player) {
      return ProtocolEngineResult(disconnect = true)
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
      return ProtocolEngineResult(disconnect = true)
    }

    val handshake = parseHandshake(message.data)
    val attach = attachToLogicalClient(connection, handshake)
    if (attach == null) {
      return ProtocolEngineResult(
        replies = listOf(OutgoingMessage(ProtocolConstants.NotAllowed, "single_client_only")),
        disconnect = true,
        rejectionReason = "single_client_only"
      )
    }

    connection.role = attach.role
    connection.clientId = attach.clientId
    connection.protocolVersion = attach.protocolVersion
    connection.handshakeState = if (attach.role == SocketRole.BROADCAST) {
      HandshakeState.AWAITING_INIT
    } else {
      HandshakeState.READY
    }

    return ProtocolEngineResult(
      replies = listOf(OutgoingMessage(ProtocolConstants.Protocol, serverProtocolVersion)),
      sessionChanged = true,
      sessionSnapshot = logicalClient?.toSnapshot()
    )
  }

  private fun handleAwaitingInit(
    connection: ConnectionState,
    message: IncomingMessage
  ): ProtocolEngineResult {
    if (message.context != ProtocolConstants.Init) {
      return ProtocolEngineResult(disconnect = true)
    }

    connection.handshakeState = HandshakeState.READY
    logicalClient = logicalClient?.copy(broadcastInitialized = true)

    return ProtocolEngineResult(
      clientInfo = connection.toClientInfo(),
      sessionChanged = true,
      sessionSnapshot = logicalClient?.toSnapshot(),
      sendInitialSnapshot = true
    )
  }

  private fun handleReady(
    connection: ConnectionState,
    message: IncomingMessage
  ): ProtocolEngineResult = ProtocolEngineResult(
    delegateMessage = message,
    clientInfo = connection.toClientInfo()
  )

  private fun attachToLogicalClient(
    connection: ConnectionState,
    handshake: ParsedHandshake
  ): ProtocolClientInfo? {
    val role = if (handshake.noBroadcast) SocketRole.REQUEST else SocketRole.BROADCAST
    val currentSession = logicalClient
    val requestedClientId = handshake.clientId?.takeIf { it.isNotBlank() }

    if (currentSession == null) {
      logicalClient = LogicalClientSession(
        remoteAddress = connection.remoteAddress,
        clientId = requestedClientId,
        protocolVersion = handshake.protocolVersion,
        broadcastSocketId = if (role == SocketRole.BROADCAST) connection.socketId else null,
        requestSocketId = if (role == SocketRole.REQUEST) connection.socketId else null,
        broadcastInitialized = false
      )
      return ProtocolClientInfo(
        remoteAddress = connection.remoteAddress,
        clientId = requestedClientId,
        protocolVersion = handshake.protocolVersion,
        role = role
      )
    }

    if (currentSession.remoteAddress != connection.remoteAddress) {
      return null
    }

    if (requestedClientId != null && currentSession.clientId != null && currentSession.clientId != requestedClientId) {
      return null
    }

    if (role == SocketRole.BROADCAST && currentSession.broadcastSocketId != null) {
      return null
    }

    if (role == SocketRole.REQUEST && currentSession.requestSocketId != null) {
      return null
    }

    val effectiveClientId = currentSession.clientId ?: requestedClientId
    logicalClient = currentSession.copy(
      clientId = effectiveClientId,
      protocolVersion = handshake.protocolVersion,
      broadcastSocketId = if (role == SocketRole.BROADCAST) connection.socketId else currentSession.broadcastSocketId,
      requestSocketId = if (role == SocketRole.REQUEST) connection.socketId else currentSession.requestSocketId
    )

    return ProtocolClientInfo(
      remoteAddress = connection.remoteAddress,
      clientId = effectiveClientId,
      protocolVersion = handshake.protocolVersion,
      role = role
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

  private data class ParsedHandshake(
    val protocolVersion: Int,
    val noBroadcast: Boolean,
    val clientId: String?
  )

  private data class ConnectionState(
    val socketId: String,
    val remoteAddress: String,
    var role: SocketRole? = null,
    var clientId: String? = null,
    var protocolVersion: Int = ProtocolConstants.ProtocolVersion,
    var handshakeState: HandshakeState = HandshakeState.AWAITING_PLAYER
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
    val broadcastSocketId: String?,
    val requestSocketId: String?,
    val broadcastInitialized: Boolean
  ) {
    fun toSnapshot(): LogicalClientSnapshot = LogicalClientSnapshot(
      remoteAddress = remoteAddress,
      clientId = clientId,
      protocolVersion = protocolVersion,
      broadcastSocketConnected = broadcastSocketId != null,
      broadcastInitialized = broadcastInitialized,
      requestSocketConnected = requestSocketId != null
    )
  }
}
