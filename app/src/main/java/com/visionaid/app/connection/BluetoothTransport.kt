package com.visionaid.app.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bluetooth RFCOMM transport for wireless Pi communication.
 *
 * Connects to the Pi wearable over a standard Bluetooth serial
 * connection (SPP profile). Messages are sent as newline-delimited
 * JSON strings over the RFCOMM socket.
 *
 * Advantages:
 * - Wireless (no cable needed)
 * - Works when USB is not connected
 *
 * Disadvantages (vs WebSocket):
 * - Higher latency (~10-30ms vs ~1ms)
 * - Can drop in crowded spaces with lots of 2.4GHz interference
 * - Lower bandwidth
 *
 * The [PiConnectionManager] falls back to this transport when USB
 * tethering is not available, and falls back to USB when Bluetooth drops.
 */
@Singleton
class BluetoothTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : PiTransport {

    companion object {
        private const val TAG = "BluetoothTransport"

        /**
         * UUID for the VisionAid SPP service.
         * Must match the UUID used by the Pi's Bluetooth server.
         */
        val VISIONAID_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val RECONNECT_DELAY_MS = 2000L
    }

    override val transportName: String = "Bluetooth"

    private val _connectionState = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val connectionState: StateFlow<TransportState> = _connectionState.asStateFlow()

    private val _incomingChannel = Channel<PiMessage.Incoming>(Channel.BUFFERED)
    override val incomingMessages: Flow<PiMessage.Incoming> = _incomingChannel.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: BluetoothSocket? = null
    private var writer: PrintWriter? = null
    private var readerJob: kotlinx.coroutines.Job? = null

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = TransportState.Connecting
            Log.i(TAG, "Connecting to Bluetooth: $address")

            val adapter = bluetoothAdapter ?: run {
                _connectionState.value = TransportState.Error("Bluetooth not available")
                return@withContext false
            }

            // Cancel discovery to speed up connection
            try {
                adapter.cancelDiscovery()
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot cancel discovery: ${e.message}")
            }

            val device = adapter.getRemoteDevice(address)
            val btSocket = device.createRfcommSocketToServiceRecord(VISIONAID_SPP_UUID)

            btSocket.connect()

            socket = btSocket
            writer = PrintWriter(btSocket.outputStream, true)

            _connectionState.value = TransportState.Connected
            Log.i(TAG, "Bluetooth connected to $address")

            // Start the reader loop
            startReaderLoop(btSocket)

            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission denied", e)
            _connectionState.value = TransportState.Error("Bluetooth permission denied")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth connection failed", e)
            _connectionState.value = TransportState.Error("Connection failed: ${e.message}")
            false
        }
    }

    override suspend fun disconnect() {
        Log.i(TAG, "Disconnecting Bluetooth")
        readerJob?.cancel()
        readerJob = null

        try {
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error during Bluetooth close", e)
        }

        writer = null
        socket = null
        _connectionState.value = TransportState.Disconnected
    }

    override suspend fun send(message: PiMessage.Outgoing): Boolean {
        val w = writer ?: run {
            Log.w(TAG, "Cannot send — Bluetooth not connected")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val json = MessageSerializer.serialize(message)
                w.println(json) // newline-delimited JSON
                if (w.checkError()) {
                    Log.e(TAG, "Bluetooth write error")
                    _connectionState.value = TransportState.Error("Write failed")
                    false
                } else {
                    Log.d(TAG, "Sent: ${message::class.simpleName}")
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send via Bluetooth", e)
                _connectionState.value = TransportState.Error("Send failed: ${e.message}")
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun isAvailable(): Boolean {
        return try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot check Bluetooth state: ${e.message}")
            false
        }
    }

    /**
     * Continuously reads newline-delimited JSON from the Bluetooth socket.
     * Each line is deserialized and emitted to [incomingMessages].
     *
     * Runs in a coroutine that is cancelled on disconnect.
     */
    private fun startReaderLoop(btSocket: BluetoothSocket) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(btSocket.inputStream))
                while (isActive && btSocket.isConnected) {
                    val line = reader.readLine() ?: break
                    Log.d(TAG, "Received: ${line.take(100)}")

                    val message = MessageSerializer.deserialize(line)
                    if (message != null) {
                        _incomingChannel.send(message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth reader error", e)
                if (_connectionState.value is TransportState.Connected) {
                    _connectionState.value = TransportState.Error("Read failed: ${e.message}")
                }
            }

            Log.i(TAG, "Bluetooth reader loop ended")
        }
    }
}
