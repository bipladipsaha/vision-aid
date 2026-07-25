package com.visionaid.app.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport layer abstraction for Pi ↔ App communication.
 *
 * Implementations:
 * - [WebSocketTransport]: USB-tethered WebSocket (zero-latency, unbreakable)
 * - [BluetoothTransport]: Bluetooth RFCOMM socket (wireless, may drop in crowds)
 * - [MockTransport]: Testing without hardware
 *
 * The [PiConnectionManager] orchestrates these transports and handles
 * automatic failover.
 */
interface PiTransport {

    /** Human-readable name for logging and UI display. */
    val transportName: String

    /** Current connection state of this transport. */
    val connectionState: StateFlow<TransportState>

    /**
     * Flow of incoming messages from the Pi.
     * Emits messages as they arrive over this transport.
     */
    val incomingMessages: Flow<PiMessage.Incoming>

    /**
     * Attempt to establish a connection via this transport.
     *
     * @param address The connection target:
     *   - WebSocket: "ws://192.168.42.1:8765" (Pi's USB tethering IP)
     *   - Bluetooth: MAC address "XX:XX:XX:XX:XX:XX"
     *   - Mock: any string (ignored)
     * @return true if connection was established successfully
     */
    suspend fun connect(address: String): Boolean

    /**
     * Gracefully disconnect this transport.
     */
    suspend fun disconnect()

    /**
     * Send a message to the Pi over this transport.
     *
     * @param message The outgoing message to send
     * @return true if the message was sent successfully
     */
    suspend fun send(message: PiMessage.Outgoing): Boolean

    /**
     * Check if this transport is currently available for connection.
     * For example, Bluetooth checks if the adapter is enabled;
     * WebSocket checks if USB tethering is active.
     */
    suspend fun isAvailable(): Boolean
}

/**
 * Connection state for a single transport channel.
 */
sealed class TransportState {
    /** Transport is idle, not connected. */
    data object Disconnected : TransportState()

    /** Transport is actively trying to connect. */
    data object Connecting : TransportState()

    /** Transport is connected and ready. */
    data object Connected : TransportState()

    /** Transport encountered an error. */
    data class Error(val message: String) : TransportState()
}
