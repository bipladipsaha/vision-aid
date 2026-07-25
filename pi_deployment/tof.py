"""
VisionAid ToF Module
Handles the initialization and reading of 4 VL53L0X sensors via I2C and XSHUT pins.
"""
import time
import config
import random

try:
    import board
    import busio
    import digitalio
    import adafruit_vl53l0x
    HARDWARE_AVAILABLE = True
except NotImplementedError:
    # board throws NotImplementedError on non-Pi platforms sometimes
    HARDWARE_AVAILABLE = False
except ImportError:
    HARDWARE_AVAILABLE = False

class RealToFManager:
    def __init__(self):
        print("[INFO] Initializing I2C bus...")
        self.i2c = busio.I2C(board.SCL, board.SDA)
        self.sensors = {}
        self.xshut_pins = {}
        
        # 1. Initialize all XSHUT pins and pull them LOW (turn off all sensors)
        print("[INFO] Resetting all ToF sensors...")
        for position, pin_num in config.TOF_PINS.items():
            # Get the correct pin attribute from board (e.g., board.D17)
            pin_attr = getattr(board, f"D{pin_num}")
            pin = digitalio.DigitalInOut(pin_attr)
            pin.direction = digitalio.Direction.OUTPUT
            pin.value = False  # Keep sensor in reset
            self.xshut_pins[position] = pin
            
        time.sleep(0.1) # Wait for sensors to reset
        
        # 2. Turn on sensors one by one and assign new I2C addresses
        for position in ['left', 'center', 'right', 'bottom']:
            pin = self.xshut_pins[position]
            new_address = config.TOF_ADDRESSES[position]
            
            # Turn on this specific sensor
            pin.value = True
            time.sleep(0.05) # Wait for it to boot up
            
            try:
                # The sensor boots up at default address 0x29
                sensor = adafruit_vl53l0x.VL53L0X(self.i2c, address=0x29)
                
                # Change its address
                sensor.set_address(new_address)
                
                # Basic initialization (removed advanced timing budget as some sensors crash)
                self.sensors[position] = sensor
                print(f"[INFO] Initialized {position} sensor at {hex(new_address)}")
            except Exception as e:
                print(f"[ERROR] Failed to initialize {position} sensor: {e}")
                
    def get_distances(self):
        """
        Reads all 4 sensors and returns a dictionary of distances.
        Returns 8190 if out of range, or -1 if the sensor isn't connected/failed.
        """
        distances = {}
        # Pre-fill with -1 in case a sensor failed to initialize
        for pos in ['left', 'center', 'right', 'bottom']:
            distances[pos] = -1
            
        for position, sensor in self.sensors.items():
            try:
                dist = sensor.range
                distances[position] = dist
            except Exception as e:
                # Print the exact error so we can debug I2C crashes
                print(f"[ERR] {position} sensor failed: {e}")
                pass
                
        return distances

class MockToFManager:
    def __init__(self):
        print("[INFO] Initializing MOCK ToF sensors for Windows testing...")
        self.sensors = {'left': True, 'center': True, 'right': True, 'bottom': True}
        self.mock_distances = {'left': 1500, 'center': 1500, 'right': 1500, 'bottom': 1500}
        
    def get_distances(self):
        # We will add some small random jitter to look realistic
        return {
            pos: max(10, val + random.randint(-5, 5))
            for pos, val in self.mock_distances.items()
        }
        
    def trigger_fake_warning(self, pos='center', dist=500):
        """Allows us to manually simulate a person walking in front of a sensor."""
        if pos in self.mock_distances:
            self.mock_distances[pos] = dist
            print(f"[MOCK] Simulated obstacle on {pos} sensor at {dist}mm")

def ToFManager():
    """Factory function returning the Real or Mock manager depending on the OS."""
    if HARDWARE_AVAILABLE:
        return RealToFManager()
    else:
        return MockToFManager()

if __name__ == "__main__":
    # Test script for running just this file on the Pi
    print("Testing VisionAid ToF Module...")
    try:
        tof = ToFManager()
        print("\nReading sensors... (Press Ctrl+C to stop)")
        while True:
            data = tof.get_distances()
            
            # Format output cleanly
            out = []
            for pos in ['left', 'center', 'right', 'bottom']:
                val = data.get(pos, -1)
                if val >= 8000:
                    val_str = " OOR " # Out of Range
                elif val == -1:
                    val_str = " ERR "
                else:
                    val_str = f"{val:4d}mm"
                out.append(f"{pos.capitalize()}: {val_str}")
                
            print(" | ".join(out), end="\r", flush=True)
            time.sleep(0.1)
            
    except KeyboardInterrupt:
        print("\nTest stopped.")
    except Exception as e:
        print(f"\nFatal error: {e}")
