package com.visionaid.app.connection

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkScanner @Inject constructor() {

    companion object {
        private const val TAG = "NetworkScanner"
        private const val SCAN_TIMEOUT_MS = 200
    }

    /**
     * Scans the local subnet for the Pi's WebSocket server on the given port.
     * It pings all 254 addresses in parallel and returns the first IP that responds.
     *
     * @param port The port to probe (e.g., 8765)
     * @return The IP address of the Pi, or null if not found.
     */
    suspend fun scanForPi(port: Int = 8765): String? = withContext(Dispatchers.IO) {
        val localIp = getLocalIpAddress() ?: return@withContext null
        Log.i(TAG, "Local IP: $localIp")
        
        val subnet = localIp.substringBeforeLast(".") + "."
        Log.i(TAG, "Scanning subnet: ${subnet}x on port $port")

        // First, quickly check the most common Pi Hotspot IPs (usually .1 or .2)
        val likelyIps = listOf("${subnet}1", "${subnet}2", "${subnet}3", "${subnet}254")
        for (ip in likelyIps) {
            if (ip == localIp) continue
            if (checkIp(ip, port) != null) return@withContext ip
        }

        // Scan remaining IPs in small chunks to avoid thread/socket exhaustion
        val allOtherIps = (4..253).map { "$subnet$it" }.filter { it != localIp }
        val chunked = allOtherIps.chunked(32)

        for (chunk in chunked) {
            val deferreds = chunk.map { ip ->
                async { checkIp(ip, port) }
            }
            for (deferred in deferreds) {
                val result = deferred.await()
                if (result != null) return@withContext result
            }
        }

        Log.w(TAG, "Pi not found on local subnet")
        return@withContext null
    }

    private fun checkIp(ip: String, port: Int): String? {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), SCAN_TIMEOUT_MS)
            socket.close()
            Log.i(TAG, "Found Pi at: $ip")
            return ip
        } catch (e: Throwable) {
            // Catch Throwable to handle OutOfMemoryError or other fatal network errors gracefully
            return null
        }
    }

    /**
     * Helper to get the local IPv4 address of the device's hotspot or Wi-Fi interface.
     */
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                
                // We only care about active network interfaces
                if (!intf.isUp || intf.isLoopback) continue
                
                for (inetAddress in intf.inetAddresses) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address", e)
        }
        return null
    }
}
