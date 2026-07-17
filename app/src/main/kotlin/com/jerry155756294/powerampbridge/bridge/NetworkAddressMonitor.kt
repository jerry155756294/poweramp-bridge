package com.jerry155756294.powerampbridge.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal data class LocalAddressCandidate(
  val interfaceName: String,
  val address: String
)

/** Puts the address most likely to work for a nearby controller at the top. */
internal fun prioritizeLocalAddresses(candidates: Collection<LocalAddressCandidate>): List<String> =
  candidates
    .distinctBy(LocalAddressCandidate::address)
    .sortedWith(
      compareBy<LocalAddressCandidate> { candidate ->
        val isWifi = candidate.interfaceName.contains("wlan", ignoreCase = true) ||
          candidate.interfaceName.contains("wifi", ignoreCase = true)
        val ipv4 = runCatching { java.net.InetAddress.getByName(candidate.address) as? Inet4Address }
          .getOrNull()
        when {
          isWifi && ipv4?.isSiteLocalAddress == true -> 0
          ipv4?.isSiteLocalAddress == true -> 1
          ipv4 != null -> 2
          else -> 3
        }
      }.thenBy { it.address }
    )
    .map(LocalAddressCandidate::address)

class NetworkAddressMonitor(
  context: Context,
  private val stateRepository: BridgeStateRepository
) {
  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var registered = false

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) = refreshAsync()

    override fun onLost(network: Network) = refreshAsync()

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
      refreshAsync()

    override fun onUnavailable() = refreshAsync()
  }

  fun start() {
    if (registered) return
    registered = true
    refreshAsync()
    runCatching {
      connectivityManager.registerNetworkCallback(
        NetworkRequest.Builder()
          .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
          .build(),
        callback
      )
    }.onFailure {
      registered = false
      refreshAsync()
    }
  }

  fun stop() {
    if (!registered) return
    registered = false
    runCatching {
      connectivityManager.unregisterNetworkCallback(callback)
    }
  }

  private fun refreshAsync() {
    scope.launch {
      stateRepository.setLocalAddresses(resolveLocalAddresses())
    }
  }

  internal fun resolveLocalAddresses(): List<String> {
    val addresses = mutableListOf<LocalAddressCandidate>()
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()

    while (interfaces.hasMoreElements()) {
      val networkInterface = interfaces.nextElement()
      val isUsable = runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)
      if (!isUsable) continue

      val inetAddresses = networkInterface.inetAddresses
      while (inetAddresses.hasMoreElements()) {
        val address = inetAddresses.nextElement()
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
          continue
        }

        val hostAddress = when (address) {
          is Inet6Address -> address.hostAddress.substringBefore('%')
          is Inet4Address -> address.hostAddress
          else -> address.hostAddress
        }
        if (hostAddress.isNullOrBlank()) continue

        addresses += LocalAddressCandidate(networkInterface.name, hostAddress)
      }
    }

    return prioritizeLocalAddresses(addresses)
  }
}
