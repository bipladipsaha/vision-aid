#!/usr/bin/env python3
"""
VisionAid Phase 3: Raspberry Pi 4B Inference Script
====================================================
Runs the trained YOLOv8n/YOLO11n ONNX model on a Raspberry Pi 4B using
OpenCV's DNN module. Fully offline, no internet required.

Requirements (install on Pi):
    sudo apt update && sudo apt install -y python3-opencv
    pip3 install numpy

Usage:
    python3 pi_inference.py --model best.onnx
    python3 pi_inference.py --model best.onnx --conf 0.4 --skip-frames 3
    python3 pi_inference.py --model best.onnx --headless  # No display window
"""

import cv2
import numpy as np
import time
import argparse
import sys
import os
import pyttsx3
import threading
import queue
import asyncio
import websockets
import json
import tof

# ══════════════════════════════════════════════════════════
# SYSTEM STATE (Controlled by WebSocket)
# ══════════════════════════════════════════════════════════
class SystemState:
    def __init__(self):
        self.mode = "IDLE"  # Options: IDLE, FIND_OBJECT, DESCRIBE, IDENTIFY_OBSTACLE
        self.target_object = None
        self.clients = set() # Connected websocket clients
        self.lock = threading.Lock()
        self.loop = None
        self.tof_manager = None
        
    def set_mode(self, mode, target=None):
        with self.lock:
            self.mode = mode
            self.target_object = target
            print(f"[STATE] Mode changed to {mode}, Target: {target}")

state = SystemState()

def broadcast_ws_message(message_dict):
    """Sends a JSON message to all connected WebSocket clients from the main thread."""
    if not state.clients or not state.loop:
        return
    msg_str = json.dumps(message_dict)
    
    async def _send():
        with state.lock:
            clients = list(state.clients)
        for ws in clients:
            try:
                await ws.send(msg_str)
            except websockets.exceptions.ConnectionClosed:
                pass
                
    asyncio.run_coroutine_threadsafe(_send(), state.loop)

async def ws_handler(websocket):
    print(f"[WS] Client connected from {websocket.remote_address}")
    with state.lock:
        state.clients.add(websocket)
    try:
        async for message in websocket:
            print(f"[WS] Received: {message}")
            try:
                data = json.loads(message)
                cmd_type = data.get("type", data.get("command")) # fallback for old test client
                
                if cmd_type == "find_object" or cmd_type == "FIND_OBJECT":
                    target = data.get("payload", {}).get("object_name", data.get("target"))
                    if target and target in CLASS_NAMES:
                        state.set_mode("FIND_OBJECT", target)
                        await websocket.send(json.dumps({"type": "ack", "message": f"Searching for {target}"}))
                    else:
                        await websocket.send(json.dumps({"type": "error", "message": "Unknown object"}))
                
                elif cmd_type == "describe_scene" or cmd_type == "DESCRIBE_FRONT":
                    state.set_mode("DESCRIBE")
                    await websocket.send(json.dumps({"type": "ack", "message": "Describing scene"}))
                    
                elif cmd_type == "ping":
                    ping_id = data.get("payload", {}).get("ping_id", "")
                    await websocket.send(json.dumps({"type": "pong", "payload": {"ping_id": ping_id}}))
                    
                elif cmd_type == "STOP" or cmd_type == "stop":
                    state.set_mode("IDLE")
                    await websocket.send(json.dumps({"type": "ack", "message": "Stopped search"}))
                    
                elif cmd_type == "SIMULATE_OBSTACLE":
                    if state.tof_manager and hasattr(state.tof_manager, 'trigger_fake_warning'):
                        state.tof_manager.trigger_fake_warning('center', 400)
                        await websocket.send(json.dumps({"type": "ack", "message": "Simulated ToF obstacle!"}))
                        
                elif cmd_type == "request_telemetry":
                    await websocket.send(json.dumps({
                        "type": "telemetry",
                        "payload": {
                            "cpu_temp": get_cpu_temp(),
                            "camera_connected": True,
                            "tof_active": state.tof_manager is not None,
                            "vision_paused": state.mode == "PAUSED"
                        }
                    }))
                    
                elif cmd_type == "pause_vision":
                    state.set_mode("PAUSED")
                    await websocket.send(json.dumps({"type": "ack", "message": "Vision AI paused"}))
                    
                elif cmd_type == "resume_vision":
                    state.set_mode("IDLE")
                    await websocket.send(json.dumps({"type": "ack", "message": "Vision AI resumed"}))
                    
            except json.JSONDecodeError:
                print("[WS] Invalid JSON received")
    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        with state.lock:
            state.clients.remove(websocket)
        print("[WS] Client disconnected")

