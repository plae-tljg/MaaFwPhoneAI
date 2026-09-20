import io
import re
import subprocess
import time

from PIL import Image

from . import config
from . import uiauto

KEYCODES = {
    "back": 4,
    "home": 3,
    "enter": 66,
    "delete": 67,
    "menu": 82,
    "power": 26,
    "volume_up": 24,
    "volume_down": 25,
    "tab": 61,
    "escape": 111,
}


class Device:
    def __init__(self, serial=None):
        self.serial = serial or config.DEVICE_SERIAL
        self.base = ["adb"] + (["-s", self.serial] if self.serial else [])
        self._size = None

    def _shell(self, *args, timeout=30):
        return subprocess.run(
            self.base + ["shell", *args], capture_output=True, text=True, timeout=timeout
        )

    def _execout(self, *args, timeout=60):
        return subprocess.run(
            self.base + ["exec-out", *args], capture_output=True, timeout=timeout
        ).stdout

    def screencap(self):
        raw = self._execout("screencap", "-p")
        return Image.open(io.BytesIO(raw)).convert("RGB")

    def size(self):
        if self._size:
            return self._size
        out = self._shell("wm", "size").stdout
        m = re.search(r"(\d+)x(\d+)", out)
        self._size = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
        return self._size

    def tap(self, x, y):
        self._shell("input", "tap", str(int(x)), str(int(y)))

    def swipe(self, x1, y1, x2, y2, duration=300):
        self._shell(
            "input", "swipe", *[str(int(v)) for v in (x1, y1, x2, y2, duration)]
        )

    def text(self, value):
        self._shell("input", "text", str(value).replace(" ", "%s"))

    def key(self, name):
        code = KEYCODES.get(str(name), name)
        if not str(code).isdigit():
            raise ValueError(f"unknown key: {name}")
        self._shell("input", "keyevent", str(code))

    def launch(self, package):
        self._shell(
            "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1"
        )

    def current_app(self):
        out = self._shell("dumpsys", "window").stdout
        m = re.search(r"mCurrentFocus=Window\{[0-9a-f]+ u0 ([^/}\s]+)", out)
        if not m:
            m = re.search(r"mFocusedApp=.*? ([A-Za-z0-9_.]+)/", out)
        return m.group(1) if m else ""

    def uiauto(self):
        self._shell("rm", "-f", "/sdcard/window_dump.xml")
        self._shell("uiautomator", "dump", "/sdcard/window_dump.xml")
        xml = self._execout("cat", "/sdcard/window_dump.xml").decode("utf-8", "replace")
        if "<hierarchy" not in xml:
            return []
        return uiauto.parse(xml)

    def packages(self, pattern=""):
        out = self._shell("pm", "list", "packages").stdout
        return [
            line.split(":", 1)[1]
            for line in out.splitlines()
            if line.startswith("package:") and pattern in line
        ]

    def wait_stable(self, seconds=0.8):
        time.sleep(seconds)
