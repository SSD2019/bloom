package com.rrajath.grove.remote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Helpers for detecting active VPN transports, opening WireGuard / Android VPN Settings,
 * and optionally remote-controlling a configured WireGuard tunnel.
 */
object VpnHelper {
    const val WIREGUARD_PACKAGE = "com.wireguard.android"
    const val PERMISSION_CONTROL_TUNNELS = "com.wireguard.android.permission.CONTROL_TUNNELS"
    private const val ACTION_SET_TUNNEL_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
    private const val ACTION_SET_TUNNEL_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
    private const val EXTRA_TUNNEL = "tunnel"
    private const val TAG = "GroveVpn"
    private val connectMutex = Mutex()

    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    fun hasControlTunnelsPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_CONTROL_TUNNELS) == PackageManager.PERMISSION_GRANTED

    fun isWireGuardInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(WIREGUARD_PACKAGE) != null

    fun setTunnelUp(context: Context, tunnelName: String) {
        val trimmed = tunnelName.trim()
        if (trimmed.isEmpty() || !hasControlTunnelsPermission(context)) return
        Log.d(TAG, "Sending SET_TUNNEL_UP for '$trimmed'")
        val intent = Intent(ACTION_SET_TUNNEL_UP)
            .setPackage(WIREGUARD_PACKAGE)
            .putExtra(EXTRA_TUNNEL, trimmed)
        context.sendBroadcast(intent)
    }

    fun setTunnelDown(context: Context, tunnelName: String) {
        val trimmed = tunnelName.trim()
        if (trimmed.isEmpty() || !hasControlTunnelsPermission(context)) return
        Log.d(TAG, "Sending SET_TUNNEL_DOWN for '$trimmed'")
        val intent = Intent(ACTION_SET_TUNNEL_DOWN)
            .setPackage(WIREGUARD_PACKAGE)
            .putExtra(EXTRA_TUNNEL, trimmed)
        context.sendBroadcast(intent)
    }

    /**
     * Brings up [tunnelName], waits up to [timeoutMs] for `TRANSPORT_VPN` to appear,
     * and briefly polls [probe] until routes are ready. Serialized by [connectMutex] so
     * launch and sync paths never race each other.
     */
    suspend fun connectTunnelAndProbe(
        context: Context,
        tunnelName: String,
        timeoutMs: Long = 8_000L,
        probe: suspend () -> Boolean,
    ): Boolean = connectMutex.withLock {
        if (tunnelName.isBlank() || !hasControlTunnelsPermission(context)) return@withLock false
        if (isVpnActive(context) && probe()) {
            Log.d(TAG, "VPN already active and reachable for '$tunnelName'")
            return@withLock true
        }
        setTunnelUp(context, tunnelName)

        val vpnUp = if (isVpnActive(context)) {
            true
        } else {
            awaitVpnTransport(context, timeoutMs)
        }
        if (!vpnUp) {
            Log.w(TAG, "Timed out waiting for TRANSPORT_VPN for '$tunnelName'")
            return@withLock false
        }

        // TRANSPORT_VPN can appear before WireGuard finishes handshake / installing routes.
        val deadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < deadline) {
            if (probe()) {
                Log.d(TAG, "Tunnel '$tunnelName' is up and reachable")
                return@withLock true
            }
            delay(300L)
        }
        Log.w(TAG, "TRANSPORT_VPN came up for '$tunnelName' but probe did not succeed")
        false
    }

    private suspend fun awaitVpnTransport(context: Context, timeoutMs: Long): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(true)
                    }
                }
                cm.registerNetworkCallback(request, callback)
                cont.invokeOnCancellation {
                    runCatching { cm.unregisterNetworkCallback(callback) }
                }
                if (isVpnActive(context) && cont.isActive) {
                    cont.resume(true)
                }
            }
        } ?: false
    }

    fun openWireGuardOrVpnSettings(context: Context) {
        val wgIntent = context.packageManager.getLaunchIntentForPackage(WIREGUARD_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (wgIntent != null) {
            context.startActivity(wgIntent)
            return
        }
        val vpnIntent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(vpnIntent) }
    }
}

