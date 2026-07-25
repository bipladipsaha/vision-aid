package com.visionaid.app.data.model

data class Telemetry(
    val cpuTempCelsius: Float,
    val batteryPercent: Int?,
    val cameraConnected: Boolean,
    val tofSensorActive: Boolean,
    val visionAIPaused: Boolean
)
