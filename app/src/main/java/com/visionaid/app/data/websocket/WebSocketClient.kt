package com.visionaid.app.data.websocket

import android.util.Log
import com.visionaid.app.data.model.PiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebSocketClient @Inject constructor() {
    private val TAG = "WebSocketClient"

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = Channel<PiMessage.Incoming>(Channel.BUFFERED)
    val incomingMessages: Flow<PiMessage.Incoming> = _incomingMessages.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect() {
        if (_connectionState.value == ConnectionState.Connected || _connectionState.value == ConnectionState.Connecting) {
            return
        }

        reconnectJob?.cancel()
        _connectionState.value = ConnectionState.Connecting
        
        scope.launch {
            Log.i(TAG, "Starting subnet scan to find Pi...")
            val piIp = findPiAddress()
            
            if (piIp == null) {
                Log.e(TAG, "Could not find Pi on the local network. Retrying...")
                scheduleReconnect()
                return@launch
            }
            
            val targetUrl = "ws://$piIp:8765"
            Log.i(TAG, "Connecting to $targetUrl")
            val request = Request.Builder().url(targetUrl).build()
            
            val listener = PiWebSocketListener(
                scope = scope,
                connectionStateFlow = _connectionState,
                onMessageReceived = { msg ->
                    scope.launch { _incomingMessages.send(msg) }
                },
                onSocketClosed = {
                    webSocket = null
                    stopHeartbeat()
                    scheduleReconnect()
                }
            )
            
            webSocket = client.newWebSocket(request, listener)
            startHeartbeat()
        }
    }

    private suspend fun findPiAddress(): String? = kotlinx.coroutines.coroutineScope {
        val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()
        val localIps = mutableListOf<String>()
        
        while (networkInterfaces.hasMoreElements()) {
            val networkInterface = networkInterfaces.nextElement()
            val inetAddresses = networkInterface.inetAddresses
            while (inetAddresses.hasMoreElements()) {
                val inetAddress = inetAddresses.nextElement()
                if (!inetAddress.isLoopbackAddress && inetAddress.hostAddress?.contains(":") == false) {
                    val ip = inetAddress.hostAddress
                    if (ip?.startsWith("192.168.") == true || ip?.startsWith("10.") == true || ip?.startsWith("172.") == true) {
                        localIps.add(ip)
                    }
                }
            }
        }

        if (localIps.isEmpty()) {
            return@coroutineScope null
        }

        val myIp = localIps.first()
        val subnetPrefix = myIp.substringBeforeLast(".")
        
        val deferreds = (1..254).map { i ->
            async<String?>(Dispatchers.IO) {
                val targetIp = "$subnetPrefix.$i"
                try {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(targetIp, 8765), 500)
                    socket.close()
                    targetIp
                } catch (e: Exception) {
                    null
                }
            }
        }
        
        deferreds.awaitAll().firstOrNull { it != null }
    }

    fun disconnect() {
        Log.i(TAG, "Disconnecting manually")
        reconnectJob?.cancel()
        stopHeartbeat()
        webSocket?.close(1000, "User initiated disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun sendMessage(message: PiMessage.Outgoing): Boolean {
        val ws = webSocket ?: return false
        val json = MessageParser.serialize(message)
        return ws.send(json)
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(5000) // 5 second heartbeat as requested
                if (_connectionState.value == ConnectionState.Connected) {
                    sendMessage(PiMessage.Outgoing.Ping)
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun scheduleReconnect() {
        if (_connectionState.value == ConnectionState.Retrying) return
        _connectionState.value = ConnectionState.Retrying
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Reconnecting in 3 seconds...")
            delay(3000) // Exponential backoff can be added here
            connect()
        }
    }
}
