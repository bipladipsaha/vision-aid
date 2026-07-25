package com.visionaid.app.data.repository

import com.visionaid.app.data.model.PiMessage
import com.visionaid.app.data.websocket.ConnectionState
import com.visionaid.app.data.websocket.WebSocketClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PiRepository @Inject constructor(
    private val webSocketClient: WebSocketClient
) {
    val connectionState: StateFlow<ConnectionState> = webSocketClient.connectionState
    val incomingMessages: Flow<PiMessage.Incoming> = webSocketClient.incomingMessages

    fun connect() {
        webSocketClient.connect()
    }

    fun disconnect() {
        webSocketClient.disconnect()
    }

    fun requestSceneDescription(): Boolean {
        return webSocketClient.sendMessage(PiMessage.Outgoing.DescribeScene)
    }

    fun findObject(objectName: String): Boolean {
        return webSocketClient.sendMessage(PiMessage.Outgoing.FindObject(objectName))
    }

    fun pauseVision(): Boolean {
        return webSocketClient.sendMessage(PiMessage.Outgoing.PauseVisionAI)
    }

    fun resumeVision(): Boolean {
        return webSocketClient.sendMessage(PiMessage.Outgoing.ResumeVisionAI)
    }
}
