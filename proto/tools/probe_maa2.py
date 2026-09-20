import inspect
from pathlib import Path

import numpy as np
from PIL import Image

from maa.controller import AdbController
from maa.pipeline import JClick, JDirectHit, JActionType, JRecognitionType, JTemplateMatch
from maa.resource import Resource
from maa.tasker import Tasker
from maa.toolkit import Toolkit

ROOT = Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "bundle"


def to_pil(img: np.ndarray) -> Image.Image:
    return Image.fromarray(img[:, :, ::-1])


def main():
    Toolkit.init_option(str(ROOT / "data" / "maa"))
    device = Toolkit.find_adb_devices()[0]
    controller = AdbController(
        adb_path=device.adb_path,
        address=device.address,
        screencap_methods=device.screencap_methods,
        input_methods=device.input_methods,
        config=device.config,
    )
    print("use_raw sig:", inspect.signature(controller.set_screenshot_use_raw_size))
    controller.set_screenshot_use_raw_size(True)
    controller.post_connection().wait()
    resource = Resource()
    resource.post_bundle(str(BUNDLE)).wait()
    tasker = Tasker()
    tasker.bind(resource, controller)
    tasker.set_save_draw(True)

    img = controller.post_screencap().wait().get()
    print("raw screencap shape:", img.shape)
    pil = to_pil(img)

    print("post_image sig:", inspect.signature(resource.post_image))
    print("override_image sig:", inspect.signature(resource.override_image))
    crop = pil.crop((560, 980, 780, 1160))
    tpl_path = ROOT / "data" / "probe_camera_raw.png"
    crop.save(tpl_path)
    job = resource.post_image(str(tpl_path))
    print("post_image loaded:", job.wait().succeeded)
    tpl = tasker.post_recognition(
        JRecognitionType.TemplateMatch,
        JTemplateMatch(template=["probe_camera_raw.png"], threshold=[0.7]),
        img,
    ).wait().get()
    reco = tpl.nodes[0].recognition
    print("template hit:", reco.hit, "box:", reco.box, "draws:", len(reco.draw_images))
    if reco.hit and reco.draw_images:
        Image.fromarray(reco.draw_images[0][:, :, ::-1]).save(ROOT / "data" / "maa_probe_draw.png")

    from maa.pipeline import JOCR

    ocr = tasker.post_recognition(
        JRecognitionType.OCR, JOCR(expected=["设置"]), img
    ).wait().get().nodes[0].recognition
    print("ocr expected hit:", ocr.hit, "box:", ocr.box)

    click = tasker.post_action(
        JActionType.Click, JClick(target=(ocr.box[0] + ocr.box[2] // 2, ocr.box[1] + ocr.box[3] // 2))
    ).wait()
    print("click job:", click.succeeded)
    import time

    time.sleep(1.5)
    from maa.controller import AdbController as _A

    out = controller.post_shell("dumpsys window | grep -m1 mCurrentFocus", timeout=5000).wait()
    print("focus:", controller.shell_output)
    controller.post_click_key(4).wait()


if __name__ == "__main__":
    main()
