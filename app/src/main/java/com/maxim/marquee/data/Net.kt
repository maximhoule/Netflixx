package com.maxim.marquee.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Is the TV connected to a network that has internet? */
object Net {
    fun online(ctx: Context): Boolean = safe {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@safe false) ?: return@safe false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } ?: true

    /** Calls back (on a background thread) whenever the connection comes or goes. Returns an unregister function. */
    fun watch(ctx: Context, onChange: (Boolean) -> Unit): () -> Unit {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(true)
            override fun onLost(network: Network) = onChange(online(ctx))
        }
        val registered = try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb,
            )
            true
        } catch (e: Exception) {
            false
        }
        val unregister: () -> Unit = { if (registered) runCatching { cm.unregisterNetworkCallback(cb) } }
        return unregister
    }
}
