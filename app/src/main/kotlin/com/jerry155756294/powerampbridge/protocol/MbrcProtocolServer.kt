package com.jerry155756294.powerampbridge.protocol

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
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
    suspend fun onClientConnected(remoteAddress: String): Boolean
    suspend fun onClientDisconnected(remoteAddress: String)
    suspend fun onMessage(remoteAddress: String, message: IncomingMessage): List<String>
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var serverSocket: ServerSocket? = null
  private var acceptJob: Job? = null
  private val activeSession = AtomicReference<ClientSession?>(null)

  suspend fun start(port: Int) {
    stop()
    serverSocket = ServerSocket(port)
    acceptJob = scope.launch {
      while (isActive) {
        val socket = try {
          serverSocket?.accept()
        } catch (_: Exception) {
          null
        } ?: break

        val remoteAddress = socket.inetAddress?.hostAddress.orEmpty()
        if (activeSession.get() != null) {
          rejectClient(socket)
          continue
        }

        if (!listener.onClientConnected(remoteAddress)) {
          rejectClient(socket)
          continue
        }

        val session = ClientSession(socket)
        activeSession.set(session)
        scope.launch {
          handleClient(session, remoteAddress)
        }
      }
    }
  }

  suspend fun stop() {
    acceptJob?.cancelAndJoin()
    acceptJob = null
    activeSession.getAndSet(null)?.close()
    serverSocket?.close()
    serverSocket = null
  }

  suspend fun sendToActive(messages: List<String>) {
    val session = activeSession.get() ?: return
    messages.forEach { session.send(it) }
  }

  private suspend fun handleClient(session: ClientSession, remoteAddress: String) {
    try {
      while (true) {
        val line = session.reader.readLine() ?: break
        if (line.isBlank()) continue
        val message = codec.parse(line)
        val replies = listener.onMessage(remoteAddress, message)
        replies.forEach { session.send(it) }
      }
    } catch (error: Exception) {
      Timber.w(error, "Client loop ended for %s", remoteAddress)
    } finally {
      activeSession.compareAndSet(session, null)
      session.close()
      listener.onClientDisconnected(remoteAddress)
    }
  }

  private suspend fun rejectClient(socket: Socket) {
    runCatching {
      val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
      writer.write(codec.encode(ProtocolConstants.NotAllowed, "single_client_only") + "\r\n")
      writer.flush()
      writer.close()
    }
    runCatching { socket.close() }
  }

  private class ClientSession(
    socket: Socket
  ) {
    private val mutex = Mutex()
    private val rawSocket = socket
    val reader: BufferedReader = BufferedReader(InputStreamReader(socket.getInputStream()))
    private val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)

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
