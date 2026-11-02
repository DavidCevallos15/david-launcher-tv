package dev.davidcevallos.tvremote.ui

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.ArrayDeque

/** Encuentra TVs en la red local vía mDNS: el servicio de control remoto anuncia _androidtvremote2._tcp. */
class TvDiscovery(context: Context, private val onFound: (name: String, host: String) -> Unit) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var active = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) = main.post { pending.add(info); resolveNext() }.let { }
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) { active = false }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
    }

    fun start() {
        if (active) return
        active = true
        nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        if (!active) return
        active = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
        pending.clear()
    }

    /** NsdManager solo resuelve un servicio a la vez: cola secuencial. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || !active) return
        val next = pending.poll() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) = main.post {
                resolving = false
                val host = info.host
                if (host is Inet4Address) onFound(info.serviceName, host.hostAddress!!)
                resolveNext()
            }.let { }

            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = main.post {
                resolving = false; resolveNext()
            }.let { }
        })
    }

    companion object {
        private const val SERVICE = "_androidtvremote2._tcp."
    }
}
