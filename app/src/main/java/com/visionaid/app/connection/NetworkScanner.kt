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

        val deferreds = (1..254).map { i ->
            async {
                val ip = "$subnet$i"
                if (ip == localIp) return@async null // Don't scan ourselves

                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, port), SCAN_TIMEOUT_MS)
                    socket.close()
                    Log.i(TAG, "Found Pi at: $ip")
                    return@async ip
                } catch (e: Exception) {
                    // Timeout or connection refused
                    return@async null
                }
            }
        }

        // Return the first successful IP (if any)
        for (deferred in deferreds) {
            val result = deferred.await()
            if (result != null) return@withContext result
        }

        Log.w(TAG, "Pi not found on local subnet")
        return@withContext null
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
