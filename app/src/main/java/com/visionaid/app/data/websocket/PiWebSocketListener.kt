package com.visionaid.app.data.websocket

import android.util.Log
import com.visionaid.app.data.model.PiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class PiWebSocketListener(
    private val scope: CoroutineScope,
    private val connectionStateFlow: MutableStateFlow<ConnectionState>,
    private val onMessageReceived: (PiMessage.Incoming) -> Unit,
    private val onSocketClosed: () -> Unit
) : WebSocketListener() {

    private val TAG = "PiWebSocketListener"

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.i(TAG, "Connected to Pi WebSocket")
        connectionStateFlow.value = ConnectionState.Connected
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val parsed = MessageParser.deserialize(text)
        if (parsed != null) {
            scope.launch {
                onMessageReceived(parsed)
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "WebSocket closing: $reason")
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.i(TAG, "WebSocket closed")
        connectionStateFlow.value = ConnectionState.Disconnected
        onSocketClosed()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "WebSocket failure: ${t.message}", t)
        connectionStateFlow.value = ConnectionState.Disconnected
        onSocketClosed()
    }
}
