package com.voxapps.commander.utils

import com.voxapps.logging.Logger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Not "true": a network can be available and not yet carry traffic. The capabilities
                // callback that follows says whether it does.
                _online.value = checkConnectivity()
                Logger.log("Network available, online=${_online.value}", TAG)
            }

            override fun onLost(network: Network) {
                _online.value = checkConnectivity()
                Logger.log("Network lost, online=${_online.value}", TAG)
            }

            override fun onUnavailable() {
                _online.value = false
                Logger.log("No network available", TAG)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val reachable = capabilities.reachesInternet()
                if (reachable != _online.value) {
                    Logger.log("Connectivity changed, online=$reachable", TAG)
                }
                _online.value = reachable
            }
        }

        try {
            // The *default* network — the one the system would actually use — rather than a filtered
            // request. A request that selects for NET_CAPABILITY_INTERNET only ever reports networks
            // that have it, so the capabilities callback above could report nothing but "online",
            // and losing internet on a connected Wi-Fi network was invisible.
            connectivityManager?.registerDefaultNetworkCallback(callback ?: return)
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
        return caps.reachesInternet()
    }

    /**
     * Whether traffic sent to this network would actually reach the internet.
     *
     * NET_CAPABILITY_INTERNET alone is a claim about the transport, not about the connection: it
     * stays set on a Wi-Fi network whose upstream has died, and on a captive portal that intercepts
     * everything. VALIDATED is the answer to the question a user means by "am I online" — Android
     * has probed this network and traffic got through. Asking only the first is why pulling the
     * internet from a still-connected Wi-Fi left the app believing it was online.
     */
    private fun NetworkCapabilities.reachesInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    /**
     * Can be called to re-check connectivity on demand.
     */
    fun refresh() {
        _online.value = checkConnectivity()
        Logger.log("Refreshed, online=${_online.value}", TAG)
    }
}
