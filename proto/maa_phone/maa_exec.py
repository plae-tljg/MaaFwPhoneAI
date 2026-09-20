from pathlib import Path

import numpy as np
from PIL import Image

from maa.controller import AdbController
from maa.pipeline import (
    JClick,
    JClickKey,
    JInputText,
    JOCR,
    JRecognitionType,
    JStartApp,
    JSwipe,
    JTemplateMatch,
)
from maa.resource import Resource
from maa.tasker import Tasker
from maa.toolkit import Toolkit

from . import config


class MaaExecError(RuntimeError):
    pass


def to_pil(image):
    if image.ndim == 3 and image.shape[2] == 3:
        return Image.fromarray(image[:, :, ::-1])
    return Image.fromarray(image)


def to_bgr(image):
    if image is None:
        return None
    return np.asarray(image.convert("RGB"))[:, :, ::-1].copy()


class MaaExec:
    def __init__(self, log_dir=None):
        config.MAA_DIR.mkdir(parents=True, exist_ok=True)
        Toolkit.init_option(str(config.MAA_DIR))
        devices = Toolkit.find_adb_devices()
        if not devices:
            raise MaaExecError("no ADB device found")
        device = devices[0]
        self.device_name = device.name
        self.controller = AdbController(
            adb_path=device.adb_path,
            address=device.address,
            screencap_methods=device.screencap_methods,
            input_methods=device.input_methods,
            config=device.config,
        )
        self.controller.set_screenshot_use_raw_size(True)
        self.controller.post_connection().wait()
        if not self.controller.connected:
            raise MaaExecError("controller not connected")
        self.resource = Resource()
        if not self.resource.post_bundle(str(config.BUNDLE)).wait().succeeded:
            raise MaaExecError("bundle load failed")
        self.tasker = Tasker()
        if not self.tasker.bind(self.resource, self.controller) or not self.tasker.inited:
            raise MaaExecError("tasker init failed")
        self._templates = set()
        self.set_log_dir(log_dir or (config.MAA_DIR / "default"))

    def set_log_dir(self, path):
        path = Path(path)
        path.mkdir(parents=True, exist_ok=True)
        self.tasker.set_log_dir(str(path))

    def set_debug(self, save_draw=False, debug_mode=False, save_on_error=False):
        self.tasker.set_save_draw(bool(save_draw))
        self.tasker.set_debug_mode(bool(debug_mode))
        self.tasker.set_save_on_error(bool(save_on_error))

    def image(self):
        return to_pil(self.controller.post_screencap().wait().get())

    def current_app(self):
        import re
        import subprocess

        try:
            out = subprocess.run(
                ["adb", "shell", "dumpsys", "window"],
                capture_output=True,
                text=True,
                timeout=20,
            ).stdout
            match = re.search(r"mCurrentFocus=Window\{[0-9a-f]+ u0 ([^/}\s]+)", out)
            return match.group(1) if match else ""
        except Exception:
            return ""

    def _array(self, image):
        return to_bgr(image) if image is not None else self.controller.post_screencap().wait().get()

    def ocr(self, image=None, expected=None, roi=None, threshold=0.3):
        params = JOCR(expected=list(expected or []), threshold=threshold)
        if roi:
            params.roi = tuple(roi)
        detail = (
            self.tasker.post_recognition(JRecognitionType.OCR, params, self._array(image))
            .wait()
            .get()
        )
        return detail.nodes[0].recognition if detail and detail.nodes else None

    def ocr_texts(self, image=None, roi=None):
        reco = self.ocr(image=image, roi=roi)
        out = []
        if reco:
            for result in reco.all_results:
                text = getattr(result, "text", "") or ""
                if text:
                    out.append(
                        {"text": text, "box": tuple(result.box), "score": float(result.score)}
                    )
        return out

    def load_template(self, name, path=None, force=False):
        if name in self._templates and not force:
            return True
        path = Path(path) if path else (config.BUNDLE_IMAGE / name)
        if not path.is_absolute():
            path = config.ROOT / path
        if not path.exists():
            return False
        ok = self.resource.post_image(str(path)).wait().succeeded
        if ok:
            self._templates.add(name)
        return ok

    def match(self, template, image=None, threshold=0.7):
        params = JTemplateMatch(template=[template], threshold=[threshold])
        detail = (
            self.tasker.post_recognition(
                JRecognitionType.TemplateMatch, params, self._array(image)
            )
            .wait()
            .get()
        )
        return detail.nodes[0].recognition if detail and detail.nodes else None

    def click(self, x, y):
        return self.controller.post_click(int(x), int(y)).wait().succeeded

    def swipe(self, x1, y1, x2, y2, duration=300):
        return self.controller.post_swipe(
            int(x1), int(y1), int(x2), int(y2), int(duration)
        ).wait().succeeded

    def input_text(self, text):
        return self.controller.post_input_text(str(text)).wait().succeeded

    def click_key(self, code):
        return self.controller.post_click_key(int(code)).wait().succeeded

    def start_app(self, package):
        return self.controller.post_start_app(str(package)).wait().succeeded

    def run_node(self, name, node):
        entry = f"{name}_entry"
        wrapper = {
            entry: {
                "recognition": "DirectHit",
                "action": "DoNothing",
                "next": [name],
                "timeout": int(node.get("timeout", 10000)),
            },
            name: node,
        }
        self.resource.override_pipeline(wrapper)
        detail = self.tasker.post_task(entry).wait().get()
        if not detail:
            return None, None
        target = None
        for node_detail in detail.nodes:
            if getattr(node_detail, "name", "") == name:
                target = node_detail
        return bool(detail.status.succeeded), target
