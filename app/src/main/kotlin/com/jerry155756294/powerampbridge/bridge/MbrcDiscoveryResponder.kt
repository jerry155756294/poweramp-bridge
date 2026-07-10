package com.jerry155756294.powerampbridge.bridge

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import com.jerry155756294.powerampbridge.protocol.ProtocolConstants
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/** Responds to the MBRC sender's UDP multicast discovery request while the bridge is running. */
class MbrcDiscoveryResponder(
  context: Context,
  private val onEvent: (String) -> Unit
) {
  private val appContext = context.applicationContext
  private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  private val multicastLock = wifiManager.createMulticastLock("poweramp-bridge-discovery").apply {
    setReferenceCounted(false)
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()
  private val sockets = mutableListOf<MulticastSocket>()
  private val receiveJobs = mutableListOf<Job>()

  @Volatile
  private var running = false

  fun start(listeningPort: Int) = synchronized(lock) {
    stopLocked()
    if (listeningPort !in 1..65535) {
      onEvent("discovery_not_started:invalid_tcp_port=$listeningPort")
      return@synchronized
    }

    val lockAcquired = runCatching {
      multicastLock.acquire()
      true
    }.getOrElse { error ->
      onEvent("discovery_not_started:multicast_lock:${error.javaClass.simpleName}")
      false
    }
    if (!lockAcquired) return@synchronized

    running = true
    val name = deviceName(appContext)
    multicastBindings().forEach { binding ->
      runCatching {
        MulticastSocket(null).apply {
          reuseAddress = true
          bind(InetSocketAddress(binding.address, DISCOVERY_PORT))
          joinGroup(InetSocketAddress(MULTICAST_GROUP, DISCOVERY_PORT), binding.networkInterface)
        }
      }.onSuccess { socket ->
        sockets += socket
        receiveJobs += scope.launch {
          receiveLoop(socket, binding.address, listeningPort, name)
        }
      }.onFailure { error ->
        Timber.w(error, "Discovery bind failed for %s", binding.address.hostAddress)
      }
    }

    if (sockets.isEmpty()) {
      running = false
      releaseMulticastLock()
      onEvent("discovery_not_started:no_ipv4_multicast_interface")
    } else {
      onEvent("discovery_started:port=$listeningPort interfaces=${sockets.size} name=$name")
    }
  }

  fun stop() = synchronized(lock) { stopLocked() }

  fun close() {
    stop()
    scope.cancel()
  }

  private fun stopLocked() {
    if (!running && sockets.isEmpty()) return
    running = false
    receiveJobs.forEach(Job::cancel)
    receiveJobs.clear()
    sockets.forEach { socket -> runCatching { socket.close() } }
    sockets.clear()
    releaseMulticastLock()
    onEvent("discovery_stopped")
  }

  private fun releaseMulticastLock() {
    if (multicastLock.isHeld) {
      runCatching { multicastLock.release() }
    }
  }

  private fun receiveLoop(
    socket: MulticastSocket,
    localAddress: Inet4Address,
    listeningPort: Int,
    name: String
  ) {
    val buffer = ByteArray(MAX_PACKET_SIZE)
    while (running && !socket.isClosed) {
      try {
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        if (!MbrcDiscoveryProtocol.isRequest(packet.data, packet.length)) continue

        val response = MbrcDiscoveryProtocol.notify(
          address = localAddress.hostAddress,
          name = name,
          port = listeningPort
        )
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
        onEvent("discovery_reply:${localAddress.hostAddress}:$listeningPort")
      } catch (error: Exception) {
        if (running && !socket.isClosed) {
          Timber.w(error, "Discovery receive failed for %s", localAddress.hostAddress)
          onEvent("discovery_receive_error:${error.javaClass.simpleName}")
        }
        break
      }
    }
  }

  private fun multicastBindings(): List<MulticastBinding> {
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
      ?: return emptyList()
    val bindings = mutableListOf<MulticastBinding>()
    while (interfaces.hasMoreElements()) {
      val networkInterface = interfaces.nextElement()
      if (!runCatching {
          networkInterface.isUp && !networkInterface.isLoopback && networkInterface.supportsMulticast()
        }.getOrDefault(false)
      ) {
        continue
      }
      val addresses = networkInterface.inetAddresses
      while (addresses.hasMoreElements()) {
        val address = addresses.nextElement() as? Inet4Address ?: continue
        if (!address.isSiteLocalAddress) continue
        bindings += MulticastBinding(networkInterface, address)
      }
    }
    return bindings
  }

  private data class MulticastBinding(
    val networkInterface: NetworkInterface,
    val address: Inet4Address
  )

  companion object {
    private const val DISCOVERY_PORT = 45345
    private const val MAX_PACKET_SIZE = 1024
    private val MULTICAST_GROUP: InetAddress = InetAddress.getByName("239.1.5.10")

    internal fun deviceName(context: Context): String {
      val configuredName = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
      }.getOrNull()
      return configuredName
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: android.os.Build.MODEL.trim().takeIf(String::isNotEmpty)
        ?: "Android"
    }
  }
}

internal object MbrcDiscoveryProtocol {
  fun isRequest(bytes: ByteArray, length: Int): Boolean = runCatching {
    JSONObject(String(bytes, 0, length, StandardCharsets.UTF_8))
      .optString("context")
      .equals(ProtocolConstants.Discovery, ignoreCase = true)
  }.getOrDefault(false)

  fun notify(address: String, name: String, port: Int): String = JSONObject()
    .put("context", ProtocolConstants.Notify)
    .put("address", address)
    .put("name", name)
    .put("port", port)
    .toString()
}
