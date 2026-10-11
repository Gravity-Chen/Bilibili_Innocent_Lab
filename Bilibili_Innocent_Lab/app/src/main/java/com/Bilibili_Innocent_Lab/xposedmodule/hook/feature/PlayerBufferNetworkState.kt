package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.util.concurrent.atomic.AtomicBoolean

/** One process-lifetime default-network subscription, initialized only by the disk worker. */
internal class PlayerBufferNetworkState {
    private val started = AtomicBoolean(false)
    private val wifi = AtomicBoolean(false)
    private var current: Network? = null

    fun isWifi(): Boolean = wifi.get()

    // Runs with the injected host Context, whose normal permission is checked below.
    // Lint only sees the module manifest, which is not the caller's permission boundary.
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            wifi.set(false)
            started.set(false)
            return
        }
        val manager = runCatching { context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }.getOrNull()
        if (manager == null) {
            started.set(false)
            return
        }
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = HostThreadGuard.run("player_buffer_network") {
                    synchronized(this@PlayerBufferNetworkState) {
                        current = network
                        wifi.set(false)
                    }
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    HostThreadGuard.run("player_buffer_network") {
                        synchronized(this@PlayerBufferNetworkState) {
                            if (current == network) wifi.set(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        }
                    }
                override fun onLost(network: Network) = HostThreadGuard.run("player_buffer_network") {
                    synchronized(this@PlayerBufferNetworkState) {
                        if (current == network) {
                            current = null
                            wifi.set(false)
                        }
                    }
                }
            })
        } catch (_: RuntimeException) {
            wifi.set(false)
            started.set(false)
        }
    }
}
