package com.voxapps.commander.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Realtime network connectivity monitor.
 * Uses ConnectivityManager.NetworkCallback to track online/offline status.
 * Exposes a StateFlow<Boolean> that can be observed from Compose or coroutines.
 *
 * Usage:
 *   NetworkMonitor.init(context)  // call once in Application.onCreate
 *   NetworkMonitor.isOnline       // synchronous check
 *   NetworkMonitor.onlineFlow     // reactive StateFlow for UI
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"

    private val _online = MutableStateFlow(true)
    val onlineFlow: StateFlow<Boolean> = _online.asStateFlow()

    val isOnline: Boolean get() = _online.value

    val isMetered: Boolean get() = isOnline && !isUnmetered

    val isUnmetered: Boolean
        get() {
            val cm = connectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        // Set initial state
        _online.value = checkConnectivity()

        // Register callback for realtime updates
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _online.value = true
                Logger.log("Network became available", TAG)
            }

            override fun onLost(network: Network) {
                // Only mark offline if no other network is available
                _online.value = checkConnectivity()
                Logger.log("Network lost, online=${_online.value}", TAG)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                _online.value = hasInternet
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(networkRequest, callback ?: return)
        } catch (e: Exception) {
            Logger.log("Failed to register network callback: ${e.message}", TAG)
        }

        Logger.log("Initialized, online=${_online.value}", TAG)
    }

    /**
     * Synchronous connectivity check.
     * Returns true if there's an active network with internet capability.
     */
    private fun checkConnectivity(): Boolean {
        val cm = connectivityManager ?: return true // assume online if CM not available
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Can be called to re-check connectivity on demand.
     */
    fun refresh() {
        _online.value = checkConnectivity()
        Logger.log("Refreshed, online=${_online.value}", TAG)
    }
}
