# VisionAid: Smart Wearable for the Visually Impaired

VisionAid is an AI-powered wearable system designed to assist the visually impaired by combining deep learning-based object detection with hardware spatial sensors (Time-of-Flight lasers). It provides real-time, directional audio feedback to help users navigate their environment safely.

## Features
- **Real-Time Object Detection**: Uses a custom-trained YOLOv8 model to detect 10 distinct classes including stairs, doors, currency, chairs, and people.
- **Hardware Sensor Fusion**: Integrates four VL53L0X Time-of-Flight sensors (Left, Center, Right, Bottom) for instant physical obstacle warnings.
- **Directional Audio Feedback**: Built-in Text-to-Speech (TTS) immediately announces approaching hazards (e.g., "Obstacle below", "Chair left").
- **Companion WebSocket API**: Designed to seamlessly connect to an Android companion app via WebSockets for voice commands ("Find my chair").

## Repository Structure

```
├── pi_deployment/           # The final, lightweight code to be run on the Raspberry Pi
│   ├── pi_inference.py      # The main multi-threaded AI & WebSocket engine
│   ├── tof.py               # Hardware logic for managing the 4 I2C ToF Lasers
│   ├── config.py            # Central settings (Pins, I2C addresses, Camera config)
│   └── best.onnx            # The exported YOLOv8 model optimized for OpenCV DNN
├── test_client.html         # A browser-based WebSocket testing tool
└── README.md                # Project documentation
```

## Hardware Requirements
- **Raspberry Pi**: Raspberry Pi 4 (4GB+) recommended.
- **Camera**: Official Raspberry Pi Ribbon Camera (PiCamera2) or USB Webcam.
- **Sensors**: 4x VL53L0X Laser Ranging Sensors.
- **Audio**: Headphones or speakers connected to the 3.5mm jack.

## Getting Started

1. **Wiring the Sensors**: Connect the I2C pins (SDA/SCL) in parallel. Connect the XSHUT pins to GPIO 17, 27, 22, and 23 as defined in `config.py`.
2. **Deploying**: Copy the `pi_deployment` folder to your Raspberry Pi.
3. **Running the System**:
   ```bash
   cd pi_deployment
   python3 pi_inference.py --model best.onnx
   ```
   *(The system will automatically default to using the Pi Ribbon Camera and open the WebSocket Server on port 8765).*

## Testing without the App
You can simulate the Android App connecting to the glasses by opening `test_client.html` in your web browser. Click "Connect" and type commands like "FIND_OBJECT" to see how the camera reacts!
