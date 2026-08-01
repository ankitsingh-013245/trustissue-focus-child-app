package com.trustissue.child

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Read-only VPN/network inspection shared by the consent flow and DNS service.
 */
object VpnConflictDetector {
    fun isExternalVpnActive(context: Context): Boolean {
        if (FocusWebProtectionService.isRunningInProcess()) return false
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return runCatching {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(false)
    }

    fun preferredUnderlyingNetwork(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return runCatching {
            val candidates = manager.allNetworks.mapNotNull { network ->
                val capabilities =
                    manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    return@mapNotNull null
                }
                network to capabilities
            }
            candidates.firstOrNull { (_, capabilities) ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }?.first ?: candidates.firstOrNull()?.first
        }.getOrNull()
    }

    fun isUsableUnderlyingNetwork(context: Context, network: Network): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return runCatching {
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }
}
