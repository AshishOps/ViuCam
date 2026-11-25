package com.personal.viucam

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Finds the primary local IPv4 address (e.g., 192.168.x.x or 10.x.x.x) for the Wi-Fi or tethering interface.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prefer wlan0 / Wi-Fi interfaces first
            val sortedInterfaces = interfaces.sortedByDescending {
                it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("rndis")
            }

            for (networkInterface in sortedInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && !hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
