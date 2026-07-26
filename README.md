# VisionAid – AI-Powered Wearable Assistance System for the Visually Impaired

[![Download APK](https://img.shields.io/badge/Download-APK-green.svg?style=for-the-badge&logo=android)](https://github.com/bipladipsaha/vision-aid/releases/latest)

## 1. Project Overview

**VisionAid** is an offline-first wearable assistive system designed to significantly improve the independence and mobility of visually impaired users. The system synergizes computer vision, depth sensing, voice interaction, and a mobile application to provide real-time environmental awareness.

Unlike traditional smart glasses that continuously analyze video (which drains battery and processing power), VisionAid follows an efficient **hybrid approach**:

* **Continuous obstacle awareness** using low-power Time-of-Flight (ToF) sensors.
* **On-demand AI vision** using a Raspberry Pi camera triggered only when the user requests visual information.

This approach reduces power consumption, improves response time, and allows the system to run efficiently on embedded hardware.

---

## 2. Objectives

* 🚶 **Safe Navigation**: Assist visually impaired users in safely navigating indoor and outdoor environments.
* 🛑 **Collision Prevention**: Detect nearby obstacles before collisions occur.
* 🔍 **Object Detection**: Identify and locate everyday objects on demand.
* 🎙️ **Scene Description**: Describe surrounding environments using AI.
* 🗣️ **Conversational Interface**: Provide natural, hands-free voice-based interaction.
* 📶 **Offline Reliability**: Operate locally without requiring constant internet connectivity.
* 🧩 **Extensibility**: Maintain a modular architecture that supports future feature expansion.

---

## 3. System Architecture

```text
                    USER
                      │
          Bluetooth Earbuds (Mic + Speaker)
                      │
               Android Application
            (Voice Assistant & Interface)
                      │
          Wi-Fi (Hotspot / Local Network)
                 WebSocket Protocol
                      │
                Raspberry Pi 4
                      │
      ┌───────────────┼───────────────┐
      │               │               │
 Pi Camera       ToF Sensors      AI Engine
      │               │               │
      └───────────────┼───────────────┘
                      │
              Assistance Engine
                      │
              Voice Response
```

---

## 4. Core Features

### A. Obstacle Awareness
Continuously monitors the environment using ToF sensors.
* Obstacle detection
* Distance estimation
* Direction detection (Left, Center, Right, Bottom)
* Immediate collision alerts
* Adaptive warning levels (Haptic and Audio)

### B. AI Scene Description
Activated by voice commands. (e.g., *"What's in front of me?"*)
The camera captures an image and the AI describes objects, layout, and nearby hazards.

### C. Object Finder
Allows users to locate specific objects. (e.g., *"Find my bottle."*)
The system searches the environment, detects the requested object, and guides the user toward it via directional audio cues.

### D. Voice Assistant
Natural voice interaction via the Companion App (e.g., *"What's ahead?"*, *"Find my keys"*, *"Battery status"*).

### E. Android Companion App
Provides device connection, voice interface, telemetry, settings, and accessibility controls.

---

## 5. Technology Stack

### Hardware Components
* **Compute:** Raspberry Pi 4 Model B
* **Vision:** Raspberry Pi Camera Module
* **Depth:** 4x VL53L0X Time-of-Flight Sensors
* **Audio:** Bluetooth Earbuds

### Software (Raspberry Pi)
* Python, OpenCV, Picamera2, NumPy, Asyncio, WebSockets

### Software (Android)
* Kotlin, Jetpack Compose, MVVM Architecture, WebSockets, Android SpeechRecognizer, Native TextToSpeech, **ElevenLabs TTS API**

### AI Pipeline
* Ultralytics YOLOv8, PyTorch, ONNX, Custom Roboflow Dataset (10 Classes)

---

## 6. Communication Architecture

Communication between the Android application and Raspberry Pi uses a **Wi-Fi Hotspot** and **WebSockets (JSON)** for low latency, real-time updates, and completely offline operation.

### Data Flow Example
`Voice Command` ➔ `Speech Recognition (App)` ➔ `WebSocket` ➔ `Raspberry Pi (AI)` ➔ `Result` ➔ `WebSocket` ➔ `Text-to-Speech (App)` ➔ `User`

---

## 7. Setup & Deployment (Raspberry Pi)

1. **Wiring the Sensors**: Connect the ToF sensors to the I2C pins. Connect the XSHUT pins to GPIO 17 (Left), 27 (Center), 22 (Right), and 23 (Bottom).
2. **Deploying**: Copy the `pi_deployment` folder to the Raspberry Pi.
3. **Running the Pipeline**:
   ```bash
   cd pi_deployment
   python3 pi_inference.py --model best.onnx
   ```
   *(The WebSocket server automatically starts on port 8765).*
