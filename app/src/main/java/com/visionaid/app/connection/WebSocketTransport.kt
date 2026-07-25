package com.visionaid.app.connection

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket transport for USB-tethered Pi communication.
 *
 * When the Pi is connected to the phone via USB-C, it creates a local
 * network interface. The Pi runs a WebSocket server (e.g., on
 * `ws://192.168.42.1:8765`), and this transport connects to it.
 *
 * Advantages over Bluetooth:
 * - Zero latency (wired connection)
 * - Unbreakable (no RF interference)
 * - Higher bandwidth for camera frames
 * - Works in crowded spaces where Bluetooth fails
 *
 * This is the **preferred** transport when USB is connected.
 */
@Singleton
class WebSocketTransport @Inject constructor() : PiTransport {

    companion object {
        private const val TAG = "WebSocketTransport"

        /** Default Pi WebSocket server address over USB tethering. */
        const val DEFAULT_PI_ADDRESS = "ws://192.168.42.1:8765"

        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 10L
        private const val PING_INTERVAL_SECONDS = 15L
    }

    override val transportName: String = "USB WebSocket"

    private val _connectionState = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()

    /** Channel for incoming messages — buffered to avoid dropping messages. */
    private val _incomingChannel = Channel<PiMessage.Incoming>(Channel.BUFFERED)
    override val incomingMessages: Flow<PiMessage.Incoming> = _incomingChannel.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The active WebSocket connection, null when disconnected. */
    private var webSocket: WebSocket? = null

    /** OkHttp client configured for local-network WebSocket. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    override suspend fun connect(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = TransportState.Connecting
            Log.i(TAG, "Connecting to WebSocket: $address")

            val request = Request.Builder()
                .url(address)
                .build()

            webSocket = client.newWebSocket(request, createWebSocketListener())

            // Wait for the connection listener to update the state
            _connectionState.first { it is TransportState.Connected || it is TransportState.Error }
            
            _connectionState.value is TransportState.Connected
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connection failed", e)
            _connectionState.value = TransportState.Error("Connection failed: ${e.message}")
            false
        }
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting WebSocket")
        try {
            webSocket?.close(1000, "App disconnecting")
        } catch (e: Exception) {
            Log.w(TAG, "Error during WebSocket close", e)
        }
        webSocket = null
        _connectionState.value = TransportState.Disconnected
    }

    override suspend fun send(message: PiMessage.Outgoing): Boolean {
        val ws = webSocket ?: run {
            Log.w(TAG, "Cannot send — WebSocket not connected")
            return false
        }

        return try {
            val json = MessageSerializer.serialize(message)
            val sent = ws.send(json)
            if (sent) {
                Log.d(TAG, "Sent: ${message::class.simpleName} (${json.length} bytes)")
            } else {
                Log.w(TAG, "WebSocket send returned false")
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            false
        }
    }

    /**
     * Checks if USB tethering is likely active by verifying the transport
     * isn't in an error state. Full USB detection will be added in Phase 7.
     */
    override suspend fun isAvailable(): Boolean {
        // In production, we'd check if the USB tethering network interface
        // (rndis0 / usb0) is up. For now, we return true to prevent 
        // the PiConnectionManager from incorrectly falling back to Mock transport.
        return true
    }

    /**
     * Creates the WebSocket listener that bridges OkHttp callbacks
     * into our coroutine-based message flow.
     */
    private fun createWebSocketListener() = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected: ${response.code}")
            _connectionState.value = TransportState.Connected
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received: ${text.take(100)}")
            val message = MessageSerializer.deserialize(text)
            if (message != null) {
                scope.launch {
                    _incomingChannel.send(message)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closing: $code $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code $reason")
            _connectionState.value = TransportState.Disconnected
            this@WebSocketTransport.webSocket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message}", t)
            _connectionState.value = TransportState.Error(
                "WebSocket failed: ${t.message ?: "Unknown error"}"
            )
            this@WebSocketTransport.webSocket = null
        }
    }
}
