package org.androidgradletools.adbrelayandroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Dial order for a DIAL frame (server plan §4): Wi-Fi IPv4 link addresses first, then
 * `127.0.0.1`. adbd's wireless listeners are often on the Wi-Fi address; on some Android 11
 * builds only loopback works, on others loopback returns ECONNREFUSED. Never log the address —
 * the only observable is `via=link|loopback`.
 */
fun interface LocalTargetSelector {
    /** Candidates in dial order; empty list means nothing to try (`no_addr`). */
    fun candidates(): List<Candidate>
}

data class Candidate(val host: String, val via: String) {
    companion object {
        const val VIA_LINK = "link"
        const val VIA_LOOPBACK = "loopback"
        val LOOPBACK = Candidate("127.0.0.1", VIA_LOOPBACK)
    }
}

class ConnectivityTargetSelector(private val context: Context) : LocalTargetSelector {

    override fun candidates(): List<Candidate> {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return listOf(Candidate.LOOPBACK)
        val link = mutableListOf<Candidate>()
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            // A VPN's link addresses are the tunnel's, not adbd's; skip them.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val props: LinkProperties = cm.getLinkProperties(network) ?: continue
            for (address in props.linkAddresses) {
                val addr: InetAddress = address.address
                if (addr is Inet4Address && !addr.isLinkLocalAddress && !addr.isLoopbackAddress) {
                    link.add(Candidate(addr.hostAddress ?: continue, Candidate.VIA_LINK))
                }
            }
        }
        return link + listOf(Candidate.LOOPBACK)
    }
}