def get_cpu_temp():
    try:
        with open('/sys/class/thermal/thermal_zone0/temp', 'r') as f:
            return float(f.read()) / 1000.0
    except Exception:
        return 0.0

def start_ws_server(port=8765):
    async def main_ws():
        state.loop = asyncio.get_running_loop()
        print(f"[WS] Server listening on ws://0.0.0.0:{port}")
        async with websockets.serve(ws_handler, "0.0.0.0", port):
            await asyncio.Future()  # run forever
            
    asyncio.run(main_ws())

def start_tof_thread(tof_manager, tts_manager):
    """Continuously polls ToF sensors at 10Hz and triggers warnings."""
    while True:
        distances = tof_manager.get_distances()
        
        warning_triggered = False
        warning_sensor = None
        warning_dist = 9999
        
        # Center & Bottom thresholds
        for pos in ['center', 'bottom']:
            d = distances.get(pos, 9999)
            if d != -1 and d < 800:
                warning_triggered = True
                warning_sensor = pos
                warning_dist = d
                break
                
        # Left & Right thresholds
        if not warning_triggered:
            for pos in ['left', 'right']:
                d = distances.get(pos, 9999)
                if d != -1 and d < 600:
                    warning_triggered = True
                    warning_sensor = pos
                    warning_dist = d
                    break
                    
        with state.lock:
            current_mode = state.mode
            
        if warning_triggered:
            # Calculate proximity (0.0 to 1.0) for the app
            proximity = max(0.0, min(1.0, 1.0 - (warning_dist / 1500.0)))
            
            # 1. Send WebSocket Alert matching Android App schema
            broadcast_ws_message({
                "type": "obstacle_warning",
                "payload": {
                    "proximity": round(proximity, 2),
                    "direction": warning_sensor,
                    "distance_cm": warning_dist // 10
                }
            })
            
            # 2. Audio Directional Warning
            if current_mode == "IDLE":
                if warning_sensor == "bottom":
                    tts_manager.announce("Obstacle below")
                elif warning_sensor == "center":
                    tts_manager.announce("Obstacle ahead")
                else:
                    tts_manager.announce(f"Obstacle {warning_sensor}")
                    
                # Force AI to identify
                state.set_mode("IDENTIFY_OBSTACLE")
        else:
            if current_mode == "IDENTIFY_OBSTACLE":
                state.set_mode("IDLE")
                
        time.sleep(0.1) # 10Hz

# ══════════════════════════════════════════════════════════
# 10-CLASS MAPPING (must match training data.yaml exactly)
# ══════════════════════════════════════════════════════════
CLASS_NAMES = [
    'person', 'chair', 'table', 'bottle', 'backpack',
    'mobile_phone', 'laptop', 'stairs', 'door', 'currency'
]
NUM_CLASSES = len(CLASS_NAMES)

# Safety-critical classes (highlighted in red, announced with priority)
SAFETY_CRITICAL = {'person', 'stairs', 'door'}

# Class colors (BGR format for OpenCV)
CLASS_COLORS = {
    'person':       (0, 0, 255),       # Red (safety)
    'chair':        (255, 165, 0),     # Orange
    'table':        (255, 200, 50),    # Gold
    'bottle':       (50, 205, 50),     # Lime green
    'backpack':     (147, 112, 219),   # Purple
    'mobile_phone': (0, 191, 255),     # Deep sky blue
    'laptop':       (255, 105, 180),   # Pink
    'stairs':       (0, 0, 255),       # Red (safety)
    'door':         (0, 0, 255),       # Red (safety)
    'currency':     (0, 255, 255),     # Yellow
}



class AudioDebouncer:
    """Prevents the TTS system from announcing the same object multiple times a second."""
    def __init__(self, cooldown=3.0):
        self.last_announced = {}
        self.cooldown = cooldown
        
    def should_announce(self, text):
        now = time.time()
        # If we haven't announced this exact text, or the cooldown has passed
        if text not in self.last_announced or (now - self.last_announced[text] > self.cooldown):
            self.last_announced[text] = now
            return True
        return False


