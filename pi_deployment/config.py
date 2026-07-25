"""
VisionAid Configuration File
Stores all constants for Pins, I2C Addresses, Model Paths, and Settings.
"""

# -------------------------
# ToF Sensor Configuration
# -------------------------
# XSHUT Pins (BCM numbering)
TOF_PINS = {
    'left': 17,    # Pin 11
    'center': 27,  # Pin 13
    'right': 22,   # Pin 15
    'bottom': 23   # Pin 16
}

# Target I2C Addresses (must be unique)
TOF_ADDRESSES = {
    'left': 0x30,
    'center': 0x31,
    'right': 0x32,
    'bottom': 0x33
}

# -------------------------
# Camera Configuration
# -------------------------
CAMERA_WIDTH = 640
CAMERA_HEIGHT = 480
CAMERA_INDEX = 0
PREFER_PICAM2 = True
SKIP_FRAMES = 2

# -------------------------
# YOLO Model Configuration
# -------------------------
MODEL_PATH = 'necessary_files/best.onnx'
IMG_SIZE = 640
CONF_THRESH = 0.45
IOU_THRESH = 0.45
SAFETY_CONF = 0.35

# 10-Class Mapping
CLASS_NAMES = [
    'person', 'chair', 'table', 'bottle', 'backpack',
    'mobile_phone', 'laptop', 'stairs', 'door', 'currency'
]

# Safety-critical classes
SAFETY_CRITICAL = {'person', 'stairs', 'door'}
