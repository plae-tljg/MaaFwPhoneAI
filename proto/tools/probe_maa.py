import time
from pathlib import Path

import numpy as np
from PIL import Image

from maa.controller import AdbController
from maa.pipeline import JClick, JDoNothing, JOCR, JRecognitionType, JTemplateMatch, JActionType
from maa.resource import Resource
from maa.tasker import Tasker
from maa.toolkit import Toolkit

ROOT = Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "bundle"


def to_pil(img: np.ndarray) -> Image.Image:
    if img.ndim == 3 and img.shape[2] >= 3:
        return Image.fromarray(img[:, :, 2::-1] if img.shape[2] == 3 else img)
    return Image.fromarray(img)


def main():
    Toolkit.init_option(str(ROOT / "data" / "maa"))
    devices = Toolkit.find_adb_devices()
    print("devices:", [(d.address, d.name) for d in devices])
    device = devices[0]
    controller = AdbController(
        adb_path=device.adb_path,
        address=device.address,
        screencap_methods=device.screencap_methods,
        input_methods=device.input_methods,
        config=device.config,
    )
    controller.post_connection().wait()
    print("connected:", controller.connected, "resolution:", controller.resolution)

    resource = Resource()
    print("bundle loaded:", resource.post_bundle(str(BUNDLE)).wait().succeeded)
    tasker = Tasker()
    print("bind:", tasker.bind(resource, controller), "inited:", tasker.inited)
    tasker.set_save_draw(True)
    tasker.set_log_dir(str(ROOT / "data" / "maa" / "probe"))

    job = controller.post_screencap().wait()
    img = job.get()
    print("screencap:", type(img), img.shape, img.dtype)
    pil = to_pil(img)
    pil.save(ROOT / "data" / "maa_probe.png")

    reco_job = tasker.post_recognition(JRecognitionType.OCR, JOCR(), img)
    detail = reco_job.wait().get()
    print("reco detail type:", type(detail))
    nodes = detail.nodes if hasattr(detail, "nodes") else None
    if nodes:
        reco = nodes[0].recognition
    else:
        reco = detail
    print("hit:", reco.hit, "box:", reco.box)
    texts = []
    for r in reco.all_results[:12]:
        texts.append((getattr(r, "text", ""), tuple(r.box), round(float(r.score), 2)))
    print("ocr sample:", texts)

    crop = pil.crop((560, 980, 780, 1160))
    crop.save(BUNDLE / "resource" / "image" / "probe_camera.png")
    tpl = tasker.post_recognition(
        JRecognitionType.TemplateMatch,
        JTemplateMatch(template=["probe_camera.png"], threshold=[0.7]),
        img,
    ).wait().get()
    tpl_reco = tpl.nodes[0].recognition if hasattr(tpl, "nodes") else tpl
    print("template hit:", tpl_reco.hit, "box:", tpl_reco.box, "draws:", len(tpl_reco.draw_images))

    override = {
        "probe_click": {
            "recognition": "DirectHit",
            "action": "DoNothing",
        }
    }
    task = tasker.post_task("probe_click", override).wait().get()
    print("task status:", task.status, "succeeded attr:", getattr(task.status, "succeeded", None))
    print("node count:", len(task.nodes))


if __name__ == "__main__":
    main()