class TTSManager:
    """Manages audio feedback in a separate thread so it doesn't freeze the camera feed."""
    def __init__(self):
        # Bounded queue (max 3) ensures we drop old messages if speech falls behind
        self.queue = queue.Queue(maxsize=3)
        self.thread = threading.Thread(target=self._worker, daemon=True)
        self.thread.start()
        
    def _worker(self):
        is_windows = (os.name == 'nt')
        engine = None
        speaker = None
        
        use_subprocess_espeak = False
        try:
            if is_windows:
                import pythoncom
                import win32com.client
                pythoncom.CoInitialize()
                speaker = win32com.client.Dispatch("SAPI.SpVoice")
                speaker.Rate = -2
            else:
                import pyttsx3
                try:
                    # On Linux, explicitly request espeak
                    engine = pyttsx3.init('espeak')
                    try:
                        engine.setProperty('rate', 150)
                    except Exception:
                        pass # Ignore rate setting errors
                except Exception as e:
                    if "gmw/en" not in str(e):
                        print(f"[TTS Info] pyttsx3 init failed ({e}), using subprocess fallback.")
                    use_subprocess_espeak = True
        except Exception as e:
            print(f"[TTS Info] Audio engine init fallback activated: {e}")
            use_subprocess_espeak = True
            
        def run_espeak_fallback(text):
            import subprocess
            try:
                subprocess.run(["espeak-ng", text], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except FileNotFoundError:
                try:
                    subprocess.run(["espeak", text], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                except Exception:
                    pass

        while True:
            text = self.queue.get()
            if text is None:
                break
            try:
                if is_windows and speaker:
                    speaker.Speak(text)
                elif engine and not use_subprocess_espeak:
                    engine.say(text)
                    engine.runAndWait()
                else:
                    run_espeak_fallback(text)
            except Exception as e:
                run_espeak_fallback(text)
            finally:
                self.queue.task_done()
                
    def announce(self, text):
        # If the queue is full (too much talking), remove the oldest message 
        # so we always announce the most recent real-time detection.
        if self.queue.full():
            try:
                self.queue.get_nowait()
                self.queue.task_done()
            except queue.Empty:
                pass
                
        # We can eventually replace this with a WebSocket send for the Kotlin App
        self.queue.put(text)


def parse_args():
    parser = argparse.ArgumentParser(description='VisionAid Pi Inference')
    parser.add_argument('--model', type=str, default='best.onnx',
                        help='Path to ONNX model file')
    parser.add_argument('--conf', type=float, default=0.45,
                        help='Confidence threshold (0.0-1.0)')
    parser.add_argument('--iou', type=float, default=0.45,
                        help='NMS IoU threshold')
    parser.add_argument('--imgsz', type=int, default=640,
                        help='Input image size (must match training)')
    parser.add_argument('--skip-frames', type=int, default=2,
                        help='Process 1 in every N frames (higher = faster display)')
    parser.add_argument('--camera', type=int, default=0,
                        help='Camera index')
    parser.add_argument('--width', type=int, default=640,
                        help='Camera capture width')
    parser.add_argument('--height', type=int, default=480,
                        help='Camera capture height')
    parser.add_argument('--headless', action='store_true',
                        help='Run without display (print detections to console)')
    parser.add_argument('--safety-conf', type=float, default=0.35,
                        help='Lower confidence threshold for safety-critical classes')
    parser.add_argument('--use-picam2', action='store_true',
                        help='Explicitly use Picamera2 for Raspberry Pi Camera Module')
    parser.add_argument('--image', type=str, default=None,
                        help='Path to a static image file (for testing without a webcam)')
    return parser.parse_args()


class CameraStream:
    """Universal camera reader supporting both OpenCV VideoCapture and Picamera2 (Raspberry Pi OS)."""
    def __init__(self, camera_idx=0, width=640, height=480, prefer_picam2=False):
        self.camera_type = None
        self.cap = None
        self.picam2 = None
        
        if prefer_picam2:
            try:
                from picamera2 import Picamera2
                print("[INFO] Starting Pi Camera (Picamera2)...")
                self.picam2 = Picamera2()
                config = self.picam2.create_preview_configuration(
                    main={"size": (width, height), "format": "RGB888"}
                )
                self.picam2.configure(config)
                self.picam2.start()
                time.sleep(2)
                self.camera_type = "picam2"
                self.width, self.height = width, height
                print(f"[INFO] Picamera2 opened successfully: {width}x{height}")
                return
            except Exception as e:
                print(f"[WARN] Failed to open Picamera2: {e}. Falling back to OpenCV VideoCapture...")
                
        # Try OpenCV VideoCapture
        print(f"[INFO] Opening OpenCV camera index {camera_idx}...")
        self.cap = cv2.VideoCapture(camera_idx)
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
        self.cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)  # Minimize latency
        
        if self.cap.isOpened():
            self.camera_type = "opencv"
            self.width = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            self.height = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            print(f"[INFO] OpenCV Camera opened: {self.width}x{self.height}")
            return
            
        # Fallback to Picamera2 if OpenCV fails (e.g., Pi Camera without V4L2 driver)
        try:
            from picamera2 import Picamera2
            print("[INFO] OpenCV VideoCapture failed. Attempting Picamera2...")
            self.picam2 = Picamera2()
            config = self.picam2.create_preview_configuration(
                main={"size": (width, height), "format": "RGB888"}
            )
            self.picam2.configure(config)
            self.picam2.start()
            time.sleep(2)
            self.camera_type = "picam2"
            self.width, self.height = width, height
            print(f"[INFO] Picamera2 opened successfully: {width}x{height}")
            return
        except Exception as pe:
            print(f"[ERROR] Cannot open camera using OpenCV or Picamera2: {pe}")
            sys.exit(1)

    def read(self):
        if self.camera_type == "picam2":
            frame = self.picam2.capture_array()
            if frame is None:
                return False, None
            # Convert Picamera2 RGB888 to BGR format for OpenCV compatibility
            frame_bgr = cv2.cvtColor(frame, cv2.COLOR_RGB2BGR)
            return True, frame_bgr
        else:
            return self.cap.read()

    def release(self):
        if self.camera_type == "picam2" and self.picam2 is not None:
            try:
                self.picam2.stop()
                print("[INFO] Picamera2 stopped.")
            except Exception as e:
                print(f"[WARN] Error stopping Picamera2: {e}")
        elif self.cap is not None:
            self.cap.release()
            print("[INFO] OpenCV camera released.")


def letterbox(frame, new_shape=(640, 640)):
    """Resize and pad image to square while maintaining aspect ratio."""
    h, w = frame.shape[:2]
    ratio = min(new_shape[0] / h, new_shape[1] / w)
    new_unpad = int(round(w * ratio)), int(round(h * ratio))
    
    dw = (new_shape[1] - new_unpad[0]) / 2
    dh = (new_shape[0] - new_unpad[1]) / 2
    
    if (w, h) != new_unpad:
        frame = cv2.resize(frame, new_unpad, interpolation=cv2.INTER_LINEAR)
    
    top, bottom = int(round(dh - 0.1)), int(round(dh + 0.1))
    left, right = int(round(dw - 0.1)), int(round(dw + 0.1))
    frame = cv2.copyMakeBorder(frame, top, bottom, left, right,
                                cv2.BORDER_CONSTANT, value=(114, 114, 114))
    
    return frame, ratio, (dw, dh)


def process_detections(outputs, frame_shape, imgsz, ratio, pad, conf_thresh,
                       iou_thresh, safety_conf):
    """Parse YOLOv8/YOLO11 ONNX output and apply NMS."""
    out = np.squeeze(outputs[0])
    
    if len(out.shape) != 2:
        print(f"WARNING: Unexpected output shape {outputs[0].shape}")
        return []
        
    if out.shape[0] >= 4 and out.shape[1] > out.shape[0]:
        predictions = out.T
    elif out.shape[1] >= 4 and out.shape[0] > out.shape[1]:
        predictions = out
    else:
        print(f"WARNING: Expected at least 4 features, got shape {out.shape}")
        return []
    
    frame_h, frame_w = frame_shape[:2]
    
    boxes = []
    confidences = []
    class_ids = []
    
    bboxes = predictions[:, :4]
    class_scores = predictions[:, 4:]
    
    max_scores = np.max(class_scores, axis=1)
    max_class_ids = np.argmax(class_scores, axis=1)
    
    min_thresh = min(conf_thresh, safety_conf)
    mask = max_scores >= min_thresh
    
    filtered_bboxes = bboxes[mask]
    filtered_scores = max_scores[mask]
    filtered_class_ids = max_class_ids[mask]
    
    for i in range(len(filtered_bboxes)):
        class_id = filtered_class_ids[i]
        confidence = filtered_scores[i]
        
        class_name = CLASS_NAMES[class_id] if class_id < NUM_CLASSES else ''
        threshold = safety_conf if class_name in SAFETY_CRITICAL else conf_thresh
        
        if confidence < threshold:
            continue
        
        cx, cy, w, h = filtered_bboxes[i]
        
        x1 = cx - w / 2
        y1 = cy - h / 2
        
        x1 = (x1 - pad[0]) / ratio
        y1 = (y1 - pad[1]) / ratio
        box_w = w / ratio
        box_h = h / ratio
        
        x1 = max(0, int(x1))
        y1 = max(0, int(y1))
        box_w = min(int(box_w), frame_w - x1)
        box_h = min(int(box_h), frame_h - y1)
        
        if box_w > 0 and box_h > 0:
            boxes.append([x1, y1, box_w, box_h])
            confidences.append(float(confidence))
            class_ids.append(int(class_id))
    
    if not boxes:
        return []
    
    indices = cv2.dnn.NMSBoxes(boxes, confidences, min_thresh, iou_thresh)
    
    detections = []
    if len(indices) > 0:
        for i in indices.flatten():
            class_name = CLASS_NAMES[class_ids[i]] if class_ids[i] < NUM_CLASSES else f'class_{class_ids[i]}'
            detections.append({
                'class_id': class_ids[i],
                'class_name': class_name,
                'confidence': confidences[i],
                'box': boxes[i],
                'safety': class_name in SAFETY_CRITICAL,
            })
    
    detections.sort(key=lambda d: (-d['safety'], -d['confidence']))
    return detections


def get_direction(box, frame_w):
    """Determine if object is on left, center, or right of frame."""
    cx = box[0] + box[2] / 2
    third = frame_w / 3
    if cx < third:
        return 'LEFT'
    elif cx < 2 * third:
        return 'CENTER'
    else:
        return 'RIGHT'


def draw_detections(frame, detections, fps):
    """Draw bounding boxes, labels, and FPS on frame."""
    frame_h, frame_w = frame.shape[:2]
    
    for det in detections:
        x, y, w, h = det['box']
        color = CLASS_COLORS.get(det['class_name'], (200, 200, 200))
        direction = get_direction(det['box'], frame_w)
        
        thickness = 3 if det['safety'] else 2
        
        # Highlight target object if in FIND_OBJECT mode
        with state.lock:
            current_mode = state.mode
            target_obj = state.target_object
            
        if current_mode == "FIND_OBJECT" and det['class_name'] == target_obj:
            color = (0, 255, 0) # Green for target
            thickness = 4
            
        cv2.rectangle(frame, (x, y), (x + w, y + h), color, thickness)
        
        label = f"{det['class_name']} {det['confidence']:.0%} [{direction}]"
        
        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.5, 1)
        cv2.rectangle(frame, (x, y - th - 8), (x + tw + 4, y), color, -1)
        cv2.putText(frame, label, (x + 2, y - 4),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (255, 255, 255), 1)
    
    cv2.putText(frame, f'FPS: {fps:.1f}', (10, 30),
                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
    
    n_safety = sum(1 for d in detections if d['safety'])
    cv2.putText(frame, f'Detections: {len(detections)} (safety: {n_safety})',
                (10, 60), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
    
    return frame


def main():
    args = parse_args()
    
    # Configure Qt / Display environment for Raspberry Pi OS Wayland / Raspberry Pi Connect
    if not args.headless:
        if 'WAYLAND_DISPLAY' in os.environ:
            os.environ.setdefault('QT_QPA_PLATFORM', 'wayland')
        elif 'DISPLAY' not in os.environ:
            os.environ['DISPLAY'] = ':0'
    
    print(f'[INFO] Loading ONNX model: {args.model}')
    if not os.path.exists(args.model):
        print(f'[ERROR] Model file not found: {args.model}')
        sys.exit(1)
    
    net = cv2.dnn.readNetFromONNX(args.model)
    net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
    net.setPreferableTarget(cv2.dnn.DNN_TARGET_CPU)
    print('[INFO] Model loaded successfully.')
    
    static_frame = None
    if args.image:
        print(f'[INFO] Loading static image for testing: {args.image}')
        static_frame = cv2.imread(args.image)
        if static_frame is None:
            print(f'[ERROR] Could not read image: {args.image}')
            sys.exit(1)
        actual_w, actual_h = static_frame.shape[1], static_frame.shape[0]
        camera_stream = None
    else:
        camera_stream = CameraStream(
            camera_idx=args.camera,
            width=args.width,
            height=args.height,
            prefer_picam2=True
        )
        actual_w, actual_h = camera_stream.width, camera_stream.height
    
    print('[INFO] Starting Audio System (TTS)...')
    tts_manager = TTSManager()
    debouncer = AudioDebouncer(cooldown=3.0)
    
    print('[INFO] Starting WebSocket Server...')
    ws_thread = threading.Thread(target=start_ws_server, args=(8765,), daemon=True)
    ws_thread.start()
    
    print('[INFO] Starting ToF Sensors...')
    tof_manager = tof.ToFManager()
    state.tof_manager = tof_manager
    tof_thread = threading.Thread(target=start_tof_thread, args=(tof_manager, tts_manager), daemon=True)
    tof_thread.start()
    
    print(f'[INFO] Confidence threshold: {args.conf}')
    print(f'[INFO] Safety-class threshold: {args.safety_conf}')
    print(f'[INFO] Frame skip: process 1 in {args.skip_frames}')
    if not args.headless:
        print('[INFO] Press "q" to quit, "+" to raise conf, "-" to lower conf')
    
    frame_count = 0
    fps = 0.0
    fps_history = []
    last_detections = []
    
    try:
        while True:
            if static_frame is not None:
                frame = static_frame.copy()
                ret = True
                time.sleep(0.1) # Prevent CPU spinning at 1000 FPS on a static image
            else:
                ret, frame = camera_stream.read()
                if not ret:
                    print('[WARN] Camera read failed, retrying...')
                    time.sleep(0.1)
                    continue
            
            with state.lock:
                current_mode = state.mode
            
            if current_mode == "PAUSED":
                time.sleep(0.5) # Save massive CPU/Battery when vision is paused by App
                continue
            
            frame_count += 1
            
            # Skip frames to boost display FPS
            if frame_count % args.skip_frames != 0:
                if not args.headless:
                    display = draw_detections(frame.copy(), last_detections, fps)
                    try:
                        cv2.imshow('VisionAid', display)
                        key = cv2.waitKey(1) & 0xFF
                        if key == ord('q'):
                            break
                        elif key == ord('+') or key == ord('='):
                            args.conf = min(0.95, args.conf + 0.05)
                            print(f'[INFO] Confidence: {args.conf:.2f}')
                        elif key == ord('-'):
                            args.conf = max(0.1, args.conf - 0.05)
                            print(f'[INFO] Confidence: {args.conf:.2f}')
                    except Exception as e:
                        print(f'[WARN] Display window failed ({e}). Switching to headless mode.')
                        args.headless = True
                continue
            
            # ── Preprocess ──
            letterboxed, ratio, pad = letterbox(frame, (args.imgsz, args.imgsz))
            blob = cv2.dnn.blobFromImage(letterboxed, 1.0 / 255.0,
                                          (args.imgsz, args.imgsz),
                                          swapRB=True, crop=False)
            
            # ── Inference ──
            net.setInput(blob)
            t_start = time.perf_counter()
            outputs = net.forward(net.getUnconnectedOutLayersNames())
            t_end = time.perf_counter()
            
            inference_ms = (t_end - t_start) * 1000
            fps = 1000.0 / inference_ms if inference_ms > 0 else 0
            fps_history.append(fps)
            if len(fps_history) > 30:
                fps_history.pop(0)
            avg_fps = sum(fps_history) / len(fps_history)
            
            # ── Post-process ──
            detections = process_detections(
                outputs, frame.shape, args.imgsz, ratio, pad,
                args.conf, args.iou, args.safety_conf
            )
            last_detections = detections
            
            # ── Modes & Audio/Network Output ──
            with state.lock:
                current_mode = state.mode
                target_obj = state.target_object
                
            if current_mode == "IDLE":
                # In IDLE, only announce safety-critical objects (person, door, stairs)
                if detections:
                    for det in detections:
                        if det['safety']:
                            direction = get_direction(det['box'], actual_w)
                            text = f"{det['class_name']} {direction.lower()}"
                            if debouncer.should_announce(text):
                                tts_manager.announce(text)
                                broadcast_ws_message({
                                    "type": "object_found",
                                    "payload": {
                                        "object_name": det['class_name'],
                                        "found": True,
                                        "direction": direction,
                                        "distance": 1.5 # Placeholder
                                    }
                                })
                                break
                                
            elif current_mode == "FIND_OBJECT":
                if detections:
                    for det in detections:
                        if det['class_name'] == target_obj:
                            direction = get_direction(det['box'], actual_w)
                            text = f"{det['class_name']} {direction.lower()}"
                            if debouncer.should_announce(text):
                                tts_manager.announce(text)
                                broadcast_ws_message({
                                    "type": "object_found",
                                    "payload": {
                                        "object_name": target_obj,
                                        "found": True,
                                        "direction": direction,
                                        "distance": 1.5
                                    }
                                })
                            break
                            
            elif current_mode == "DESCRIBE":
                # Very basic DESCRIBE implementation for now: announce everything
                if detections:
                    objects_seen = []
                    for det in detections:
                        direction = get_direction(det['box'], actual_w)
                        text = f"{det['class_name']} {direction.lower()}"
                        objects_seen.append(f"{det['class_name']} to the {direction.lower()}")
                        
                        if debouncer.should_announce(text):
                            tts_manager.announce(text)
                            
                    if objects_seen:
                        broadcast_ws_message({
                            "type": "scene_description",
                            "payload": {
                                "description": f"I see {', '.join(objects_seen)}."
                            }
                        })

            elif current_mode == "IDENTIFY_OBSTACLE":
                # Announce the object closest to the center of the frame
                if detections:
                    best_det = None
                    min_dist_to_center = 9999
                    for det in detections:
                        cx = det['box'][0] + det['box'][2] / 2
                        dist = abs(cx - (actual_w / 2))
                        if dist < min_dist_to_center:
                            min_dist_to_center = dist
                            best_det = det
                            
                    if best_det:
                        text = f"Obstacle: {best_det['class_name']}"
                        if debouncer.should_announce(text):
                            tts_manager.announce(text)
                            broadcast_ws_message({
                                "type": "scene_description",
                                "payload": {
                                    "description": f"Obstacle identified: {best_det['class_name']}"
                                }
                            })
            
            # ── Output ──
            if args.headless:
                if detections:
                    det_strs = [f"{d['class_name']}({d['confidence']:.0%}@{get_direction(d['box'], frame.shape[1])})"
                                for d in detections]
                    print(f'[{avg_fps:.1f} FPS] {" | ".join(det_strs)}')
            else:
                display = draw_detections(frame.copy(), detections, avg_fps)
                try:
                    cv2.imshow('VisionAid', display)
                    key = cv2.waitKey(1) & 0xFF
                    if key == ord('q'):
                        break
                    elif key == ord('+') or key == ord('='):
                        args.conf = min(0.95, args.conf + 0.05)
                        print(f'[INFO] Confidence: {args.conf:.2f}')
                    elif key == ord('-'):
                        args.conf = max(0.1, args.conf - 0.05)
                        print(f'[INFO] Confidence: {args.conf:.2f}')
                except Exception as e:
                    print(f'[WARN] Display window failed ({e}). Switching to headless mode.')
                    args.headless = True
    
    except KeyboardInterrupt:
        print('\n[INFO] Interrupted by user.')
    
    finally:
        if camera_stream:
            camera_stream.release()
        if not args.headless:
            try:
                cv2.destroyAllWindows()
            except Exception:
                pass
        if fps_history:
            print(f'[INFO] Average FPS: {sum(fps_history)/len(fps_history):.1f}')
        print('[INFO] VisionAid stopped.')

if __name__ == '__main__':
    main()
