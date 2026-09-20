import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "bundle"
OCR_DIR = BUNDLE / "model" / "ocr"
OCR_URL = "https://download.maafw.xyz/MaaCommonAssets/OCR/ppocr_v5/ppocr_v5-zh_cn.zip"
OCR_FILES = ["det.onnx", "rec.onnx", "keys.txt"]


def ensure_bundle():
    for sub in ("resource/pipeline", "resource/image", "model/ocr"):
        (BUNDLE / sub).mkdir(parents=True, exist_ok=True)
    print(f"bundle: {BUNDLE}")


def ensure_ocr():
    if all((OCR_DIR / f).exists() for f in OCR_FILES):
        print(f"ocr: already present in {OCR_DIR}")
        return
    tmp = ROOT / "data"
    tmp.mkdir(parents=True, exist_ok=True)
    archive = tmp / "ppocr_v5-zh_cn.zip"
    if not archive.exists():
        print(f"downloading {OCR_URL} ...")
        request = urllib.request.Request(OCR_URL, headers={"User-Agent": "maa-phone-setup"})
        with urllib.request.urlopen(request, timeout=900) as resp, open(archive, "wb") as out:
            shutil.copyfileobj(resp, out)
    print(f"zip bytes: {archive.stat().st_size}")
    extract = tmp / "ocr_extract"
    shutil.rmtree(extract, ignore_errors=True)
    with zipfile.ZipFile(archive) as zf:
        zf.extractall(extract)
    for name in OCR_FILES:
        found = next(extract.rglob(name), None)
        if not found:
            raise RuntimeError(f"{name} not found in archive")
        shutil.move(str(found), str(OCR_DIR / name))
    shutil.rmtree(extract, ignore_errors=True)
    print(f"ocr: installed to {OCR_DIR}")


if __name__ == "__main__":
    ensure_bundle()
    ensure_ocr()
