package com.visionaid.app.connection

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates Pi ↔ App communication across multiple transports
 * with automatic failover.
 *
 * ## Transport Priority (highest to lowest):
 * 1. **USB WebSocket** — Preferred. Zero-latency wired connection.
 * 2. **Bluetooth RFCOMM** — Wireless fallback.
 * 3. **Mock** — Development/testing only.
 *
 * ## Failover Strategy:
 * When the active transport disconnects:
 * 1. Fire haptic error feedback
 * 2. Try the next transport in priority order
 * 3. If all transports fail, wait and retry from the top
 * 4. Exponential backoff: 2s → 4s → 8s → max 30s
 *
 * ## Message Routing:
 * - Outgoing messages are sent via the active transport
 * - Incoming messages from ANY transport are merged into a single Flow
 * - The UI and service observe [incomingMessages] regardless of which
 *   transport is active
 */
@Singleton
class PiConnectionManager @Inject constructor(
    private val webSocketTransport: WebSocketTransport,
    private val bluetoothTransport: BluetoothTransport,
    private val mockTransport: MockTransport
) {
    companion object {
        private const val TAG = "PiConnectionManager"

        private const val INITIAL_RETRY_DELAY_MS = 2000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val HEALTH_CHECK_INTERVAL_MS = 10000L
    }

    // ── State ────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionManagerState())
    val connectionState: StateFlow<ConnectionManagerState> = _connectionState.asStateFlow()

    /** Merged incoming messages from all transports. */
    private val _incomingMessages = MutableSharedFlow<PiMessage.Incoming>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingMessages: Flow<PiMessage.Incoming> = _incomingMessages.asSharedFlow()

    /** The currently active transport, null if disconnected. */
    private var activeTransport: PiTransport? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var healthCheckJob: Job? = null
    private var messageCollectorJobs = mutableListOf<Job>()

    /** Whether to use mock transport (set true when hardware isn't ready). */
    private var useMockTransport = false

    /** Addresses for each transport. */
    private var webSocketAddress = WebSocketTransport.DEFAULT_PI_ADDRESS
    private var bluetoothAddress: String? = null

    // ── Public API ───────────────────────────────────────────────

    /**
     * Start the connection manager.
     *
     * @param btAddress Bluetooth MAC address of the Pi (null to skip BT)
     * @param wsAddress WebSocket URL of the Pi (default: USB tethering IP)
     * @param useMock If true, connect to mock transport (no hardware needed)
     */
    fun start(
        btAddress: String? = null,
        wsAddress: String = WebSocketTransport.DEFAULT_PI_ADDRESS,
        useMock: Boolean = false
    ) {
        this.bluetoothAddress = btAddress
        this.webSocketAddress = wsAddress
        this.useMockTransport = useMock

        Log.i(TAG, "Starting connection manager (mock=$useMock, bt=$btAddress, ws=$wsAddress)")

        // Start collecting messages from all transports
        startMessageCollectors()

        // Begin connection attempt
        connectionJob?.cancel()
        connectionJob = scope.launch {
            connectWithFailover()
        }
    }

    /**
     * Stop the connection manager and disconnect all transports.
     */
    fun stop() {
        Log.i(TAG, "Stopping connection manager")
        connectionJob?.cancel()
        healthCheckJob?.cancel()
        messageCollectorJobs.forEach { it.cancel() }
        messageCollectorJobs.clear()

        scope.launch {
            activeTransport?.disconnect()
            activeTransport = null
            _connectionState.value = ConnectionManagerState()
        }
    }

    /**
     * Send a message to the Pi via the active transport.
     *
     * @return true if the message was sent successfully
     */
    suspend fun send(message: PiMessage.Outgoing): Boolean {
        val transport = activeTransport ?: run {
            Log.w(TAG, "Cannot send — no active transport")
            return false
        }

        val sent = transport.send(message)
        if (!sent) {
            Log.w(TAG, "Send failed on ${transport.transportName}, triggering failover")
            connectionJob?.cancel()
            connectionJob = scope.launch { connectWithFailover() }
        }
        return sent
    }

    /**
     * Force a specific transport type for testing.
     */
    fun forceTransport(type: TransportType) {
        scope.launch {
            activeTransport?.disconnect()
            val transport = when (type) {
                TransportType.USB_WEBSOCKET -> webSocketTransport
                TransportType.BLUETOOTH -> bluetoothTransport
                TransportType.MOCK -> mockTransport
            }
            connectSingleTransport(transport, getAddressForTransport(transport))
        }
    }

    // ── Failover Logic ───────────────────────────────────────────

    /**
     * Attempts to connect using the transport priority chain.
     * If all fail, retries with exponential backoff.
     */
    private suspend fun connectWithFailover() {
        var retryDelay = INITIAL_RETRY_DELAY_MS

        while (scope.isActive) {
            val transportsToTry = buildTransportList()

            for ((transport, address) in transportsToTry) {
                if (!scope.isActive) return

                Log.i(TAG, "Trying ${transport.transportName}...")
                _connectionState.value = _connectionState.value.copy(
                    status = ConnectionStatus.CONNECTING,
                    activeTransportType = getTransportType(transport),
                    lastError = null
                )

                val connected = connectSingleTransport(transport, address)
                if (connected) {
                    // Success! Reset retry delay and start health checks
                    retryDelay = INITIAL_RETRY_DELAY_MS
                    startHealthCheck(transport)

                    // Wait here until disconnection
                    waitForDisconnection(transport)

                    Log.w(TAG, "${transport.transportName} disconnected, failing over...")
                    healthCheckJob?.cancel()

                    // Small delay before trying next transport
                    delay(500)
                    break
                }
            }

            // All transports failed — retry with backoff
            if (activeTransport == null && scope.isActive) {
                Log.w(TAG, "All transports failed. Retrying in ${retryDelay}ms...")
                _connectionState.value = _connectionState.value.copy(
                    status = ConnectionStatus.RECONNECTING,
                    lastError = "All transports failed, retrying..."
                )
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * Builds the ordered list of transports to attempt based on
     * availability and configuration.
     */
    private suspend fun buildTransportList(): List<Pair<PiTransport, String>> {
        val transports = mutableListOf<Pair<PiTransport, String>>()

        if (useMockTransport) {
            transports.add(mockTransport to "VisionAid-Pi-Mock")
            return transports
        }

        // Priority 1: USB WebSocket (if available)
        if (webSocketTransport.isAvailable()) {
            transports.add(webSocketTransport to webSocketAddress)
        }

        // Priority 2: Bluetooth (if address is configured)
        val btAddr = bluetoothAddress
        if (btAddr != null && bluetoothTransport.isAvailable()) {
            transports.add(bluetoothTransport to btAddr)
        }

        // Fallback: Mock (always available as last resort during dev)
        if (transports.isEmpty()) {
            Log.w(TAG, "No real transports available, falling back to mock")
            transports.add(mockTransport to "VisionAid-Pi-Mock")
        }

        return transports
    }

    /**
     * Attempts to connect a single transport.
     */
    private suspend fun connectSingleTransport(
        transport: PiTransport,
        address: String
    ): Boolean {
        return try {
            val success = transport.connect(address)
            if (success) {
                activeTransport = transport
                _connectionState.value = _connectionState.value.copy(
                    status = ConnectionStatus.CONNECTED,
                    activeTransportType = getTransportType(transport),
                    transportName = transport.transportName,
                    lastError = null
                )
                Log.i(TAG, "Connected via ${transport.transportName}")
            }
            success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "${transport.transportName} connection failed", e)
            _connectionState.value = _connectionState.value.copy(
                lastError = "${transport.transportName}: ${e.message}"
            )
            false
        }
    }

    /**
     * Suspends until the given transport disconnects or errors out.
     */
    private suspend fun waitForDisconnection(transport: PiTransport) {
        transport.connectionState.collect { state ->
            when (state) {
                is TransportState.Disconnected,
                is TransportState.Error -> {
                    activeTransport = null
                    _connectionState.value = _connectionState.value.copy(
                        status = ConnectionStatus.DISCONNECTED,
                        lastError = (state as? TransportState.Error)?.message
                    )
                    return@collect
                }
                else -> { /* still connected, keep waiting */ }
            }
        }
    }

    /**
     * Starts collecting incoming messages from all transports and
     * merging them into [incomingMessages].
     */
    private fun startMessageCollectors() {
        messageCollectorJobs.forEach { it.cancel() }
        messageCollectorJobs.clear()

        val transports = listOf(webSocketTransport, bluetoothTransport, mockTransport)
        for (transport in transports) {
            val job = scope.launch {
                transport.incomingMessages.collect { message ->
                    _incomingMessages.emit(message)
                }
            }
            messageCollectorJobs.add(job)
        }
    }

    /**
     * Periodic health check — sends a Ping and verifies we get a Pong.
     * If health check fails, triggers failover.
     */
    private fun startHealthCheck(transport: PiTransport) {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (transport.connectionState.value is TransportState.Connected) {
                    val ping = PiMessage.Outgoing.Ping()
                    val sent = transport.send(ping)
                    if (!sent) {
                        Log.w(TAG, "Health check failed on ${transport.transportName}")
                        transport.disconnect()
                        break
                    }
                }
            }
        }
    }

    private fun getTransportType(transport: PiTransport): TransportType {
        return when (transport) {
            is WebSocketTransport -> TransportType.USB_WEBSOCKET
            is BluetoothTransport -> TransportType.BLUETOOTH
            is MockTransport -> TransportType.MOCK
            else -> TransportType.MOCK
        }
    }

    private fun getAddressForTransport(transport: PiTransport): String {
        return when (transport) {
            is WebSocketTransport -> webSocketAddress
            is BluetoothTransport -> bluetoothAddress ?: ""
            is MockTransport -> "VisionAid-Pi-Mock"
            else -> ""
        }
    }
}

/**
 * Overall connection manager state, observed by the UI layer.
 */
data class ConnectionManagerState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val activeTransportType: TransportType? = null,
    val transportName: String = "",
    val lastError: String? = null
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

enum class TransportType {
    USB_WEBSOCKET,
    BLUETOOTH,
    MOCK
}
